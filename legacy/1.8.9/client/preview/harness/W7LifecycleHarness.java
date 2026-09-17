package org.polydevs.opusmc.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Locale;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;

public final class W7LifecycleHarness {
    private static final long TIMEOUT_MS = 30000L;

    public static void main(String[] args) throws Exception {
        Logger log = logger();
        String helper = OpusWebViewClient.resolveHelperPath(log);
        require(helper != null, "CEF helper unavailable");
        File dist = new File(System.getProperty("opus.ui.v2.dist", ""));
        require(new File(dist, "index.html").isFile(), "React dist missing");
        HttpServer server = staticServer(dist);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/index.html";
        OpusWebViewClient client = null;
        try {
            client = new OpusWebViewClient(log, helper);
            require(client.start(960, 540, 2.0F, url), "initial helper start failed");
            awaitFrame(client, 960, 540, 2.0F);
            pass("initial helper renders React surface");

            long reloadGeneration = client.reload();
            require(reloadGeneration > 0L, "reload did not allocate a new generation");
            awaitFrame(client, 960, 540, 2.0F);
            require(client.hasPublishedGeneration(reloadGeneration),
                    "reload generation was not published before readiness");
            pass("reload waits for a newly published CEF generation");

            int[][] sizes = {{1280, 720}, {800, 500}, {1440, 900}, {960, 540}};
            float[] scales = {1.5F, 2.0F, 1.25F, 2.0F};
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < sizes.length; i++) {
                    int rasterWidth = Math.round(sizes[i][0] * scales[i]);
                    int rasterHeight = Math.round(sizes[i][1] * scales[i]);
                    System.out.println("TRACE resize " + (round + 1) + "/3." + (i + 1)
                            + " -> " + sizes[i][0] + "x" + sizes[i][1] + " @ " + scales[i]
                            + " (" + rasterWidth + "x" + rasterHeight + ")");
                    client.resize(sizes[i][0], sizes[i][1], scales[i]);
                    awaitFrame(client, sizes[i][0], sizes[i][1], scales[i]);
                }
            }
            pass("12 consecutive resize/DPR transitions remain live");

            Process process = helperProcess(client);
            process.destroyForcibly();
            process.waitFor();
            awaitFailed(client);
            pass("forced helper death enters fail-closed state");
            client.stop();
            client = null;

            client = new OpusWebViewClient(log, helper);
            require(client.start(960, 540, 2.0F, url), "recovery helper start failed");
            awaitFrame(client, 960, 540, 2.0F);
            pass("fresh helper recovers after renderer process death");
        } finally {
            if (client != null) client.stop();
            server.stop(0);
        }
        System.out.println("W7 LIFECYCLE: ALL PASSED");
    }

    private static void awaitFrame(OpusWebViewClient client, int w, int h, float scale) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            require(!client.isFailed(), "helper failed while waiting for frame");
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                try {
                    if (client.isCurrent(frame, w, h, scale)) return;
                } finally {
                    client.release(frame);
                }
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("timed out waiting for current frame");
    }

    private static void awaitFailed(OpusWebViewClient client) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (client.isFailed()) return;
            Thread.sleep(20L);
        }
        throw new IllegalStateException("helper death did not propagate to failed state");
    }

    private static Process helperProcess(OpusWebViewClient client) throws Exception {
        Field field = OpusWebViewClient.class.getDeclaredField("helper");
        field.setAccessible(true);
        Process process = (Process) field.get(client);
        require(process != null && process.isAlive(), "helper process is not alive");
        return process;
    }

    private static HttpServer staticServer(final File dist) throws IOException {
        final File root = dist.getCanonicalFile();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override public void handle(HttpExchange exchange) throws IOException {
                String path = exchange.getRequestURI().getPath();
                if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
                File target = new File(root, path.substring(1)).getCanonicalFile();
                if ((!target.equals(root) && !target.getPath().startsWith(root.getPath() + File.separator)) || !target.isFile()) {
                    byte[] body = "not found".getBytes("UTF-8");
                    exchange.sendResponseHeaders(404, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", contentType(target.getName()));
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, Files.size(target.toPath()));
                FileInputStream in = new FileInputStream(target);
                OutputStream out = exchange.getResponseBody();
                try {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
                } finally {
                    in.close(); out.close();
                }
            }
        });
        server.start();
        return server;
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private static Logger logger() {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[] {Logger.class}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                if (method.getName().startsWith("is") && method.getReturnType() == boolean.class) return Boolean.FALSE;
                if (("warn".equals(method.getName()) || "error".equals(method.getName()))
                        && args != null && args.length > 0) {
                    System.out.println(method.getName().toUpperCase(Locale.ROOT) + " " + String.valueOf(args[0]));
                }
                return null;
            }
        });
    }

    private static void pass(String message) { System.out.println("PASS " + message); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
