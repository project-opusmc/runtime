package org.polydevs.opusmc.nativeui;

import java.util.ArrayList;
import java.util.List;

import gg.essential.elementa.ElementaVersion;
import gg.essential.elementa.UIComponent;
import gg.essential.elementa.WindowScreen;
import gg.essential.elementa.components.UIText;
import gg.essential.elementa.constraints.CenterConstraint;
import gg.essential.elementa.constraints.PixelConstraint;
import gg.essential.elementa.constraints.RelativeConstraint;
import gg.essential.elementa.font.DefaultFonts;
import gg.essential.elementa.font.FontProvider;
import gg.essential.universal.UKeyboard;
import gg.essential.universal.UGraphics;
import gg.essential.universal.UMatrixStack;
import gg.essential.universal.UResolution;
import gg.essential.universal.render.URenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiSelectWorld;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.polydevs.opusmc.nativeui.design.ElementaThemeAdapter;
import org.polydevs.opusmc.nativeui.design.OpusDesign;
import org.polydevs.opusmc.nativeui.design.OpusDesign.ColorRole;
import org.polydevs.opusmc.nativeui.design.OpusDesign.RadiusToken;

/** Elementa-owned Minecraft shell for the main-menu and pause-menu routes. */
public final class OpusElementaShellScreen extends WindowScreen {
    public enum Route {
        MAIN_MENU,
        PAUSE_MENU
    }

    private static final OpusDesign DESIGN = OpusDesign.defaultDesign();
    private static final ElementaThemeAdapter THEME = new ElementaThemeAdapter(DESIGN);
    private static final java.awt.Color BUTTON_NORMAL = THEME.color(ColorRole.SURFACE_RAISED);
    private static final float TEXT_SCALE_MAX_DELTA_SECONDS = 0.1f;

    private final Route route;
    private final List<ShellButton> buttons = new ArrayList<>();
    private final SmoothText status = new SmoothText(
            "RIGHT SHIFT  •  OPUSCONFIG",
            false,
            THEME.color(ColorRole.MUTED));
    private OpusLegacyBlock panel;
    private SmoothText title;
    private SmoothText subtitle;
    private SmoothTextScale titleScale;
    private SmoothTextScale subtitleScale;
    private SmoothTextScale statusScale;
    private OpusShellLayout layout;
    private int frames;

    public OpusElementaShellScreen(Route route) {
        /*
         * Auto GUI scale is selected by integer thresholds in
         * ScaledResolution. During a native edge drag those thresholds can be
         * crossed several times per second, which changes Elementa's logical
         * viewport from (for example) 450x250 to 900x500 in one frame. Lock
         * this screen to GUI scale 1; UScreen restores the user's original
         * setting automatically on close. The shell layout applies its own
         * continuous density curve, so it keeps the same visual weight while
         * Minecraft's Auto-scale thresholds are completely out of the path.
         */
        super(ElementaVersion.V11, true, false, true, 1);
        this.route = route;
        NativeUiRuntimeDiagnostics.record("elementaGuiScaleLatch", Integer.toString(getNewGuiScale()));
        buildUi();
        getWindow().addUpdateFunc((deltaSeconds, frameDeltaMillis) -> {
            updateTextScales(deltaSeconds);
            return kotlin.Unit.INSTANCE;
        });
        NativeUiRuntimeDiagnostics.record("elementaShellConstructed", route.name());
    }

    private void buildUi() {
        OpusLegacyBlock backdrop = new OpusLegacyBlock(THEME.color(ColorRole.BACKDROP));
        backdrop.setWidth(new RelativeConstraint(1f));
        backdrop.setHeight(new RelativeConstraint(1f));
        backdrop.setChildOf(getWindow());

        panel = new OpusLegacyBlock(
                THEME.color(ColorRole.SURFACE),
                THEME.radius(RadiusToken.MODAL));
        panel.setOutline(THEME.color(ColorRole.BORDER), 1f);
        panel.setChildOf(backdrop);

        title = new SmoothText(
                route == Route.MAIN_MENU ? "OPUS" : "OPUS PAUSE",
                false,
                THEME.color(ColorRole.TEXT));
        title.setChildOf(panel);
        titleScale = new SmoothTextScale(title);

        subtitle = new SmoothText(
                route == Route.MAIN_MENU
                        ? "Play, configure, and manage your instance"
                        : "The world is paused; choose an action",
                false,
                THEME.color(ColorRole.MUTED));
        subtitle.setChildOf(panel);
        subtitleScale = new SmoothTextScale(subtitle);

        if (route == Route.MAIN_MENU) {
            addButton("Singleplayer", this::openSingleplayer, panel);
            addButton("Multiplayer", this::openMultiplayer, panel);
            addButton("Minecraft Settings", this::openMinecraftSettings, panel);
            addButton("OpusConfig", OpusNativeUiMod::openOpusConfig, panel);
            addButton("Quit Game", this::quitGame, panel);
        } else {
            addButton("Resume Game", this::resumeGame, panel);
            addButton("OpusConfig", OpusNativeUiMod::openOpusConfig, panel);
            addButton("Minecraft Settings", this::openMinecraftSettings, panel);
            addButton("Main Menu", this::openMainMenu, panel);
        }

        status.setChildOf(panel);
        statusScale = new SmoothTextScale(status);
    }

    private void addButton(String label, Runnable action, UIComponent parent) {
        OpusLegacyBlock surface = new OpusLegacyBlock(
                BUTTON_NORMAL,
                THEME.radius(RadiusToken.CONTROL));
        surface.setChildOf(parent);

        UIText text = new UIText(label, false, THEME.color(ColorRole.TEXT));
        text.setX(new CenterConstraint());
        text.setY(new CenterConstraint());
        text.setChildOf(surface);

        ShellButton button = new ShellButton(label, surface, text, action);
        buttons.add(button);
        surface.onMouseEnterRunnable(() -> animateButton(button, true));
        surface.onMouseLeaveRunnable(() -> animateButton(button, false));
        surface.onMouseClickConsumer(event -> {
            status.setText("SELECTED  •  " + label);
            NativeUiRuntimeDiagnostics.record("shellAction", label);
            action.run();
        });
    }

    private void animateButton(ShellButton button, boolean hovered) {
        button.surface.setColor(hovered
                ? THEME.color(ColorRole.ACCENT_HOVER)
                : BUTTON_NORMAL);
    }

    /**
     * Elementa's WindowScreen invalidates its constraint tree first. The
     * second pass below then replaces every shell bound from the same
     * calculated geometry, so drawing and hit testing cannot drift apart.
     */
    @Override
    public void initScreen(int width, int height) {
        super.initScreen(width, height);
        if (width > 0 && height > 0) {
            applyLayout(width, height);
        }
    }

    private void applyLayout(int width, int height) {
        OpusShellLayout next = OpusShellLayout.calculate(width, height, buttons.size());
        layout = next;

        panel.setX(new PixelConstraint(next.panelX));
        panel.setY(new PixelConstraint(next.panelY));
        panel.setWidth(new PixelConstraint(next.panelWidth));
        panel.setHeight(new PixelConstraint(next.panelHeight));

        float contentX = next.buttonX - next.panelX;
        title.setX(new PixelConstraint(contentX));
        title.setY(new PixelConstraint(next.titleY - next.panelY));
        titleScale.setTarget(next.titleScale);

        subtitle.setX(new PixelConstraint(contentX));
        subtitle.setY(new PixelConstraint(next.subtitleY - next.panelY));
        subtitleScale.setTarget(next.secondaryScale);
        if (next.showSubtitle) {
            subtitle.unhide(false);
        } else {
            subtitle.hide(false);
        }

        for (int index = 0; index < buttons.size(); index++) {
            ShellButton button = buttons.get(index);
            button.surface.setX(new PixelConstraint(contentX));
            button.surface.setY(new PixelConstraint(next.buttonY(index) - next.panelY));
            button.surface.setWidth(new PixelConstraint(next.buttonWidth));
            button.surface.setHeight(new PixelConstraint(next.buttonHeight));
            button.textScale.setTarget(next.buttonTextScale);
        }

        status.setX(new PixelConstraint(contentX));
        status.setY(new PixelConstraint(next.footerY - next.panelY));
        statusScale.setTarget(next.secondaryScale);
        if (next.showFooter) {
            status.unhide(false);
        } else {
            status.hide(false);
        }

        /*
         * Elementa's public resize lifecycle is recursive: it invalidates
         * every child component's cached position, size, text scale, color,
         * and font constraints. Invalidating only Window's own cache leaves
         * descendants with the previous drag-step geometry until Minecraft
         * performs its final post-resize screen initialization. That is the
         * source of the deformed intermediate shell seen during live drags.
         */
        getWindow().onWindowResize();
        getWindow().invalidateCachedConstraints();
        NativeUiRuntimeDiagnostics.recordDeferred("elementaLayout", next.diagnosticValue());
    }

    /**
     * Layout geometry follows the newest drawable size immediately, but text
     * scale follows it over time. This prevents a font-size jump every time
     * Cocoa publishes another live-resize step while retaining a responsive
     * final size after the drag stops.
     */
    private void updateTextScales(float deltaSeconds) {
        float boundedDelta = Math.max(0f, Math.min(deltaSeconds, TEXT_SCALE_MAX_DELTA_SECONDS));
        titleScale.update(boundedDelta);
        subtitleScale.update(boundedDelta);
        for (ShellButton button : buttons) {
            button.textScale.update(boundedDelta);
        }
        statusScale.update(boundedDelta);
    }

    private void openSingleplayer() {
        Minecraft.getMinecraft().displayGuiScreen(new GuiSelectWorld(this));
    }

    private void openMultiplayer() {
        Minecraft.getMinecraft().displayGuiScreen(new GuiMultiplayer(this));
    }

    private void openMinecraftSettings() {
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.displayGuiScreen(new GuiOptions(this, minecraft.gameSettings));
    }

    private void openMainMenu() {
        Minecraft.getMinecraft().displayGuiScreen(new GuiMainMenu());
    }

    private void resumeGame() {
        restorePreviousScreen();
    }

    private void quitGame() {
        Minecraft.getMinecraft().shutdown();
    }

    @Override
    public void afterInitialization() {
        NativeUiRuntimeDiagnostics.record("elementaAfterInitialization", "true");
        NativeUiRuntimeDiagnostics.record("elementaShellRoute", route.name());
        if (layout != null) {
            NativeUiRuntimeDiagnostics.record("elementaLayout", layout.diagnosticValue());
        }
    }

    @Override
    public void onDrawScreen(UMatrixStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        refreshLayoutFromCurrentViewport();
        int errorBefore = GL11.glGetError();
        NativeUiRuntimeDiagnostics.recordScreenDraw("elementa", "inner-before", errorBefore);
        super.onDrawScreen(matrixStack, mouseX, mouseY, partialTicks);
        /*
         * The native display guard fences the completed frame at the Cocoa
         * live-resize boundary. Do not call glFinish() for every Elementa
         * frame: that serializes the shell's normal render loop and makes
         * native edge dragging visibly hitch, while the guard already fences
         * exactly when AppKit is about to replace the drawable.
         */
        int errorAfter = GL11.glGetError();
        NativeUiRuntimeDiagnostics.recordScreenDraw("elementa", "inner-after", errorAfter);
        frames++;
        String value = "GUI " + UResolution.getScaledWidth() + "x" + UResolution.getScaledHeight()
                + " / framebuffer " + UResolution.getViewportWidth() + "x" + UResolution.getViewportHeight()
                + " / window " + Display.getWidth() + "x" + Display.getHeight()
                + " / scale " + String.format("%.3f", UResolution.getScaleFactor())
                + " / GL " + errorBefore + "->" + errorAfter;
        if (frames == 1 || frames % 120 == 0 || errorBefore != GL11.GL_NO_ERROR || errorAfter != GL11.GL_NO_ERROR) {
            if (errorBefore != GL11.GL_NO_ERROR || errorAfter != GL11.GL_NO_ERROR) {
                NativeUiRuntimeDiagnostics.record("elementaShellDiagnostics", value);
            } else {
                NativeUiRuntimeDiagnostics.recordDeferred("elementaShellDiagnostics", value);
            }
        }
    }

    /**
     * Cocoa can deliver a new drawable size between Minecraft's screen
     * initialization pass and the next draw pass. Refreshing here makes the
     * Elementa constraint tree consume that size in the same frame instead of
     * showing one stale frame while the native window is being dragged.
     */
    private void refreshLayoutFromCurrentViewport() {
        /*
         * The shell is latched to GUI scale 1, so the viewport is also the
         * shell's logical coordinate space. Reading the viewport here keeps
         * the layout tied to the drawable size as soon as the render thread
         * observes a native drag step, instead of waiting for a later
         * ScaledResolution refresh.
         */
        int width = UResolution.getViewportWidth();
        int height = UResolution.getViewportHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (layout == null
                || layout.viewportWidth != width
                || layout.viewportHeight != height) {
            applyLayout(width, height);
        }
    }

    @Override
    public void onScreenClose() {
        NativeUiRuntimeDiagnostics.record("elementaClosed", "true");
        NativeUiRuntimeDiagnostics.record("elementaShellRouteClosed", route.name());
        super.onScreenClose();
    }

    @Override
    public void onKeyPressed(int keyCode, char typedChar, UKeyboard.Modifiers modifiers) {
        if (keyCode == UKeyboard.KEY_ESCAPE) {
            NativeUiRuntimeDiagnostics.record("elementaEscape", "restore-previous-screen");
            restorePreviousScreen();
            return;
        }
        super.onKeyPressed(keyCode, typedChar, modifiers);
    }

    private static final class ShellButton {
        private final String label;
        private final OpusLegacyBlock surface;
        private final SmoothTextScale textScale;
        private final Runnable action;

        private ShellButton(String label, OpusLegacyBlock surface, UIText text, Runnable action) {
            this.label = label;
            this.surface = surface;
            this.textScale = new SmoothTextScale(text);
            this.action = action;
        }
    }

    /**
     * A text component whose Elementa bounds stay at the font's intrinsic
     * size while its visible font scale is controlled independently.
     *
     * <p>{@link UIText} derives its draw scale from a text-width constraint.
     * During a native resize that constraint is recalculated alongside the
     * viewport, which makes free-standing text jump even when its requested
     * scale is interpolated. This component keeps only stable intrinsic
     * bounds in the constraint tree and passes the interpolated scale directly
     * to the same Elementa font provider used by {@code UIText}.</p>
     */
    private static final class SmoothText extends UIComponent {
        private final FontProvider fontProvider = DefaultFonts.getVANILLA_FONT_RENDERER();
        private final boolean shadow;
        private final java.awt.Color shadowColor;
        private final java.awt.Color color;
        private String value;
        private float renderScale = 1f;

        private SmoothText(String value, boolean shadow, java.awt.Color color) {
            if (value == null) {
                throw new IllegalArgumentException("value cannot be null");
            }
            if (color == null) {
                throw new IllegalArgumentException("color cannot be null");
            }
            this.value = value;
            this.shadow = shadow;
            this.shadowColor = null;
            this.color = color;
            updateIntrinsicBounds();
        }

        private void setText(String nextValue) {
            if (nextValue == null) {
                throw new IllegalArgumentException("value cannot be null");
            }
            if (value.equals(nextValue)) {
                return;
            }
            value = nextValue;
            updateIntrinsicBounds();
        }

        private void setRenderScale(float nextScale) {
            renderScale = Math.max(0.001f, nextScale);
        }

        private void updateIntrinsicBounds() {
            float width = Math.max(1f, fontProvider.getStringWidth(value, 10f));
            float height = Math.max(1f, fontProvider.getStringHeight(value, 10f));
            setWidth(new PixelConstraint(width));
            setHeight(new PixelConstraint(height));
        }

        @Override
        public void draw(UMatrixStack matrixStack) {
            beforeDrawCompat(matrixStack);
            if (!value.isEmpty() && color.getAlpha() > 10) {
                if (!URenderPipeline.Companion.isRequired()
                        && !ElementaVersion.Companion.getAtLeastV9Active$Elementa()) {
                    UGraphics.enableBlend();
                }
                fontProvider.drawString(
                        matrixStack,
                        value,
                        color,
                        getLeft(),
                        getTop(),
                        10f,
                        renderScale,
                        shadow,
                        shadowColor);
            }
            super.draw(matrixStack);
        }
    }

    private interface TextScaleSink {
        void set(float value);
    }

    private static final class SmoothTextScale {
        private final OpusSmoothScale value = new OpusSmoothScale();
        private final TextScaleSink sink;

        private SmoothTextScale(UIText text) {
            sink = current -> text.setTextScale(new PixelConstraint(current));
        }

        private SmoothTextScale(SmoothText text) {
            sink = text::setRenderScale;
        }

        private void setTarget(float nextTarget) {
            if (value.setTarget(nextTarget)) {
                sink.set(value.getCurrent());
            }
        }

        private void update(float deltaSeconds) {
            float previous = value.getCurrent();
            float next = value.update(deltaSeconds);
            if (next != previous) {
                sink.set(next);
            }
        }
    }
}
