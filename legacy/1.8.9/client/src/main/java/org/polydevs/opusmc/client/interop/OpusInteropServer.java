package org.polydevs.opusmc.client.interop;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.List;
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

    private final Logger log;
    private final String authCode;
    private final List<OpusWebSocket> sessions = new CopyOnWriteArrayList<OpusWebSocket>();
    private final ExecutorService workers;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile int port = -1;
    private volatile boolean running;
    private OpusVirtualScreenManager screenManager;
    private OpusBridgeProvider bridgeProvider;

    public OpusInteropServer(Logger log) {
        this.log = log;
        this.authCode = randomAuthCode();
        this.workers = Executors.newCachedThreadPool(daemonThreads("opus-bridge"));
    }

    public void setScreenManager(OpusVirtualScreenManager screenManager) {
        this.screenManager = screenManager;
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
    }

    public int port() {
        return port;
    }

    public String url() {
        return "http://127.0.0.1:" + port + "/?code=" + authCode + "#/title";
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
        if ("/api/v1/client".equals(request.path) && "GET".equals(request.method)) {
            respond(out, 200, "application/json",
                    OpusJson.object(
                            "name", "Opus Client",
                            "version", "0.0.1",
                            "minecraft", "1.8.9",
                            "forge", "11.15.1.2318",
                            "optifine", "HD U M5"),
                    null);
            return;
        }
        if ("/api/v1/client/virtualScreen".equals(request.path)) {
            if ("GET".equals(request.method)) {
                String current = screenManager == null ? "" : screenManager.current();
                respond(out, 200, "application/json",
                        OpusJson.object("name", current),
                        null);
                return;
            }
            if ("POST".equals(request.method)) {
                String name = OpusJson.extractString(request.body, "name");
                if (screenManager != null) {
                    screenManager.confirm(name == null ? "" : name);
                }
                respond(out, 204, "application/json", new byte[0], null);
                return;
            }
        }
        if ("/api/v1/client/screen".equals(request.path) && screenManager != null) {
            if ("PUT".equals(request.method)) {
                String name = OpusJson.extractString(request.body, "name");
                screenManager.open(name == null ? "" : name);
                respond(out, 204, "application/json", new byte[0], null);
                return;
            }
            if ("DELETE".equals(request.method)) {
                screenManager.close();
                respond(out, 204, "application/json", new byte[0], null);
                return;
            }
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
            String delta = OpusJson.extractString(request.body, "delta");
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
            String value = OpusJson.extractString(request.body, "value");
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
        if ("/api/v1/client/screen/leave-world".equals(request.path)
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
            String enabled = OpusJson.extractString(request.body, "enabled");
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
        respond(out, 404, "application/json",
                OpusJson.object("error", "Not found"), null);
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
            respond(
                    out,
                    200,
                    mimeFor(resource),
                    buffer.toByteArray(),
                    extraHeaders);
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
        if (resource.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (resource.endsWith(".json")) {
            return "application/json";
        }
        return "application/octet-stream";
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
