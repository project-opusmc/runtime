package org.polydevs.opusmc.client;

import java.io.IOException;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.polydevs.opusmc.client.embed.OpusWebTextureSurface;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;
import org.polydevs.opusmc.client.hud.HudManager;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiNavigationState;
import org.polydevs.opusmc.client.ui.UiRoute;
import org.polydevs.opusmc.client.ui.render.MinecraftUiRenderer;
import org.polydevs.opusmc.websurface.OpusDisplayMetrics;
import org.polydevs.opusmc.websurface.OpusDisplayMetricsTracker;
import org.polydevs.opusmc.websurface.OpusInputMapper;

/**
 * Minecraft host for the canonical Svelte/CEF product UI.
 *
 * <p>This screen deliberately has no native product-page fallback. Java owns
 * route, lifecycle and the live HUD editor boundary; CEF is the only product
 * compositor. Before CEF has a frame, or after it fails, the screen renders a
 * minimal lifecycle shell rather than a second title/menu/settings UI.</p>
 */
final class OpusClientScreen extends GuiScreen {
    private enum WebState {
        STARTING,
        READY,
        FAILED,
        CLOSED
    }

    private final ClientOverlayController controller;
    private final UiNavigationState navigation;
    private final MinecraftUiRenderer nativeRenderer;
    private final OpusWebTextureSurface webSurface = new OpusWebTextureSurface();
    private final OpusDisplayMetricsTracker displayMetricsTracker = new OpusDisplayMetricsTracker();
    private UiBounds productViewport = new UiBounds(0, 0, 1, 1);
    private OpusDisplayMetrics displayMetrics;
    private OpusInputMapper inputMapper;
    private OpusWebViewClient webView;
    private WebState webState = WebState.STARTING;
    private int viewportDisplayWidth = -1;
    private int viewportDisplayHeight = -1;
    private float viewportPixelScale = -1.0F;
    private int lastForwardedX = Integer.MIN_VALUE;
    private int lastForwardedY = Integer.MIN_VALUE;
    private int pointerX;
    private int pointerY;
    private UiBounds nativeHudCanvas = new UiBounds(0, 0, 0, 0);
    private long nativeHudCanvasRevision = -1L;
    /** Owns a native-canvas button even when it did not start a drag. */
    private boolean nativeHudPointerOwner;
    private int nativeHudPointerButton = -1;
    private boolean nativeHudPointerCapture;
    private boolean webPointerCapture;
    private int webPointerButton = -1;
    private boolean suppressNextMouseRelease;
    private boolean webPointerInside;
    private boolean previewPointerActive;
    private int previewPointerX;
    private int previewPointerY;

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
        if (!controller.canNavigateTo(route())) {
            controller.closeNavigation(navigation);
            return;
        }
        refreshViewport(true);
        controller.onNavigationChanged(navigation);
        acquireEmbeddedWebView();
    }

    private void acquireEmbeddedWebView() {
        if (webState == WebState.CLOSED || mc.currentScreen != this) {
            return;
        }
        webView = controller.acquireEmbeddedWebView(
                productViewport.width,
                productViewport.height,
                embeddedScale(),
                controller.routeIdFor(route()),
                route().moduleId(),
                route().settingsSection() == null
                        ? null
                        : route().settingsSection().wireValue());
        webState = webView == null ? WebState.FAILED : WebState.STARTING;
    }

    @Override
    protected void keyTyped(char typedCharacter, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
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
        refreshViewport(false);
        OpusInputMapper.Pointer pointer = previewPointerActive
                ? new OpusInputMapper.Pointer(previewPointerX, previewPointerY)
                : currentPointer();
        int uiX = pointer.x;
        int uiY = pointer.y;
        pointerX = uiX;
        pointerY = uiY;

        consumeWebFrame();
        if (isHudEditor() && productSurfaceReady()) {
            boolean nativePointer = nativeHudCanvasOwnsPoint(uiX, uiY) || nativeHudPointerCapture;
            drawHudEditor(nativePointer ? uiX : -1, nativePointer ? uiY : -1, partialTicks);
        }
        // CEF chrome is the product compositor and must stay visually above
        // the native live widget/editor layer.
        drawProductSurface();
        updateWebPointer(uiX, uiY);
    }

    private void consumeWebFrame() {
        if (webView == null) {
            webState = WebState.FAILED;
            return;
        }
        if (webView.isFailed()) {
            webState = WebState.FAILED;
            webSurface.dispose();
            return;
        }
        OpusWebViewClient.Frame frame = webView.consumeFrame();
        if (frame != null) {
            try {
                if (webView.isCurrent(
                        frame, productViewport.width, productViewport.height, embeddedScale())) {
                    webSurface.upload(frame);
                } else {
                    webSurface.dispose();
                }
            } finally {
                webView.release(frame);
            }
        }
        if (webView.isReady() && webSurface.hasTexture()) {
            webState = WebState.READY;
        }
    }

    private void drawProductSurface() {
        if (!productSurfaceReady()) {
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
        drawDefaultBackground();
        String title = webState == WebState.FAILED ? "OPUS UI unavailable" : "Starting OPUS UI";
        String detail = webState == WebState.FAILED
                ? "Press R to retry or Esc to close"
                : "Loading the embedded interface";
        drawCenteredString(fontRendererObj, title, width / 2, height / 2 - 8, 0xFFFFFFFF);
        drawCenteredString(fontRendererObj, detail, width / 2, height / 2 + 8, 0xFFAAAAAA);
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
        // A prior native-editor route change may have invalidated the matching
        // mouse-up; a new press starts a fresh ownership sequence.
        suppressNextMouseRelease = false;
        OpusInputMapper.Pointer mapped = previewPointerActive
                ? new OpusInputMapper.Pointer(previewPointerX, previewPointerY)
                : currentEventPointer();
        int x = mapped.x;
        int y = mapped.y;
        pointerX = x;
        pointerY = y;
        if (!webPointerCapture && nativeHudCanvasOwnsPoint(x, y)) {
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
        webPointerCapture = forwardPointerButton(x, y, mouseButton, true);
        webPointerButton = webPointerCapture ? mouseButton : -1;
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
        if (suppressNextMouseRelease) {
            suppressNextMouseRelease = false;
            webPointerCapture = false;
            webPointerButton = -1;
            return;
        }
        if (nativeHudPointerOwner) {
            controller.hudEditorMouseUp(
                    nativeHudPointerButton >= 0 ? nativeHudPointerButton : state);
            nativeHudPointerOwner = false;
            nativeHudPointerButton = -1;
            nativeHudPointerCapture = false;
            return;
        }
        if (webPointerCapture || !nativeHudCanvasOwnsPoint(x, y)) {
            forwardPointerButton(x, y, state, false);
        }
        webPointerCapture = false;
        webPointerButton = -1;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
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
        if (webPointerCapture || !nativeHudCanvasOwnsPoint(x, y)) {
            forwardPointerMove(x, y);
        } else {
            leaveWebPointer(x, y);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && embeddedInputActive() && !nativeHudPointerOwner
                && (webPointerCapture || !nativeHudCanvasOwnsPoint(pointerX, pointerY))) {
            webView.sendWheel(
                    Math.max(0, pointerX),
                    Math.max(0, pointerY),
                    wheel,
                    currentModifiers());
        }
    }

    private void retryEmbeddedWebView() {
        controller.retryEmbeddedWebView();
        webSurface.dispose();
        webView = null;
        webState = WebState.STARTING;
        acquireEmbeddedWebView();
    }

    @Override
    public void onGuiClosed() {
        OpusWebViewClient owner = webView;
        webState = WebState.CLOSED;
        webSurface.dispose();
        webView = null;
        clearInputOwnership();
        controller.releaseEmbeddedWebView(owner);
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
        controller.closeNavigation(navigation);
    }

    private void commitNavigation() {
        clearInputOwnership();
        webSurface.dispose();
        webState = WebState.STARTING;
        controller.onNavigationChanged(navigation);
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
        if (!isHudEditor() || revision != controller.currentUiNavigationRevision() || bounds == null) {
            return;
        }
        UiBounds clipped = bounds.intersect(productViewport);
        nativeHudCanvas = clipped.width > 0 && clipped.height > 0
                ? clipped
                : new UiBounds(0, 0, 0, 0);
        nativeHudCanvasRevision = revision;
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
        int framebufferWidth = Math.max(1, mc.displayWidth);
        int framebufferHeight = Math.max(1, mc.displayHeight);
        float pixelScale = Math.max(1.0F, Display.getPixelScaleFactor());
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
        webSurface.dispose();
        webState = WebState.STARTING;
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
        return webState == WebState.READY && webView != null && !webView.isFailed();
    }

    private boolean productSurfaceReady() {
        return embeddedInputActive() && webSurface.hasTexture();
    }

    private boolean nativeHudCanvasOwnsPoint(int x, int y) {
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
        if (!webPointerCapture && nativeHudCanvasOwnsPoint(x, y)) {
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
        boolean nativeReleaseWasInvalidated = nativeHudPointerOwner;
        boolean webReleaseWasInvalidated = webPointerCapture;
        releaseWebPointerOwnership();
        controller.cancelHudEditorPointerCapture();
        nativeHudCanvas = new UiBounds(0, 0, 0, 0);
        nativeHudCanvasRevision = -1L;
        nativeHudPointerOwner = false;
        nativeHudPointerButton = -1;
        nativeHudPointerCapture = false;
        webPointerCapture = false;
        webPointerButton = -1;
        // A route/viewport transition may happen between the physical mouse
        // press and release. We already synthesize the matching release for the
        // old CEF document, so swallow the stale physical release instead of
        // delivering it to the newly committed route.
        suppressNextMouseRelease = suppressNextMouseRelease
                || nativeReleaseWasInvalidated
                || webReleaseWasInvalidated;
        webPointerInside = false;
        lastForwardedX = Integer.MIN_VALUE;
        lastForwardedY = Integer.MIN_VALUE;
    }

    /** Release browser pointer state before a route/viewport/screen transition. */
    private void releaseWebPointerOwnership() {
        if (webView == null || webView.isFailed()) {
            return;
        }
        int x = Math.max(0, pointerX);
        int y = Math.max(0, pointerY);
        if (webPointerCapture) {
            webView.sendMouseButton(
                    x, y, webPointerButton >= 0 ? webPointerButton : 0, false, currentModifiers());
        }
        if (webPointerInside) {
            webView.sendMouseLeave(x, y, currentModifiers());
        }
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
        webView.sendMouseButton(x, y, button, down, currentModifiers());
        return true;
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
        return inputMapper.fromLwjglFramebuffer(Mouse.getX(), Mouse.getY());
    }

    private OpusInputMapper.Pointer currentEventPointer() {
        if (inputMapper == null) {
            refreshViewport(true);
        }
        return inputMapper.fromLwjglFramebuffer(Mouse.getEventX(), Mouse.getEventY());
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
