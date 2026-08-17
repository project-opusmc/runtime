import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.interop.OpusInteropServer;
import org.polydevs.opusmc.client.interop.OpusBridgeProvider;
import org.polydevs.opusmc.client.interop.OpusVirtualScreenManager;

/**
 * Standalone verification for the Opus loopback interop bridge. Starts the
 * same server the client mod uses, then checks auth cookie, REST contract,
 * WebSocket handshake, virtual-screen events and acknowledgement.
 */
public final class BridgeHarness {
    private static int failures;

    public static void main(String[] args) throws Exception {
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
        OpusVirtualScreenManager screens = new OpusVirtualScreenManager(log, server);
        server.setScreenManager(screens);
        server.setBridgeProvider(new OpusBridgeProvider() {
            @Override
            public List<String[]> listWorlds() {
                return Collections.emptyList();
            }

            @Override
            public void loadWorld(String fileName, String displayName) {
            }

            @Override
            public List<String[]> listServers() {
                return Collections.emptyList();
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

        HttpURLConnection worlds = http(origin + "/api/v1/client/worlds", "GET", cookie);
        check("rest worlds", worlds.getResponseCode() == 200
                && read(worlds).contains("\"worlds\":[]"));

        HttpURLConnection servers = http(origin + "/api/v1/client/servers", "GET", cookie);
        check("rest servers", servers.getResponseCode() == 200
                && read(servers).contains("\"servers\":[]"));

        HttpURLConnection options = http(origin + "/api/v1/client/options", "GET", cookie);
        check("rest options", options.getResponseCode() == 200
                && read(options).contains("\"options\":[]"));

        WsClient ws = new WsClient(origin, code);
        ws.connect();
        screens.open("title");
        Message event = ws.readMessage(3000);
        check("ws virtualScreen event", event != null
                && event.text.contains("\"virtualScreen\"")
                && event.text.contains("\"title\""));

        HttpURLConnection current =
                http(origin + "/api/v1/client/virtualScreen", "GET", cookie);
        check("current screen", read(current).contains("\"title\""));

        HttpURLConnection ack = http(
                origin + "/api/v1/client/virtualScreen",
                "POST",
                cookie,
                "{\"name\":\"title\"}");
        check("ack status", ack.getResponseCode() == 204);

        screens.close();
        Message closed = ws.readMessage(3000);
        check("ws close event", closed != null && closed.text.contains("\"close\""));

        ws.close();
        server.stop();

        System.out.println(failures == 0
                ? "BRIDGE HARNESS: ALL PASSED"
                : "BRIDGE HARNESS: FAILURES=" + failures);
        if (failures != 0) {
            System.exit(1);
        }
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
