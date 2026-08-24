package org.polydevs.opusmc.client;

import java.io.IOException;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.polydevs.opusmc.client.embed.OpusWebTextureSurface;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;
import org.polydevs.opusmc.client.hud.HudManager;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiNavigationState;
import org.polydevs.opusmc.client.ui.UiRoute;
import org.polydevs.opusmc.client.ui.render.MinecraftUiRenderer;
import org.polydevs.opusmc.client.ui.render.MinecraftUiScale;

/**
 * Minecraft host for the canonical Svelte/CEF product UI.
 *
 * <p>This screen deliberately has no native product-page fallback. Java owns
 * route, lifecycle and the live HUD editor boundary; CEF is the only product
 * compositor. Before CEF has a frame, or after it fails, the screen renders a
 * minimal lifecycle shell rather than a second title/menu/settings UI.</p>
 */
final class OpusClientScreen extends GuiScreen {
    private static final int MAX_BROWSER_PIXEL_WIDTH = 2880;
    private static final int MAX_BROWSER_PIXEL_HEIGHT = 2160;

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
    private UiBounds productViewport = new UiBounds(0, 0, 1, 1);
    private OpusWebViewClient webView;
    private WebState webState = WebState.STARTING;
    private int viewportDisplayWidth = -1;
    private int viewportDisplayHeight = -1;
    private int viewportGuiWidth = -1;
    private int viewportGuiHeight = -1;
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
        int uiX = previewPointerActive ? previewPointerX : toUiX(mouseX);
        int uiY = previewPointerActive ? previewPointerY : toUiY(mouseY);
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
        int x = toUiX(mouseX);
        int y = toUiY(mouseY);
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
        int x = toUiX(mouseX);
        int y = toUiY(mouseY);
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
        int x = toUiX(mouseX);
        int y = toUiY(mouseY);
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
                    wheel);
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
        int displayWidth = Math.max(1, mc.displayWidth);
        int displayHeight = Math.max(1, mc.displayHeight);
        boolean framebufferChanged = displayWidth != viewportDisplayWidth
                || displayHeight != viewportDisplayHeight;
        boolean guiScaleChanged = width != viewportGuiWidth || height != viewportGuiHeight;
        boolean viewportChanged = force || framebufferChanged || guiScaleChanged;
        if (!viewportChanged) {
            return;
        }
        viewportDisplayWidth = displayWidth;
        viewportDisplayHeight = displayHeight;
        viewportGuiWidth = width;
        viewportGuiHeight = height;
        productViewport = MinecraftUiScale.viewport(mc);
        clearInputOwnership();
        webSurface.dispose();
        webState = WebState.STARTING;
        if (webView != null && !webView.isFailed()) {
            webView.resize(productViewport.width, productViewport.height, embeddedScale());
        }
    }

    private float embeddedScale() {
        float framebufferRatioX = mc.displayWidth / (float) Math.max(1, productViewport.width);
        float framebufferRatioY = mc.displayHeight / (float) Math.max(1, productViewport.height);
        float widthCap = MAX_BROWSER_PIXEL_WIDTH / (float) Math.max(1, productViewport.width);
        float heightCap = MAX_BROWSER_PIXEL_HEIGHT / (float) Math.max(1, productViewport.height);
        // Use the limiting axis so an unusual aspect ratio cannot produce a
        // frame taller than the native slot while width still appears valid.
        return Math.max(1.0F, Math.min(
                Math.min(framebufferRatioX, framebufferRatioY),
                Math.min(widthCap, heightCap)));
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
        webView.sendMouseLeave(x, y);
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
            webView.sendMouseButton(x, y, webPointerButton >= 0 ? webPointerButton : 0, false);
        }
        if (webPointerInside) {
            webView.sendMouseLeave(x, y);
        }
    }

    private void forwardPointerMove(int x, int y) {
        if (!embeddedInputActive()) {
            return;
        }
        lastForwardedX = x;
        lastForwardedY = y;
        webView.sendMouseMove(x, y);
        webPointerInside = true;
    }

    private boolean forwardPointerButton(int x, int y, int button, boolean down) {
        if (!embeddedInputActive()) {
            return false;
        }
        if (x != lastForwardedX || y != lastForwardedY) {
            forwardPointerMove(x, y);
        }
        webView.sendMouseButton(x, y, button, down);
        return true;
    }

    private void forwardKey(char typedCharacter, int keyCode) {
        int virtualKey = windowsVirtualKey(keyCode);
        if (virtualKey != 0) {
            webView.sendKey(virtualKey, true);
            webView.sendKey(virtualKey, false);
        } else if (typedCharacter >= 0x20 && typedCharacter != 0x7F) {
            webView.sendChar(typedCharacter);
        }
    }

    private int toUiX(int minecraftGuiX) {
        return MinecraftUiScale.convertX(minecraftGuiX, width, productViewport);
    }

    private int toUiY(int minecraftGuiY) {
        return MinecraftUiScale.convertY(minecraftGuiY, height, productViewport);
    }

    private int fromUiX(int uiX) {
        return Math.round(uiX * width / (float) Math.max(1, productViewport.width));
    }

    private int fromUiY(int uiY) {
        return Math.round(uiY * height / (float) Math.max(1, productViewport.height));
    }

    private static int windowsVirtualKey(int keyCode) {
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE:
                return 0x1B;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                return 0x0D;
            case Keyboard.KEY_BACK:
                return 0x08;
            case Keyboard.KEY_TAB:
                return 0x09;
            case Keyboard.KEY_DELETE:
                return 0x2E;
            case Keyboard.KEY_LEFT:
                return 0x25;
            case Keyboard.KEY_UP:
                return 0x26;
            case Keyboard.KEY_RIGHT:
                return 0x27;
            case Keyboard.KEY_DOWN:
                return 0x28;
            case Keyboard.KEY_HOME:
                return 0x24;
            case Keyboard.KEY_END:
                return 0x23;
            case Keyboard.KEY_PRIOR:
                return 0x21;
            case Keyboard.KEY_NEXT:
                return 0x22;
            case Keyboard.KEY_SPACE:
                return 0x20;
            default:
                return 0;
        }
    }
}
