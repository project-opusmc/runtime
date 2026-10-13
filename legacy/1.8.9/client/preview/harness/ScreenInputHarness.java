package org.polydevs.opusmc.client;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.GameSettings;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.polydevs.opusmc.client.embed.OpusWebTextureSurface;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;
import org.polydevs.opusmc.client.interop.UiNavigationManager;
import org.polydevs.opusmc.client.ui.UiRoute;

/** Synthetic LWJGL event state in a disposable JVM; no display or OS input. */
public final class ScreenInputHarness {
    public static void main(String[] args) throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        Object previousMinecraft = get(Minecraft.class, null, "theMinecraft");
        Object previousKeyboardCreated = get(Keyboard.class, null, "created");
        Object previousMouseCreated = get(Mouse.class, null, "created");
        Object previousMouseButtons = get(Mouse.class, null, "buttons");
        Object previousMouseButtonCount = get(Mouse.class, null, "buttonCount");
        set(Minecraft.class, null, "theMinecraft", minecraft);
        set(Keyboard.class, null, "created", true);
        set(Mouse.class, null, "created", true);
        set(Mouse.class, null, "buttons", ByteBuffer.allocateDirect(3));
        set(Mouse.class, null, "buttonCount", 3);
        minecraft.gameSettings = (GameSettings) unsafe.allocateInstance(GameSettings.class);
        ClientOverlayController controller = new ClientOverlayController(LogManager.getLogger("ScreenInputHarness"));
        ByteArrayOutputStream control = new ByteArrayOutputStream();
        OpusWebViewClient view = new OpusWebViewClient(LogManager.getLogger("ScreenInputWire"), null);
        set(OpusWebViewClient.class, view, "running", true);
        set(OpusWebViewClient.class, view, "out", control);
        set(ClientOverlayController.class, controller, "embeddedWebView", view);
        UiNavigationManager navigation = (UiNavigationManager) get(ClientOverlayController.class, controller, "uiNavigation");
        OpusClientScreen screen = new OpusClientScreen(controller, UiRoute.mainMenu());
        try {
            set(GuiScreen.class, screen, "mc", minecraft);
            minecraft.currentScreen = screen;
            screen.width = 427;
            screen.height = 240;
            minecraft.displayWidth = 854;
            minecraft.displayHeight = 480;
            Method geometry = OpusClientScreen.class.getDeclaredMethod("refreshViewport", int.class, int.class, float.class, boolean.class);
            geometry.setAccessible(true);
            geometry.invoke(screen, 854, 480, 2.0F, true);
            navigation.open("title");
            set(UiNavigationManager.class, navigation, "confirmed", true);

            set(OpusClientScreen.class, screen, "expectedHostRevision", navigation.revision());
            set(OpusClientScreen.class, screen, "requestedFrameRevision", navigation.revision());
            set(OpusClientScreen.class, screen, "webView", view);
            set(OpusClientScreen.class, screen, "webLeaseId", controller.claimEmbeddedWebView(view));
            OpusWebTextureSurface texture = (OpusWebTextureSurface) get(OpusClientScreen.class, screen, "webSurface");
            set(OpusWebTextureSurface.class, texture, "textureId", 1);
            set(OpusWebTextureSurface.class, texture, "textureWidth", 854);
            set(OpusWebTextureSurface.class, texture, "textureHeight", 480);
            Field state = OpusClientScreen.class.getDeclaredField("webState");
            state.setAccessible(true);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object ready = Enum.valueOf((Class) state.getType(), "READY");
            state.set(screen, ready);
            require(!screen.allowUserInput, "Home must exclusively own vanilla screen input");

            // Centers of the actual compact Home controls in CSS units. Unlike
            // previewClick, these enter GuiScreen.handleMouseInput as raw
            // bottom-origin macOS points, including Minecraft's GUI scaling.
            int[][] points = {{60, 24}, {110, 134}, {317, 134}, {110, 176}, {317, 176}};
            for (int[] point : points) {
                control.reset();
                event(point[0], 239 - point[1], true);
                screen.handleMouseInput();
                event(point[0], 239 - point[1], false);
                screen.handleMouseInput();
                String wire = new String(control.toByteArray(), StandardCharsets.UTF_8);
                require(wire.contains(" down " + point[0] + " " + point[1] + " 0 0\n"),
                        "Real screen lost/misplaced mouse-down: " + wire);
                require(wire.contains(" up " + point[0] + " " + point[1] + " 0 0\n"),
                        "Real screen lost/misplaced mouse-up: " + wire);
            }

            // An ACK/readiness change is allowed to reject new presses, but
            // cannot swallow the release of a press the browser already owns.
            control.reset();
            event(317, 105, true);
            screen.handleMouseInput();
            set(UiNavigationManager.class, navigation, "confirmed", false);
            event(317, 105, false);
            screen.handleMouseInput();
            require(wire(control).contains(" up 317 134 0 0\n"),
                    "An ACK change swallowed the matching CEF release: " + wire(control));
            set(UiNavigationManager.class, navigation, "confirmed", true);

            control.reset();
            event(110, 105, 0, true);
            screen.handleMouseInput();
            event(110, 105, 1, true);
            screen.handleMouseInput();
            event(110, 105, 1, false);
            screen.handleMouseInput();
            event(110, 105, 0, false);
            screen.handleMouseInput();
            require(wire(control).contains(" up 110 134 0 0\n")
                            && wire(control).contains(" up 110 134 1 0\n"),
                    "Overlapping buttons lost an owned release: " + wire(control));
            control.reset();
            event(110, 105, 0, false);
            screen.handleMouseInput();
            require(control.size() == 0, "An unowned repeated release escaped to CEF");

            UiRoute[] routeCycle = {UiRoute.singleplayer(), UiRoute.mainMenu(),
                    UiRoute.multiplayer(), UiRoute.mainMenu()};
            for (int cycle = 0; cycle < 8; cycle++) {
                for (UiRoute next : routeCycle) {
                    control.reset();
                    event(110, 105, true);
                    screen.handleMouseInput();
                    screen.previewNavigate(next);
                    String transitionWire = wire(control);
                    require(transitionWire.indexOf(" up 428 241 0 0\n") >= 0
                                    && !transitionWire.contains(" up 110 134 0 0\n")
                                    && !transitionWire.contains("VIEW "),
                            "Retained-SPA transition must cancel outside old controls without reloading CEF: " + transitionWire);
                    require(!navigation.isConfirmed() && state.get(screen)!=ready,
                            "Retained route transition did not revoke the old DOM's input authority");
                    control.reset();
                    event(317,105,1,true);
                    screen.handleMouseInput();
                    require(control.size()==0,"Route transition accepted a click before its DOM ACK/current frame");
                    set(UiNavigationManager.class, navigation, "confirmed", true);
                    set(OpusClientScreen.class, screen, "requestedFrameRevision", navigation.revision());
                    state.set(screen, ready);
                    // A new right press cannot erase suppression of the old
                    // left release, or the old release erase the right owner.
                    control.reset();
                    event(317, 105, 1, true);
                    screen.handleMouseInput();
                    event(110, 105, 0, false);
                    screen.handleMouseInput();
                    event(317, 105, 1, false);
                    screen.handleMouseInput();
                    require(!wire(control).contains(" up 110 134 0 0\n")
                                    && wire(control).contains(" up 317 134 1 0\n"),
                            "Stale release escaped or canceled another owned button: " + wire(control));
                    screen.keyTyped('q', Keyboard.KEY_Q);
                    require(wire(control).contains(" char 113 0\n"),
                            "Committed route did not resume text forwarding: " + wire(control));
                }
            }
            // A visible retained image is not input permission for another
            // revision, an unconfirmed DOM or a revoked browser owner.
            control.reset();
            navigation.open("multiplayer");
            event(317, 105, true);
            screen.handleMouseInput();
            require(control.size() == 0, "Stale Home click escaped revision/ACK guard");
            navigation.open("title");
            set(OpusClientScreen.class, screen, "expectedHostRevision", navigation.revision());
            set(OpusClientScreen.class, screen, "requestedFrameRevision", navigation.revision());
            event(110, 105, true);
            screen.handleMouseInput();
            require(control.size() == 0, "Unconfirmed layout accepted input");
            set(UiNavigationManager.class, navigation, "confirmed", true);
            controller.claimEmbeddedWebView(view);
            screen.handleMouseInput();
            require(control.size() == 0, "Revoked screen retained input permission");

            set(OpusClientScreen.class, screen, "webLeaseId", controller.claimEmbeddedWebView(view));
            // Only the display-focus query is substituted: no window is made.
            // Exercise the actual tick path with real polled LWJGL buttons,
            // omitting the mouse-up event just as a focus switch can do.
            try (DisplayFocusFixture focus = new DisplayFocusFixture(unsafe)) {
                control.reset();
                event(110, 105, true);
                screen.handleMouseInput();
                screen.updateScreen();
                require(!wire(control).contains(" up "), "A physically held press was canceled");
                ((ByteBuffer) get(Mouse.class, null, "buttons")).put(0, (byte) 0);
                screen.updateScreen();
                require(wire(control).contains(" up 428 241 0 0\n"),
                        "A lost physical release was not canceled outside the browser: " + wire(control));
                control.reset();
                event(110, 105, false);
                screen.handleMouseInput();
                require(control.size() == 0, "Late physical release escaped cancellation suppression");

                event(110, 105, true);
                screen.handleMouseInput();
                control.reset();
                focus.active.set(false);
                screen.updateScreen();
                require(wire(control).contains(" up 428 241 0 0\n"),
                        "Focus loss did not cancel the held CEF press: " + wire(control));
                control.reset();
                screen.keyTyped('q', Keyboard.KEY_Q);
                event(317, 105, true);
                screen.handleMouseInput();
                require(control.size() == 0, "Unfocused Client accepted new pointer/text input");
                focus.active.set(true);
                screen.updateScreen();
                control.reset();
                event(317, 105, true);
                screen.handleMouseInput();
                event(317, 105, false);
                screen.handleMouseInput();
                screen.keyTyped('q', Keyboard.KEY_Q);
                require(wire(control).contains(" down 317 134 0 0\n")
                                && wire(control).contains(" up 317 134 0 0\n")
                                && wire(control).contains(" char 113 0\n"),
                        "Focus return did not resume fresh click/text input: " + wire(control));

                // Render-only frames may happen before the next input tick.
                // A normal release can already be polled but still queued:
                // rendering must not cancel it as a supposedly lost event.
                control.reset();
                event(110, 105, true);
                screen.handleMouseInput();
                ((ByteBuffer) get(Mouse.class, null, "buttons")).put(0, (byte) 0);
                try { screen.drawScreen(110, 134, 0.0F); }
                catch (RuntimeException noContext) {
                    // The real render/input preamble runs; only its later GL
                    // draw is unavailable in this explicitly no-window JVM.
                    require(noContext.getMessage() != null
                                    && noContext.getMessage().contains("No OpenGL context"),
                            "Unexpected render failure: " + noContext);
                }
                require(!wire(control).contains(" up "),
                        "A render-only frame canceled a normally queued release: " + wire(control));
                event(110, 105, false);
                screen.handleMouseInput();
                require(wire(control).contains(" up 110 134 0 0\n"),
                        "Normally queued release did not complete its original click: " + wire(control));
            }

            // Full-game editor: native canvas surrounds CEF chrome, but Mods
            // and Done must retain browser ownership, including the release.
            minecraft.theWorld = (net.minecraft.client.multiplayer.WorldClient)
                    unsafe.allocateInstance(net.minecraft.client.multiplayer.WorldClient.class);
            screen.previewNavigate(UiRoute.hudEditor());
            set(UiNavigationManager.class, navigation, "confirmed", true);
            set(OpusClientScreen.class, screen, "requestedFrameRevision", navigation.revision());
            state.set(screen, ready);
            Method hudCanvas = OpusClientScreen.class.getDeclaredMethod("acceptHudEditorCanvas",
                    long.class, org.polydevs.opusmc.client.ui.UiBounds.class, java.util.List.class);
            hudCanvas.setAccessible(true);
            org.polydevs.opusmc.client.ui.UiBounds fullCanvas = new org.polydevs.opusmc.client.ui.UiBounds(0, 0, 427, 240);
            java.util.List<org.polydevs.opusmc.client.ui.UiBounds> holes = java.util.Arrays.asList(
                    new org.polydevs.opusmc.client.ui.UiBounds(170, 105, 90, 40));
            hudCanvas.invoke(screen, navigation.revision(), fullCanvas, holes);
            control.reset();
            event(214, 119, true);
            screen.handleMouseInput();
            event(214, 119, false);
            screen.handleMouseInput();
            require(wire(control).contains(" down 214 120 0 0\n") && wire(control).contains(" up 214 120 0 0\n"),
                    "Native editor stole the central Mods click/release: " + wire(control));

            // Minecraft already supplies the actual button. Ctrl is often
            // held for sprint, so AppKit must not reinterpret its primary
            // click as a context menu. Keyboard shortcuts/right clicks retain
            // their modifier, and Shift remains available to pointer input.
            ByteBuffer keys = ((ByteBuffer) get(Keyboard.class, null, "keyDownBuffer")).duplicate();
            keys.clear();
            byte previousControl = keys.get(Keyboard.KEY_LCONTROL);
            byte previousShift = keys.get(Keyboard.KEY_LSHIFT);
            try {
                keys.put(Keyboard.KEY_LCONTROL, (byte) 1);
                keys.put(Keyboard.KEY_LSHIFT, (byte) 0);
                control.reset();
                event(214, 119, true);
                screen.handleMouseInput();
                event(214, 119, false);
                screen.handleMouseInput();
                require(wire(control).contains(" down 214 120 0 0\n")
                                && wire(control).contains(" up 214 120 0 0\n"),
                        "Sprint Control reinterpreted a physical primary HUD click: " + wire(control));

                control.reset();
                for (int key : new int[] {Keyboard.KEY_A, Keyboard.KEY_C, Keyboard.KEY_X, Keyboard.KEY_V}) {
                    screen.keyTyped('\0', key);
                }
                for (int key : new int[] {65, 67, 88, 86}) {
                    require(wire(control).contains(" key " + key + " 1 2\n")
                                    && wire(control).contains(" key " + key + " 0 2\n"),
                            "Primary-click fix stripped an editing shortcut: " + wire(control));
                }

                control.reset();
                event(214, 119, 1, true);
                screen.handleMouseInput();
                event(214, 119, 1, false);
                screen.handleMouseInput();
                require(wire(control).contains(" down 214 120 1 2\n")
                                && wire(control).contains(" up 214 120 1 2\n"),
                        "Explicit secondary click lost its Control modifier: " + wire(control));

                keys.put(Keyboard.KEY_LSHIFT, (byte) 1);
                control.reset();
                event(214, 119, true);
                screen.handleMouseInput();
                event(214, 119, false);
                screen.handleMouseInput();
                require(wire(control).contains(" down 214 120 0 1\n")
                                && wire(control).contains(" up 214 120 0 1\n"),
                        "Primary-click normalization stripped Shift as well: " + wire(control));
            } finally {
                keys.put(Keyboard.KEY_LCONTROL, previousControl);
                keys.put(Keyboard.KEY_LSHIFT, previousShift);
            }
            control.reset();
            event(40, 189, true);
            screen.handleMouseInput();
            event(40, 189, false);
            screen.handleMouseInput();
            require(!wire(control).contains(" down ") && !wire(control).contains(" up "),
                    "Empty native canvas leaked a drag into CEF: " + wire(control));
            // A late region report cannot overwrite the current chrome holes.
            hudCanvas.invoke(screen, navigation.revision() - 1, fullCanvas, java.util.Collections.emptyList());
            control.reset();
            event(214, 119, true);
            screen.handleMouseInput();
            event(214, 119, false);
            screen.handleMouseInput();
            require(wire(control).contains(" down 214 120 0 0\n") && wire(control).contains(" up 214 120 0 0\n"),
                    "Stale editor geometry revoked CEF control ownership: " + wire(control));
            minecraft.theWorld = null;
            screen.previewNavigate(UiRoute.mainMenu());
            set(UiNavigationManager.class, navigation, "confirmed", true);
            set(OpusClientScreen.class, screen, "requestedFrameRevision", navigation.revision());
            state.set(screen, ready);

            // A route can close while a press is still held. The persistent
            // browser must receive its matching release before it is parked,
            // otherwise its next route inherits an unfinished drag.
            set(OpusClientScreen.class, screen, "webLeaseId", controller.claimEmbeddedWebView(view));
            control.reset();
            event(110, 105, true);
            screen.handleMouseInput();
            require(wire(control).contains(" down 110 134 0 0\n"), "Close regression did not begin with a real press");
            // No GL display is created by this harness; the press was already
            // accepted with a texture, so dispose only the synthetic texture id.
            set(OpusWebTextureSurface.class, texture, "textureId", -1);
            screen.onGuiClosed();
            String closedWire = wire(control);
            int released = closedWire.indexOf(" up 428 241 0 0\n");
            int parked = closedWire.indexOf("SURFACE HIDE\n");
            require(released >= 0 && parked > released,
                    "Closing a held screen must release CEF before parking it: " + closedWire);

            OpusClientScreen retryScreen = new OpusClientScreen(controller, UiRoute.mainMenu());
            set(GuiScreen.class, retryScreen, "mc", minecraft);
            minecraft.currentScreen = retryScreen;
            retryScreen.width = 427;
            retryScreen.height = 240;
            geometry.invoke(retryScreen, 854, 480, 2.0F, true);
            set(OpusClientScreen.class, retryScreen, "expectedHostRevision", navigation.revision());
            set(OpusClientScreen.class, retryScreen, "requestedFrameRevision", navigation.revision());
            set(OpusClientScreen.class, retryScreen, "webView", view);
            set(OpusClientScreen.class, retryScreen, "webLeaseId", controller.claimEmbeddedWebView(view));
            OpusWebTextureSurface retryTexture = (OpusWebTextureSurface) get(OpusClientScreen.class, retryScreen, "webSurface");
            set(OpusWebTextureSurface.class, retryTexture, "textureId", 1);
            set(OpusWebTextureSurface.class, retryTexture, "textureWidth", 854);
            set(OpusWebTextureSurface.class, retryTexture, "textureHeight", 480);
            state.set(retryScreen, ready);
            control.reset();
            event(110, 105, true);
            retryScreen.handleMouseInput();
            set(OpusWebTextureSurface.class, retryTexture, "textureId", -1);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object failed = Enum.valueOf((Class) state.getType(), "FAILED");
            state.set(retryScreen, failed);
            // The real retry releases its old lease; only helper prewarming is
            // fenced off, since this test must never start a native window.
            set(ClientOverlayController.class, controller, "shuttingDown", true);
            try { retryScreen.keyTyped('\0', Keyboard.KEY_R); }
            finally { set(ClientOverlayController.class, controller, "shuttingDown", false); }
            String retryWire = wire(control);
            int retryReleased = retryWire.indexOf(" up 428 241 0 0\n");
            require(retryReleased >= 0 && retryWire.indexOf("SURFACE HIDE\n") > retryReleased,
                    "Retry must cancel outside old controls before detaching the helper: " + retryWire);
            verifyRetainedScreenReopen(minecraft, controller, navigation, view, control, geometry, state, ready, unsafe);
            System.out.println("PASS: real GuiScreen Retina clicks; 32 held-press route transitions; ACK-loss/multi-button/close/retry releases; post-dispatch focus/lost-release cancellation; render-only frames preserve queued clicks; stale/unconfirmed/revoked input rejected");
        } finally {
            set(ClientOverlayController.class, controller, "embeddedWebView", null);
            controller.shutdown();
            set(Minecraft.class, null, "theMinecraft", previousMinecraft);
            set(Keyboard.class, null, "created", previousKeyboardCreated);
            set(Mouse.class, null, "created", previousMouseCreated);
            set(Mouse.class, null, "buttons", previousMouseButtons);
            set(Mouse.class, null, "buttonCount", previousMouseButtonCount);
        }
    }

    private static void event(int x, int bottomY, boolean down) throws Exception {
        event(x, bottomY, 0, down);
    }

    private static void verifyRetainedScreenReopen(Minecraft minecraft, ClientOverlayController controller,
            UiNavigationManager navigation, OpusWebViewClient view, ByteArrayOutputStream control,
            Method geometry, Field state, Object ready, sun.misc.Unsafe unsafe) throws Exception {
        OpusClientScreen closing = new OpusClientScreen(controller, UiRoute.hudEditor());
        set(GuiScreen.class, closing, "mc", minecraft);
        minecraft.currentScreen = closing; closing.width = 427; closing.height = 240;
        geometry.invoke(closing, 854, 480, 2.0F, true);
        navigation.open("hud_editor"); set(UiNavigationManager.class, navigation, "confirmed", true);
        set(OpusClientScreen.class, closing, "webView", view);
        set(OpusClientScreen.class, closing, "webLeaseId", controller.claimEmbeddedWebView(view));
        OpusWebTextureSurface snapshot = (OpusWebTextureSurface) get(OpusClientScreen.class, closing, "webSurface");
        set(OpusWebTextureSurface.class, snapshot, "textureId", 1);
        set(OpusWebTextureSurface.class, snapshot, "textureWidth", 854);
        set(OpusWebTextureSurface.class, snapshot, "textureHeight", 480);
        Method key = OpusClientScreen.class.getDeclaredMethod("surfaceKey"); key.setAccessible(true);
        set(OpusClientScreen.class, closing, "lastSurfaceKey", key.invoke(closing));
        set(OpusClientScreen.class, closing, "lastSurfaceEpoch", controller.retainedSurfaceEpoch());
        state.set(closing, ready);
        closing.onGuiClosed();
        require(snapshot.hasTexture(), "GuiScreen close discarded its committed GPU surface");

        OpusClientScreen reopening = new OpusClientScreen(controller, UiRoute.hudEditor());
        set(GuiScreen.class, reopening, "mc", minecraft);
        minecraft.currentScreen = reopening; reopening.width = 427; reopening.height = 240;
        geometry.invoke(reopening, 854, 480, 2.0F, true);
        navigation.open("hud_editor");
        set(OpusClientScreen.class, reopening, "expectedHostRevision", navigation.revision());
        set(OpusClientScreen.class, reopening, "webView", view);
        set(OpusClientScreen.class, reopening, "webLeaseId", controller.claimEmbeddedWebView(view));
        Method restore = OpusClientScreen.class.getDeclaredMethod("restoreRetainedSurface"); restore.setAccessible(true); restore.invoke(reopening);
        require(get(OpusClientScreen.class, reopening, "webSurface") == snapshot,
                "new GuiScreen did not take ownership of its matching retained surface");
        require(!reopening.previewProductSurfaceReady(), "a cached image bypassed fresh-frame confirmation");
        try (DisplayFocusFixture focus = new DisplayFocusFixture(unsafe)) {
            control.reset(); event(110, 105, true); reopening.handleMouseInput();
            require(control.size() == 0, "a click activated an old cached image before the new ACK/frame");
        }
        set(OpusWebTextureSurface.class, snapshot, "textureId", -1);
        System.out.println("PASS: actual GuiScreen close/reopen transfers the retained surface while stale image input stays locked");
    }

    private static void event(int x, int bottomY, int button, boolean down) throws Exception {
        set(Mouse.class, null, "event_x", x);
        set(Mouse.class, null, "event_y", bottomY);
        set(Mouse.class, null, "eventButton", button);
        set(Mouse.class, null, "eventState", down);
        set(Mouse.class, null, "event_dwheel", 0);
        ((ByteBuffer) get(Mouse.class, null, "buttons")).put(button, down ? (byte) 1 : (byte) 0);
    }

    private static final class DisplayFocusFixture implements AutoCloseable {
        final AtomicBoolean active = new AtomicBoolean(true);
        private final sun.misc.Unsafe unsafe;
        private final Field implementation;
        private final Object previousImplementation;
        private final Object previousCreated;

        DisplayFocusFixture(sun.misc.Unsafe unsafe) throws Exception {
            this.unsafe = unsafe;
            implementation = Display.class.getDeclaredField("display_impl");
            implementation.setAccessible(true);
            previousImplementation = implementation.get(null);
            previousCreated = get(Display.class, null, "window_created");
            Class<?> displayInterface = implementation.getType();
            Object syntheticDisplay = Proxy.newProxyInstance(displayInterface.getClassLoader(),
                    new Class<?>[] {displayInterface}, (proxy, method, arguments) -> {
                        if ("isActive".equals(method.getName())) return active.get();
                        if ("getPixelScaleFactor".equals(method.getName())) return 2.0F;
                        throw new AssertionError("Unexpected native display call: " + method.getName());
                    });
            unsafe.putObject(unsafe.staticFieldBase(implementation), unsafe.staticFieldOffset(implementation), syntheticDisplay);
            set(Display.class, null, "window_created", true);
        }

        @Override
        public void close() throws Exception {
            set(Display.class, null, "window_created", previousCreated);
            unsafe.putObject(unsafe.staticFieldBase(implementation), unsafe.staticFieldOffset(implementation), previousImplementation);
        }
    }

    private static String wire(ByteArrayOutputStream control) {
        return new String(control.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Object get(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
