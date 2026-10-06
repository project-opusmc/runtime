package org.polydevs.opusmc.client.interop;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.apache.logging.log4j.Logger;

/**
 * Loopback interop server for the Opus web UI (architecture doc sections 6-7,
 * 48). Serves the bundled Svelte SPA, exposes a small REST contract and
 * WebSocket events. Binds 127.0.0.1 only, uses a random port and a random
 * per-session auth code; no external dependency beyond the JDK.
 */
public final class OpusInteropServer {
    public static final String AUTH_COOKIE = "opus_auth";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Gson GSON = new Gson();
    private static final Object ACCOUNT_CATALOG_LOCK = new Object();

    private final Logger log;
    private final String authCode;
    private final List<OpusWebSocket> sessions = new CopyOnWriteArrayList<OpusWebSocket>();
    private final ExecutorService workers;
    private final ServerStatusProbe serverStatusProbe;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile int port = -1;
    private volatile boolean running;
    private UiNavigationManager navigationManager;
    private OpusBridgeProvider bridgeProvider;

    public OpusInteropServer(Logger log) {
        this.log = log;
        this.authCode = randomAuthCode();
        this.workers = Executors.newCachedThreadPool(daemonThreads("opus-bridge"));
        this.serverStatusProbe = new ServerStatusProbe();
    }

    public void setNavigationManager(UiNavigationManager navigationManager) {
        this.navigationManager = navigationManager;
    }

    public void setBridgeProvider(OpusBridgeProvider bridgeProvider) {
        this.bridgeProvider = bridgeProvider;
    }

    public boolean start() {
        try {
            serverSocket = new ServerSocket(
                    0,
                    50,
                    InetAddress.getByName("127.0.0.1"));
            port = serverSocket.getLocalPort();
            running = true;
            acceptThread = daemonThreads("opus-bridge-accept").newThread(this::acceptLoop);
            acceptThread.start();
            return true;
        } catch (IOException failure) {
            log.warn("Opus interop server could not bind", failure);
            return false;
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // Closing anyway.
        }
        for (OpusWebSocket session : sessions) {
            session.close(1001);
        }
        workers.shutdownNow();
        serverStatusProbe.stop();
    }

    public int port() {
        return port;
    }

    public String url() {
        return "http://127.0.0.1:" + port + "/?code=" + authCode + "&port=" + port + "#/title";
    }

    /**
     * Builds a loopback SPA URL for a specific canonical route (for example
     * {@code title} or {@code settings}). Used by the embedded web-view surface
     * so the in-game browser opens directly on the active Opus route.
     */
    public String urlForRoute(String route) {
        return urlForRoute(route, null);
    }

    public String urlForRoute(String route, String moduleId) {
        return urlForRoute(route, moduleId, null);
    }

    public String urlForRoute(String route, String moduleId, String settingsSection) {
        String target = (route == null || route.isEmpty()) ? "title" : route;
        StringBuilder url = new StringBuilder("http://127.0.0.1:")
                .append(port)
                .append("/?code=").append(authCode)
                .append("&port=").append(port)
                .append("#/").append(target);
        boolean hasModule = moduleId != null && !moduleId.isEmpty();
        boolean hasSettings = settingsSection != null && !settingsSection.isEmpty();
        if (hasModule || hasSettings) {
            try {
                boolean needsAmpersand = false;
                url.append('?');
                if (hasModule) {
                    url.append("moduleId=")
                            .append(java.net.URLEncoder.encode(moduleId, "UTF-8"));
                    needsAmpersand = true;
                }
                if (hasSettings) {
                    if (needsAmpersand) {
                        url.append('&');
                    }
                    url.append("settingsSection=")
                            .append(java.net.URLEncoder.encode(settingsSection, "UTF-8"));
                }
            } catch (java.io.UnsupportedEncodingException impossible) {
                url.append("moduleId=").append(moduleId);
            }
        }
        return url.toString();
    }

    public void broadcast(String message) {
        for (OpusWebSocket session : sessions) {
            session.sendText(message);
        }
    }

    void attach(OpusWebSocket session) {
        sessions.add(session);
    }

    void detach(OpusWebSocket session) {
        sessions.remove(session);
    }

    void onMessage(String json) {
        String name = OpusJson.extractString(json, "name");
        if ("ping".equals(name)) {
            return;
        }
        log.debug("Opus bridge message: {}", json);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                workers.execute(() -> handleConnection(socket));
            } catch (SocketException closed) {
                if (running) {
                    log.warn("Opus interop accept failed", closed);
                }
                return;
            } catch (IOException failure) {
                if (running) {
                    log.warn("Opus interop accept failed", failure);
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        boolean websocket = false;
        try {
            socket.setTcpNoDelay(true);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            Request request = readRequest(in);
            if (request == null) {
                socket.close();
                return;
            }
            log.debug("Opus bridge request {} {}", request.method, request.path);
            if ("/ws".equals(request.path)) {
                if (!authorized(request)) {
                    respond(out, 401, "application/json",
                            OpusJson.object("error", "Unauthorized"),
                            null);
                    socket.close();
                    return;
                }
                String accept = websocketAccept(request.headers.get("sec-websocket-key"));
                if (accept == null) {
                    respond(out, 400, "application/json",
                            OpusJson.object("error", "Bad WebSocket key"), null);
                    socket.close();
                    return;
                }
                String response = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n"
                        + "\r\n";
                // Attach before the handshake reaches the client so an event
                // broadcast immediately after connect() can never miss it.
                OpusWebSocket session = new OpusWebSocket(log, in, out, this);
                attach(session);
                log.debug("Opus bridge WebSocket authenticated");
                out.write(response.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                workers.execute(session);
                websocket = true;
                return;
            }

            Map<String, String> cookies = new HashMap<String, String>();
            String cookieHeader = request.headers.get("cookie");
            if (cookieHeader != null) {
                for (String part : cookieHeader.split(";")) {
                    int separator = part.indexOf('=');
                    if (separator > 0) {
                        cookies.put(
                                part.substring(0, separator).trim(),
                                part.substring(separator + 1).trim());
                    }
                }
            }
            boolean authByCookie = authCode.equals(cookies.get(AUTH_COOKIE));
            boolean authByQuery = authCode.equals(request.query.get("code"));

            if ("/".equals(request.path) && authByQuery) {
                Map<String, String> headers = new HashMap<String, String>();
                headers.put("Set-Cookie", AUTH_COOKIE + "=" + authCode
                        + "; Path=/; HttpOnly; SameSite=Lax");
                serveSpa(request, out, headers);
                return;
            }

            if (request.path.startsWith("/api/")) {
                if (!authByCookie && !authByQuery) {
                    respond(out, 401, "application/json",
                            OpusJson.object("error", "Authentication required"),
                            null);
                    return;
                }
                handleApi(request, out);
                return;
            }

            serveSpa(request, out, null);
        } catch (Throwable failure) {
            log.warn("Opus interop request failed", failure);
        } finally {
            if (!websocket) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Closing anyway.
                }
            }
        }
    }

    private void handleApi(Request request, OutputStream out) throws IOException {
        if ("/api/v1/client/assets/title-background".equals(request.path)
                && "GET".equals(request.method)) {
            serveMinecraftResource(
                    "/assets/minecraft/textures/gui/title/background/panorama_0.png",
                    "image/png",
                    out);
            return;
        }
        if ("/api/v1/client".equals(request.path) && "GET".equals(request.method)) {
            respond(out, 200, "application/json",
                    OpusJson.object(
                            "name", "Opus Client",
                            "version", "0.0.1",
                            "minecraft", "1.8.9",
                            "forge", "11.15.1.2318",
                            "optifine", "HD U M5",
                            "session", currentSessionName(),
                            "accountKind", currentSessionKind()),
                    null);
            return;
        }
        if ("/api/v1/client/accounts".equals(request.path) && "GET".equals(request.method)) {
            respond(out, 200, "application/json", accountsJson(), null);
            return;
        }
        if ("/api/v1/client/accounts/select".equals(request.path)
                && "POST".equals(request.method)) {
            String id = OpusJson.extractString(request.body, "id");
            if (id == null || id.trim().isEmpty()) {
                respond(out, 400, "application/json",
                        OpusJson.object("error", "Missing account id"), null);
                return;
            }
            String result = selectAccount(id.trim());
            if (result == null) {
                respond(out, 404, "application/json",
                        OpusJson.object("error", "Account does not exist"), null);
                return;
            }
            respond(out, 200, "application/json", result, null);
            return;
        }
        if ("/api/v1/client/ui-state".equals(request.path)
                && "GET".equals(request.method) && navigationManager != null) {
            respond(out, 200, "application/json", navigationManager.stateJson(), null);
            return;
        }
        if ("/api/v1/client/ui-state/ack".equals(request.path)
                && "POST".equals(request.method) && navigationManager != null) {
            Long revision = OpusJson.extractLong(request.body, "revision");
            if (revision == null) {
                respond(out, 400, "application/json",
                        OpusJson.object("error", "Missing revision"), null);
                return;
            }
            navigationManager.confirm(revision.longValue());
            respond(out, 204, "application/json", new byte[0], null);
            return;
        }
        if ("/api/v1/client/ui-actions".equals(request.path)
                && "POST".equals(request.method) && bridgeProvider != null) {
            String action = OpusJson.extractString(request.body, "action");
            Long expectedRevision = OpusJson.extractLong(request.body, "revision");
            String routeJson = nestedObject(request.body, "route");
            String routeId = OpusJson.extractString(routeJson, "id");
            String paramsJson = nestedObject(routeJson, "params");
            String moduleId = OpusJson.extractString(paramsJson, "moduleId");
            String settingsSection = OpusJson.extractString(paramsJson, "settingsSection");
            if (action == null || action.isEmpty()) {
                respond(out, 400, "application/json",
                        OpusJson.object("error", "Missing UI action"), null);
                return;
            }
            if (!("navigate".equals(action)
                    || "back".equals(action)
                    || "close".equals(action)
                    || "minecraft-settings".equals(action)
                    || "create-world".equals(action)
                    || "quit".equals(action))) {
                respond(out, 400, "application/json",
                        OpusJson.object("error", "Unknown UI action"), null);
                return;
            }
            if (!"quit".equals(action) && expectedRevision == null) {
                respond(out, 400, "application/json",
                        OpusJson.object("error", "Missing navigation revision"), null);
                return;
            }
            if (!"quit".equals(action) && expectedRevision != null
                    && navigationManager != null
                    && !navigationManager.isCurrentRevision(expectedRevision.longValue())) {
                respond(out, 409, "application/json",
                        navigationManager.stateJson(), null);
                return;
            }
            if ("navigate".equals(action)) {
                String routeError = validateRoute(routeId, moduleId, settingsSection);
                if (routeError != null) {
                    respond(out, 400, "application/json",
                            OpusJson.object("error", routeError), null);
                    return;
                }
            }
            boolean accepted = bridgeProvider.performUiAction(
                    action,
                    routeId,
                    moduleId,
                    settingsSection,
                    expectedRevision == null ? -1L : expectedRevision.longValue());
            if (!accepted) {
                respond(out, 409, "application/json",
                        navigationManager == null
                                ? OpusJson.object("error", "UI action was not committed")
                                : navigationManager.stateJson(),
                        null);
                return;
            }
            if ("quit".equals(action) || navigationManager == null) {
                respond(out, 202, "application/json",
                        OpusJson.object("status", "accepted"), null);
            } else {
                respond(out, 200, "application/json", navigationManager.stateJson(), null);
            }
            return;
        }
        if ("/api/v1/client/worlds".equals(request.path)) {
            if ("GET".equals(request.method)) {
                respond(out, 200, "application/json", worldsJson(), null);
                return;
            }
        }
        if ("/api/v1/client/worlds/load".equals(request.path)
                && "POST".equals(request.method) && bridgeProvider != null) {
            String file = OpusJson.extractString(request.body, "file");
            String name = OpusJson.extractString(request.body, "name");
            if (file != null && !file.isEmpty()) {
                bridgeProvider.loadWorld(file, name == null ? file : name);
                respond(out, 204, "application/json", new byte[0], null);
                return;
            }
        }
        if ("/api/v1/client/servers".equals(request.path)) {
            if ("GET".equals(request.method)) {
                respond(out, 200, "application/json", serversJson(), null);
                return;
            }
            if ("POST".equals(request.method) && bridgeProvider != null) {
                String name = OpusJson.extractString(request.body, "name");
                String address = OpusJson.extractString(request.body, "address");
                if (address != null && !address.isEmpty()) {
                    bridgeProvider.addServer(name == null ? "" : name, address);
                    respond(out, 204, "application/json", new byte[0], null);
                    return;
                }
            }
        }
        if ("/api/v1/client/servers/status".equals(request.path)
                && ("GET".equals(request.method) || "POST".equals(request.method))) {
            boolean refresh = "POST".equals(request.method);
            respond(out, 200, "application/json", serverStatusesJson(refresh), null);
            return;
        }
        if ("/api/v1/client/servers/connect".equals(request.path)
                && "POST".equals(request.method) && bridgeProvider != null) {
            String address = OpusJson.extractString(request.body, "address");
            if (address != null && !address.isEmpty()) {
                bridgeProvider.connectServer(address);
                respond(out, 204, "application/json", new byte[0], null);
                return;
            }
        }
        if ("/api/v1/client/options".equals(request.path)) {
            if ("GET".equals(request.method)) {
                respond(out, 200, "application/json", optionsJson(), null);
                return;
            }
        }
        if ("/api/v1/client/options/adjust".equals(request.path)
                && "POST".equals(request.method) && bridgeProvider != null) {
            String key = OpusJson.extractString(request.body, "key");
            String delta = OpusJson.extractScalar(request.body, "delta");
            if (key != null && delta != null) {
                try {
                    bridgeProvider.adjustGameOption(key, Integer.parseInt(delta));
                    respond(out, 204, "application/json", new byte[0], null);
                } catch (NumberFormatException ignored) {
                    respond(out, 400, "application/json",
                            OpusJson.object("error", "Bad delta"), null);
                }
                return;
            }
        }
        if ("/api/v1/client/options/set".equals(request.path)
                && "POST".equals(request.method) && bridgeProvider != null) {
            String key = OpusJson.extractString(request.body, "key");
            String value = OpusJson.extractScalar(request.body, "value");
            if (key != null && value != null) {
                try {
                    bridgeProvider.setGameOptionFloat(key, Float.parseFloat(value));
                    respond(out, 204, "application/json", new byte[0], null);
                } catch (NumberFormatException ignored) {
                    respond(out, 400, "application/json",
                            OpusJson.object("error", "Bad value"), null);
                }
                return;
            }
        }
        if ("/api/v1/client/world/leave".equals(request.path)
                && "POST".equals(request.method) && bridgeProvider != null) {
            bridgeProvider.leaveWorld();
            respond(out, 204, "application/json", new byte[0], null);
            return;
        }
        if ("/api/v1/client/modules".equals(request.path)) {
            if ("GET".equals(request.method)) {
                respond(out, 200, "application/json", modulesJson(), null);
                return;
            }
        }
        if ("/api/v1/client/modules/toggle".equals(request.path)
                && "POST".equals(request.method) && bridgeProvider != null) {
            String id = OpusJson.extractString(request.body, "id");
            String enabled = OpusJson.extractScalar(request.body, "enabled");
            if (id != null && enabled != null) {
                bridgeProvider.setModuleEnabled(id,
                        "1".equals(enabled) || "true".equals(enabled));
                respond(out, 204, "application/json", new byte[0], null);
                return;
            }
        }
        if ("/api/v1/client/modules/settings".equals(request.path)
                && "POST".equals(request.method) && bridgeProvider != null) {
            String id = OpusJson.extractString(request.body, "id");
            String key = OpusJson.extractString(request.body, "key");
            String value = OpusJson.extractString(request.body, "value");
            if (id != null && key != null && value != null) {
                bridgeProvider.setModuleSetting(id, key, value);
                respond(out, 204, "application/json", new byte[0], null);
                return;
            }
        }
        if ("/api/v1/client/hud".equals(request.path)) {
            if ("GET".equals(request.method)) {
                respond(out, 200, "application/json", hudJson(), null);
                return;
            }
        }
        if ("/api/v1/client/hud/move".equals(request.path)
                && "POST".equals(request.method) && bridgeProvider != null) {
            String id = OpusJson.extractString(request.body, "id");
            // The SPA sends coordinates as JSON numbers. Accept quoted values
            // too for older clients, but do not require a string token here.
            String x = OpusJson.extractScalar(request.body, "x");
            String y = OpusJson.extractScalar(request.body, "y");
            if (id != null && x != null && y != null) {
                try {
                    bridgeProvider.moveHudModule(
                            id, Integer.parseInt(x), Integer.parseInt(y));
                    respond(out, 204, "application/json", new byte[0], null);
                } catch (NumberFormatException ignored) {
                    respond(out, 400, "application/json",
                            OpusJson.object("error", "Bad position"), null);
                }
                return;
            }
        }
        if ("/api/v1/client/ui-input-region".equals(request.path)
                && "POST".equals(request.method) && bridgeProvider != null) {
            Long revision = OpusJson.extractLong(request.body, "revision");
            Long x = OpusJson.extractLong(request.body, "x");
            Long y = OpusJson.extractLong(request.body, "y");
            Long width = OpusJson.extractLong(request.body, "width");
            Long height = OpusJson.extractLong(request.body, "height");
            if (revision == null || x == null || y == null || width == null || height == null
                    || revision.longValue() < 0L || width.longValue() < 0L
                    || height.longValue() < 0L
                    || x.longValue() < Integer.MIN_VALUE || x.longValue() > Integer.MAX_VALUE
                    || y.longValue() < Integer.MIN_VALUE || y.longValue() > Integer.MAX_VALUE
                    || width.longValue() > Integer.MAX_VALUE
                    || height.longValue() > Integer.MAX_VALUE) {
                respond(out, 400, "application/json",
                        OpusJson.object("error", "Invalid UI input region"), null);
                return;
            }
            List<int[]> exclusions = new ArrayList<int[]>();
            try {
                JsonObject body = new JsonParser().parse(request.body).getAsJsonObject();
                if (body.has("exclusions")) {
                    JsonArray holes = body.getAsJsonArray("exclusions");
                    if (holes.size() > 8) throw new IllegalArgumentException("Too many exclusions");
                    for (JsonElement hole : holes) {
                        JsonObject rect = hole.getAsJsonObject();
                        int[] values = new int[4];
                        String[] keys = {"x", "y", "width", "height"};
                        for (int i = 0; i < keys.length; i++) {
                            JsonElement value = rect.get(keys[i]);
                            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                                throw new IllegalArgumentException("Bad exclusion");
                            values[i] = value.getAsBigDecimal().intValueExact();
                            if (i >= 2 && values[i] < 0) throw new IllegalArgumentException("Bad exclusion size");
                        }
                        if ((long) values[0] + values[2] > Integer.MAX_VALUE
                                || (long) values[1] + values[3] > Integer.MAX_VALUE)
                            throw new IllegalArgumentException("Exclusion overflow");
                        exclusions.add(values);
                    }
                }
            } catch (RuntimeException invalid) {
                respond(out, 400, "application/json", OpusJson.object("error", "Invalid HUD chrome exclusions"), null);
                return;
            }
            bridgeProvider.updateHudEditorCanvas(
                    revision.longValue(),
                    x.intValue(),
                    y.intValue(),
                    width.intValue(),
                    height.intValue(), exclusions);
            log.info("Opus HUD editor input region revision {}: {}x{} at {},{}",
                    revision, width, height, x, y);
            respond(out, 204, "application/json", new byte[0], null);
            return;
        }
        respond(out, 404, "application/json",
                OpusJson.object("error", "Not found"), null);
    }

    private String hudJson() {
        StringBuilder out = new StringBuilder("{\"hud\":[");
        List<String[]> hud = bridgeProvider == null
                ? java.util.Collections.<String[]>emptyList()
                : bridgeProvider.hudModules();
        for (int index = 0; index < hud.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            String[] row = hud.get(index);
            String id = row.length > 0 ? row[0] : "";
            String name = row.length > 1 ? row[1] : id;
            String enabled = row.length > 2 ? row[2] : "false";
            String offsetX = row.length > 3 ? row[3] : "12";
            String offsetY = row.length > 4 ? row[4] : "12";
            String scale = row.length > 5 ? row[5] : "100";
            String anchor = row.length > 6 ? row[6] : "top-left";
            out.append("{\"id\":\"").append(OpusJson.escape(id))
                    .append("\",\"name\":\"").append(OpusJson.escape(name))
                    .append("\",\"enabled\":")
                    .append("1".equals(enabled) || "true".equals(enabled)
                            ? "true" : "false")
                    .append(",\"offsetX\":\"").append(OpusJson.escape(offsetX))
                    .append("\",\"offsetY\":\"").append(OpusJson.escape(offsetY))
                    .append("\",\"scale\":\"").append(OpusJson.escape(scale))
                    .append("\",\"anchor\":\"").append(OpusJson.escape(anchor))
                    .append("\"}");
        }
        out.append("]}");
        return out.toString();
    }

    private static String accountsJson() throws IOException {
        synchronized (ACCOUNT_CATALOG_LOCK) {
            JsonObject catalog = readAccountCatalog();
            JsonArray source = catalog.has("accounts") && catalog.get("accounts").isJsonArray()
                    ? catalog.getAsJsonArray("accounts")
                    : new JsonArray();
            String selected = jsonString(catalog, "selectedAccountId");
            String currentName = currentSessionName();
            String currentKind = currentSessionKind();
            JsonArray safe = new JsonArray();
            for (JsonElement element : source) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject account = element.getAsJsonObject();
                String id = jsonString(account, "id");
                String username = jsonString(account, "username");
                String kind = jsonString(account, "kind");
                String badge = jsonString(account, "badge");
                if (id.isEmpty() || username.isEmpty()
                        || !("microsoft".equals(kind) || "offline".equals(kind))) {
                    continue;
                }
                JsonObject row = new JsonObject();
                row.addProperty("id", id);
                row.addProperty("username", username);
                row.addProperty("kind", kind);
                row.addProperty("badge", badge.isEmpty() ? kind : badge);
                row.addProperty("selected", id.equals(selected));
                boolean current = username.equals(currentName)
                        && (("official".equals(currentKind) && "microsoft".equals(kind))
                        || ("unofficial".equals(currentKind) && "offline".equals(kind)));
                row.addProperty("current", current);
                safe.add(row);
            }
            JsonObject result = new JsonObject();
            result.add("accounts", safe);
            result.addProperty("selectedAccountId", selected);
            return GSON.toJson(result);
        }
    }

    private static String selectAccount(String id) throws IOException {
        synchronized (ACCOUNT_CATALOG_LOCK) {
            File file = accountCatalogFile();
            if (file == null || !file.isFile()) {
                return null;
            }
            JsonObject catalog = readAccountCatalog();
            JsonArray source = catalog.has("accounts") && catalog.get("accounts").isJsonArray()
                    ? catalog.getAsJsonArray("accounts")
                    : new JsonArray();
            boolean found = false;
            for (JsonElement element : source) {
                if (element.isJsonObject() && id.equals(jsonString(element.getAsJsonObject(), "id"))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return null;
            }
            catalog.addProperty("selectedAccountId", id);
            writeAccountCatalog(file.toPath(), catalog);
            return accountsJson();
        }
    }

    private static JsonObject readAccountCatalog() throws IOException {
        File file = accountCatalogFile();
        if (file == null || !file.isFile()) {
            JsonObject empty = new JsonObject();
            empty.add("accounts", new JsonArray());
            empty.addProperty("selectedAccountId", "");
            return empty;
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        try {
            JsonElement parsed = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (RuntimeException ignored) {
            // Invalid launcher metadata must not break the title UI.
        }
        JsonObject empty = new JsonObject();
        empty.add("accounts", new JsonArray());
        empty.addProperty("selectedAccountId", "");
        return empty;
    }

    private static void writeAccountCatalog(Path path, JsonObject catalog) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Account catalog path has no parent");
        }
        Files.createDirectories(parent);
        Path temporary = parent.resolve(path.getFileName().toString()
                + ".opus-" + Long.toHexString(System.nanoTime()) + ".tmp");
        byte[] content = (GSON.toJson(catalog) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static File accountCatalogFile() {
        String configured = System.getProperty("opus.accounts.file", "").trim();
        return configured.isEmpty() ? null : new File(configured);
    }

    private static String jsonString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key)) {
            return "";
        }
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return "";
        }
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String currentSessionName() {
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getMinecraft();
            return minecraft.getSession() == null ? "" : minecraft.getSession().getUsername();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Reports the actual launch identity without exposing the session token. */
    private static String currentSessionKind() {
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getMinecraft();
            net.minecraft.util.Session session = minecraft.getSession();
            if (session == null) {
                return "unofficial";
            }
            if (session.getSessionType() == net.minecraft.util.Session.Type.LEGACY) {
                return "unofficial";
            }
            String token = session.getToken();
            return token == null || token.isEmpty() || "0".equals(token)
                    ? "unofficial"
                    : "official";
        } catch (Throwable ignored) {
            // The bridge must remain usable even during early client startup.
            return "unofficial";
        }
    }

    private static String nestedObject(String json, String key) {
        if (json == null || key == null) {
            return null;
        }
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        if (keyIndex < 0) {
            return null;
        }
        int start = json.indexOf('{', keyIndex + marker.length());
        if (start < 0) {
            return null;
        }
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = start; index < json.length(); index++) {
            char character = json.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    quoted = false;
                }
                continue;
            }
            if (character == '"') {
                quoted = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return json.substring(start, index + 1);
            }
        }
        return null;
    }

    private static String validateRoute(
            String routeId,
            String moduleId,
            String settingsSection) {
        if (routeId == null || routeId.isEmpty() || "none".equals(routeId)) {
            return "Invalid UI route";
        }
        if ("module_detail".equals(routeId)) {
            if (moduleId == null || moduleId.trim().isEmpty()) {
                return "module_detail requires params.moduleId";
            }
            return settingsSection == null || settingsSection.isEmpty()
                    ? null
                    : "module_detail does not accept settingsSection";
        }
        if ("settings".equals(routeId)) {
            if (moduleId != null && !moduleId.isEmpty()) {
                return "settings does not accept moduleId";
            }
            if (settingsSection == null || settingsSection.isEmpty()
                    || "interface".equals(settingsSection)
                    || "game".equals(settingsSection)) {
                return null;
            }
            return "Invalid settingsSection";
        }
        if (!isCanonicalRoute(routeId)) {
            return "Invalid UI route";
        }
        if ((moduleId != null && !moduleId.isEmpty())
                || (settingsSection != null && !settingsSection.isEmpty())) {
            return "Route parameters are not allowed for this route";
        }
        return null;
    }

    private static boolean isCanonicalRoute(String routeId) {
        return "title".equals(routeId)
                || "singleplayer".equals(routeId)
                || "multiplayer".equals(routeId)
                || "accounts".equals(routeId)
                || "game_menu".equals(routeId)
                || "quick_hub".equals(routeId)
                || "mods_catalog".equals(routeId)
                || "hud_editor".equals(routeId);
    }

    private String modulesJson() {
        StringBuilder out = new StringBuilder("{\"modules\":[");
        List<String[]> modules = bridgeProvider == null
                ? java.util.Collections.<String[]>emptyList()
                : bridgeProvider.modules();
        for (int index = 0; index < modules.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            String[] module = modules.get(index);
            String id = module.length > 0 ? module[0] : "";
            String name = module.length > 1 ? module[1] : id;
            String enabled = module.length > 2 ? module[2] : "false";
            out.append("{\"id\":\"").append(OpusJson.escape(id))
                    .append("\",\"name\":\"").append(OpusJson.escape(name))
                    .append("\",\"enabled\":").append(
                            "1".equals(enabled) || "true".equals(enabled)
                                    ? "true" : "false")
                    .append(",\"settings\":");
            appendSettings(out, id);
            out.append('}');
        }
        out.append("]}");
        return out.toString();
    }

    private void appendSettings(StringBuilder out, String moduleId) {
        out.append('[');
        List<String[]> settings = bridgeProvider == null
                ? java.util.Collections.<String[]>emptyList()
                : bridgeProvider.moduleSettings(moduleId);
        for (int index = 0; index < settings.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            String[] row = settings.get(index);
            String key = row.length > 0 ? row[0] : "";
            String label = row.length > 1 ? row[1] : key;
            String type = row.length > 2 ? row[2] : "string";
            String value = row.length > 3 ? row[3] : "";
            String min = row.length > 4 ? row[4] : "0";
            String max = row.length > 5 ? row[5] : "1";
            String step = row.length > 6 ? row[6] : "1";
            String options = row.length > 7 ? row[7] : "";
            out.append("{\"key\":\"").append(OpusJson.escape(key))
                    .append("\",\"label\":\"").append(OpusJson.escape(label))
                    .append("\",\"type\":\"").append(OpusJson.escape(type))
                    .append("\",\"value\":\"").append(OpusJson.escape(value))
                    .append("\",\"min\":\"").append(OpusJson.escape(min))
                    .append("\",\"max\":\"").append(OpusJson.escape(max))
                    .append("\",\"step\":\"").append(OpusJson.escape(step))
                    .append("\",\"options\":");
            if (options.isEmpty()) {
                out.append("[]");
            } else {
                String[] parts = options.split(",");
                out.append('[');
                for (int optionIndex = 0; optionIndex < parts.length; optionIndex++) {
                    if (optionIndex > 0) {
                        out.append(',');
                    }
                    out.append("{\"value\":\"")
                            .append(OpusJson.escape(parts[optionIndex]))
                            .append("\",\"label\":\"")
                            .append(OpusJson.escape(parts[optionIndex]))
                            .append("\"}");
                }
                out.append(']');
            }
            out.append('}');
        }
        out.append(']');
    }

    private String optionsJson() {
        StringBuilder out = new StringBuilder("{\"options\":[");
        List<String[]> options = bridgeProvider == null
                ? java.util.Collections.<String[]>emptyList()
                : bridgeProvider.gameOptions();
        for (int index = 0; index < options.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            String[] row = options.get(index);
            String key = row.length > 0 ? row[0] : "";
            String label = row.length > 1 ? row[1] : key;
            String type = row.length > 2 ? row[2] : "enum";
            String value = row.length > 3 ? row[3] : "0";
            String min = row.length > 4 ? row[4] : "0";
            String max = row.length > 5 ? row[5] : "1";
            String step = row.length > 6 ? row[6] : "1";
            out.append("{\"key\":\"").append(OpusJson.escape(key))
                    .append("\",\"label\":\"").append(OpusJson.escape(label))
                    .append("\",\"type\":\"").append(OpusJson.escape(type))
                    .append("\",\"value\":\"").append(OpusJson.escape(value))
                    .append("\",\"min\":\"").append(OpusJson.escape(min))
                    .append("\",\"max\":\"").append(OpusJson.escape(max))
                    .append("\",\"step\":\"").append(OpusJson.escape(step))
                    .append("\"}");
        }
        out.append("]}");
        return out.toString();
    }

    private String worldsJson() {
        StringBuilder out = new StringBuilder("{\"worlds\":[");
        List<String[]> worlds = bridgeProvider == null
                ? java.util.Collections.<String[]>emptyList()
                : bridgeProvider.listWorlds();
        for (int index = 0; index < worlds.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            String[] row = worlds.get(index);
            String file = row.length > 0 ? row[0] : "";
            String name = row.length > 1 ? row[1] : file;
            out.append(OpusJson.object("file", file, "name", name));
        }
        out.append("]}");
        return out.toString();
    }

    private String serversJson() {
        StringBuilder out = new StringBuilder("{\"servers\":[");
        List<String[]> servers = bridgeProvider == null
                ? java.util.Collections.<String[]>emptyList()
                : bridgeProvider.listServers();
        for (int index = 0; index < servers.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            String[] row = servers.get(index);
            String name = row.length > 0 ? row[0] : "";
            String address = row.length > 1 ? row[1] : "";
            out.append(OpusJson.object("name", name, "address", address));
        }
        out.append("]}");
        return out.toString();
    }

    private String serverStatusesJson(boolean refresh) {
        if (refresh) {
            List<String[]> servers = bridgeProvider == null
                    ? java.util.Collections.<String[]>emptyList()
                    : bridgeProvider.listServers();
            serverStatusProbe.refresh(servers);
        }

        List<ServerStatusProbe.Snapshot> statuses = serverStatusProbe.snapshots();
        StringBuilder out = new StringBuilder("{\"servers\":[");
        for (int index = 0; index < statuses.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            ServerStatusProbe.Snapshot status = statuses.get(index);
            out.append("{\"address\":\"").append(OpusJson.escape(status.address))
                    .append("\",\"state\":\"").append(OpusJson.escape(status.state))
                    .append('\"');
            if (!status.motd.isEmpty()) {
                out.append(",\"motd\":\"").append(OpusJson.escape(status.motd)).append('\"');
            }
            if (status.onlinePlayers >= 0) {
                out.append(",\"onlinePlayers\":").append(status.onlinePlayers);
            }
            if (status.maxPlayers >= 0) {
                out.append(",\"maxPlayers\":").append(status.maxPlayers);
            }
            if (!status.version.isEmpty()) {
                out.append(",\"version\":\"").append(OpusJson.escape(status.version)).append('\"');
            }
            if (status.pingMs >= 0L) {
                out.append(",\"pingMs\":").append(status.pingMs);
            }
            out.append('}');
        }
        out.append("]}");
        return out.toString();
    }

    private void serveMinecraftResource(
            String resource,
            String contentType,
            OutputStream out) throws IOException {
        InputStream stream = OpusInteropServer.class.getResourceAsStream(resource);
        if (stream == null) {
            respond(out, 404, "text/plain",
                    "Minecraft resource unavailable".getBytes(StandardCharsets.UTF_8),
                    null);
            return;
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, count);
            }
            respond(out, 200, contentType, buffer.toByteArray(), null);
        } finally {
            stream.close();
        }
    }

    private void serveSpa(
            Request request,
            OutputStream out,
            Map<String, String> extraHeaders) throws IOException {
        String resource = resourceFor(request.path);
        InputStream stream = OpusInteropServer.class.getResourceAsStream(resource);
        if (stream == null) {
            respond(out, 404, "text/plain",
                    "Opus UI is not bundled in this build.".getBytes(StandardCharsets.UTF_8),
                    extraHeaders);
            return;
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, count);
            }
            byte[] body = buffer.toByteArray();
            Map<String, String> responseHeaders = extraHeaders == null
                    ? new HashMap<String, String>()
                    : new HashMap<String, String>(extraHeaders);
            if (resource.endsWith(".mp4") || resource.endsWith(".webm")) {
                responseHeaders.put("Accept-Ranges", "bytes");
                long[] range = parseByteRange(request.headers.get("range"), body.length);
                if (range != null) {
                    int start = (int) range[0];
                    int end = (int) range[1];
                    responseHeaders.put(
                            "Content-Range",
                            "bytes " + start + "-" + end + "/" + body.length);
                    respond(
                            out,
                            206,
                            mimeFor(resource),
                            Arrays.copyOfRange(body, start, end + 1),
                            responseHeaders);
                    return;
                }
            }
            respond(out, 200, mimeFor(resource), body, responseHeaders);
        } finally {
            stream.close();
        }
    }

    private static String resourceFor(String path) {
        if (path == null || "/".equals(path) || path.isEmpty()) {
            return "/opusui/index.html";
        }
        String candidate = path.startsWith("/") ? path.substring(1) : path;
        String resource = "/opusui/" + candidate;
        if (OpusInteropServer.class.getResourceAsStream(resource) != null) {
            return resource;
        }
        return "/opusui/index.html";
    }

    private static String mimeFor(String resource) {
        if (resource.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (resource.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (resource.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (resource.endsWith(".png")) {
            return "image/png";
        }
        if (resource.endsWith(".jpg") || resource.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (resource.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (resource.endsWith(".json")) {
            return "application/json";
        }
        if (resource.endsWith(".webm")) {
            return "video/webm";
        }
        if (resource.endsWith(".mp4")) {
            return "video/mp4";
        }
        return "application/octet-stream";
    }

    private static long[] parseByteRange(String header, int length) {
        if (header == null || length <= 0 || !header.startsWith("bytes=")) {
            return null;
        }
        String value = header.substring("bytes=".length()).trim();
        if (value.indexOf(',') >= 0) {
            return null; // The embedded UI only needs a single media range.
        }
        int separator = value.indexOf('-');
        if (separator < 0) {
            return null;
        }
        try {
            String startText = value.substring(0, separator).trim();
            String endText = value.substring(separator + 1).trim();
            long start;
            long end;
            if (startText.isEmpty()) {
                long suffix = Long.parseLong(endText);
                if (suffix <= 0L) return null;
                start = Math.max(0L, length - suffix);
                end = length - 1L;
            } else {
                start = Long.parseLong(startText);
                end = endText.isEmpty() ? length - 1L : Long.parseLong(endText);
            }
            if (start < 0L || start >= length || end < start) {
                return null;
            }
            return new long[] {start, Math.min(end, length - 1L)};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean authorized(Request request) {
        Map<String, String> cookies = new HashMap<String, String>();
        String cookieHeader = request.headers.get("cookie");
        if (cookieHeader != null) {
            for (String part : cookieHeader.split(";")) {
                int separator = part.indexOf('=');
                if (separator > 0) {
                    cookies.put(
                            part.substring(0, separator).trim(),
                            part.substring(separator + 1).trim());
                }
            }
        }
        return authCode.equals(cookies.get(AUTH_COOKIE))
                || authCode.equals(request.query.get("code"));
    }

    private static String websocketAccept(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((key + WS_GUID)
                    .getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception failure) {
            return null;
        }
    }

    private static Request readRequest(BufferedInputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return null;
        }
        Request request = new Request();
        request.method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        int queryIndex = target.indexOf('?');
        if (queryIndex >= 0) {
            request.path = target.substring(0, queryIndex);
            parseQuery(target.substring(queryIndex + 1), request.query);
        } else {
            request.path = target;
        }
        String headerLine;
        while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
            int colon = headerLine.indexOf(':');
            if (colon > 0) {
                request.headers.put(
                        headerLine.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        headerLine.substring(colon + 1).trim());
            }
        }
        String contentLength = request.headers.get("content-length");
        if (contentLength != null) {
            try {
                int length = Integer.parseInt(contentLength.trim());
                if (length > 0 && length <= 1024 * 1024) {
                    byte[] body = new byte[length];
                    int offset = 0;
                    while (offset < length) {
                        int count = in.read(body, offset, length - offset);
                        if (count < 0) {
                            break;
                        }
                        offset += count;
                    }
                    request.body = new String(body, 0, offset, StandardCharsets.UTF_8);
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed content-length.
            }
        }
        return request;
    }

    private static String readLine(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = in.read()) >= 0) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1),
                        StandardCharsets.ISO_8859_1);
            }
            line.write(current);
            previous = current;
        }
        return line.size() == 0 ? null
                : new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void parseQuery(String query, Map<String, String> target) {
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            if (separator > 0) {
                target.put(
                        pair.substring(0, separator),
                        pair.substring(separator + 1));
            }
        }
    }

    private static void respond(
            OutputStream out,
            int status,
            String contentType,
            byte[] body,
            Map<String, String> extraHeaders) throws IOException {
        String statusText = statusText(status);
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(statusText)
                .append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(body == null ? 0 : body.length)
                .append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("X-Content-Type-Options: nosniff\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                head.append(entry.getKey()).append(": ")
                        .append(entry.getValue()).append("\r\n");
            }
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null && body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    private static void respond(
            OutputStream out,
            int status,
            String contentType,
            String body,
            Map<String, String> extraHeaders) throws IOException {
        respond(
                out,
                status,
                contentType,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                extraHeaders);
    }

    private static String statusText(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 206:
                return "Partial Content";
            case 204:
                return "No Content";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 404:
                return "Not Found";
            default:
                return "Unknown";
        }
    }

    private static String randomAuthCode() {
        StringBuilder code = new StringBuilder(16);
        Random random = new Random();
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int index = 0; index < 16; index++) {
            code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return code.toString();
    }

    private static ThreadFactory daemonThreads(final String prefix) {
        return new ThreadFactory() {
            private int sequence;

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable,
                        prefix + "-" + (++sequence));
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static final class Request {
        String method;
        String path;
        String body = "";
        final Map<String, String> headers = new HashMap<String, String>();
        final Map<String, String> query = new HashMap<String, String>();
    }
}
