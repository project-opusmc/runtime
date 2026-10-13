package org.polydevs.opusmc.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
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
import javax.imageio.ImageIO;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;
import org.polydevs.opusmc.websurface.OpusDisplayMetrics;

public final class W5ReactSurfaceHarness {
    private static final long TIMEOUT_MS = 45000L;
    private static BufferedImage lastMatchedImage;
    private static long lastMatchedSequence = Long.MIN_VALUE;
    // The primary Home action uses the active accent fill, unlike the animated
    // backdrop whose pixels cannot provide a stable surface oracle.
    private static final int HOME_SURFACE_MIN_LUMA = 190;
    private static final int HOME_SURFACE_MAX_LUMA = 235;
    private static final int ROUTE_SURFACE_MIN_LUMA = 12;
    private static final int ROUTE_SURFACE_MAX_LUMA = 38;
    private static final int NEUTRAL_CHANNEL_SPREAD = 14;

    public static void main(String[] args) throws Exception {
        Logger log = logger();
        String helper = OpusWebViewClient.resolveHelperPath(log);
        require(helper != null, "CEF helper unavailable");
        String distPath = System.getProperty("opus.ui.v2.dist", "");
        File dist = new File(distPath);
        File index = new File(dist, "index.html");
        require(index.isFile(), "React V2 dist/index.html is missing: " + index);
        HttpServer staticServer = startStaticServer(dist);
        String url = "http://127.0.0.1:" + staticServer.getAddress().getPort()
                + "/index.html?opusHarness=1";

        OpusWebViewClient client = new OpusWebViewClient(log, helper);
        try {
            require(client.start(1280, 720, 2.0F, url), "CEF helper did not start React V2");
            VisualFingerprint home = awaitMarker(client, 1280, 720, 2.0F, 0x00ff00, "Home marker");
            System.out.println("PASS real React production bundle reaches the Home launch surface at DPR 2");
            captureIfRequested(client, 1280, 720, 2.0F, "home");

            clickHomeAction(client, 1280, 720, false);
            VisualFingerprint singleplayer = awaitMarkerAfter(client, 1280, 720, 2.0F,
                    lastMatchedSequence, 0x0000ff, "Singleplayer marker");
            requireDistinct(home, singleplayer, "Home click did not visibly open Singleplayer");
            System.out.println("PASS native pointer opens Singleplayer at the normal DPR 2 viewport");
            captureIfRequested(client, 1280, 720, 2.0F, "singleplayer");

            long homeAfterBackSequence = lastMatchedSequence;
            click(client, routeBackCenterX(1280), routeBackCenterY(720), 0);
            awaitMarkerAfter(client, 1280, 720, 2.0F, homeAfterBackSequence,
                    0x00ff00, "Home marker");
            captureIfRequested(client, 1280, 720, 2.0F, "home-after-back");
            System.out.println("PASS native pointer returns from Singleplayer to Home");

            long multiplayerSequence = lastMatchedSequence;
            clickHomeAction(client, 1280, 720, true);
            VisualFingerprint multiplayer = awaitMarkerAfter(client, 1280, 720, 2.0F,
                    multiplayerSequence, 0xff0000, "Multiplayer marker");
            requireDistinct(singleplayer, multiplayer,
                    "Multiplayer Home action did not open its route");
            System.out.println("PASS native pointer opens Multiplayer at the normal DPR 2 viewport");
            captureIfRequested(client, 1280, 720, 2.0F, "multiplayer");

            client.resize(900, 600, 2.0F);
            awaitMarker(client, 900, 600, 2.0F, 0xff0000, "Multiplayer marker after resize");
            System.out.println("PASS routed React surface remains current after 900x600 @2x resize");

            click(client, routeBackCenterX(900), routeBackCenterY(600), 0);
            awaitMarker(client, 900, 600, 2.0F, 0x00ff00, "Home marker after resize");
            System.out.println("PASS Back remains live after a normal-window resize");

            client.resize(427, 240, 2.0F);
            awaitMarker(client, 427, 240, 2.0F, 0x00ff00, "Home marker at compact viewport");
            long compactSequence = lastMatchedSequence;
            clickHomeAction(client, 427, 240, true);
            VisualFingerprint compactMultiplayer = awaitMarkerAfter(client, 427, 240, 2.0F,
                    compactSequence, 0xff0000, "Multiplayer marker at compact viewport");
            requireDistinct(home, compactMultiplayer,
                    "Compact Multiplayer Home action did not open its route");
            System.out.println("PASS compact 427x240 Home primary action remains clickable at DPR 2");

            client.resize(960, 540, 2.0F);
            long gameMenuSequence = lastMatchedSequence;
            client.navigate(url + "#/game_menu");
            VisualFingerprint pauseMenu = awaitMarkerAfter(client, 960, 540, 2.0F,
                    gameMenuSequence, 0xff00ff, "Game menu marker");
            System.out.println("PASS pause CEF route reaches Game menu at DPR 2");
            captureIfRequested(client, 960, 540, 2.0F, "pause-menu");
            long settingsSequence = lastMatchedSequence;
            // Production Options bounds at 960x540: x300.5 y289.84 w176 h34.
            click(client, 388, 307, 0);
            VisualFingerprint settings = awaitMarkerAfter(client, 960, 540, 2.0F,
                    settingsSequence, 0xffff00, "Minecraft Settings marker");
            requireDistinct(pauseMenu, settings, "Pause Options action did not open its route");
            System.out.println("PASS Pause Options opens Minecraft settings in the Opus window");
        } finally {
            client.stop();
            staticServer.stop(0);
        }

        System.out.println("W5 REACT CEF ORACLE: ALL PASSED");
    }

    private static void captureIfRequested(OpusWebViewClient client, int logicalWidth,
            int logicalHeight, float scale, String name) throws Exception {
        String directory = System.getenv("OPUS_VISUAL_CAPTURE_DIR");
        if (directory == null || directory.trim().isEmpty()) return;
        File output = new File(directory.trim(), name + ".png");
        File parent = output.getParentFile();
        if (parent != null) parent.mkdirs();
        BufferedImage latest = lastMatchedImage;
        require(latest != null && lastMatchedSequence > Long.MIN_VALUE,
                "no current CEF frame available for " + name + " capture");
        lastMatchedImage = null;
        lastMatchedSequence = Long.MIN_VALUE;
        ImageIO.write(latest, "png", output);
        System.out.println("PASS visual capture written to " + output.getAbsolutePath());
    }

    private static BufferedImage image(OpusWebViewClient.Frame frame) {
        BufferedImage image = new BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB);
        ByteBuffer pixels = frame.pixels.duplicate();
        for (int y = 0; y < frame.height; y++) {
            for (int x = 0; x < frame.width; x++) {
                int offset = (y * frame.width + x) * 4;
                int blue = pixels.get(offset) & 0xFF;
                int green = pixels.get(offset + 1) & 0xFF;
                int red = pixels.get(offset + 2) & 0xFF;
                int alpha = pixels.get(offset + 3) & 0xFF;
                image.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }

    private static int alphaAt(OpusWebViewClient.Frame frame, int x, int y) {
        return frame.pixels.get((y * frame.width + x) * 4 + 3) & 0xFF;
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
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    private static void click(OpusWebViewClient client, int x, int y, int button) {
        client.sendMouseMove(x, y);
        client.sendMouseButton(x, y, button, true);
        client.sendMouseButton(x, y, button, false);
    }

    private static void clickHomeAction(OpusWebViewClient client, int width, int height,
            boolean multiplayer) {
        int actionX = homeActionCenterX(width, multiplayer);
        int actionY = homeActionCenterY(height, multiplayer);
        click(client, actionX, actionY, 0);
    }

    // Centers measured from the approved precision layout at the oracle's
    // three viewport sizes. Route markers prove actual React state transitions.
    private static int routeBackCenterX(int width) { return width <= 900 ? 41 : 51; }
    private static int routeBackCenterY(int height) { return height <= 600 ? 102 : 116; }
    private static int homeActionCenterX(int width, boolean multiplayer) {
        return width <= 450 ? 213 : width <= 900 ? 206 : 253;
    }
    private static int homeActionCenterY(int height, boolean multiplayer) {
        return height <= 360 ? multiplayer ? 125 : 91 : height <= 600 ? multiplayer ? 318 : 246 : multiplayer ? 398 : 316;
    }

    private static VisualFingerprint awaitMarker(OpusWebViewClient client, int logicalWidth,
            int logicalHeight, float scale, final int rgb, String expected) throws Exception {
        return awaitMarkerAfter(client, logicalWidth, logicalHeight, scale, Long.MIN_VALUE,
                rgb, expected);
    }

    private static VisualFingerprint awaitMarkerAfter(OpusWebViewClient client, int logicalWidth,
            int logicalHeight, float scale, long minimumSequence, final int rgb,
            String expected) throws Exception {
        return awaitVisual(client, logicalWidth, logicalHeight, scale, minimumSequence,
                new FrameMatcher() {
                    @Override
                    public boolean matches(OpusWebViewClient.Frame frame, int width, int height) {
                        int markerX = Math.min(frame.width - 1, Math.max(0,
                                Math.round(4 * frame.width / (float) width)));
                        int markerY = Math.min(frame.height - 1, Math.max(0,
                                Math.round(4 * frame.height / (float) height)));
                        int offset = (markerY * frame.width + markerX) * 4;
                        int blue = frame.pixels.get(offset) & 0xFF;
                        int green = frame.pixels.get(offset + 1) & 0xFF;
                        int red = frame.pixels.get(offset + 2) & 0xFF;
                        int expectedRed = (rgb >> 16) & 0xFF;
                        int expectedGreen = (rgb >> 8) & 0xFF;
                        int expectedBlue = rgb & 0xFF;
                        return Math.abs(red - expectedRed) <= 8
                                && Math.abs(green - expectedGreen) <= 8
                                && Math.abs(blue - expectedBlue) <= 8;
                    }
                }, expected);
    }

    private static VisualFingerprint awaitRouteShell(OpusWebViewClient client, int logicalWidth,
            int logicalHeight, float scale) throws Exception {
        return awaitVisual(client, logicalWidth, logicalHeight, scale, new FrameMatcher() {
            @Override
            public boolean matches(OpusWebViewClient.Frame frame, int width, int height) {
                int headerTop = height <= 380 ? 6 : 18;
                int headerBottom = height <= 380 ? 42 : 76;
                int left = width / 2 - 90;
                int right = width / 2 + 90;
                return regionHasNeutralCoverage(frame, width, height,
                        ROUTE_SURFACE_MIN_LUMA, ROUTE_SURFACE_MAX_LUMA,
                        NEUTRAL_CHANNEL_SPREAD, 68,
                        left, headerTop, right, headerBottom)
                        && regionHasNeutralCoverage(frame, width, height,
                                150, 255, 20, 1,
                                left, headerTop, right, headerBottom);
            }
        }, "routed client header");
    }

    private static VisualFingerprint awaitVisual(OpusWebViewClient client, int logicalWidth,
            int logicalHeight, float scale, FrameMatcher matcher, String expected) throws Exception {
        return awaitVisual(client, logicalWidth, logicalHeight, scale, Long.MIN_VALUE,
                matcher, expected);
    }

    private static VisualFingerprint awaitVisual(OpusWebViewClient client, int logicalWidth,
            int logicalHeight, float scale, long minimumSequence, FrameMatcher matcher,
            String expected) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            require(!client.isFailed(), "CEF helper entered failed state");
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                try {
                    if (frame.sequence > minimumSequence
                            && client.isCurrent(frame, logicalWidth, logicalHeight, scale)
                            && matcher.matches(frame, logicalWidth, logicalHeight)) {
                        lastMatchedImage = image(frame);
                        lastMatchedSequence = frame.sequence;
                        return fingerprint(frame);
                    }
                } finally {
                    client.release(frame);
                }
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("timed out waiting for " + expected);
    }

    private static boolean regionHasNeutralCoverage(OpusWebViewClient.Frame frame,
            int logicalWidth, int logicalHeight, int minLuma, int maxLuma,
            int maxChannelSpread, int minCoveragePercent,
            int left, int top, int right, int bottom) {
        OpusDisplayMetrics metrics = new OpusDisplayMetrics(
                1L, logicalWidth, logicalHeight, frame.width, frame.height);
        int minX = metrics.logicalToFramebufferX(Math.max(0, Math.min(logicalWidth - 1, left)));
        int maxX = metrics.logicalToFramebufferX(Math.max(0, Math.min(logicalWidth - 1, right)));
        int minY = metrics.logicalToFramebufferTopY(Math.max(0, Math.min(logicalHeight - 1, top)));
        int maxY = metrics.logicalToFramebufferTopY(Math.max(0, Math.min(logicalHeight - 1, bottom)));
        ByteBuffer pixels = frame.pixels.duplicate();
        int matched = 0;
        int sampled = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                int offset = (y * frame.width + x) * 4;
                int blue = pixels.get(offset) & 0xFF;
                int green = pixels.get(offset + 1) & 0xFF;
                int red = pixels.get(offset + 2) & 0xFF;
                int maximum = Math.max(red, Math.max(green, blue));
                int minimum = Math.min(red, Math.min(green, blue));
                int luminance = (red * 3 + green * 6 + blue) / 10;
                sampled++;
                if (maximum - minimum <= maxChannelSpread
                        && luminance >= minLuma && luminance <= maxLuma) {
                    matched++;
                }
            }
        }
        return sampled > 0 && matched * 100L >= sampled * (long) minCoveragePercent;
    }

    private static VisualFingerprint fingerprint(OpusWebViewClient.Frame frame) {
        final int columns = 32;
        final int rows = 18;
        int[] samples = new int[columns * rows];
        ByteBuffer pixels = frame.pixels.duplicate();
        int index = 0;
        for (int row = 0; row < rows; row++) {
            int y = Math.min(frame.height - 1, (row * 2 + 1) * frame.height / (rows * 2));
            for (int column = 0; column < columns; column++) {
                int x = Math.min(frame.width - 1,
                        (column * 2 + 1) * frame.width / (columns * 2));
                int offset = (y * frame.width + x) * 4;
                int blue = pixels.get(offset) & 0xFF;
                int green = pixels.get(offset + 1) & 0xFF;
                int red = pixels.get(offset + 2) & 0xFF;
                samples[index++] = (red << 16) | (green << 8) | blue;
            }
        }
        return new VisualFingerprint(samples);
    }

    private static void requireDistinct(VisualFingerprint left, VisualFingerprint right,
            String message) {
        require(!left.equals(right), message);
    }

    private interface FrameMatcher {
        boolean matches(OpusWebViewClient.Frame frame, int logicalWidth, int logicalHeight);
    }

    private static final class VisualFingerprint {
        private final int[] samples;

        VisualFingerprint(int[] samples) {
            this.samples = samples;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof VisualFingerprint)) return false;
            int[] candidate = ((VisualFingerprint) other).samples;
            if (candidate.length != samples.length) return false;
            for (int index = 0; index < samples.length; index++) {
                if (samples[index] != candidate[index]) return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = 1;
            for (int sample : samples) hash = 31 * hash + sample;
            return hash;
        }
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
