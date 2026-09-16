package org.polydevs.opusmc.client;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Locale;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;
import org.polydevs.opusmc.websurface.OpusDisplayMetrics;
import org.polydevs.opusmc.websurface.OpusDisplayMetricsTracker;

public final class W2CefCoordinateHarness {
    private static final long FRAME_TIMEOUT_MS = 60000L;
    private static final int[][] MATRIX = {
            {640, 480, 100},
            {800, 500, 125},
            {480, 320, 150},
            {1920, 1080, 200}
    };

    public static void main(String[] args) throws Exception {
        Logger log = logger();
        String helper = OpusWebViewClient.resolveHelperPath(log);
        require(helper != null, "CEF helper unavailable");

        String url = dataUrl();
        OpusWebViewClient client = null;
        OpusDisplayMetricsTracker tracker = new OpusDisplayMetricsTracker();
        try {
            int[] first = MATRIX[0];
            float firstScale = first[2] / 100.0F;
            client = new OpusWebViewClient(log, helper);
            require(client.start(first[0], first[1], firstScale, url), "CEF helper did not start");

            for (int index = 0; index < MATRIX.length; index++) {
                int[] row = MATRIX[index];
                int logicalWidth = row[0];
                int logicalHeight = row[1];
                float scale = row[2] / 100.0F;
                if (index > 0) client.resize(logicalWidth, logicalHeight, scale);

                OpusWebViewClient.Frame frame = awaitPatternFrame(
                        client, logicalWidth, logicalHeight, scale);
                OpusDisplayMetrics metrics = tracker.update(
                        logicalWidth, logicalHeight, frame.width, frame.height);
                try {
                    require(frame.width == Math.round(logicalWidth * scale), "raster width mismatch");
                    require(frame.height == Math.round(logicalHeight * scale), "raster height mismatch");
                    verifyPattern(frame, metrics);
                    System.out.println("PASS CEF pixels " + logicalWidth + "x" + logicalHeight
                            + " @ " + scale + " -> " + frame.width + "x" + frame.height
                            + " generation=" + metrics.generation());
                } finally {
                    client.release(frame);
                }
            }

            int[] clickMatrix = MATRIX[1];
            float clickScale = clickMatrix[2] / 100.0F;
            client.resize(clickMatrix[0], clickMatrix[1], clickScale);
            OpusWebViewClient.Frame before = awaitFrame(
                    client, clickMatrix[0], clickMatrix[1], clickScale);
            long beforeHash;
            try {
                beforeHash = sampledHash(before.pixels);
            } finally {
                client.release(before);
            }

            client.sendMouseMove(160, 104);
            client.sendMouseButton(160, 104, 0, true);
            client.sendMouseButton(160, 104, 0, false);

            OpusWebViewClient.Frame after = awaitDistinctFrame(
                    client, clickMatrix[0], clickMatrix[1], clickScale, beforeHash);
            OpusDisplayMetrics clickMetrics = tracker.update(
                    clickMatrix[0], clickMatrix[1], after.width, after.height);
            try {
                requireColor(after, clickMetrics, 300, 200, 9, 200, 40, "clicked body");
            } finally {
                client.release(after);
            }
            System.out.println("PASS logical CEF click uses the same CSS coordinate space as raster sampling");
        } finally {
            if (client != null) client.stop();
        }

        System.out.println("W2 CEF COORDINATE ORACLE: ALL PASSED");
    }

    private static void verifyPattern(OpusWebViewClient.Frame frame, OpusDisplayMetrics metrics) {
        requireColor(frame, metrics, 8, 8, 255, 0, 0, "top-left");
        requireColor(frame, metrics, metrics.logicalWidth() - 9, 8, 0, 255, 0, "top-right");
        requireColor(frame, metrics, 8, metrics.logicalHeight() - 9, 0, 0, 255, "bottom-left");
        requireColor(frame, metrics, metrics.logicalWidth() - 9,
                metrics.logicalHeight() - 9, 255, 255, 0, "bottom-right");
        requireColor(frame, metrics, 300, 200, 16, 32, 48, "body");
        requireColor(frame, metrics, 160, 104, 17, 68, 119, "click target");
    }

    private static void requireColor(OpusWebViewClient.Frame frame, OpusDisplayMetrics metrics,
            int logicalX, int logicalY, int red, int green, int blue, String label) {
        int x = metrics.logicalToFramebufferX(logicalX);
        int y = metrics.logicalToFramebufferTopY(logicalY);
        int offset = (y * frame.width + x) * 4;
        ByteBuffer pixels = frame.pixels.duplicate();
        require(offset >= 0 && offset + 3 < pixels.limit(), label + " sample outside frame");
        int actualBlue = pixels.get(offset) & 0xFF;
        int actualGreen = pixels.get(offset + 1) & 0xFF;
        int actualRed = pixels.get(offset + 2) & 0xFF;
        int actualAlpha = pixels.get(offset + 3) & 0xFF;
        require(actualAlpha == 255, label + " alpha=" + actualAlpha);
        require(close(actualRed, red) && close(actualGreen, green) && close(actualBlue, blue),
                label + " color=" + actualRed + "," + actualGreen + "," + actualBlue
                        + " expected=" + red + "," + green + "," + blue
                        + " at framebuffer=" + x + "," + y);
    }

    private static boolean close(int actual, int expected) {
        return Math.abs(actual - expected) <= 2;
    }

    private static OpusWebViewClient.Frame awaitPatternFrame(OpusWebViewClient client,
            int logicalWidth, int logicalHeight, float scale) throws Exception {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            require(!client.isFailed(), "CEF helper entered failed state");
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                if (client.isCurrent(frame, logicalWidth, logicalHeight, scale)) {
                    OpusDisplayMetrics metrics = new OpusDisplayMetrics(
                            1L, logicalWidth, logicalHeight, frame.width, frame.height);
                    if (colorMatches(frame, metrics, 160, 104, 17, 68, 119)) return frame;
                }
                client.release(frame);
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("timed out waiting for fully rasterized CEF pattern");
    }

    private static boolean colorMatches(OpusWebViewClient.Frame frame, OpusDisplayMetrics metrics,
            int logicalX, int logicalY, int red, int green, int blue) {
        int x = metrics.logicalToFramebufferX(logicalX);
        int y = metrics.logicalToFramebufferTopY(logicalY);
        int offset = (y * frame.width + x) * 4;
        ByteBuffer pixels = frame.pixels.duplicate();
        if (offset < 0 || offset + 3 >= pixels.limit()) return false;
        int actualBlue = pixels.get(offset) & 0xFF;
        int actualGreen = pixels.get(offset + 1) & 0xFF;
        int actualRed = pixels.get(offset + 2) & 0xFF;
        int actualAlpha = pixels.get(offset + 3) & 0xFF;
        return actualAlpha == 255
                && close(actualRed, red) && close(actualGreen, green) && close(actualBlue, blue);
    }

    private static OpusWebViewClient.Frame awaitFrame(OpusWebViewClient client,
            int logicalWidth, int logicalHeight, float scale) throws Exception {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            require(!client.isFailed(), "CEF helper entered failed state");
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                if (client.isCurrent(frame, logicalWidth, logicalHeight, scale)) return frame;
                client.release(frame);
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("timed out waiting for CEF frame");
    }

    private static OpusWebViewClient.Frame awaitDistinctFrame(OpusWebViewClient client,
            int logicalWidth, int logicalHeight, float scale, long previousHash) throws Exception {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            require(!client.isFailed(), "CEF helper entered failed state");
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                if (client.isCurrent(frame, logicalWidth, logicalHeight, scale)
                        && sampledHash(frame.pixels) != previousHash) return frame;
                client.release(frame);
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("timed out waiting for changed CEF frame");
    }

    private static long sampledHash(ByteBuffer source) {
        ByteBuffer pixels = source.duplicate();
        long hash = 0xcbf29ce484222325L;
        for (int offset = 0; offset < pixels.limit(); offset += 64) {
            hash ^= pixels.get(offset) & 0xFFL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static String dataUrl() {
        String html = "<!doctype html><html><head><meta charset='utf-8'><style>"
                + "html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#102030;}"
                + ".corner{position:fixed;width:20px;height:20px;}"
                + "#tl{left:0;top:0;background:#ff0000}#tr{right:0;top:0;background:#00ff00}"
                + "#bl{left:0;bottom:0;background:#0000ff}#br{right:0;bottom:0;background:#ffff00}"
                + "#target{position:absolute;left:100px;top:80px;width:120px;height:48px;"
                + "border:0;padding:0;background:#114477;}body.clicked{background:#09c828;}"
                + "</style></head><body><div id='tl' class='corner'></div><div id='tr' class='corner'></div>"
                + "<div id='bl' class='corner'></div><div id='br' class='corner'></div>"
                + "<button id='target' onclick=\"document.body.className='clicked'\"></button>"
                + "</body></html>";
        return "data:text/html;base64," + Base64.getEncoder().encodeToString(
                html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
