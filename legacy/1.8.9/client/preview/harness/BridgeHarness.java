package org.polydevs.opusmc.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.interop.OpusInteropServer;
import org.polydevs.opusmc.client.interop.OpusBridgeProvider;
import org.polydevs.opusmc.client.interop.UiNavigationManager;
import org.polydevs.opusmc.client.ui.UiNavigationState;
import org.polydevs.opusmc.client.ui.UiRoute;

/**
 * Standalone verification for the Opus loopback interop bridge. Starts the
 * same server the client mod uses, then checks auth cookie, REST contract,
 * WebSocket handshake, canonical navigation events and acknowledgement.
 */
public final class BridgeHarness {
    private static int failures;

    public static void main(String[] args) throws Exception {
        verifyPreviewContract();
        verifyJavaNavigationHistory();

        Path accountCatalog = Files.createTempFile("opus-accounts-", ".json");
        Files.write(accountCatalog, ("{\"schemaVersion\":1,\"selectedAccountId\":\"offline:first\","
                + "\"accounts\":[{\"id\":\"offline:first\",\"username\":\"First\",\"uuid\":\"11111111111111111111111111111111\",\"kind\":\"offline\",\"badge\":\"unofficial\"},"
                + "{\"id\":\"offline:second\",\"username\":\"Second\",\"uuid\":\"22222222222222222222222222222222\",\"kind\":\"offline\",\"badge\":\"unofficial\"}]}\n").getBytes(StandardCharsets.UTF_8));
        System.setProperty("opus.accounts.file", accountCatalog.toString());
        final StatusTestServer statusServer = new StatusTestServer();
        final String statusAddress = "127.0.0.1:" + statusServer.port();

        Logger log = (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] {Logger.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] call)
                            throws Throwable {
                        String name = method.getName();
                        if (name.equals("isDebugEnabled") || name.equals("isTraceEnabled")) {
                            return Boolean.FALSE;
                        }
                        if (name.startsWith("is") && method.getReturnType() == boolean.class) {
                            return Boolean.TRUE;
                        }
                        return null;
                    }
                });

        OpusInteropServer server = new OpusInteropServer(log);
        UiNavigationManager navigation = new UiNavigationManager(log, server);
        final String[] lastUiAction = new String[4];
        final int[] uiActionCount = {0};
        final long[] lastUiRevision = {-1L};
        final String[] lastModuleToggle = new String[2];
        final int[] lastHudMove = new int[3];
        server.setNavigationManager(navigation);
        server.setBridgeProvider(new OpusBridgeProvider() {
            @Override
            public boolean performUiAction(
                    String action,
                    String routeId,
                    String moduleId,
                    String settingsSection,
                    long expectedRevision) {
                lastUiAction[0] = action;
                lastUiAction[1] = routeId;
                lastUiAction[2] = moduleId;
                lastUiAction[3] = settingsSection;
                uiActionCount[0]++;
                lastUiRevision[0] = expectedRevision;
                return true;
            }

            @Override
            public List<String[]> listWorlds() {
                return Collections.emptyList();
            }

            @Override
            public void loadWorld(String fileName, String displayName) {
            }

            @Override
            public List<String[]> listServers() {
                return Collections.singletonList(
                        new String[] {"Bridge status server", statusAddress});
            }

            @Override
            public void connectServer(String address) {
            }

            @Override
            public void addServer(String name, String address) {
            }

            @Override
            public List<String[]> gameOptions() {
                return Collections.emptyList();
            }

            @Override
            public void adjustGameOption(String key, int delta) {
            }

            @Override
            public void setGameOptionFloat(String key, float value) {
            }

            @Override
            public void leaveWorld() {
            }

            @Override
            public List<String[]> modules() {
                return Collections.emptyList();
            }

            @Override
            public void setModuleEnabled(String moduleId, boolean enabled) {
                lastModuleToggle[0] = moduleId;
                lastModuleToggle[1] = String.valueOf(enabled);
            }

            @Override
            public List<String[]> moduleSettings(String moduleId) {
                return Collections.emptyList();
            }

            @Override
            public void setModuleSetting(String moduleId, String key, String value) {
            }

            @Override
            public List<String[]> hudModules() {
                return Collections.emptyList();
            }

            @Override
            public void moveHudModule(String moduleId, int x, int y) {
                lastHudMove[0] = moduleId == null ? 0 : 1;
                lastHudMove[1] = x;
                lastHudMove[2] = y;
            }

            @Override
            public void updateHudEditorCanvas(long revision, int x, int y, int width, int height) {
            }
        });
        check("server.start", server.start());

        String url = server.url();
        int query = url.indexOf('?');
        int hash = url.indexOf('#');
        String origin = url.substring(0, query - 1);
        String code = url.substring(query + 1, hash).replace("code=", "");
        System.out.println("bridge url: " + url);

        HttpURLConnection root = http(origin + "/?code=" + code, "GET", null);
        int rootStatus = root.getResponseCode();
        String cookie = root.getHeaderField("Set-Cookie");
        String rootBody = read(root);
        check("root status", rootStatus == 200);
        check("cookie set", cookie != null && cookie.startsWith("opus_auth="));
        check("spa served", rootBody.contains("Opus UI") || rootBody.contains("index"));

        HttpURLConnection unauthorized = http(origin + "/api/v1/client", "GET", null);
        check("rest unauthorized", unauthorized.getResponseCode() == 401);

        HttpURLConnection hello = http(origin + "/api/v1/client", "GET", cookie);
        check("rest hello", hello.getResponseCode() == 200);
        check("rest hello body", read(hello).contains("Opus Client"));

        HttpURLConnection accounts = http(origin + "/api/v1/client/accounts", "GET", cookie);
        String accountsBody = read(accounts);
        check("rest accounts exposes safe catalog metadata", accounts.getResponseCode() == 200
                && accountsBody.contains("\"username\":\"First\"")
                && accountsBody.contains("\"selected\":true")
                && !accountsBody.contains("uuid"));

        HttpURLConnection selectAccount = http(
                origin + "/api/v1/client/accounts/select",
                "POST",
                cookie,
                "{\"id\":\"offline:second\"}");
        String selectedBody = read(selectAccount);
        check("rest account selection persists next-launch identity",
                selectAccount.getResponseCode() == 200
                        && selectedBody.contains("\"id\":\"offline:second\"")
                        && selectedBody.contains("\"selected\":true")
                        && new String(Files.readAllBytes(accountCatalog), StandardCharsets.UTF_8)
                                .contains("\"selectedAccountId\":\"offline:second\""));

        HttpURLConnection worlds = http(origin + "/api/v1/client/worlds", "GET", cookie);
        check("rest worlds", worlds.getResponseCode() == 200
                && read(worlds).contains("\"worlds\":[]"));

        HttpURLConnection servers = http(origin + "/api/v1/client/servers", "GET", cookie);
        check("rest servers", servers.getResponseCode() == 200
                && read(servers).contains("\"address\":\"" + statusAddress + "\""));

        HttpURLConnection beginStatus = http(
                origin + "/api/v1/client/servers/status", "POST", cookie);
        String beginStatusBody = read(beginStatus);
        check("rest server status begins live probe", beginStatus.getResponseCode() == 200
                && beginStatusBody.contains("\"address\":\"" + statusAddress + "\"")
                && (beginStatusBody.contains("\"state\":\"checking\"")
                        || beginStatusBody.contains("\"state\":\"online\"")));

        String statusBody = "";
        for (int attempt = 0; attempt < 25; attempt++) {
            HttpURLConnection status = http(
                    origin + "/api/v1/client/servers/status", "GET", cookie);
            statusBody = read(status);
            if (status.getResponseCode() == 200
                    && statusBody.contains("\"state\":\"online\"")) {
                break;
            }
            Thread.sleep(100L);
        }
        statusServer.awaitCompletion();
        check("rest server status exposes sanitized MOTD and telemetry",
                statusServer.failure() == null
                        && statusBody.contains("\"state\":\"online\"")
                        && statusBody.contains("\"motd\":\"Test MOTD\"")
                        && statusBody.contains("\"onlinePlayers\":7")
                        && statusBody.contains("\"maxPlayers\":64")
                        && statusBody.contains("\"version\":\"1.8.9 test\"")
                        && statusBody.contains("\"pingMs\":"));

        HttpURLConnection options = http(origin + "/api/v1/client/options", "GET", cookie);
        check("rest options", options.getResponseCode() == 200
                && read(options).contains("\"options\":[]"));

        HttpURLConnection modules = http(origin + "/api/v1/client/modules", "GET", cookie);
        check("rest modules", modules.getResponseCode() == 200
                && read(modules).contains("\"modules\":[]"));

        HttpURLConnection moduleToggle = http(
                origin + "/api/v1/client/modules/toggle",
                "POST",
                cookie,
                "{\"id\":\"fps\",\"enabled\":true}");
        check("rest module toggle accepts a JSON boolean",
                moduleToggle.getResponseCode() == 204
                        && "fps".equals(lastModuleToggle[0])
                        && "true".equals(lastModuleToggle[1]));

        HttpURLConnection hud = http(origin + "/api/v1/client/hud", "GET", cookie);
        check("rest hud", hud.getResponseCode() == 200
                && read(hud).contains("\"hud\":[]"));

        HttpURLConnection hudMove = http(
                origin + "/api/v1/client/hud/move",
                "POST",
                cookie,
                "{\"id\":\"fps\",\"x\":42,\"y\":24}");
        check("rest hud move accepts numeric coordinates",
                hudMove.getResponseCode() == 204
                        && lastHudMove[0] == 1
                        && lastHudMove[1] == 42
                        && lastHudMove[2] == 24);

        HttpURLConnection inputRegion = http(
                origin + "/api/v1/client/ui-input-region",
                "POST",
                cookie,
                "{\"revision\":1,\"x\":0,\"y\":48,\"width\":960,\"height\":420}");
        check("rest ui input region", inputRegion.getResponseCode() == 204);

        HttpURLConnection invalidExclusion = http(
                origin + "/api/v1/client/ui-input-region", "POST", cookie,
                "{\"revision\":1,\"x\":0,\"y\":0,\"width\":900,\"height\":600,"
                        + "\"exclusions\":[{\"x\":340,\"y\":210,\"width\":-20,\"height\":40}]}");
        check("HUD rejects malformed CEF input exclusions", invalidExclusion.getResponseCode() == 400);

        WsClient ws = new WsClient(origin, code);
        ws.connect();
        navigation.open("title");
        Message event = ws.readMessage(3000);
        check("ws navigation event", event != null
                && event.text.contains("\"uiNavigationChanged\"")
                && event.text.contains("\"revision\":1")
                && event.text.contains("\"title\""));
        HttpURLConnection uiState = http(origin + "/api/v1/client/ui-state", "GET", cookie);
        check("ui state", uiState.getResponseCode() == 200
                && read(uiState).contains("\"current\":{\"id\":\"title\"}"));

        HttpURLConnection uiAck = http(
                origin + "/api/v1/client/ui-state/ack",
                "POST",
                cookie,
                "{\"revision\":1}");
        check("ui revision ack", uiAck.getResponseCode() == 204);

        HttpURLConnection uiAction = http(
                origin + "/api/v1/client/ui-actions",
                "POST",
                cookie,
                "{\"action\":\"navigate\",\"revision\":1,\"route\":{\"id\":\"module_detail\","
                        + "\"params\":{\"moduleId\":\"fps\"}}}");
        check("ui action committed", uiAction.getResponseCode() == 200);
        check("ui action structured route", "navigate".equals(lastUiAction[0])
                && "module_detail".equals(lastUiAction[1])
                && "fps".equals(lastUiAction[2]));

        HttpURLConnection minecraftSettings = http(
                origin + "/api/v1/client/ui-actions",
                "POST",
                cookie,
                "{\"action\":\"minecraft-settings\",\"revision\":1}");
        check("minecraft settings action reaches Java bridge",
                minecraftSettings.getResponseCode() == 200
                        && "minecraft-settings".equals(lastUiAction[0]));

        HttpURLConnection createWorld = http(
                origin + "/api/v1/client/ui-actions", "POST", cookie,
                "{\"action\":\"create-world\",\"revision\":1}");
        check("create world reaches the real revisioned Java action bridge",
                createWorld.getResponseCode() == 200 && "create-world".equals(lastUiAction[0])
                        && lastUiRevision[0] == 1L);
        int acceptedActions = uiActionCount[0];
        HttpURLConnection staleCreateWorld = http(
                origin + "/api/v1/client/ui-actions", "POST", cookie,
                "{\"action\":\"create-world\",\"revision\":0}");
        check("stale create world does not reach the Java action provider",
                staleCreateWorld.getResponseCode() == 409 && uiActionCount[0] == acceptedActions);
        HttpURLConnection missingCreateRevision = http(
                origin + "/api/v1/client/ui-actions", "POST", cookie,
                "{\"action\":\"create-world\"}");
        check("create world requires the revision of the visible control",
                missingCreateRevision.getResponseCode() == 400 && uiActionCount[0] == acceptedActions);

        HttpURLConnection spoofedConnection = http(
                origin + "/api/v1/client/ui-actions", "POST", cookie,
                "{\"action\":\"navigate\",\"revision\":1,\"route\":{\"id\":\"connection\"}}");
        check("browser cannot fabricate a host connection route", spoofedConnection.getResponseCode() == 400
                && "create-world".equals(lastUiAction[0]));

        for (String action : new String[] {"back", "close"}) {
            HttpURLConnection stale = http(origin + "/api/v1/client/ui-actions", "POST", cookie,
                    "{\"action\":\"" + action + "\",\"revision\":0}");
            check("stale " + action + " cannot reach a replacement native session",
                    stale.getResponseCode() == 409 && uiActionCount[0] == acceptedActions);
        }
        for (String action : new String[] {"back", "close"}) {
            HttpURLConnection current = http(origin + "/api/v1/client/ui-actions", "POST", cookie,
                    "{\"action\":\"" + action + "\",\"revision\":1}");
            check("current " + action + " still reaches the native bridge",
                    current.getResponseCode() == 200 && action.equals(lastUiAction[0])
                            && lastUiRevision[0] == 1L);
        }
        HttpURLConnection revisionIndependentQuit = http(origin + "/api/v1/client/ui-actions", "POST", cookie,
                "{\"action\":\"quit\",\"revision\":0}");
        check("quit retains its revision-independent contract",
                revisionIndependentQuit.getResponseCode() == 202 && "quit".equals(lastUiAction[0]));

        navigation.close();
        Message closed = ws.readMessage(3000);
        check("ws close navigation", closed != null
                && closed.text.contains("\"uiNavigationChanged\"")
                && closed.text.contains("\"id\":\"none\""));
        ws.close();
        navigation.shutdown();
        server.stop();
        statusServer.close();
        System.clearProperty("opus.accounts.file");
        Files.deleteIfExists(accountCatalog);

        System.out.println(failures == 0
                ? "BRIDGE HARNESS: ALL PASSED"
                : "BRIDGE HARNESS: FAILURES=" + failures);
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void verifyPreviewContract() {
        JsonObject command = new JsonParser().parse(
                "{\"schemaVersion\":2,\"revision\":7,\"fixture\":\"world\","
                        + "\"route\":{\"id\":\"mods_catalog\"},"
                        + "\"input\":{\"type\":\"key\","
                        + "\"key\":\"escape\"}}")
                .getAsJsonObject();
        UiPreviewSpec spec = UiPreviewSpec.fromJson(command);
        check("preview key input parsed", spec.input.kind == UiPreviewSpec.InputKind.KEY
                && "escape".equals(spec.input.key)
                && spec.route.equals(UiRoute.modHub()));
        UiPreviewSpec noInput = UiPreviewSpec.fromJson(new JsonParser().parse(
                "{\"schemaVersion\":2,\"revision\":8,\"fixture\":\"pause-menu\","
                        + "\"route\":{\"id\":\"mods_catalog\"},"
                        + "\"input\":{\"type\":\"none\"}}")
                .getAsJsonObject());
        check("preview none input parsed", noInput.input.kind == UiPreviewSpec.InputKind.NONE);
        check("preview blocks capture before CEF frame",
                !UiPreviewSession.canCaptureSurfaceState("starting")
                        && !UiPreviewSession.canCaptureSurfaceState("failed"));
        check("preview captures only settled surfaces",
                UiPreviewSession.canCaptureSurfaceState("ready")
                        && UiPreviewSession.canCaptureSurfaceState("closed"));
    }

    private static void verifyJavaNavigationHistory() {
        UiNavigationState navigation = new UiNavigationState(
                UiRoute.quickHub(), UiNavigationState.EntryPoint.HOTKEY);
        check("navigation enters catalog", navigation.navigate(UiRoute.modHub()));
        check("navigation enters module detail",
                navigation.navigate(UiRoute.moduleDetail(FpsModule.ID)));
        check("module detail keeps hotkey flow ownership",
                navigation.isRootHotkeyFlow());
        check("escape returns to catalog", UiRoute.modHub().equals(navigation.back()));
        check("escape returns to quick hub", UiRoute.quickHub().equals(navigation.back()));
        check("escape at root requests close", navigation.back() == null);
    }

    private static void check(String name, boolean condition) {
        System.out.println((condition ? "PASS " : "FAIL ") + name);
        if (!condition) {
            failures++;
        }
    }

    private static HttpURLConnection http(String url, String method, String cookie)
            throws Exception {
        return http(url, method, cookie, null);
    }

    private static HttpURLConnection http(String url, String method, String cookie,
            String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie.split(";")[0]);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        return connection;
    }

    private static String read(HttpURLConnection connection) throws Exception {
        InputStream stream = connection.getResponseCode() < 400
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (stream == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = stream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, count);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            stream.close();
        }
    }

    private static final class StatusTestServer {
        private final ServerSocket listener;
        private final Thread thread;
        private volatile Throwable failure;

        StatusTestServer() throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve();
                }
            }, "opus-status-harness");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        Throwable failure() {
            return failure;
        }

        void awaitCompletion() throws InterruptedException {
            thread.join(3000L);
        }

        void close() throws IOException {
            listener.close();
            thread.interrupt();
        }

        private void serve() {
            try {
                Socket socket = listener.accept();
                try {
                    socket.setSoTimeout(3000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    readPacket(input); // Handshake: the probe must send it before status request.
                    DataInputStream statusRequest = packetStream(input);
                    if (readVarInt(statusRequest) != 0) {
                        throw new IOException("Expected Minecraft status request");
                    }
                    writeStatusResponse(output);

                    DataInputStream pingRequest = packetStream(input);
                    if (readVarInt(pingRequest) != 1) {
                        throw new IOException("Expected Minecraft status ping");
                    }
                    long timestamp = pingRequest.readLong();
                    writePingResponse(output, timestamp);
                } finally {
                    socket.close();
                }
            } catch (Throwable caught) {
                if (!listener.isClosed()) {
                    failure = caught;
                }
            }
        }

        private static void writeStatusResponse(DataOutputStream output) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream body = new DataOutputStream(buffer);
            writeVarInt(body, 0);
            writeString(body, "{\"description\":{\"text\":\"§bTest \",\"extra\":[{\"text\":\"MOTD\"}]},"
                    + "\"players\":{\"online\":7,\"max\":64},"
                    + "\"version\":{\"name\":\"1.8.9 test\"}}");
            writePacket(output, buffer.toByteArray());
        }

        private static void writePingResponse(DataOutputStream output, long timestamp)
                throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream body = new DataOutputStream(buffer);
            writeVarInt(body, 1);
            body.writeLong(timestamp);
            writePacket(output, buffer.toByteArray());
        }

        private static void readPacket(DataInputStream input) throws IOException {
            packetStream(input);
        }

        private static DataInputStream packetStream(DataInputStream input) throws IOException {
            int length = readVarInt(input);
            if (length <= 0 || length > 65536) {
                throw new IOException("Invalid test status packet length");
            }
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            return new DataInputStream(new ByteArrayInputStream(bytes));
        }

        private static void writePacket(DataOutputStream output, byte[] bytes) throws IOException {
            writeVarInt(output, bytes.length);
            output.write(bytes);
            output.flush();
        }

        private static void writeString(DataOutputStream output, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            writeVarInt(output, bytes.length);
            output.write(bytes);
        }

        private static void writeVarInt(DataOutputStream output, int value) throws IOException {
            do {
                int part = value & 0x7F;
                value >>>= 7;
                if (value != 0) {
                    part |= 0x80;
                }
                output.writeByte(part);
            } while (value != 0);
        }

        private static int readVarInt(DataInputStream input) throws IOException {
            int result = 0;
            int position = 0;
            while (true) {
                int part = input.readUnsignedByte();
                result |= (part & 0x7F) << (position++ * 7);
                if (position > 5) {
                    throw new IOException("Test status varint is too large");
                }
                if ((part & 0x80) == 0) {
                    return result;
                }
            }
        }
    }

    private static final class WsClient {
        private final URI uri;
        private Socket socket;
        private InputStream in;
        private OutputStream out;
        private final Random random = new Random();

        WsClient(String origin, String code) {
            uri = URI.create(origin.replace("http", "ws") + "/ws?code=" + code);
        }

        void connect() throws Exception {
            socket = new Socket(uri.getHost(), uri.getPort());
            socket.setSoTimeout(3000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            String key = Base64.getEncoder().encodeToString(
                    ("harness-" + random.nextInt()).getBytes(StandardCharsets.UTF_8));
            String request = "GET " + uri.getRawPath() + "?" + uri.getRawQuery()
                    + " HTTP/1.1\r\n"
                    + "Host: " + uri.getHost() + ":" + uri.getPort() + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            StringBuilder head = new StringBuilder();
            int previous = -1;
            int current;
            while ((current = in.read()) >= 0) {
                head.append((char) current);
                if (previous == '\r' && current == '\n'
                        && head.toString().endsWith("\r\n\r\n")) {
                    break;
                }
                previous = current;
            }
            if (!head.toString().contains("101")) {
                throw new IllegalStateException("handshake failed: " + head);
            }
            byte[] accept = MessageDigest.getInstance("SHA-1").digest(
                    (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                            .getBytes(StandardCharsets.ISO_8859_1));
            String expected = Base64.getEncoder().encodeToString(accept);
            if (!head.toString().contains(expected)) {
                throw new IllegalStateException("bad accept key");
            }
        }

        Message readMessage(int timeoutMillis) throws Exception {
            socket.setSoTimeout(timeoutMillis);
            try {
                int first = in.read();
                if (first < 0) {
                    return null;
                }
                int second = in.read();
                int opcode = first & 0x0F;
                int length = second & 0x7F;
                if (length == 126) {
                    length = (in.read() << 8) | in.read();
                } else if (length == 127) {
                    long extended = 0;
                    for (int index = 0; index < 8; index++) {
                        extended = (extended << 8) | in.read();
                    }
                    length = (int) extended;
                }
                byte[] payload = new byte[length];
                int offset = 0;
                while (offset < length) {
                    int count = in.read(payload, offset, length - offset);
                    if (count < 0) {
                        break;
                    }
                    offset += count;
                }
                return new Message(opcode,
                        new String(payload, 0, offset, StandardCharsets.UTF_8));
            } catch (java.net.SocketTimeoutException timeout) {
                return null;
            }
        }

        void close() throws Exception {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private static final class Message {
        final int opcode;
        final String text;

        Message(int opcode, String text) {
            this.opcode = opcode;
            this.text = text;
        }
    }
}
