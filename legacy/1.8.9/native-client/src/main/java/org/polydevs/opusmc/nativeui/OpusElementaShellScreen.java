package org.polydevs.opusmc.nativeui;

import java.util.ArrayList;
import java.util.List;

import gg.essential.elementa.ElementaVersion;
import gg.essential.elementa.UIComponent;
import gg.essential.elementa.WindowScreen;
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
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.polydevs.opusmc.nativeui.design.ElementaThemeAdapter;
import org.polydevs.opusmc.nativeui.design.OpusDesign;
import org.polydevs.opusmc.nativeui.design.OpusDesign.ColorRole;

/** Elementa-owned Minecraft shell for the main-menu and pause-menu routes. */
public final class OpusElementaShellScreen extends WindowScreen {
    public enum Route {
        MAIN_MENU,
        PAUSE_MENU
    }

    private enum ButtonGlyph {
        NONE,
        SETTINGS,
        POWER
    }

    private static final OpusDesign DESIGN = OpusDesign.defaultDesign();
    private static final ElementaThemeAdapter THEME = new ElementaThemeAdapter(DESIGN);
    private static final java.awt.Color BUTTON_NORMAL = new java.awt.Color(14, 16, 25, 232);
    private static final java.awt.Color BUTTON_HOVER = new java.awt.Color(56, 61, 84, 246);
    private static final java.awt.Color BUTTON_BORDER = new java.awt.Color(255, 255, 255, 28);
    private static final java.awt.Color MAIN_MENU_TINT = new java.awt.Color(0, 0, 0, 88);
    private static final java.awt.Color PAUSE_MENU_TINT = new java.awt.Color(0, 0, 0, 104);
    private static final float BUTTON_RADIUS = 7f;
    private static final ResourceLocation MENU_BACKGROUND = new ResourceLocation(
            "textures/gui/title/background/panorama_0.png");
    private static final ResourceLocation OPUS_LOCKUP = new ResourceLocation(
            OpusNativeUiMod.MOD_ID,
            "textures/gui/opus-wordmark-transparent.png");
    private static final float TEXT_SCALE_MAX_DELTA_SECONDS = 0.1f;

    private final Route route;
    private final List<ShellButton> buttons = new ArrayList<>();
    private OpusTexture background;
    private OpusLegacyBlock backgroundTint;
    private OpusTexture logo;
    private OpusLegacyBlock panel;
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
        if (route == Route.MAIN_MENU) {
            /*
             * Use Minecraft's own panorama source without its multi-pass blur.
             * This restores visible terrain detail while keeping the shell
             * recognizably vanilla.
             */
            background = new OpusTexture(
                    MENU_BACKGROUND,
                    256,
                    256,
                    OpusTexture.Fit.COVER,
                    1f);
            background.setWidth(new RelativeConstraint(1f));
            background.setHeight(new RelativeConstraint(1f));
            background.setChildOf(getWindow());
        }

        backgroundTint = new OpusLegacyBlock(
                route == Route.MAIN_MENU ? MAIN_MENU_TINT : PAUSE_MENU_TINT);
        backgroundTint.setWidth(new RelativeConstraint(1f));
        backgroundTint.setHeight(new RelativeConstraint(1f));
        backgroundTint.setChildOf(getWindow());

        /*
         * This is a transparent Elementa container. The old opaque modal
         * surface made the shell look like a settings dialog; Lunar's main
         * menu leaves the scene visible and lets the controls carry the visual
         * hierarchy.
         */
        panel = new OpusLegacyBlock(new java.awt.Color(0, 0, 0, 0));
        panel.setChildOf(getWindow());

        logo = new OpusTexture(
                OPUS_LOCKUP,
                OpusShellLayout.LOCKUP_TEXTURE_WIDTH,
                OpusShellLayout.LOCKUP_TEXTURE_HEIGHT,
                OpusTexture.Fit.CONTAIN,
                1f);
        logo.setChildOf(panel);

        if (route == Route.MAIN_MENU) {
            addButton("Singleplayer", ButtonGlyph.NONE, this::openSingleplayer, panel);
            addButton("Multiplayer", ButtonGlyph.NONE, this::openMultiplayer, panel);
            addButton("Client", ButtonGlyph.SETTINGS, OpusNativeUiMod::openOpusConfig, panel);
            addButton("Minecraft", ButtonGlyph.SETTINGS, this::openMinecraftSettings, panel);
            addButton("Quit Game", ButtonGlyph.POWER, this::quitGame, panel);
        } else {
            addButton("Resume Game", ButtonGlyph.NONE, this::resumeGame, panel);
            addButton("Main Menu", ButtonGlyph.NONE, this::openMainMenu, panel);
            addButton("Client", ButtonGlyph.SETTINGS, OpusNativeUiMod::openOpusConfig, panel);
            addButton("Minecraft", ButtonGlyph.SETTINGS, this::openMinecraftSettings, panel);
        }
    }

    private void addButton(
            String label,
            ButtonGlyph glyph,
            Runnable action,
            UIComponent parent) {
        OpusLegacyBlock surface = new OpusLegacyBlock(
                BUTTON_NORMAL,
                BUTTON_RADIUS);
        surface.setOutline(BUTTON_BORDER, 1f);
        surface.setChildOf(parent);

        ButtonLabel content = new ButtonLabel(
                label,
                glyph,
                THEME.color(ColorRole.TEXT));
        content.setWidth(new RelativeConstraint(1f));
        content.setHeight(new RelativeConstraint(1f));
        content.setChildOf(surface);

        ShellButton button = new ShellButton(label, surface, content, action);
        buttons.add(button);
        surface.onMouseEnterRunnable(() -> animateButton(button, true));
        surface.onMouseLeaveRunnable(() -> animateButton(button, false));
        surface.onMouseClickConsumer(event -> {
            NativeUiRuntimeDiagnostics.record("shellAction", label);
            action.run();
        });
    }

    private void animateButton(ShellButton button, boolean hovered) {
        button.surface.setColor(hovered
                ? BUTTON_HOVER
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

        logo.setX(new PixelConstraint(next.logoX - next.panelX));
        logo.setY(new PixelConstraint(next.logoY - next.panelY));
        logo.setWidth(new PixelConstraint(next.logoWidth));
        logo.setHeight(new PixelConstraint(next.logoHeight));

        for (int index = 0; index < buttons.size(); index++) {
            ShellButton button = buttons.get(index);
            button.surface.setX(new PixelConstraint(next.buttonX(index) - next.panelX));
            button.surface.setY(new PixelConstraint(next.buttonY(index) - next.panelY));
            button.surface.setWidth(new PixelConstraint(next.buttonWidth(index)));
            button.surface.setHeight(new PixelConstraint(next.buttonHeight(index)));
            button.textScale.setTarget(next.buttonTextScale);
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
        for (ShellButton button : buttons) {
            button.textScale.update(boundedDelta);
        }
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
        NativeUiRuntimeDiagnostics.record("elementaBackgroundBlur", "disabled");
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

        private ShellButton(
                String label,
                OpusLegacyBlock surface,
                ButtonLabel content,
                Runnable action) {
            this.label = label;
            this.surface = surface;
            this.textScale = new SmoothTextScale(content);
            this.action = action;
        }
    }

    /**
     * Button content whose font and icon scale are both interpolated directly
     * during native window resizing. It uses stable full-button bounds, so
     * neither the text nor the icon can jump at an Elementa constraint
     * recalculation boundary.
     */
    private static final class ButtonLabel extends UIComponent {
        private final FontProvider fontProvider = DefaultFonts.getVANILLA_FONT_RENDERER();
        private final String value;
        private final ButtonGlyph glyph;
        private final java.awt.Color color;
        private float renderScale = 1f;

        private ButtonLabel(
                String value,
                ButtonGlyph glyph,
                java.awt.Color color) {
            if (value == null) {
                throw new IllegalArgumentException("value cannot be null");
            }
            if (color == null) {
                throw new IllegalArgumentException("color cannot be null");
            }
            if (glyph == null) {
                throw new IllegalArgumentException("glyph cannot be null");
            }
            this.value = value;
            this.glyph = glyph;
            this.color = color;
        }

        private void setRenderScale(float nextScale) {
            renderScale = Math.max(0.001f, nextScale);
        }

        @Override
        public void draw(UMatrixStack matrixStack) {
            beforeDrawCompat(matrixStack);
            if (!value.isEmpty() && color.getAlpha() > 10) {
                if (!URenderPipeline.Companion.isRequired()
                        && !ElementaVersion.Companion.getAtLeastV9Active$Elementa()) {
                    UGraphics.enableBlend();
                }

                boolean iconOnly = glyph == ButtonGlyph.POWER;
                float textWidth = iconOnly
                        ? 0f
                        : fontProvider.getStringWidth(value, 10f) * renderScale;
                float textHeight = iconOnly
                        ? 0f
                        : fontProvider.getStringHeight(value, 10f) * renderScale;
                float iconSize = glyph == ButtonGlyph.NONE ? 0f : 8f * renderScale;
                float gap = glyph == ButtonGlyph.SETTINGS ? 4f * renderScale : 0f;
                float contentWidth = iconSize + gap + textWidth;
                float drawLeft = getLeft() + (getWidth() - contentWidth) / 2f;
                float centerY = getTop() + getHeight() / 2f;

                if (glyph == ButtonGlyph.SETTINGS) {
                    drawSettingsGlyph(
                            drawLeft,
                            centerY - iconSize / 2f,
                            iconSize,
                            color.getRGB());
                    drawLeft += iconSize + gap;
                } else if (glyph == ButtonGlyph.POWER) {
                    drawPowerGlyph(
                            drawLeft,
                            centerY - iconSize / 2f,
                            iconSize,
                            color.getRGB());
                }

                if (!iconOnly) {
                    fontProvider.drawString(
                            matrixStack,
                            value,
                            color,
                            drawLeft,
                            centerY - textHeight / 2f,
                            10f,
                            renderScale,
                            false,
                            null);
                }
            }
            super.draw(matrixStack);
        }

        private static void drawSettingsGlyph(
                float left,
                float top,
                float size,
                int color) {
            float stroke = Math.max(1f, size * 0.13f);
            float knob = Math.max(2f, size * 0.28f);
            float[] rows = new float[]{0.18f, 0.50f, 0.82f};
            float[] knobs = new float[]{0.28f, 0.70f, 0.43f};
            for (int index = 0; index < rows.length; index++) {
                float y = top + size * rows[index];
                drawRect(left, y - stroke / 2f, left + size, y + stroke / 2f, color);
                float centerX = left + size * knobs[index];
                drawRect(
                        centerX - knob / 2f,
                        y - knob / 2f,
                        centerX + knob / 2f,
                        y + knob / 2f,
                        color);
            }
        }

        private static void drawPowerGlyph(
                float left,
                float top,
                float size,
                int color) {
            float stroke = Math.max(1f, size * 0.14f);
            float centerX = left + size / 2f;
            drawRect(
                    centerX - stroke / 2f,
                    top,
                    centerX + stroke / 2f,
                    top + size * 0.50f,
                    color);

            drawRect(
                    left + size * 0.16f,
                    top + size * 0.25f,
                    left + size * 0.30f,
                    top + size * 0.70f,
                    color);
            drawRect(
                    left + size * 0.70f,
                    top + size * 0.25f,
                    left + size * 0.84f,
                    top + size * 0.70f,
                    color);
            drawRect(
                    left + size * 0.26f,
                    top + size * 0.68f,
                    left + size * 0.42f,
                    top + size * 0.84f,
                    color);
            drawRect(
                    left + size * 0.58f,
                    top + size * 0.68f,
                    left + size * 0.74f,
                    top + size * 0.84f,
                    color);
            drawRect(
                    left + size * 0.40f,
                    top + size * 0.78f,
                    left + size * 0.60f,
                    top + size * 0.92f,
                    color);
        }

        private static void drawRect(
                float left,
                float top,
                float right,
                float bottom,
                int color) {
            Gui.drawRect(
                    (int) Math.floor(left),
                    (int) Math.floor(top),
                    (int) Math.ceil(right),
                    (int) Math.ceil(bottom),
                    color);
        }
    }

    private static final class SmoothTextScale {
        private final OpusSmoothScale value = new OpusSmoothScale();
        private final ButtonLabel label;

        private SmoothTextScale(ButtonLabel label) {
            this.label = label;
        }

        private void setTarget(float nextTarget) {
            if (value.setTarget(nextTarget)) {
                label.setRenderScale(value.getCurrent());
            }
        }

        private void update(float deltaSeconds) {
            float previous = value.getCurrent();
            float next = value.update(deltaSeconds);
            if (next != previous) {
                label.setRenderScale(next);
            }
        }
    }
}
