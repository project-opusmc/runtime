package org.polydevs.opusmc.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Locale;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;
import org.polydevs.opusmc.websurface.OpusDisplayMetrics;

public final class W5ReactSurfaceHarness {
    private static final long TIMEOUT_MS = 45000L;
    private static final int HOME = 0x2A4CFF;
    private static final int MODULES_ON = 0x19B87A;
    private static final int MODULES_OFF = 0xE14E66;
    private static final int SETTINGS = 0x8A5CFF;
    private static final int SETTINGS_CHANGED = 0xE99B39;

    public static void main(String[] args) throws Exception {
        Logger log = logger();
        String helper = OpusWebViewClient.resolveHelperPath(log);
        require(helper != null, "CEF helper unavailable");
        String distPath = System.getProperty("opus.ui.v2.dist", "");
        File dist = new File(distPath);
        File index = new File(dist, "index.html");
        require(index.isFile(), "React V2 dist/index.html is missing: " + index);
        HttpServer staticServer = startStaticServer(dist);
        String url = "http://127.0.0.1:" + staticServer.getAddress().getPort() + "/index.html";

        OpusWebViewClient client = new OpusWebViewClient(log, helper);
        try {
            require(client.start(1280, 720, 1.5F, url), "CEF helper did not start React V2");
            awaitOracle(client, 1280, 720, 1.5F, HOME);
            System.out.println("PASS real React production bundle reaches Home oracle");

            click(client, 70, 200, 0);
            awaitOracle(client, 1280, 720, 1.5F, MODULES_ON);
            System.out.println("PASS native pointer routes Home -> Modules in React bundle");

            // The route oracle can be painted before the local variable font has
            // completed its final layout pass. Give Chromium one bounded settle
            // interval before exercising the fixed native hit point.
            Thread.sleep(250L);
            client.sendMouseMove(1230, 694);
            Thread.sleep(50L);
            click(client, 1230, 694, 0);
            awaitOracle(client, 1280, 720, 1.5F, MODULES_OFF);
            System.out.println("PASS native pointer toggles selected React module");

            key(client, 0x53); // S
            awaitOracle(client, 1280, 720, 1.5F, SETTINGS);
            System.out.println("PASS native keyboard S routes Modules -> Settings");

            click(client, 500, 194, 0);
            awaitOracle(client, 1280, 720, 1.5F, SETTINGS_CHANGED);
            System.out.println("PASS settings pointer interaction repaints deterministic React state");

            key(client, 0x48); // H
            awaitOracle(client, 1280, 720, 1.5F, HOME);
            System.out.println("PASS native keyboard H routes Settings -> Home");

            key(client, 0x4D); // M
            awaitOracle(client, 1280, 720, 1.5F, MODULES_OFF);
            System.out.println("PASS native keyboard M returns to Modules and preserves state");

            client.resize(900, 600, 2.0F);
            awaitOracle(client, 900, 600, 2.0F, MODULES_OFF);
            System.out.println("PASS React bundle remains current after 900x600 @2x resize");
        } finally {
            client.stop();
            staticServer.stop(0);
        }

        System.out.println("W5 REACT CEF ORACLE: ALL PASSED");
    }

    private static HttpServer startStaticServer(final File dist) throws IOException {
        final File root = dist.getCanonicalFile();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String path = exchange.getRequestURI().getPath();
                if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
                File target = new File(root, path.substring(1)).getCanonicalFile();
                String rootPath = root.getPath() + File.separator;
                if ((!target.equals(root) && !target.getPath().startsWith(rootPath)) || !target.isFile()) {
                    byte[] notFound = "not found".getBytes("UTF-8");
                    exchange.sendResponseHeaders(404, notFound.length);
                    OutputStream out = exchange.getResponseBody();
                    out.write(notFound);
                    out.close();
                    return;
                }
                String contentType = contentType(target.getName());
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                long size = Files.size(target.toPath());
                exchange.sendResponseHeaders(200, size);
                FileInputStream in = new FileInputStream(target);
                OutputStream out = exchange.getResponseBody();
                try {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
                } finally {
                    in.close();
                    out.close();
                }
            }
        });
        server.setExecutor(null);
        server.start();
        return server;
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    private static void click(OpusWebViewClient client, int x, int y, int button) {
        client.sendMouseMove(x, y);
        client.sendMouseButton(x, y, button, true);
        client.sendMouseButton(x, y, button, false);
    }

    private static void key(OpusWebViewClient client, int windowsVirtualKey) {
        client.sendKey(windowsVirtualKey, true);
        client.sendKey(windowsVirtualKey, false);
    }

    private static void awaitOracle(OpusWebViewClient client, int logicalWidth,
            int logicalHeight, float scale, int expectedRgb) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        int last = -1;
        while (System.currentTimeMillis() < deadline) {
            require(!client.isFailed(), "CEF helper entered failed state");
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                try {
                    if (client.isCurrent(frame, logicalWidth, logicalHeight, scale)) {
                        last = oracleRgb(frame, logicalWidth, logicalHeight);
                        if (last == expectedRgb
                                || oracleRegionContains(frame, logicalWidth, logicalHeight, expectedRgb)) return;
                    }
                } finally {
                    client.release(frame);
                }
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("timed out waiting for React oracle #"
                + Integer.toHexString(expectedRgb) + "; last=#" + Integer.toHexString(last));
    }

    private static boolean oracleRegionContains(OpusWebViewClient.Frame frame, int logicalWidth,
            int logicalHeight, int expectedRgb) {
        OpusDisplayMetrics metrics = new OpusDisplayMetrics(
                1L, logicalWidth, logicalHeight, frame.width, frame.height);
        int minX = metrics.logicalToFramebufferX(Math.max(0, logicalWidth - 32));
        int maxX = metrics.logicalToFramebufferX(logicalWidth - 1);
        int minY = metrics.logicalToFramebufferTopY(Math.max(0, logicalHeight - 32));
        int maxY = metrics.logicalToFramebufferTopY(logicalHeight - 1);
        ByteBuffer pixels = frame.pixels.duplicate();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                int offset = (y * frame.width + x) * 4;
                int blue = pixels.get(offset) & 0xFF;
                int green = pixels.get(offset + 1) & 0xFF;
                int red = pixels.get(offset + 2) & 0xFF;
                if (((red << 16) | (green << 8) | blue) == expectedRgb) return true;
            }
        }
        return false;
    }

    private static int oracleRgb(OpusWebViewClient.Frame frame, int logicalWidth, int logicalHeight) {
        OpusDisplayMetrics metrics = new OpusDisplayMetrics(
                1L, logicalWidth, logicalHeight, frame.width, frame.height);
        int x = metrics.logicalToFramebufferX(logicalWidth - 12);
        int y = metrics.logicalToFramebufferTopY(logicalHeight - 12);
        int offset = (y * frame.width + x) * 4;
        ByteBuffer pixels = frame.pixels.duplicate();
        int blue = pixels.get(offset) & 0xFF;
        int green = pixels.get(offset + 1) & 0xFF;
        int red = pixels.get(offset + 2) & 0xFF;
        return (red << 16) | (green << 8) | blue;
    }

    private static Logger logger() {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(),
                new Class<?>[] {Logger.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] call) {
                        String name = method.getName();
                        if (name.startsWith("is") && method.getReturnType() == boolean.class) {
                            return Boolean.FALSE;
                        }
                        if (("warn".equals(name) || "error".equals(name) || "info".equals(name))
                                && call != null && call.length > 0) {
                            String rendered = format(String.valueOf(call[0]), call);
                            if (rendered.contains("[helper]") || !"info".equals(name)) {
                                System.out.println(name.toUpperCase(Locale.ROOT) + " " + rendered);
                            }
                            for (Object value : call) {
                                if (value instanceof Throwable) {
                                    StringWriter stack = new StringWriter();
                                    ((Throwable) value).printStackTrace(new PrintWriter(stack));
                                    System.out.print(stack.toString());
                                }
                            }
                        }
                        return null;
                    }
                });
    }

    private static String format(String message, Object[] call) {
        String formatted = message;
        if (call == null) return formatted;
        for (int index = 1; index < call.length; index++) {
            Object value = call[index];
            if (value instanceof Object[]) {
                for (Object nested : (Object[]) value) {
                    formatted = formatted.replaceFirst("\\{\\}",
                            java.util.regex.Matcher.quoteReplacement(String.valueOf(nested)));
                }
            } else if (!(value instanceof Throwable)) {
                formatted = formatted.replaceFirst("\\{\\}",
                        java.util.regex.Matcher.quoteReplacement(String.valueOf(value)));
            }
        }
        return formatted;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
