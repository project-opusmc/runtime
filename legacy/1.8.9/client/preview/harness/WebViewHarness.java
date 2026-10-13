package org.polydevs.opusmc.client;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;
import org.polydevs.opusmc.client.interop.OpusBridgeProvider;
import org.polydevs.opusmc.client.interop.OpusInteropServer;
import org.polydevs.opusmc.client.interop.UiNavigationManager;

/**
 * End-to-end verifier for the production browser compositor without Minecraft.
 * It serves the packaged SPA, renders it through the real CEF OSR helper, reads
 * the shared-memory frame, then exercises navigation, resize and clean reopen.
 */
public final class WebViewHarness {
    private static final long FRAME_TIMEOUT_MS = 20000L;

    public static void main(String[] args) throws Exception {
        AtomicBoolean keychainAccessObserved = new AtomicBoolean();
        HostNavigationAckHarness.MediaProbe controls = new HostNavigationAckHarness.MediaProbe();
        Logger log = logger(keychainAccessObserved, controls);
        String helper = OpusWebViewClient.resolveHelperPath(log);
        if (helper == null) {
            System.out.println("WEBVIEW HARNESS: SKIPPED (CEF helper unavailable)");
            return;
        }

        Set<String> temporaryBefore = opusTemporaryEntries();
        OpusInteropServer server = new OpusInteropServer(log);
        UiNavigationManager navigation = new UiNavigationManager(log, server);
        RecordingBridgeProvider bridgeProvider = new RecordingBridgeProvider();
        OpusWebViewClient first = null;
        OpusWebViewClient reopened = null;
        try {
            server.setNavigationManager(navigation);
            server.setBridgeProvider(bridgeProvider);
            require(server.start(), "interop server did not start");

            navigation.open("title");
            first = new OpusWebViewClient(log, helper);
            require(first.start(640, 480, 1.0F,
                            server.urlForRoute("title").replace("#", "&opusMediaProbe=1#")),
                    "first CEF helper did not start");

            OpusWebViewClient.Frame title = awaitFrame(first, 640, 480, 1.0F);
            long titleHash;
            try {
                require(visibleAlphaSamples(title) > 0,
                        "title frame was completely transparent");
                titleHash = sampledHash(title.pixels);
            } finally {
                first.release(title);
            }
            awaitNavigationAck(navigation, "title");
            pass("title SPA frame and navigation ACK");

            // Native CEF mouse input must drive the real browser hover state;
            // no DOM-synthesis shim or second input owner is allowed.
            // Observe the current control geometry rather than old layout pixels.
            com.google.gson.JsonArray bounds = controls.control("home-singleplayer").getAsJsonArray("rect");
            int controlX = (int) (bounds.get(0).getAsDouble() + bounds.get(2).getAsDouble() / 2);
            int controlY = (int) (bounds.get(1).getAsDouble() + bounds.get(3).getAsDouble() / 2);
            require(controlX >= 0 && controlX < 640 && controlY >= 0 && controlY < 480,
                    "Singleplayer control is outside the CEF viewport");
            first.sendMouseMove(controlX, controlY);
            OpusWebViewClient.Frame hoveredTitle = awaitDistinctFrame(
                    first, 640, 480, 1.0F, titleHash);
            try {
                require(sampledHash(hoveredTitle.pixels) != titleHash,
                        "CEF mouse move did not repaint the hovered title control");
            } finally {
                first.release(hoveredTitle);
            }
            pass("CEF native mouse move drives browser hover state");

            // Reach the named control through real native Tab events, even when
            // the layout or initial browser focus changes.
            for (int focusStep = 0; focusStep < 80; focusStep++) {
                if (controls.focused("home-singleplayer")) break;
                first.sendKey(0x09, true);
                first.sendKey(0x09, false);
                Thread.sleep(40L);
            }
            require(controls.focused("home-singleplayer"),
                    "Singleplayer is unreachable through native keyboard focus");
            first.sendKey(0x20, true);
            first.sendKey(0x20, false);
            awaitUiAction(bridgeProvider);
            require("navigate".equals(bridgeProvider.action)
                            && "singleplayer".equals(bridgeProvider.routeId),
                    "CEF keyboard input produced " + bridgeProvider.action + " -> "
                            + bridgeProvider.routeId);
            bridgeProvider.clearAction();
            pass("CEF keyboard Space activates the canonical title command");

            first.sendMouseMove(controlX, controlY);
            Thread.sleep(50L);
            first.sendMouseButton(controlX, controlY, 0, true);
            first.sendMouseButton(controlX, controlY, 0, false);
            awaitUiAction(bridgeProvider);
            require("navigate".equals(bridgeProvider.action)
                            && "singleplayer".equals(bridgeProvider.routeId),
                    "CEF pointer input produced " + bridgeProvider.action + " -> "
                            + bridgeProvider.routeId);
            bridgeProvider.clearAction();
            pass("CEF pointer input activates the canonical title command");

            navigation.open("mods_catalog");
            first.navigate(server.urlForRoute("mods_catalog"));
            OpusWebViewClient.Frame catalog = awaitFrame(first, 640, 480, 1.0F);
            try {
                require(visibleAlphaSamples(catalog) > 0,
                        "mods catalog frame was completely transparent");
                require(sampledHash(catalog.pixels) != titleHash,
                        "route navigation did not produce a different product frame");
            } finally {
                first.release(catalog);
            }
            awaitNavigationAck(navigation, "mods_catalog");
            pass("canonical route navigation produces a new CEF frame");

            // Module selection/detail interaction is covered by the W5 React oracle.
            // W1 keeps only lifecycle, title input, canonical navigation and HUD ownership.

            navigation.open("hud_editor");
            first.navigate(server.urlForRoute("hud_editor"));
            OpusWebViewClient.Frame hudEditor = awaitFrame(first, 640, 480, 1.0F);
            try {
                require(visibleAlphaSamples(hudEditor) > 0,
                        "HUD Editor frame was completely transparent");
            } finally {
                first.release(hudEditor);
            }
            awaitNavigationAck(navigation, "hud_editor");
            awaitHudCanvas(bridgeProvider);
            require(bridgeProvider.canvasWidth > 0 && bridgeProvider.canvasHeight > 0,
                    "HUD Editor reported an empty native input region");
            pass("HUD Editor reports one revision-scoped native input region");

            // Route-specific HUD widget controls are covered by the W5/W7 product gates.
            // W1 only verifies the revision-scoped native canvas ownership boundary.

            first.resize(800, 500, 1.25F);
            OpusWebViewClient.Frame resized = awaitFrame(first, 800, 500, 1.25F);
            try {
                require(resized.width == 1000 && resized.height == 625,
                        "resize raster was " + resized.width + "x" + resized.height);
                require(visibleAlphaSamples(resized) > 0,
                        "resized frame was completely transparent");
            } finally {
                first.release(resized);
            }
            pass("logical resize and device scale produce the expected raster");

            first.stop();
            first = null;
            requireNoNewTemporaryEntries(temporaryBefore, "first close");

            navigation.open("title");
            reopened = new OpusWebViewClient(log, helper);
            require(reopened.start(480, 320, 1.5F, server.urlForRoute("title")),
                    "reopened CEF helper did not start");
            OpusWebViewClient.Frame reopenedFrame = awaitFrame(reopened, 480, 320, 1.5F);
            try {
                require(reopenedFrame.width == 720 && reopenedFrame.height == 480,
                        "reopened raster was " + reopenedFrame.width + "x"
                                + reopenedFrame.height);
                require(visibleAlphaSamples(reopenedFrame) > 0,
                        "reopened frame was completely transparent");
            } finally {
                reopened.release(reopenedFrame);
            }
            awaitNavigationAck(navigation, "reopened title");
            pass("CEF helper stops and reopens with a fresh product frame");
        } finally {
            if (first != null) {
                first.stop();
            }
            if (reopened != null) {
                reopened.stop();
            }
            navigation.shutdown();
            server.stop();
        }
        requireNoNewTemporaryEntries(temporaryBefore, "final shutdown");
        require(!keychainAccessObserved.get(),
                "CEF attempted to use macOS Keychain or unavailable browser encryption");
        System.out.println("WEBVIEW HARNESS: ALL PASSED");
    }

    private static OpusWebViewClient.Frame awaitFrame(
            OpusWebViewClient client,
            int logicalWidth,
            int logicalHeight,
            float scale) throws Exception {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (client.isFailed()) {
                throw new IllegalStateException("CEF helper entered failed state");
            }
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                if (client.isCurrent(frame, logicalWidth, logicalHeight, scale)) {
                    return frame;
                }
                client.release(frame);
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("timed out waiting for a current CEF frame");
    }

    private static OpusWebViewClient.Frame awaitDistinctFrame(
            OpusWebViewClient client,
            int logicalWidth,
            int logicalHeight,
            float scale,
            long previousHash) throws Exception {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (client.isFailed()) {
                throw new IllegalStateException("CEF helper entered failed state");
            }
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                if (client.isCurrent(frame, logicalWidth, logicalHeight, scale)
                        && sampledHash(frame.pixels) != previousHash) {
                    return frame;
                }
                client.release(frame);
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("timed out waiting for a distinct CEF frame");
    }

    private static void awaitNavigationAck(UiNavigationManager navigation, String route)
            throws Exception {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (navigation.isConfirmed()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("SPA did not acknowledge " + route + " navigation");
    }

    private static void awaitUiAction(RecordingBridgeProvider provider) throws Exception {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (provider.action != null) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("CEF input did not reach the UI action bridge");
    }

    private static boolean tryNativeClickForAction(
            OpusWebViewClient client,
            RecordingBridgeProvider provider,
            int[][] points) throws Exception {
        for (int[] point : points) {
            provider.clearAction();
            client.sendMouseMove(point[0], point[1]);
            client.sendMouseButton(point[0], point[1], 0, true);
            client.sendMouseButton(point[0], point[1], 0, false);
            long deadline = System.currentTimeMillis() + 700L;
            while (System.currentTimeMillis() < deadline) {
                if (provider.action != null) {
                    return true;
                }
                Thread.sleep(10L);
            }
        }
        return false;
    }

    private static void awaitHudCanvas(RecordingBridgeProvider provider) throws Exception {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (provider.canvasRevision >= 0L) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("HUD Editor did not report its native input region");
    }

    private static int visibleAlphaSamples(OpusWebViewClient.Frame frame) {
        ByteBuffer pixels = frame.pixels.duplicate();
        int visible = 0;
        for (int offset = 3; offset < pixels.limit(); offset += 4 * 64) {
            if ((pixels.get(offset) & 0xFF) != 0) {
                visible++;
            }
        }
        return visible;
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


    private static Set<String> opusTemporaryEntries() {
        Set<String> entries = new HashSet<String>();
        File directory = new File(System.getProperty("java.io.tmpdir", "."));
        File[] files = directory.listFiles();
        if (files == null) {
            return entries;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("opus-cef-") || name.startsWith("opus-cef-cache-")) {
                entries.add(file.getAbsolutePath());
            }
        }
        return entries;
    }

    private static void requireNoNewTemporaryEntries(Set<String> before, String stage)
            throws Exception {
        Set<String> after = opusTemporaryEntries();
        after.removeAll(before);
        require(after.isEmpty(), stage + " left temporary CEF resources: " + after);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void pass(String message) {
        System.out.println("PASS " + message);
    }

    private static Logger logger(final AtomicBoolean keychainAccessObserved,
            final HostNavigationAckHarness.MediaProbe controls) {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] {Logger.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] call) {
                        String name = method.getName();
                        if (name.equals("isDebugEnabled") || name.equals("isTraceEnabled")) {
                            return Boolean.FALSE;
                        }
                        if (name.startsWith("is") && method.getReturnType() == boolean.class) {
                            return Boolean.TRUE;
                        }
                        if (("warn".equals(name) || "error".equals(name)
                                || "info".equals(name) || "debug".equals(name))
                            && call != null && call.length > 0) {
                            String message = String.valueOf(call[0]);
                            String rendered = format(message, call);
                            controls.record(rendered);
                            String lower = rendered.toLowerCase(java.util.Locale.ROOT);
                            if (lower.contains("keychain")
                                    || lower.contains("os_crypt")
                                    || lower.contains("encryption is not available")) {
                                keychainAccessObserved.set(true);
                            }
                            if (!"info".equals(name) || message.contains("[helper]")) {
                                System.out.println(name.toUpperCase(java.util.Locale.ROOT)
                                        + " " + rendered);
                                for (int index = 1; index < call.length; index++) {
                                    if (call[index] instanceof Throwable) {
                                        StringWriter stack = new StringWriter();
                                        ((Throwable) call[index]).printStackTrace(
                                                new PrintWriter(stack));
                                        System.out.print(stack.toString());
                                    }
                                }
                            }
                        }
                        return null;
                    }
                });
    }

    private static String format(String message, Object[] call) {
        String formatted = message;
        for (int index = 1; index < call.length; index++) {
            Object value = call[index];
            if (value instanceof Object[]) {
                for (Object nested : (Object[]) value) {
                    formatted = formatted.replaceFirst("\\{\\}",
                            java.util.regex.Matcher.quoteReplacement(String.valueOf(nested)));
                }
            } else {
                formatted = formatted.replaceFirst("\\{\\}",
                        java.util.regex.Matcher.quoteReplacement(String.valueOf(value)));
            }
        }
        return formatted;
    }

    private static final class RecordingBridgeProvider implements OpusBridgeProvider {
        volatile String action;
        volatile String routeId;
        volatile String moduleId;
        volatile long canvasRevision = -1L;
        volatile int canvasX;
        volatile int canvasY;
        volatile int canvasWidth;
        volatile int canvasHeight;

        @Override
        public boolean performUiAction(
                String action,
                String routeId,
                String moduleId,
                String settingsSection,
                long expectedRevision) {
            this.routeId = routeId;
            this.moduleId = moduleId;
            this.action = action;
            return true;
        }

        void clearAction() {
            action = null;
            routeId = null;
            moduleId = null;
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

        @Override
        public List<String[]> modules() {
            return Collections.singletonList(new String[] {
                "fps", "FPS Display", "true",
            });
        }

        @Override
        public void setModuleEnabled(String moduleId, boolean enabled) {
        }

        @Override
        public List<String[]> moduleSettings(String moduleId) {
            if (!"fps".equals(moduleId)) {
                return Collections.emptyList();
            }
            return Collections.singletonList(new String[] {
                "show-average", "Show average", "boolean", "true", "0", "1", "1", "",
            });
        }

        @Override
        public void setModuleSetting(String moduleId, String key, String value) {
        }

        @Override
        public List<String[]> hudModules() {
            return Collections.singletonList(new String[] {
                "fps", "FPS Display", "true", "16", "16", "100", "top-left",
            });
        }

        @Override
        public void moveHudModule(String moduleId, int x, int y) {
        }

        @Override
        public void updateHudEditorCanvas(
                long navigationRevision, int x, int y, int width, int height) {
            canvasRevision = navigationRevision;
            canvasX = x;
            canvasY = y;
            canvasWidth = width;
            canvasHeight = height;
        }
    }
}
