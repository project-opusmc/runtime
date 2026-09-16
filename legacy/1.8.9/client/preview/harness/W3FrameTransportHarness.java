package org.polydevs.opusmc.client;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;
import org.polydevs.opusmc.websurface.OpusDisplayMetrics;

public final class W3FrameTransportHarness {
    private static final long FRAME_TIMEOUT_MS = 90000L;
    private static final long MIB = 1024L * 1024L;

    public static void main(String[] args) throws Exception {
        Logger log = logger();
        String helper = OpusWebViewClient.resolveHelperPath(log);
        require(helper != null, "CEF helper unavailable");

        OpusWebViewClient client = new OpusWebViewClient(log, helper);
        try {
            require(client.start(640, 480, 1.0F, dataUrl()), "CEF helper did not start");
            OpusWebViewClient.Frame initial = awaitFrame(client, 640, 480, 1.0F);
            try {
                verifyPattern(initial, 640, 480);
            } finally {
                client.release(initial);
            }
            require(client.sharedSlotCount() == 3, "transport must expose exactly three ownership slots");
            require(client.sharedSlotBytes() == 2L * MIB,
                    "640x480 initial slot should be 2 MiB, got " + client.sharedSlotBytes());
            require(client.sharedBufferBytes() == 6L * MIB,
                    "initial mapping should be 6 MiB, got " + client.sharedBufferBytes());
            System.out.println("PASS initial transport uses 3 x 2 MiB sparse slots");

            verifyThreeOutstandingSlots(client);

            verifyResize(client, 800, 500, 1.25F, 4L * MIB);
            verifyResize(client, 1920, 1080, 2.0F, 32L * MIB);
            verifyResize(client, 2560, 1440, 2.0F, 64L * MIB);
            long fiveKCapacity = client.sharedSlotBytes();
            require(client.sharedBufferBytes() == 3L * fiveKCapacity,
                    "total mapping is not exactly three slots");
            System.out.println("PASS 5120x2880 raster negotiated 3 x 64 MiB transport");

            verifyResize(client, 800, 500, 1.25F, 64L * MIB);
            require(client.sharedSlotBytes() == fiveKCapacity,
                    "transport unexpectedly shrank after downsizing");
            System.out.println("PASS downsizing keeps the established mapping and avoids remap churn");

            verifyResize(client, 2560, 1440, 2.0F, 64L * MIB);
            require(client.sharedSlotBytes() == fiveKCapacity,
                    "return to 5K changed an already-sufficient slot capacity");
            System.out.println("PASS returning to 5K reuses the existing negotiated mapping");
        } finally {
            client.stop();
        }

        verifyGrowthBarrier(log, helper);
        System.out.println("W3 FRAME TRANSPORT: ALL PASSED");
    }

    private static void verifyGrowthBarrier(Logger log, String helper) throws Exception {
        OpusWebViewClient client = new OpusWebViewClient(log, helper);
        OpusWebViewClient.Frame held = null;
        try {
            require(client.start(640, 480, 1.0F, dataUrl()),
                    "barrier probe helper did not start");
            held = awaitFrame(client, 640, 480, 1.0F);
            require(client.outstandingFrameCount() == 1,
                    "barrier probe did not own exactly one frame");
            client.resize(2560, 1440, 2.0F);
            require(client.isFailed(),
                    "transport allowed shared-memory growth while a frame was still owned");
            require(client.sharedSlotBytes() == 2L * MIB,
                    "failed growth changed the active slot capacity");
            System.out.println("PASS growth is fail-closed while an old frame is still owned");
        } finally {
            client.release(held);
            client.stop();
        }
    }

    private static void verifyThreeOutstandingSlots(OpusWebViewClient client) throws Exception {
        drain(client);
        OpusWebViewClient.Frame first = null;
        OpusWebViewClient.Frame second = null;
        OpusWebViewClient.Frame third = null;
        OpusWebViewClient.Frame resumed = null;
        try {
            click(client);
            first = awaitFrame(client, 640, 480, 1.0F);
            long firstHash = sampledHash(first.pixels);

            click(client);
            second = awaitDistinctFrame(client, 640, 480, 1.0F, firstHash);
            long secondHash = sampledHash(second.pixels);

            click(client);
            third = awaitDistinctFrame(client, 640, 480, 1.0F, secondHash);
            long thirdHash = sampledHash(third.pixels);

            Set<Integer> slots = new HashSet<Integer>();
            slots.add(first.slot);
            slots.add(second.slot);
            slots.add(third.slot);
            require(slots.size() == 3,
                    "three outstanding frames did not occupy three distinct slots: " + slots);
            require(client.outstandingFrameCount() == 3,
                    "Java ownership accounting expected 3 frames, got "
                            + client.outstandingFrameCount());

            click(client);
            Thread.sleep(400L);
            OpusWebViewClient.Frame blocked = client.consumeFrame();
            if (blocked != null) {
                client.release(blocked);
                throw new IllegalStateException("fourth frame published while all three slots were owned");
            }

            client.release(first);
            first = null;
            resumed = awaitDistinctFrame(client, 640, 480, 1.0F, thirdHash);
            require(resumed.slot >= 0 && resumed.slot < 3, "resumed paint used invalid slot");
            System.out.println("PASS three-slot ownership blocks the fourth paint and resumes after RELEASE");
        } finally {
            client.release(first);
            client.release(second);
            client.release(third);
            client.release(resumed);
            drain(client);
            require(client.outstandingFrameCount() == 0,
                    "frame ownership did not drain to zero: " + client.outstandingFrameCount());
        }
    }

    private static void verifyResize(OpusWebViewClient client, int logicalWidth, int logicalHeight,
            float scale, long expectedSlotBytes) throws Exception {
        client.resize(logicalWidth, logicalHeight, scale);
        OpusWebViewClient.Frame frame = awaitFrame(client, logicalWidth, logicalHeight, scale);
        try {
            require(frame.width == Math.round(logicalWidth * scale),
                    "unexpected raster width " + frame.width);
            require(frame.height == Math.round(logicalHeight * scale),
                    "unexpected raster height " + frame.height);
            verifyPattern(frame, logicalWidth, logicalHeight);
            require(client.sharedSlotBytes() == expectedSlotBytes,
                    "slot capacity mismatch for " + frame.width + "x" + frame.height
                            + ": got " + client.sharedSlotBytes()
                            + " expected " + expectedSlotBytes);
        } finally {
            client.release(frame);
        }
        System.out.println("PASS transport " + logicalWidth + "x" + logicalHeight + " @ " + scale
                + " -> slot " + (expectedSlotBytes / MIB) + " MiB");
    }

    private static void verifyPattern(OpusWebViewClient.Frame frame,
            int logicalWidth, int logicalHeight) {
        OpusDisplayMetrics metrics = new OpusDisplayMetrics(
                1L, logicalWidth, logicalHeight, frame.width, frame.height);
        requireColor(frame, metrics, 8, 8, 255, 0, 0, "top-left");
        requireColor(frame, metrics, logicalWidth - 9, 8, 0, 255, 0, "top-right");
        requireColor(frame, metrics, 8, logicalHeight - 9, 0, 0, 255, "bottom-left");
        requireColor(frame, metrics, logicalWidth - 9, logicalHeight - 9,
                255, 255, 0, "bottom-right");
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
        require(Math.abs(actualRed - red) <= 2
                        && Math.abs(actualGreen - green) <= 2
                        && Math.abs(actualBlue - blue) <= 2,
                label + " color=" + actualRed + "," + actualGreen + "," + actualBlue);
    }

    private static void click(OpusWebViewClient client) {
        client.sendMouseMove(160, 104);
        client.sendMouseButton(160, 104, 0, true);
        client.sendMouseButton(160, 104, 0, false);
    }

    private static void drain(OpusWebViewClient client) throws Exception {
        Thread.sleep(150L);
        for (;;) {
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame == null) return;
            client.release(frame);
            Thread.sleep(20L);
        }
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
        throw new IllegalStateException("timed out waiting for current CEF frame");
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
        throw new IllegalStateException("timed out waiting for distinct CEF frame");
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
                + "#target{position:absolute;left:100px;top:80px;width:120px;height:48px;border:0;"
                + "background:#114477;}body.s1{background:#09c828}body.s2{background:#7020b0}"
                + "body.s3{background:#b05010}body.s4{background:#1040a0}"
                + "</style></head><body><div id='tl' class='corner'></div><div id='tr' class='corner'></div>"
                + "<div id='bl' class='corner'></div><div id='br' class='corner'></div>"
                + "<button id='target'></button><script>let n=0;target.onclick=()=>{n=(n%4)+1;document.body.className='s'+n}</script>"
                + "</body></html>";
        return "data:text/html;base64," + Base64.getEncoder().encodeToString(
                html.getBytes(StandardCharsets.UTF_8));
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
                            if (rendered.contains("[helper]") || !"info".equals(name)
                                    || rendered.contains("shared-memory transport")) {
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
