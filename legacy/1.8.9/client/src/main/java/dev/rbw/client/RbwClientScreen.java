package dev.rbw.client;

import dev.rbw.client.ui.UiRoute;
import dev.rbw.client.ui.UiRuntime;
import dev.rbw.client.ui.render.MinecraftBlurBackdrop;
import dev.rbw.client.ui.render.MinecraftUiScale;
import dev.rbw.client.ui.render.MinecraftUiRenderer;
import dev.rbw.client.ui.render.MinecraftTitlePanorama;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Thin Minecraft adapter around UiRuntime. Product routing, layout and
 * rendering live in the Opus UI framework rather than GuiScreen itself.
 */
final class RbwClientScreen extends GuiScreen {
    private final UiRuntime runtime;
    private final MinecraftBlurBackdrop blurBackdrop;
    private final MinecraftTitlePanorama titlePanorama;
    private dev.rbw.client.ui.UiBounds productViewport;
    private long appliedThemeGeneration = -1L;
    private boolean previewPointerActive;
    private int previewPointerX;
    private int previewPointerY;

    RbwClientScreen(ClientOverlayController controller, UiRoute initialRoute) {
        Minecraft minecraft = Minecraft.getMinecraft();
        runtime = new UiRuntime(
                new MinecraftUiRenderer(minecraft),
                new RbwUiPageFactory(controller),
                initialRoute);
        blurBackdrop = new MinecraftBlurBackdrop(minecraft);
        titlePanorama = new MinecraftTitlePanorama(minecraft);
    }

    @Override
    public void initGui() {
        productViewport = MinecraftUiScale.viewport(mc);
        runtime.resize(productViewport.width, productViewport.height);
        appliedThemeGeneration = UiTheme.generation();
        if (!isMainMenu()) {
            blurBackdrop.begin();
        }
    }

    @Override
    protected void keyTyped(char typedCharacter, int keyCode) throws IOException {
        if (!runtime.keyTyped(typedCharacter, keyCode) && keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
        }
        closeIfRequested();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        refreshLayoutForTheme();
        if (isMainMenu()) {
            // Render a real, softly blurred Minecraft scene before Opus's
            // native UI. It retains the game context without any generated
            // backdrop or desktop/HTML rendering path.
            titlePanorama.tick();
            titlePanorama.render(width, height, partialTicks);
        }
        int uiX = previewPointerActive ? previewPointerX : toUiX(mouseX);
        int uiY = previewPointerActive ? previewPointerY : toUiY(mouseY);
        runtime.render(uiX, uiY, partialTicks);
        closeIfRequested();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        runtime.mouseDown(toUiX(mouseX), toUiY(mouseY), mouseButton);
        closeIfRequested();
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        runtime.mouseUp(toUiX(mouseX), toUiY(mouseY), state);
        closeIfRequested();
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        runtime.mouseDrag(toUiX(mouseX), toUiY(mouseY), clickedMouseButton, timeSinceLastClick);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            runtime.scroll(wheel);
        }
        closeIfRequested();
    }

    @Override
    public void onGuiClosed() {
        runtime.dispose();
        blurBackdrop.end();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    boolean isHudEditor() {
        return runtime.route().equals(UiRoute.hudEditor());
    }

    private boolean isMainMenu() {
        return runtime.route().kind() == UiRoute.Kind.MAIN_MENU;
    }

    UiRoute previewRoute() {
        return runtime.route();
    }

    /** Preview Mode only: navigate the same UiRuntime used by player input. */
    void previewNavigate(UiRoute route) {
        runtime.navigate(route);
        refreshLayoutForTheme();
    }

    void setPreviewPointer(int x, int y) {
        previewPointerActive = true;
        previewPointerX = x;
        previewPointerY = y;
    }

    void clearPreviewPointer() {
        previewPointerActive = false;
    }

    boolean previewClick(int x, int y, int button) {
        // A real player moves the pointer into a hover-only editor control
        // before clicking it. Reproduce that frame so Preview Mode validates
        // the same interaction sequence instead of bypassing HUD chrome.
        setPreviewPointer(x, y);
        runtime.render(x, y, 0.0F);
        boolean handled = runtime.mouseDown(x, y, button);
        runtime.mouseUp(x, y, button);
        closeIfRequested();
        return handled;
    }

    boolean previewDrag(int x, int y, int toX, int toY, int button) {
        setPreviewPointer(x, y);
        // Render the initial hover frame before press, matching the input
        // sequence that exposes HUD-editor controls to a real player.
        runtime.render(x, y, 0.0F);
        boolean handled = runtime.mouseDown(x, y, button);
        if (handled) {
            setPreviewPointer(toX, toY);
            runtime.mouseDrag(toX, toY, button, 16L);
            runtime.mouseUp(toX, toY, button);
        }
        closeIfRequested();
        return handled;
    }

    private void closeIfRequested() {
        if (runtime.consumeCloseRequest()) {
            mc.displayGuiScreen(null);
        }
    }

    private void refreshLayoutForTheme() {
        if (productViewport != null && appliedThemeGeneration != UiTheme.generation()) {
            runtime.resize(productViewport.width, productViewport.height);
            appliedThemeGeneration = UiTheme.generation();
        }
    }

    private int toUiX(int minecraftGuiX) {
        return productViewport == null
                ? minecraftGuiX
                : MinecraftUiScale.convertX(minecraftGuiX, width, productViewport);
    }

    private int toUiY(int minecraftGuiY) {
        return productViewport == null
                ? minecraftGuiY
                : MinecraftUiScale.convertY(minecraftGuiY, height, productViewport);
    }
}
