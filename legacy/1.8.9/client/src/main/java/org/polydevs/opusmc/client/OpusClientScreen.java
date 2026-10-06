package org.polydevs.opusmc.client;

import java.io.IOException;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.LWJGLUtil;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.polydevs.opusmc.client.embed.OpusWebTextureSurface;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;
import org.polydevs.opusmc.client.hud.HudManager;
import org.polydevs.opusmc.client.interop.ConnectionUiState;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiNavigationState;
import org.polydevs.opusmc.client.ui.UiRoute;
import org.polydevs.opusmc.client.ui.render.MinecraftUiRenderer;
import org.polydevs.opusmc.websurface.OpusDisplayMetrics;
import org.polydevs.opusmc.websurface.OpusDisplayMetricsTracker;
import org.polydevs.opusmc.websurface.OpusInputMapper;

/**
 * Minecraft host for the canonical React/CEF product UI.
 *
 * <p>This screen deliberately has no native product-page fallback. Java owns
 * route, lifecycle and the live HUD editor boundary; CEF is the only product
 * compositor. Before CEF has a frame, or after it fails, the screen renders a
 * minimal lifecycle shell rather than a second title/menu/settings UI.</p>
 */
final class OpusClientScreen extends GuiScreen {
    private static final Logger LOG = LogManager.getLogger(OpusClientScreen.class);
    private static final ResourceLocation PAUSE_BLUR =
            new ResourceLocation("opusclient", "shaders/post/pause_blur.json");
    private enum WebState {
        STARTING,
        READY,
        FAILED,
        CLOSED
    }

    private final ClientOverlayController controller;
    private UiNavigationState navigation;
    private UiNavigationState systemReturnNavigation;
    private NativeSystemScreenSession systemScreen;
    private final MinecraftUiRenderer nativeRenderer;
    private final OpusWebTextureSurface webSurface = new OpusWebTextureSurface();
    private final PauseWorldBlur pauseWorldBlur = new PauseWorldBlur(new PauseWorldBlur.ShaderHost() {
        @Override
        public Object currentShader() {
            EntityRenderer renderer = mc == null ? null : mc.entityRenderer;
            return renderer == null ? null : renderer.getShaderGroup();
        }

        @Override
        public Object loadPauseShader() {
            if (mc == null || mc.entityRenderer == null || !OpenGlHelper.shadersSupported
                    || !OpenGlHelper.isFramebufferEnabled()) {
                return null;
            }
            try {
                mc.entityRenderer.loadShader(PAUSE_BLUR);
                return mc.entityRenderer.getShaderGroup();
            } catch (RuntimeException error) {
                LOG.warn("Opus pause blur unavailable; keeping the transparent dim overlay", error);
                return null;
            }
        }

        @Override
        public void stopShader() {
            try {
                mc.entityRenderer.stopUseShader();
            } catch (RuntimeException error) {
                LOG.warn("Could not release the Opus pause blur", error);
            }
        }
    });
    private final OpusDisplayMetricsTracker displayMetricsTracker = new OpusDisplayMetricsTracker();
    private UiBounds productViewport = new UiBounds(0, 0, 1, 1);
    private OpusDisplayMetrics displayMetrics;
    private OpusInputMapper inputMapper;
    private OpusWebViewClient webView;
    private long webLeaseId;
    private long expectedHostRevision;
    private long requestedFrameRevision = -1L;
    private long pendingFrameSinceNanos;
    private WebState webState = WebState.STARTING;
    private int viewportDisplayWidth = -1;
    private int viewportDisplayHeight = -1;
    private float viewportPixelScale = -1.0F;
    private boolean initialized;
    private int lastForwardedX = Integer.MIN_VALUE;
    private int lastForwardedY = Integer.MIN_VALUE;
    private int pointerX;
    private int pointerY;
    private UiBounds nativeHudCanvas = new UiBounds(0, 0, 0, 0);
    private java.util.List<UiBounds> nativeHudExclusions = java.util.Collections.emptyList();
    private long nativeHudCanvasRevision = -1L;
    /** Owns a native-canvas button even when it did not start a drag. */
    private boolean nativeHudPointerOwner;
    private int nativeHudPointerButton = -1;
    private boolean nativeHudPointerCapture;
    private int webPointerButtons;
    private int suppressedMouseButtons;
    private boolean webPointerInside;
    private boolean previewPointerActive;
    private int previewPointerX;
    private int previewPointerY;
    private long inputDiagnosticRevision = -1L;
    private boolean inputDiagnosticReady;
    private long inputDiagnosticNanos;

    OpusClientScreen(ClientOverlayController controller, UiRoute initialRoute) {
        this(controller, new UiNavigationState(
                initialRoute,
                initialRoute.kind() == UiRoute.Kind.PAUSE_MENU
                        ? UiNavigationState.EntryPoint.PAUSE
                        : UiNavigationState.EntryPoint.TITLE));
    }

    OpusClientScreen(ClientOverlayController controller, UiNavigationState navigation) {
        this.controller = controller;
        this.navigation = navigation;
        this.nativeRenderer = new MinecraftUiRenderer(net.minecraft.client.Minecraft.getMinecraft());
    }

    @Override
    public void initGui() {
        if (systemScreen != null) systemScreen.initialize(mc, width, height);
        if (!controller.canNavigateTo(route())) {
            controller.closeNavigation(navigation);
            return;
        }
        pauseWorldBlur.update((route().kind() == UiRoute.Kind.PAUSE_MENU || route().kind() == UiRoute.Kind.QUICK_HUB) && mc.theWorld != null);
        // Minecraft calls initGui again for each live-resize step. Only the
        // first call opens a route/acquires CEF; subsequent calls update just
        // geometry or they restart the ACK timer and starve browser paints.
        if (initialized) {
            refreshViewport(false);
            return;
        }
        initialized = true;
        pendingFrameSinceNanos = System.nanoTime();
        refreshViewport(true);
        controller.onNavigationChanged(navigation, connectionState());
        expectedHostRevision = controller.currentUiNavigationRevision();
        requestedFrameRevision = -1L;
        acquireEmbeddedWebView();
    }

    private void acquireEmbeddedWebView() {
        if (webState == WebState.CLOSED || mc.currentScreen != this) {
            return;
        }
        OpusWebViewClient candidate = controller.acquireEmbeddedWebView(
                productViewport.width,
                productViewport.height,
                embeddedScale(),
                controller.routeIdFor(route()),
                route().moduleId(),
                route().settingsSection() == null
                        ? null
                        : route().settingsSection().wireValue());
        long leaseId = candidate == null ? 0L : controller.claimEmbeddedWebView(candidate);
        webView = leaseId == 0L ? null : candidate;
        webLeaseId = leaseId;
        webState = webView == null
                ? (controller.isEmbeddedWebViewPrewarming() ? WebState.STARTING : WebState.FAILED)
                : WebState.STARTING;
    }

    @Override
    protected void keyTyped(char typedCharacter, int keyCode) throws IOException {
        if (!windowHasInputFocus()) return;
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (systemScreen != null) {
                performSystemPrimaryAction();
                return;
            }
            // Give the CEF document first refusal so an open web modal closes
            // before route history. The web shell handles route-level Escape;
            // a failed/not-ready surface still falls back to Java navigation.
            if (embeddedInputActive()) {
                forwardKey(typedCharacter, keyCode);
            } else {
                backOrClose(true);
            }
            return;
        }
        if (webState == WebState.FAILED && keyCode == Keyboard.KEY_R) {
            retryEmbeddedWebView();
            return;
        }
        if (embeddedInputActive()) {
            forwardKey(typedCharacter, keyCode);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (mc.theWorld == null) {
            drawRect(0, 0, width, height, 0xFF0D0E15);
        }
        pauseWorldBlur.update((route().kind() == UiRoute.Kind.PAUSE_MENU || route().kind() == UiRoute.Kind.QUICK_HUB) && mc.theWorld != null);
        refreshViewport(false);
        if (webView == null && webState == WebState.STARTING
                && !controller.isEmbeddedWebViewPrewarming()) {
            if (controller.didEmbeddedWebViewPrewarmFail()) {
                webState = WebState.FAILED;
            } else {
                acquireEmbeddedWebView();
            }
        }
        if (webView != null && !webView.isFailed() && !webView.isSurfaceVisible()
                && controller.isCurrentEmbeddedWebView(webView, webLeaseId)) {
            webView.setSurfaceVisible(true);
        }
        OpusInputMapper.Pointer pointer = previewPointerActive
                ? new OpusInputMapper.Pointer(previewPointerX, previewPointerY)
                : currentPointer();
        int uiX = pointer.x;
        int uiY = pointer.y;
        pointerX = uiX;
        pointerY = uiY;

        consumeWebFrame();
        if (usesNativeSystemFallback()) {
            if (route().kind() == UiRoute.Kind.CONNECTION) {
                // Use our styled native screen instead of the vanilla GuiConnecting
                drawNativeConnectionScreen();
            } else {
                systemScreen.draw(mouseX, mouseY, partialTicks);
            }
            return;
        }
        if (isHudEditor() && productSurfaceReady()) {
            boolean nativePointer = nativeHudCanvasOwnsPoint(uiX, uiY) || nativeHudPointerCapture;
            drawHudEditor(nativePointer ? uiX : -1, nativePointer ? uiY : -1, partialTicks);
        }
        // CEF chrome is the product compositor and must stay visually above
        // the native live widget/editor layer.
        drawProductSurface();
        updateWebPointer(uiX, uiY);
    }

    @Override
    public void updateScreen() {
        reconcilePointerOwnership();
        NativeSystemScreenSession owner = systemScreen;
        if (owner == null) return;
        ConnectionUiState before = owner.state();
        owner.tick();
        if (mc.currentScreen == this && systemScreen == owner && !java.util.Objects.equals(owner.state(), before)) {
            commitNavigation();
        }
    }

    private boolean usesNativeSystemFallback() {
        long waitingMillis = pendingFrameSinceNanos == 0L ? 0L
                : (System.nanoTime() - pendingFrameSinceNanos) / 1000000L;
        return UiLifecycleShellPolicy.drawNativeSystem(systemScreen != null,
                webState == WebState.FAILED, productSurfaceReady(), waitingMillis);
    }

    private void consumeWebFrame() {
        if (webView == null) {
            if (webState != WebState.CLOSED && UiLifecycleShellPolicy.failedWithoutView(
                    controller.isEmbeddedWebViewPrewarming(),
                    controller.didEmbeddedWebViewPrewarmFail())) {
                webState = WebState.FAILED;
            }
            return;
        }
        if (!controller.isCurrentEmbeddedWebView(webView, webLeaseId)) return;
        if (webView.isFailed()) {
            webState = WebState.FAILED;
            webSurface.dispose();
            return;
        }
        if (!controller.isUiNavigationConfirmed(expectedHostRevision)) {
            webView.release(webView.consumeFrame());
            return;
        }
        if (requestedFrameRevision != expectedHostRevision) {
            if (webView.requestCommittedFrame() <= 0L) return;
            requestedFrameRevision = expectedHostRevision;
        }
        if (!UiLifecycleShellPolicy.acceptCommittedFrame(
                expectedHostRevision, requestedFrameRevision,
                controller.currentUiNavigationRevision(),
                controller.isUiNavigationConfirmed(expectedHostRevision))) {
            return;
        }
        OpusWebViewClient.Frame frame = webView.consumeFrame();
        if (frame != null) {
            try {
                if (webView.isCurrent(
                        frame, productViewport.width, productViewport.height, embeddedScale())) {
                    webSurface.upload(frame);
                    webState = WebState.READY;
                }
            } finally {
                webView.release(frame);
            }
        }
    }

    private void drawProductSurface() {
        boolean liveVisibleLease = webView != null && !webView.isFailed()
                && webView.isSurfaceVisible()
                && controller.isCurrentEmbeddedWebView(webView, webLeaseId);
        if (!UiLifecycleShellPolicy.drawTexture(
                productSurfaceReady(), webState == WebState.STARTING,
                webSurface.hasTexture(), liveVisibleLease)) {
            drawLifecycleShell();
            return;
        }
        float scaleX = width / (float) Math.max(1, productViewport.width);
        float scaleY = height / (float) Math.max(1, productViewport.height);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.scale(scaleX, scaleY, 1.0F);
            webSurface.draw(productViewport.width, productViewport.height);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private void drawLifecycleShell() {
        if (route().kind() == UiRoute.Kind.CONNECTION && webState != WebState.FAILED) {
            drawNativeConnectionScreen();
            return;
        }
        if (mc.theWorld != null) {
            drawDefaultBackground();
        } else {
            drawRect(0, 0, width, height, 0xFF0D0E15);
        }
        long elapsedMillis = pendingFrameSinceNanos == 0L ? 0L
                : (System.nanoTime() - pendingFrameSinceNanos) / 1000000L;
        if (!UiLifecycleShellPolicy.showText(elapsedMillis, webState == WebState.FAILED)) return;
        String title = webState == WebState.FAILED ? "OPUS UI unavailable" : "Starting OPUS UI";
        String detail = webState == WebState.FAILED
                ? "Press R to retry or Esc to close"
                : "Loading the embedded interface";
        drawCenteredString(fontRendererObj, title, width / 2, height / 2 - 8, 0xFFFFFFFF);
        drawCenteredString(fontRendererObj, detail, width / 2, height / 2 + 8, 0xFFAAAAAA);
    }

    /**
     * Renders a clean native connection screen (Lunar-style) that appears instantly
     * without waiting for the CEF pipeline to boot. Shows phase-appropriate text
     * with an animated ellipsis indicator.
     */
    private void drawNativeConnectionScreen() {
        // Dark semi-transparent overlay over the world/background
        drawDefaultBackground();
        net.minecraft.client.gui.Gui.drawRect(0, 0, width, height, 0xCC000000);

        ConnectionUiState conn = connectionState();
        String phase = conn == null ? "connecting" : conn.phase;
        String title = conn == null ? "Connecting..." : conn.title;
        String serverAddress = conn == null ? "" : conn.serverAddress.trim();

        // Animated dots — ticks at 20fps, cycles every 60 ticks = 3 seconds
        long tick = (System.currentTimeMillis() / 500L) % 4;
        String dots = tick == 0 ? "" : tick == 1 ? "." : tick == 2 ? ".." : "...";

        int cx = width / 2;
        int cy = height / 2;

        // Title (phase label)
        String displayTitle;
        if ("disconnected".equals(phase)) {
            displayTitle = title.isEmpty() ? "Disconnected" : title;
        } else if ("loading".equals(phase)) {
            displayTitle = "Loading terrain" + dots;
        } else {
            displayTitle = title.isEmpty() ? "Connecting" : title;
            if (!"disconnected".equals(phase)) displayTitle += dots;
        }

        // Accent color ring — draw a simple spinner arc using quads
        drawSpinnerRing(cx, cy - 30, 18, 3);

        // Server address (if available)
        int textY = cy + 6;
        drawCenteredString(fontRendererObj, displayTitle, cx, textY, 0xFFFFFFFF);
        if (!serverAddress.isEmpty()) {
            drawCenteredString(fontRendererObj, serverAddress, cx, textY + 14, 0xFF888888);
        }

        // Cancel hint
        if (conn != null && conn.canCancel) {
            drawCenteredString(fontRendererObj, "Press Esc to cancel", cx, cy + (serverAddress.isEmpty() ? 26 : 40), 0xFF555555);
        }
    }

    /** Draws a partial-arc spinner ring using GL quads. Angle advances with time. */
    private void drawSpinnerRing(int cx, int cy, int radius, int thickness) {
        long timeMs = System.currentTimeMillis();
        double startAngle = (timeMs / 800.0D) * Math.PI * 2.0D;
        double arcLength = Math.PI * 1.3D; // ~234 degrees filled

        final int segments = 24;
        net.minecraft.client.renderer.GlStateManager.disableTexture2D();
        net.minecraft.client.renderer.GlStateManager.enableBlend();
        net.minecraft.client.renderer.GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        net.minecraft.client.renderer.Tessellator tess = net.minecraft.client.renderer.Tessellator.getInstance();
        net.minecraft.client.renderer.WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_COLOR);
        float outer = radius + thickness / 2.0F;
        float inner = Math.max(0.0F, radius - thickness / 2.0F);
        // accent color ~#6C72FF with alpha
        int r = 108, g = 114, b = 255, a = 220;
        for (int i = 0; i <= segments; i++) {
            double angle = startAngle + arcLength * i / segments;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            // Fade tail end
            int alpha = i < 4 ? (int)(a * i / 4.0D) : a;
            wr.pos(cx + cos * outer, cy + sin * outer, 0).color(r, g, b, alpha).endVertex();
            wr.pos(cx + cos * inner, cy + sin * inner, 0).color(r, g, b, alpha).endVertex();
        }
        tess.draw();
        // Ghost track ring
        wr.begin(org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= segments * 3; i++) {
            double angle = Math.PI * 2.0D * i / (segments * 3);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            wr.pos(cx + cos * outer, cy + sin * outer, 0).color(255, 255, 255, 18).endVertex();
            wr.pos(cx + cos * inner, cy + sin * inner, 0).color(255, 255, 255, 18).endVertex();
        }
        tess.draw();
        net.minecraft.client.renderer.GlStateManager.enableTexture2D();
    }

    private void drawHudEditor(int mouseX, int mouseY, float partialTicks) {
        nativeRenderer.beginFrame(productViewport);
        try {
            controller.renderHudEditor(
                    nativeRenderer,
                    productViewport,
                    new UiInput(mouseX, mouseY, partialTicks));
        } finally {
            nativeRenderer.endFrame();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (!windowHasInputFocus()) return;
        // A prior native-editor route change may have invalidated the matching
        // mouse-up; a new press starts a fresh ownership sequence.
        int buttonMask = pointerButtonMask(mouseButton);
        suppressedMouseButtons &= ~buttonMask;
        OpusInputMapper.Pointer mapped = previewPointerActive
                ? new OpusInputMapper.Pointer(previewPointerX, previewPointerY)
                : currentEventPointer();
        int x = mapped.x;
        int y = mapped.y;
        pointerX = x;
        pointerY = y;
        if (mouseButton == 0) tracePointerPress(x, y);
        if (webPointerButtons == 0 && nativeHudCanvasOwnsPoint(x, y)) {
            nativeHudPointerOwner = true;
            nativeHudPointerButton = mouseButton;
            HudManager.EditorAction action = controller.hudEditorMouseDown(x, y, mouseButton);
            if (action != null && action.handled()) {
                if (action.settingsModuleId() != null) {
                    nativeHudPointerOwner = false;
                    nativeHudPointerButton = -1;
                    controller.navigateFromHudEditorToModule(action.settingsModuleId());
                    return;
                }
                nativeHudPointerCapture = controller.hudEditorHasPointerCapture();
            }
            // Empty native canvas is still owned by the native editor. It may
            // clear selection, but the same click must never reach CEF.
            return;
        }
        if (buttonMask != 0 && forwardPointerButton(x, y, mouseButton, true)) {
            webPointerButtons |= buttonMask;
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        OpusInputMapper.Pointer mapped = previewPointerActive
                ? new OpusInputMapper.Pointer(previewPointerX, previewPointerY)
                : currentEventPointer();
        int x = mapped.x;
        int y = mapped.y;
        pointerX = x;
        pointerY = y;
        int buttonMask = pointerButtonMask(state);
        if (!windowHasInputFocus()) cancelPointerOwnership(buttonMask);
        if ((suppressedMouseButtons & buttonMask) != 0) {
            suppressedMouseButtons &= ~buttonMask;
            return;
        }
        if (nativeHudPointerOwner && nativeHudPointerButton == state) {
            controller.hudEditorMouseUp(
                    nativeHudPointerButton >= 0 ? nativeHudPointerButton : state);
            nativeHudPointerOwner = false;
            nativeHudPointerButton = -1;
            nativeHudPointerCapture = false;
            return;
        }
        // Balance only a press accepted by this surface. A cancelled route has
        // already released its capture; a late up must not reach the next route.
        if ((webPointerButtons & buttonMask) != 0 && ownsVisibleBrowser()) {
            webView.sendMouseButton(x, y, state, false, pointerButtonModifiers(state));
        }
        webPointerButtons &= ~buttonMask;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (!windowHasInputFocus()) return;
        OpusInputMapper.Pointer mapped = previewPointerActive
                ? new OpusInputMapper.Pointer(previewPointerX, previewPointerY)
                : currentEventPointer();
        int x = mapped.x;
        int y = mapped.y;
        pointerX = x;
        pointerY = y;
        if (nativeHudPointerOwner) {
            if (nativeHudPointerCapture) {
                controller.hudEditorMouseDrag(productViewport, x, y, clickedMouseButton);
            }
            return;
        }
        if (webPointerButtons != 0 || !nativeHudCanvasOwnsPoint(x, y)) {
            forwardPointerMove(x, y);
        } else {
            leaveWebPointer(x, y);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        if (usesNativeSystemFallback()) {
            systemScreen.mouseInput();
            return;
        }
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && embeddedInputActive() && !nativeHudPointerOwner
                && (webPointerButtons != 0 || !nativeHudCanvasOwnsPoint(pointerX, pointerY))) {
            webView.sendWheel(
                    Math.max(0, pointerX),
                    Math.max(0, pointerY),
                    wheel,
                    currentModifiers());
        }
    }

    private void retryEmbeddedWebView() {
        cancelPointerOwnership(webPointerButtons
                | (nativeHudPointerOwner ? pointerButtonMask(nativeHudPointerButton) : 0));
        clearInputOwnership();
        controller.releaseEmbeddedWebView(webView, webLeaseId);
        webLeaseId = 0L;
        controller.retryEmbeddedWebView();
        webSurface.dispose();
        webView = null;
        webState = WebState.STARTING;
        pendingFrameSinceNanos = System.nanoTime();
    }

    @Override
    public void onGuiClosed() {
        if (systemScreen != null) systemScreen.close();
        systemScreen = null;
        systemReturnNavigation = null;
        pauseWorldBlur.close();
        OpusWebViewClient owner = webView;
        long leaseId = webLeaseId;
        // CEF stays alive between screens. Balance its pressed buttons while
        // this screen still owns the visible browser, before dropping the
        // reference or parking the surface.
        clearInputOwnership();
        webState = WebState.CLOSED;
        webSurface.dispose();
        webView = null;
        webLeaseId = 0L;
        controller.releaseEmbeddedWebView(owner, leaseId);
        // Minecraft calls this for both explicit close and replacement by a
        // newer screen. It is cleanup only; the controller emits a close
        // transition explicitly so an old screen cannot erase a new route.
    }

    @Override
    public boolean doesGuiPauseGame() {
        UiRoute.Kind kind = route().kind();
        return kind == UiRoute.Kind.PAUSE_MENU
                || (mc.theWorld != null
                        && kind == UiRoute.Kind.GAME_OPTIONS
                        && navigation.entryPoint() == UiNavigationState.EntryPoint.PAUSE);
    }

    boolean isHudEditor() {
        return route().kind() == UiRoute.Kind.HUD_EDITOR;
    }

    UiRoute route() {
        return navigation.current();
    }

    UiNavigationState navigationForSystemReturn() {
        return navigation;
    }

    boolean isSystemFlow() { return systemScreen != null; }

    NativeSystemScreenSession systemScreenSession() { return systemScreen; }

    ConnectionUiState connectionState() {
        return systemScreen == null ? null : systemScreen.state();
    }

    /** Called from GuiOpenEvent, which preserves this instance and its lease. */
    void presentSystemScreen(NativeSystemScreenSession next) {
        if (systemScreen == null) {
            systemReturnNavigation = route().kind() == UiRoute.Kind.CONNECTION
                    ? new UiNavigationState(UiRoute.multiplayer(), UiNavigationState.EntryPoint.TITLE)
                    : navigation;
        }
        if (systemScreen != null) systemScreen.close();
        systemScreen = next;
        navigation = new UiNavigationState(UiRoute.connection(), UiNavigationState.EntryPoint.TITLE,
                next.state().canCancel ? UiRoute.multiplayer() : null);
        pauseWorldBlur.close();
        if (initialized) commitNavigation();
    }

    /** Native Back/Cancel supplies its real parent; adopt its flow without reloading CEF. */
    void adoptHostFlow(UiNavigationState next) {
        if (systemScreen != null) systemScreen.close();
        systemScreen = null;
        systemReturnNavigation = null;
        navigation = next;
        pauseWorldBlur.update((route().kind() == UiRoute.Kind.PAUSE_MENU || route().kind() == UiRoute.Kind.QUICK_HUB) && mc.theWorld != null);
        if (initialized) commitNavigation();
    }

    boolean performSystemPrimaryAction() {
        if (systemScreen == null) return false;
        try {
            NativeSystemScreenSession owner = systemScreen;
            UiNavigationState returnFlow = systemReturnNavigation;
            boolean accepted = owner.primaryAction();
            // A caller may have supplied this same retained host as its
            // native parent. Restore the pre-system flow in that case too.
            if (accepted && mc.currentScreen == this && systemScreen == owner && returnFlow != null) {
                adoptHostFlow(returnFlow);
            }
            return accepted;
        } catch (IOException failure) {
            LOG.warn("Minecraft system screen action failed; retaining its native fallback", failure);
            webState = WebState.FAILED;
            return false;
        }
    }

    boolean isHotkeyFlow() {
        return navigation.isRootHotkeyFlow();
    }

    UiRoute previewRoute() {
        return route();
    }

    boolean previewProductSurfaceReady() {
        return productSurfaceReady();
    }

    boolean previewProductSurfaceFailed() {
        return webState == WebState.FAILED;
    }

    String previewProductSurfaceState() {
        return webState.name().toLowerCase(java.util.Locale.ROOT);
    }

    UiBounds previewProductViewport() {
        return productViewport;
    }

    boolean previewNativeHudCanvasReady() {
        return isHudEditor()
                && nativeHudCanvasRevision == controller.currentUiNavigationRevision()
                && nativeHudCanvas.width > 0
                && nativeHudCanvas.height > 0;
    }

    long previewNavigationRevision() {
        return controller.currentUiNavigationRevision();
    }

    void previewNavigate(UiRoute nextRoute) {
        navigate(nextRoute);
    }

    void navigate(UiRoute nextRoute) {
        if (systemScreen != null) return;
        if (!controller.canNavigateTo(nextRoute) || !navigation.navigate(nextRoute)) {
            return;
        }
        commitNavigation();
    }

    void backOrClose() {
        backOrClose(false);
    }

    // Package-private so the loopback bridge can preserve the same Escape
    // semantics as a physical key event when the SPA requests a root back.
    void backOrClose(boolean fromEscape) {
        if (systemScreen != null) {
            performSystemPrimaryAction();
            return;
        }
        if (navigation.back() == null || !controller.canNavigateTo(route())) {
            if (fromEscape) {
                controller.closeNavigationFromEscape(navigation);
            } else {
                controller.closeNavigation(navigation);
            }
            return;
        }
        commitNavigation();
    }

    void closeToGame() {
        if (systemScreen != null) {
            performSystemPrimaryAction();
            return;
        }
        controller.closeNavigation(navigation);
    }

    private void commitNavigation() {
        clearInputOwnership();
        // Keep the last good texture visible while the browser commits the
        // next route, but disable embedded input until a current frame lands.
        webState = WebState.STARTING;
        pendingFrameSinceNanos = System.nanoTime();
        controller.onNavigationChanged(navigation, connectionState());
        expectedHostRevision = controller.currentUiNavigationRevision();
        requestedFrameRevision = -1L;
        if (webView != null && !webView.isFailed()) {
            webView.navigate(controller.embeddedUrlFor(route()));
        }
    }

    void setPreviewPointer(int x, int y) {
        previewPointerActive = true;
        previewPointerX = x;
        previewPointerY = y;
    }

    void clearPreviewPointer() {
        previewPointerActive = false;
    }

    void acceptHudEditorCanvas(long revision, UiBounds bounds) {
        acceptHudEditorCanvas(revision, bounds, java.util.Collections.<UiBounds>emptyList());
    }

    void acceptHudEditorCanvas(long revision, UiBounds bounds, java.util.List<UiBounds> exclusions) {
        if (!isHudEditor() || revision != controller.currentUiNavigationRevision() || bounds == null) {
            return;
        }
        UiBounds clipped = bounds.intersect(productViewport);
        nativeHudCanvas = clipped.width > 0 && clipped.height > 0
                ? clipped
                : new UiBounds(0, 0, 0, 0);
        nativeHudCanvasRevision = revision;
        java.util.List<UiBounds> clippedExclusions = new java.util.ArrayList<UiBounds>();
        for (UiBounds exclusion : exclusions) {
            UiBounds hole = exclusion.intersect(productViewport);
            if (hole.width > 0 && hole.height > 0) clippedExclusions.add(hole);
        }
        nativeHudExclusions = java.util.Collections.unmodifiableList(clippedExclusions);
    }

    boolean previewClick(int x, int y, int button) {
        if (!productSurfaceReady() || !productViewport.contains(x, y)) {
            return false;
        }
        setPreviewPointer(x, y);
        try {
            mouseClicked(fromUiX(x), fromUiY(y), button);
            mouseReleased(fromUiX(x), fromUiY(y), button);
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    boolean previewDrag(int x, int y, int toX, int toY, int button) {
        if (!productSurfaceReady()
                || !productViewport.contains(x, y)
                || !productViewport.contains(toX, toY)) {
            return false;
        }
        setPreviewPointer(x, y);
        try {
            mouseClicked(fromUiX(x), fromUiY(y), button);
            setPreviewPointer(toX, toY);
            mouseClickMove(fromUiX(toX), fromUiY(toY), button, 16L);
            mouseReleased(fromUiX(toX), fromUiY(toY), button);
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    boolean previewKey(String key) {
        if (key == null) {
            return false;
        }
        int keyCode;
        if ("escape".equals(key)) {
            keyCode = Keyboard.KEY_ESCAPE;
        } else if ("retry".equals(key)) {
            keyCode = Keyboard.KEY_R;
        } else {
            return false;
        }
        try {
            keyTyped('\0', keyCode);
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    private void refreshViewport(boolean force) {
        refreshViewport(Math.max(1, mc.displayWidth), Math.max(1, mc.displayHeight),
                Math.max(1.0F, Display.getPixelScaleFactor()), force);
    }

    /** Geometry transition shared by live LWJGL metrics and headless checks. */
    private void refreshViewport(int framebufferWidth, int framebufferHeight, float pixelScale, boolean force) {
        int logicalWidth = Math.max(1, Math.round(framebufferWidth / pixelScale));
        int logicalHeight = Math.max(1, Math.round(framebufferHeight / pixelScale));
        boolean framebufferChanged = framebufferWidth != viewportDisplayWidth
                || framebufferHeight != viewportDisplayHeight;
        boolean pixelScaleChanged = Math.abs(pixelScale - viewportPixelScale) > 0.0001F;
        boolean viewportChanged = force || framebufferChanged || pixelScaleChanged;
        if (!viewportChanged) {
            return;
        }
        viewportDisplayWidth = framebufferWidth;
        viewportDisplayHeight = framebufferHeight;
        viewportPixelScale = pixelScale;
        displayMetrics = displayMetricsTracker.update(
                logicalWidth, logicalHeight, framebufferWidth, framebufferHeight);
        inputMapper = new OpusInputMapper(displayMetrics);
        productViewport = new UiBounds(0, 0, logicalWidth, logicalHeight);
        clearInputOwnership();
        // Keep the previous image visible while CEF catches up, but never send
        // input to its old layout or mark that raster as the current UI.
        webState = WebState.STARTING;
        pendingFrameSinceNanos = System.nanoTime();
        if (webView != null && !webView.isFailed()) {
            webView.resize(productViewport.width, productViewport.height, embeddedScale());
        }
    }

    private float embeddedScale() {
        if (displayMetrics == null) {
            return Math.max(1.0F, Display.getPixelScaleFactor());
        }
        double scale = Math.min(displayMetrics.scaleX(), displayMetrics.scaleY());
        return (float) Math.max(0.5D, Math.min(4.0D, scale));
    }

    private boolean embeddedInputActive() {
        // A retained frame keeps transitions visually continuous, but does not
        // grant input to a route that has not acknowledged its current state.
        return productSurfaceReady() && windowHasInputFocus();
    }

    private boolean windowHasInputFocus() {
        return !Display.isCreated() || Display.isActive();
    }

    /** Called after queued input, never between an ordinary down/up pair. */
    private void reconcilePointerOwnership() {
        if (!Display.isCreated()) return;
        int owned = webPointerButtons
                | (nativeHudPointerOwner ? pointerButtonMask(nativeHudPointerButton) : 0);
        if (!Display.isActive()) {
            cancelPointerOwnership(owned);
            return;
        }
        if (owned == 0) return;
        int physicallyHeld = 0;
        if (Mouse.isCreated()) {
            for (int button = 0; button < 3; button++) {
                if (Mouse.isButtonDown(button)) physicallyHeld |= pointerButtonMask(button);
            }
        }
        int lostReleases = owned & ~physicallyHeld;
        if (lostReleases != 0) cancelPointerOwnership(lostReleases);
    }

    /** Cancel, rather than click, when focus or a physical release is lost. */
    private void cancelPointerOwnership(int buttons) {
        int invalidated = buttons & (webPointerButtons
                | (nativeHudPointerOwner ? pointerButtonMask(nativeHudPointerButton) : 0));
        int webButtons = webPointerButtons & buttons;
        if (ownsVisibleBrowser() && (webButtons != 0 || webPointerInside)) {
            // A synthetic up at the old button coordinates would activate it.
            // Leave/release outside the viewport so focus loss cannot click it.
            int outsideX = productViewport.width + 1;
            int outsideY = productViewport.height + 1;
            for (int button = 0; button < 3; button++) {
                if ((webButtons & pointerButtonMask(button)) != 0) {
                    webView.sendMouseButton(outsideX, outsideY, button, false, pointerButtonModifiers(button));
                }
            }
            webView.sendMouseLeave(outsideX, outsideY, currentModifiers());
        }
        webPointerButtons &= ~buttons;
        if (nativeHudPointerOwner && (buttons & pointerButtonMask(nativeHudPointerButton)) != 0) {
            controller.cancelHudEditorPointerCapture();
            nativeHudPointerOwner = false;
            nativeHudPointerButton = -1;
            nativeHudPointerCapture = false;
        }
        suppressedMouseButtons |= invalidated;
        webPointerInside = false;
        lastForwardedX = Integer.MIN_VALUE;
        lastForwardedY = Integer.MIN_VALUE;
    }

    private boolean productSurfaceReady() {
        return webState == WebState.READY
                && UiLifecycleShellPolicy.acceptCommittedFrame(
                        expectedHostRevision, requestedFrameRevision,
                        controller.currentUiNavigationRevision(),
                        controller.isUiNavigationConfirmed(expectedHostRevision))
                && webView != null && !webView.isFailed() && webView.isSurfaceVisible()
                && controller.isCurrentEmbeddedWebView(webView, webLeaseId)
                && webSurface.hasTexture();
    }

    private boolean nativeHudCanvasOwnsPoint(int x, int y) {
        // CEF is drawn above native widgets; it must also own its chrome even
        // if a widget happens to be placed under Mods, Done or the help strip.
        for (UiBounds exclusion : nativeHudExclusions) {
            if (exclusion.contains(x, y)) return false;
        }
        return productSurfaceReady()
                && isHudEditor()
                && nativeHudCanvasRevision == controller.currentUiNavigationRevision()
                && nativeHudCanvas.contains(x, y);
    }

    private void updateWebPointer(int x, int y) {
        if (!embeddedInputActive()) {
            webPointerInside = false;
            return;
        }
        if (nativeHudPointerOwner) {
            leaveWebPointer(x, y);
            return;
        }
        if (webPointerButtons == 0 && nativeHudCanvasOwnsPoint(x, y)) {
            leaveWebPointer(x, y);
            return;
        }
        if (!webPointerInside || x != lastForwardedX || y != lastForwardedY) {
            forwardPointerMove(x, y);
        }
    }

    private void leaveWebPointer(int x, int y) {
        if (!embeddedInputActive() || !webPointerInside) {
            return;
        }
        webView.sendMouseLeave(x, y, currentModifiers());
        webPointerInside = false;
        lastForwardedX = Integer.MIN_VALUE;
        lastForwardedY = Integer.MIN_VALUE;
    }

    private void clearInputOwnership() {
        int invalidatedButtons = webPointerButtons
                | (nativeHudPointerOwner ? pointerButtonMask(nativeHudPointerButton) : 0);
        releaseWebPointerOwnership();
        controller.cancelHudEditorPointerCapture();
        nativeHudCanvas = new UiBounds(0, 0, 0, 0);
        nativeHudCanvasRevision = -1L;
        nativeHudExclusions = java.util.Collections.emptyList();
        nativeHudPointerOwner = false;
        nativeHudPointerButton = -1;
        nativeHudPointerCapture = false;
        webPointerButtons = 0;
        // A route/viewport transition may happen between the physical mouse
        // press and release. We already synthesize the matching release for the
        // old CEF document, so swallow the stale physical release instead of
        // delivering it to the newly committed route.
        suppressedMouseButtons |= invalidatedButtons;
        webPointerInside = false;
        lastForwardedX = Integer.MIN_VALUE;
        lastForwardedY = Integer.MIN_VALUE;
    }

    /** Cancel, never click, before a route/viewport/screen transition. */
    private void releaseWebPointerOwnership() {
        if (!ownsVisibleBrowser()) {
            return;
        }
        int x = Math.max(1, productViewport.width + 1);
        int y = Math.max(1, productViewport.height + 1);
        if (webPointerInside || webPointerButtons != 0) {
            webView.sendMouseLeave(x, y, currentModifiers());
        }
        for (int button = 0; button < 3; button++) {
            if ((webPointerButtons & pointerButtonMask(button)) != 0) {
                webView.sendMouseButton(x, y, button, false, pointerButtonModifiers(button));
            }
        }
    }

    private boolean ownsVisibleBrowser() {
        return webView != null && !webView.isFailed() && webView.isSurfaceVisible()
                && controller.isCurrentEmbeddedWebView(webView, webLeaseId);
    }

    private static int pointerButtonMask(int button) {
        return button >= 0 && button < 3 ? 1 << button : 0;
    }

    private void forwardPointerMove(int x, int y) {
        if (!embeddedInputActive()) {
            return;
        }
        lastForwardedX = x;
        lastForwardedY = y;
        webView.sendMouseMove(x, y, currentModifiers());
        webPointerInside = true;
    }

    private boolean forwardPointerButton(int x, int y, int button, boolean down) {
        if (!embeddedInputActive()) {
            return false;
        }
        if (x != lastForwardedX || y != lastForwardedY) {
            forwardPointerMove(x, y);
        }
        webView.sendMouseButton(x, y, button, down, pointerButtonModifiers(button));
        return true;
    }

    /** Bounded evidence for owner-controlled clicks, never a readiness bypass. */
    private void tracePointerPress(int x, int y) {
        boolean ready = embeddedInputActive();
        long now = System.nanoTime();
        if (inputDiagnosticRevision == expectedHostRevision && inputDiagnosticReady == ready
                && (ready || now - inputDiagnosticNanos < 2000000000L)) {
            return;
        }
        inputDiagnosticRevision = expectedHostRevision;
        inputDiagnosticReady = ready;
        inputDiagnosticNanos = now;
        LOG.info("Opus input r{} state={} ready={} point={}x{} viewport={}x{} confirmed={} lease={} visible={} texture={}",
                expectedHostRevision, webState, ready, x, y, productViewport.width, productViewport.height,
                controller.isUiNavigationConfirmed(expectedHostRevision),
                webView != null && controller.isCurrentEmbeddedWebView(webView, webLeaseId),
                webView != null && webView.isSurfaceVisible(), webSurface.hasTexture());
    }

    private void forwardKey(char typedCharacter, int keyCode) {
        int modifiers = currentModifiers();
        int virtualKey = OpusInputMapper.windowsVirtualKeyFromLwjgl(keyCode);
        boolean shortcut = (modifiers & (OpusInputMapper.MOD_CONTROL
                | OpusInputMapper.MOD_ALT
                | OpusInputMapper.MOD_COMMAND)) != 0;
        if (virtualKey != 0 && (shortcut || isDirectKeyEvent(keyCode))) {
            webView.sendKey(virtualKey, true, modifiers);
            webView.sendKey(virtualKey, false, modifiers);
        } else if (typedCharacter >= 0x20 && typedCharacter != 0x7F) {
            webView.sendChar(typedCharacter, modifiers);
        } else if (virtualKey != 0) {
            webView.sendKey(virtualKey, true, modifiers);
            webView.sendKey(virtualKey, false, modifiers);
        }
    }

    private int toUiX(int ignoredMinecraftGuiX) {
        if (previewPointerActive) {
            return previewPointerX;
        }
        return currentPointer().x;
    }

    private int toUiY(int ignoredMinecraftGuiY) {
        if (previewPointerActive) {
            return previewPointerY;
        }
        return currentPointer().y;
    }

    private OpusInputMapper.Pointer currentPointer() {
        if (inputMapper == null) {
            refreshViewport(true);
        }
        return mapLwjglPointer(Mouse.getX(), Mouse.getY());
    }

    private OpusInputMapper.Pointer currentEventPointer() {
        if (inputMapper == null) {
            refreshViewport(true);
        }
        return mapLwjglPointer(Mouse.getEventX(), Mouse.getEventY());
    }

    private OpusInputMapper.Pointer mapLwjglPointer(int x, int bottomY) {
        // LWJGL 2's macOS native mouse reports AppKit window points even when
        // the OpenGL framebuffer is Retina-scaled. Treating those points as
        // framebuffer pixels halves both axes and misses the visible CEF UI.
        if (LWJGLUtil.getPlatform() == LWJGLUtil.PLATFORM_MACOSX) {
            return inputMapper.fromLwjglLogical(x, bottomY);
        }
        return inputMapper.fromLwjglFramebuffer(x, bottomY);
    }

    private int fromUiX(int uiX) {
        return Math.round(uiX * width / (float) Math.max(1, productViewport.width));
    }

    private int fromUiY(int uiY) {
        return Math.round(uiY * height / (float) Math.max(1, productViewport.height));
    }

    private int currentModifiers() {
        return OpusInputMapper.modifiers(
                Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT),
                Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL),
                Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU),
                Keyboard.isKeyDown(Keyboard.KEY_LMETA) || Keyboard.isKeyDown(Keyboard.KEY_RMETA));
    }

    private int pointerButtonModifiers(int button) {
        int modifiers = currentModifiers();
        // LWJGL already identifies the physical button. On macOS, carrying
        // sprint's held Ctrl into a primary CEF click makes AppKit reinterpret
        // it as a context menu instead of activating the game UI. Normalize
        // only that click pair; keyboard shortcuts and other buttons/modifiers
        // retain their actual state, including explicit secondary clicks.
        if (button == 0 && LWJGLUtil.getPlatform() == LWJGLUtil.PLATFORM_MACOSX) {
            return modifiers & ~OpusInputMapper.MOD_CONTROL;
        }
        return modifiers;
    }

    private static boolean isDirectKeyEvent(int keyCode) {
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE:
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
            case Keyboard.KEY_BACK:
            case Keyboard.KEY_TAB:
            case Keyboard.KEY_DELETE:
            case Keyboard.KEY_LEFT:
            case Keyboard.KEY_UP:
            case Keyboard.KEY_RIGHT:
            case Keyboard.KEY_DOWN:
            case Keyboard.KEY_HOME:
            case Keyboard.KEY_END:
            case Keyboard.KEY_PRIOR:
            case Keyboard.KEY_NEXT:
            case Keyboard.KEY_SPACE:
                return true;
            default:
                return false;
        }
    }
}
