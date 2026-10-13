package org.polydevs.opusmc.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;

public final class W7LifecycleHarness {
    private static final long TIMEOUT_MS = 30000L;

    public static void main(String[] args) throws Exception {
        verifyLeaseState();
        verifyPauseBlurLease();
        verifyLifecycleShellPolicy();
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

            long committedGeneration = client.requestCommittedFrame();
            require(committedGeneration > 0L, "committed route did not allocate a fresh frame generation");
            awaitFrame(client, 960, 540, 2.0F);
            require(client.hasPublishedGeneration(committedGeneration),
                    "committed route accepted a frame from before React rendered it");
            pass("route commitment requires a fresh current CEF generation");

            verifyParkShow(client);

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

            long dragStart = System.currentTimeMillis();
            for (int i = 0; i < 60; i++) {
                client.resize(960 + i * 4, 540 + i * 2, 2.0F);
            }
            awaitFrame(client, 1196, 658, 2.0F);
            pass("60 rapid resize steps converge on the final browser layout in "
                    + (System.currentTimeMillis() - dragStart) + " ms");

            client.setSurfaceVisible(false);
            Process parked = helperProcess(client);
            parked.destroyForcibly();
            parked.waitFor();
            awaitFailed(client);
            pass("forced helper death while parked fails closed");
            client.stop();
            client = new OpusWebViewClient(log, helper);
            require(client.start(960, 540, 2.0F, url), "recovery after parked death failed");
            awaitFrame(client, 960, 540, 2.0F);

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

    private static void verifyLeaseState() {
        EmbeddedSurfaceLeaseState leases = new EmbeddedSurfaceLeaseState();
        long first = leases.attach();
        long second = leases.attach();
        require(first != second && leases.isCurrent(second), "new screen did not own the surface");
        require(!leases.detach(first), "stale screen detached the new screen's surface");
        require(leases.isCurrent(second), "stale close invalidated the current lease");
        require(leases.detach(second), "current screen could not release its lease");
        require(!leases.isCurrent(second), "released lease remained current");
        long third = leases.attach();
        leases.clear();
        require(!leases.isCurrent(third) && !leases.detach(third),
                "shutdown/retry did not revoke the active lease");
        pass("stale screen release cannot park a newer screen");
    }

    private static void verifyPauseBlurLease() throws Exception {
        Class<?> leaseClass = Class.forName("org.polydevs.opusmc.client.PauseWorldBlur");
        Class<?> hostClass = Class.forName("org.polydevs.opusmc.client.PauseWorldBlur$ShaderHost");
        final Object[] current = {null};
        final int[] loads = {0};
        final int[] stops = {0};
        Object host = Proxy.newProxyInstance(hostClass.getClassLoader(), new Class<?>[] {hostClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("currentShader".equals(method.getName())) return current[0];
                        if ("loadPauseShader".equals(method.getName())) {
                            current[0] = new Object();
                            loads[0]++;
                            return current[0];
                        }
                        if ("stopShader".equals(method.getName())) {
                            current[0] = null;
                            stops[0]++;
                        }
                        return null;
                    }
                });
        java.lang.reflect.Constructor<?> constructor = leaseClass.getDeclaredConstructor(hostClass);
        constructor.setAccessible(true);
        Object lease = constructor.newInstance(host);
        Method update = leaseClass.getDeclaredMethod("update", boolean.class);
        update.setAccessible(true);
        update.invoke(lease, true);
        update.invoke(lease, true);
        require(loads[0] == 1 && current[0] != null, "pause blur reloaded on each frame");
        update.invoke(lease, false);
        require(stops[0] == 1 && current[0] == null, "pause blur survived menu close");

        Object otherShader = new Object();
        current[0] = otherShader;
        update.invoke(lease, true);
        require(loads[0] == 1 && current[0] == otherShader,
                "pause blur replaced a shader owned by Minecraft or another mod");
        update.invoke(lease, false);
        require(stops[0] == 1 && current[0] == otherShader,
                "closing pause removed a shader owned by another component");
        current[0] = null;
        update.invoke(lease, true);
        require(loads[0] == 2, "pause blur did not return on a later pause session");
        current[0] = otherShader;
        update.invoke(lease, false);
        require(stops[0] == 1 && current[0] == otherShader,
                "closing pause removed a shader that replaced Opus's blur");
        pass("pause blur owns only its shader and releases it across routes");
    }

    private static void verifyLifecycleShellPolicy() {
        require(!UiLifecycleShellPolicy.showText(179L, false),
                "normal wait displayed loading text before 180 ms");
        require(!UiLifecycleShellPolicy.showText(180L, false),
                "normal frame wait displayed internal loading text");
        require(UiLifecycleShellPolicy.showText(0L, true),
                "explicit failure did not display immediately");
        require(!UiLifecycleShellPolicy.failedWithoutView(true, false),
                "an in-flight prewarm was misclassified as failed");
        require(!UiLifecycleShellPolicy.failedWithoutView(false, false),
                "a successful prewarm finishing between draw checks became a false failure");
        require(UiLifecycleShellPolicy.failedWithoutView(false, true),
                "a genuinely failed prewarm did not enter the failure shell");
        require(!UiLifecycleShellPolicy.acceptCommittedFrame(7L, -1L, 7L, true),
                "a pre-ACK/old-DOM frame was accepted before a fresh request");
        require(!UiLifecycleShellPolicy.acceptCommittedFrame(7L, 7L, 7L, false),
                "a frame was accepted after navigation lost confirmation");
        require(!UiLifecycleShellPolicy.acceptCommittedFrame(7L, 7L, 8L, true),
                "a frame was accepted for an obsolete Java revision");
        require(UiLifecycleShellPolicy.acceptCommittedFrame(7L, 7L, 7L, true),
                "a fresh post-commit frame remained blocked");
        require(UiLifecycleShellPolicy.drawTexture(false, true, true, true),
                "a route transition flashed the lifecycle shell despite a retained frame");
        require(!UiLifecycleShellPolicy.drawTexture(false, true, false, true),
                "a transition without a retained frame concealed the lifecycle shell");
        require(!UiLifecycleShellPolicy.drawTexture(false, true, true, false),
                "a stale or hidden lease displayed an old frame");
        require(!UiLifecycleShellPolicy.drawTexture(false, false, true, true),
                "a failed or closed surface displayed an old frame");
        require(UiLifecycleShellPolicy.drawTexture(true, false, true, true),
                "a committed current frame was not drawn");
        pass("loading text grace and immediate failure boundary");
    }

    private static void verifyParkShow(OpusWebViewClient client) throws Exception {
        Process initialProcess = helperProcess(client);
        ArrayList<Long> standardMillis = new ArrayList<Long>();
        ArrayList<Long> retinaMillis = new ArrayList<Long>();
        for (int cycle = 0; cycle < 20; cycle++) {
            client.setSurfaceVisible(false);
            require(!client.isSurfaceVisible(), "park did not disable the surface");
            // The reader may already be processing a FRAME when HIDE is sent;
            // require its RELEASE to drain rather than assuming an instant ACK.
            long slotDeadline = System.currentTimeMillis() + 1000L;
            while (client.outstandingFrameCount() != 0
                    && System.currentTimeMillis() < slotDeadline) {
                Thread.sleep(10L);
            }
            require(client.outstandingFrameCount() == 0, "park retained a shared frame slot");
            long parkedUntil = System.currentTimeMillis() + (cycle == 0 ? 10000L : 60L);
            long parkedSince = System.currentTimeMillis();
            boolean measuredIdle = false;
            while (System.currentTimeMillis() < parkedUntil) {
                OpusWebViewClient.Frame hiddenFrame = client.consumeFrame();
                if (hiddenFrame != null) {
                    client.release(hiddenFrame);
                    throw new IllegalStateException("parked CEF published a frame");
                }
                require(initialProcess.isAlive(), "park unexpectedly stopped helper");
                if (cycle == 0 && !measuredIdle
                        && System.currentTimeMillis() - parkedSince >= 5000L) {
                    reportParkedProcessCost(initialProcess);
                    measuredIdle = true;
                }
                Thread.sleep(10L);
            }
            boolean retina = cycle % 2 != 0;
            int width = retina ? 427 : 854;
            int height = retina ? 240 : 480;
            float scale = retina ? 2.0F : 1.0F;
            client.resize(width, height, scale);
            long shownAt = System.nanoTime();
            client.setSurfaceVisible(true);
            require(client.isSurfaceVisible(), "show did not enable the surface");
            awaitFrame(client, width, height, scale);
            long elapsedMillis = (System.nanoTime() - shownAt) / 1000000L;
            (retina ? retinaMillis : standardMillis).add(Long.valueOf(elapsedMillis));
            require(helperProcess(client) == initialProcess, "park/show restarted CEF helper");
        }
        reportLatency("854x480@1x", standardMillis);
        reportLatency("427x240@2x", retinaMillis);
        pass("20 park/show cycles retain one helper and current geometry");
        client.resize(960, 540, 2.0F);
        awaitFrame(client, 960, 540, 2.0F);
    }

    private static void reportParkedProcessCost(Process helper) {
        try {
            Field pidField = helper.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            int rootPid = pidField.getInt(helper);
            Process sample = new ProcessBuilder("/bin/ps", "-axo",
                    "pid=,ppid=,%cpu=,rss=,comm=").start();
            Map<Integer, int[]> processes = new HashMap<Integer, int[]>();
            BufferedReader lines = new BufferedReader(new InputStreamReader(
                    sample.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = lines.readLine()) != null) {
                String[] fields = line.trim().split("\\s+", 5);
                if (fields.length < 4) continue;
                try {
                    processes.put(Integer.valueOf(Integer.parseInt(fields[0])),
                            new int[] {Integer.parseInt(fields[1]),
                                    Math.round(Float.parseFloat(fields[2]) * 10.0F),
                                    Integer.parseInt(fields[3])});
                } catch (NumberFormatException ignored) {
                    // Another process disappeared between ps enumeration and parse.
                }
            }
            sample.waitFor();
            Set<Integer> tree = new HashSet<Integer>();
            tree.add(Integer.valueOf(rootPid));
            boolean changed;
            do {
                changed = false;
                for (Map.Entry<Integer, int[]> entry : processes.entrySet()) {
                    if (tree.contains(Integer.valueOf(entry.getValue()[0]))
                            && tree.add(entry.getKey())) changed = true;
                }
            } while (changed);
            int cpuTenths = 0;
            long rssKib = 0L;
            int count = 0;
            for (Integer pid : tree) {
                int[] values = processes.get(pid);
                if (values == null) continue;
                cpuTenths += values[1];
                rssKib += values[2];
                count++;
            }
            System.out.println("METRIC parked-CEF process_count=" + count
                    + " sampled_cpu=" + (cpuTenths / 10.0F) + "% aggregate_rss_kib=" + rssKib
                    + " (RSS sums shared pages; stopped-helper baseline is 0)");
        } catch (Exception unavailable) {
            System.out.println("METRIC parked-CEF CPU/RSS unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    private static void reportLatency(String label, ArrayList<Long> measurements) {
        Collections.sort(measurements);
        int p95Index = Math.max(0, (int) Math.ceil(measurements.size() * 0.95D) - 1);
        long median = measurements.get(measurements.size() / 2).longValue();
        long p95 = measurements.get(p95Index).longValue();
        System.out.println("METRIC warm-show " + label + " median=" + median + "ms p95=" + p95 + "ms");
        if ("854x480@1x".equals(label)) {
            require(p95 <= 150L, "warm show p95 exceeds 150 ms at 854x480: " + p95 + "ms");
        }
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
