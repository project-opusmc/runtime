package org.polydevs.opusmc.nativeui;

import gg.essential.elementa.ElementaVersion;
import gg.essential.elementa.UIComponent;
import gg.essential.elementa.WindowScreen;
import gg.essential.elementa.components.ScrollComponent;
import gg.essential.elementa.components.UIBlock;
import gg.essential.elementa.components.UIRoundedRectangle;
import gg.essential.elementa.components.UIText;
import gg.essential.elementa.components.input.UITextInput;
import gg.essential.elementa.constraints.CenterConstraint;
import gg.essential.elementa.constraints.PixelConstraint;
import gg.essential.elementa.constraints.RelativeConstraint;
import gg.essential.elementa.constraints.animation.AnimatingConstraints;
import gg.essential.elementa.constraints.animation.Animations;
import gg.essential.elementa.effects.OutlineEffect;
import gg.essential.elementa.effects.ScissorEffect;
import gg.essential.universal.UKeyboard;
import gg.essential.universal.UMatrixStack;
import gg.essential.universal.UResolution;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.polydevs.opusmc.nativeui.design.ElementaThemeAdapter;
import org.polydevs.opusmc.nativeui.design.OpusDesign;
import org.polydevs.opusmc.nativeui.design.OpusDesign.ColorRole;
import org.polydevs.opusmc.nativeui.design.OpusDesign.ComponentSize;
import org.polydevs.opusmc.nativeui.design.OpusDesign.MotionToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.RadiusToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.SpacingToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.TextStyle;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

public final class OpusElementaProofScreen extends WindowScreen {
    private static final OpusDesign DESIGN = OpusDesign.defaultDesign();
    private static final ElementaThemeAdapter THEME = new ElementaThemeAdapter(DESIGN);

    private final UIText diagnostics = new UIText(
            "initializing", false, THEME.color(ColorRole.MUTED));
    private final UIText interactionStatus = new UIText(
            "Click controls to record input.", false, THEME.color(ColorRole.MUTED));
    private final UITextInput textInput = new UITextInput("Type, select, copy, and paste", false);
    private final UIBlock overlay = new UIBlock(THEME.color(ColorRole.OVERLAY));
    private boolean overlayVisible;
    private int frames;

    public OpusElementaProofScreen() {
        super(ElementaVersion.V11, true, false, true);
        buildUi();
        NativeUiRuntimeDiagnostics.record("elementaConstructed", "true");
    }

    private void buildUi() {
        UIBlock backdrop = new UIBlock(THEME.color(ColorRole.BACKDROP));
        backdrop.setWidth(new RelativeConstraint(1f));
        backdrop.setHeight(new RelativeConstraint(1f));
        backdrop.setChildOf(getWindow());

        UIBlock panel = new UIBlock(THEME.color(ColorRole.SURFACE));
        panel.setX(new CenterConstraint());
        panel.setY(new CenterConstraint());
        panel.setWidth(new RelativeConstraint(0.88f));
        panel.setHeight(new RelativeConstraint(0.84f));
        panel.enableEffect(new OutlineEffect(THEME.color(ColorRole.BORDER), 1f));
        panel.setChildOf(backdrop);

        UIText title = new UIText(
                "Opus / Elementa V11 compatibility lab", false, THEME.color(ColorRole.TEXT));
        title.setX(new PixelConstraint(THEME.spacing(SpacingToken.PANEL_INSET)));
        title.setY(new PixelConstraint(16f));
        title.setTextScale(new PixelConstraint(THEME.textScale(TextStyle.TITLE)));
        title.setChildOf(panel);

        UIText subtitle = new UIText(
                "Focus, clipboard, clipping, nested scroll, floating overlay, animation, scale, and GL state",
                false,
                THEME.color(ColorRole.MUTED));
        subtitle.setX(new PixelConstraint(THEME.spacing(SpacingToken.PANEL_INSET)));
        subtitle.setY(new PixelConstraint(38f));
        subtitle.setChildOf(panel);

        UIBlock inputSurface = new UIBlock(THEME.color(ColorRole.SURFACE_RAISED));
        inputSurface.setX(new PixelConstraint(THEME.spacing(SpacingToken.PANEL_INSET)));
        inputSurface.setY(new PixelConstraint(62f));
        inputSurface.setWidth(new RelativeConstraint(0.54f));
        inputSurface.setHeight(new PixelConstraint(THEME.componentSize(ComponentSize.INPUT_HEIGHT)));
        inputSurface.enableEffect(new OutlineEffect(THEME.color(ColorRole.BORDER), 1f));
        inputSurface.setChildOf(panel);

        textInput.setX(new PixelConstraint(10f));
        textInput.setY(new CenterConstraint());
        textInput.setWidth(new RelativeConstraint(0.94f));
        textInput.setHeight(new PixelConstraint(12f));
        textInput.setColor(THEME.color(ColorRole.TEXT));
        textInput.setChildOf(inputSurface);
        textInput.onMouseClickConsumer(event -> {
            textInput.grabWindowFocus();
            interactionStatus.setText("Text input focused; Ctrl/Cmd+A/C/V/X exercises clipboard paths.");
            NativeUiRuntimeDiagnostics.record("lastInput", "text-focus");
        });
        textInput.onKeyTypeConsumer((typedChar, keyCode) -> {
            interactionStatus.setText("Last key: " + keyCode + " / text length " + textInput.getText().length());
            NativeUiRuntimeDiagnostics.record("lastInput", "key:" + keyCode);
        });

        UIBlock copyButton = button("Copy text", 0.59f, 62f, panel);
        copyButton.onMouseClickConsumer(event -> {
            GuiScreen.setClipboardString(textInput.getText());
            interactionStatus.setText("Copied " + textInput.getText().length() + " characters to the system clipboard.");
            NativeUiRuntimeDiagnostics.record("clipboard", "copy");
        });

        UIBlock overlayButton = button("Open overlay", 0.76f, 62f, panel);
        overlayButton.onMouseClickConsumer(event -> showOverlay());

        ScrollComponent outerScroll = new ScrollComponent(
                "No proof rows", 4f, THEME.color(ColorRole.MUTED));
        outerScroll.setX(new PixelConstraint(THEME.spacing(SpacingToken.PANEL_INSET)));
        outerScroll.setY(new PixelConstraint(112f));
        outerScroll.setWidth(new RelativeConstraint(0.58f));
        outerScroll.setHeight(new RelativeConstraint(0.58f));
        outerScroll.enableEffect(new OutlineEffect(THEME.color(ColorRole.BORDER), 1f));
        outerScroll.setChildOf(panel);

        for (int index = 0; index < 18; index++) {
            UIBlock row = new UIBlock(index % 2 == 0
                    ? THEME.color(ColorRole.SURFACE_RAISED)
                    : THEME.color(ColorRole.SURFACE));
            row.setX(new PixelConstraint(0f));
            row.setY(new PixelConstraint(index * 31f));
            row.setWidth(new RelativeConstraint(1f));
            row.setHeight(new PixelConstraint(THEME.componentSize(ComponentSize.ROW_HEIGHT)));
            row.setChildOf(outerScroll);

            UIText rowText = new UIText(
                    "Scrollable proof row " + (index + 1), false, THEME.color(ColorRole.TEXT));
            rowText.setX(new PixelConstraint(THEME.spacing(SpacingToken.SM)));
            rowText.setY(new CenterConstraint());
            rowText.setChildOf(row);

            final int selectedIndex = index;
            row.onMouseClickConsumer(event -> {
                interactionStatus.setText("Selected outer row " + (selectedIndex + 1));
                NativeUiRuntimeDiagnostics.record("lastInput", "outer-row:" + selectedIndex);
            });

            if (index == 5) {
                ScrollComponent nested = new ScrollComponent(
                        "", 2f, THEME.color(ColorRole.TEXT), true, false);
                nested.setX(new RelativeConstraint(0.52f));
                nested.setY(new PixelConstraint(3f));
                nested.setWidth(new RelativeConstraint(0.45f));
                nested.setHeight(new PixelConstraint(22f));
                nested.setChildOf(row);
                for (int chipIndex = 0; chipIndex < 8; chipIndex++) {
                    UIBlock chip = new UIBlock(chipIndex % 2 == 0
                            ? THEME.color(ColorRole.ACCENT)
                            : THEME.color(ColorRole.ACCENT_HOVER));
                    chip.setX(new PixelConstraint(chipIndex * 64f));
                    chip.setY(new PixelConstraint(0f));
                    chip.setWidth(new PixelConstraint(58f));
                    chip.setHeight(new PixelConstraint(THEME.componentSize(ComponentSize.CHIP_HEIGHT)));
                    chip.setChildOf(nested);
                    UIText chipText = new UIText(
                            "N" + (chipIndex + 1), false, THEME.color(ColorRole.TEXT));
                    chipText.setX(new CenterConstraint());
                    chipText.setY(new CenterConstraint());
                    chipText.setChildOf(chip);
                }
            }
        }

        UIBlock clipSurface = new UIBlock(THEME.color(ColorRole.SURFACE_RAISED));
        clipSurface.setX(new RelativeConstraint(0.63f));
        clipSurface.setY(new PixelConstraint(112f));
        clipSurface.setWidth(new RelativeConstraint(0.33f));
        clipSurface.setHeight(new PixelConstraint(74f));
        clipSurface.enableEffect(new OutlineEffect(THEME.color(ColorRole.BORDER), 1f));
        clipSurface.enableEffect(new ScissorEffect());
        clipSurface.setChildOf(panel);

        UIBlock clippedChild = new UIBlock(THEME.color(ColorRole.ACCENT));
        clippedChild.setX(new PixelConstraint(18f));
        clippedChild.setY(new PixelConstraint(18f));
        clippedChild.setWidth(new RelativeConstraint(1.15f));
        clippedChild.setHeight(new PixelConstraint(38f));
        clippedChild.setChildOf(clipSurface);
        UIText clipText = new UIText(
                "This accent surface must clip at the border", false, THEME.color(ColorRole.TEXT));
        clipText.setX(new PixelConstraint(THEME.spacing(SpacingToken.SM)));
        clipText.setY(new CenterConstraint());
        clipText.setChildOf(clippedChild);

        UIBlock animated = new UIBlock(THEME.color(ColorRole.ACCENT));
        animated.setX(new RelativeConstraint(0.63f));
        animated.setY(new PixelConstraint(204f));
        animated.setWidth(new RelativeConstraint(0.20f));
        animated.setHeight(new PixelConstraint(THEME.componentSize(ComponentSize.BUTTON_HEIGHT)));
        animated.setChildOf(panel);
        UIText animationText = new UIText(
                "Hover animation", false, THEME.color(ColorRole.TEXT));
        animationText.setX(new CenterConstraint());
        animationText.setY(new CenterConstraint());
        animationText.setChildOf(animated);
        animated.onMouseEnterRunnable(() -> animateWidth(
                animated, 0.31f, THEME.color(ColorRole.ACCENT_HOVER)));
        animated.onMouseLeaveRunnable(() -> animateWidth(
                animated, 0.20f, THEME.color(ColorRole.ACCENT)));

        interactionStatus.setX(new RelativeConstraint(0.63f));
        interactionStatus.setY(new PixelConstraint(260f));
        interactionStatus.setWidth(new RelativeConstraint(0.33f));
        interactionStatus.setChildOf(panel);

        diagnostics.setX(new RelativeConstraint(0.63f));
        diagnostics.setY(new PixelConstraint(302f));
        diagnostics.setWidth(new RelativeConstraint(0.33f));
        diagnostics.setChildOf(panel);

        buildOverlay();
    }

    private UIBlock button(String label, float relativeX, float y, UIComponent parent) {
        UIBlock button = new UIBlock(THEME.color(ColorRole.ACCENT));
        button.setX(new RelativeConstraint(relativeX));
        button.setY(new PixelConstraint(y));
        button.setWidth(new RelativeConstraint(0.15f));
        button.setHeight(new PixelConstraint(THEME.componentSize(ComponentSize.BUTTON_HEIGHT)));
        button.setChildOf(parent);
        UIText text = new UIText(label, false, THEME.color(ColorRole.TEXT));
        text.setX(new CenterConstraint());
        text.setY(new CenterConstraint());
        text.setChildOf(button);
        button.onMouseEnterRunnable(() -> button.setColor(THEME.color(ColorRole.ACCENT_HOVER)));
        button.onMouseLeaveRunnable(() -> button.setColor(THEME.color(ColorRole.ACCENT)));
        return button;
    }

    private void animateWidth(UIBlock component, float targetWidth, Color targetColor) {
        AnimatingConstraints animation = component.makeAnimation();
        animation.setWidthAnimation(
                Animations.OUT_EXP,
                THEME.motionSeconds(MotionToken.HOVER),
                new RelativeConstraint(targetWidth));
        component.setColor(targetColor);
        component.animateTo(animation);
        NativeUiRuntimeDiagnostics.record("animation", Float.toString(targetWidth));
    }

    private void buildOverlay() {
        overlay.setWidth(new RelativeConstraint(1f));
        overlay.setHeight(new RelativeConstraint(1f));
        overlay.setChildOf(getWindow());
        overlay.setIsFloating(true);

        UIRoundedRectangle dialog = new UIRoundedRectangle(THEME.radius(RadiusToken.MODAL));
        dialog.setX(new CenterConstraint());
        dialog.setY(new CenterConstraint());
        dialog.setWidth(new RelativeConstraint(0.42f));
        dialog.setHeight(new RelativeConstraint(0.34f));
        dialog.setColor(THEME.color(ColorRole.SURFACE_RAISED));
        dialog.enableEffect(new OutlineEffect(THEME.color(ColorRole.BORDER), 1f));
        dialog.setChildOf(overlay);

        UIText heading = new UIText(
                "Floating overlay proof", false, THEME.color(ColorRole.TEXT));
        heading.setX(new CenterConstraint());
        heading.setY(new PixelConstraint(28f));
        heading.setTextScale(new PixelConstraint(THEME.textScale(TextStyle.MODAL_TITLE)));
        heading.setChildOf(dialog);

        UIText body = new UIText(
                "The overlay must render and receive input above clipped/scrolling content.",
                false,
                THEME.color(ColorRole.MUTED));
        body.setX(new CenterConstraint());
        body.setY(new PixelConstraint(62f));
        body.setChildOf(dialog);

        UIBlock close = new UIBlock(THEME.color(ColorRole.ACCENT));
        close.setX(new CenterConstraint());
        close.setY(new PixelConstraint(104f));
        close.setWidth(new PixelConstraint(120f));
        close.setHeight(new PixelConstraint(THEME.componentSize(ComponentSize.MODAL_BUTTON_HEIGHT)));
        close.setChildOf(dialog);
        UIText closeText = new UIText(
                "Close overlay", false, THEME.color(ColorRole.TEXT));
        closeText.setX(new CenterConstraint());
        closeText.setY(new CenterConstraint());
        closeText.setChildOf(close);
        close.onMouseClickConsumer(event -> hideOverlay());

        overlay.hide();
    }

    private void showOverlay() {
        if (!overlayVisible) {
            overlayVisible = true;
            overlay.unhide(false);
        }
        interactionStatus.setText("Floating overlay opened; Escape closes it before leaving the screen.");
        NativeUiRuntimeDiagnostics.record("overlay", "open");
    }

    private void hideOverlay() {
        if (overlayVisible) {
            overlayVisible = false;
            overlay.hide();
        }
        interactionStatus.setText("Floating overlay closed.");
        NativeUiRuntimeDiagnostics.record("overlay", "closed");
    }

    @Override
    public void afterInitialization() {
        NativeUiRuntimeDiagnostics.record("elementaAfterInitialization", "true");
    }

    @Override
    public void onDrawScreen(UMatrixStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        int errorBefore = GL11.glGetError();
        NativeUiRuntimeDiagnostics.recordScreenDraw("elementa", "inner-before", errorBefore);
        super.onDrawScreen(matrixStack, mouseX, mouseY, partialTicks);
        int errorAfter = GL11.glGetError();
        NativeUiRuntimeDiagnostics.recordScreenDraw("elementa", "inner-after", errorAfter);
        frames++;
        UIComponent focused = getWindow().getFocusedComponent();
        String focusedName = focused == null ? "none" : focused.getClass().getSimpleName();
        String value = "GUI " + UResolution.getScaledWidth() + "x" + UResolution.getScaledHeight()
                + " / framebuffer " + UResolution.getViewportWidth() + "x" + UResolution.getViewportHeight()
                + " / window " + Display.getWidth() + "x" + Display.getHeight()
                + " / scale " + String.format("%.3f", UResolution.getScaleFactor())
                + " / focus " + focusedName
                + " / GL " + errorBefore + "->" + errorAfter;
        diagnostics.setText(value);
        if (frames == 1 || frames % 120 == 0 || errorBefore != GL11.GL_NO_ERROR || errorAfter != GL11.GL_NO_ERROR) {
            NativeUiRuntimeDiagnostics.record("elementaDiagnostics", value);
        }
    }

    @Override
    public void onKeyPressed(int keyCode, char typedChar, UKeyboard.Modifiers modifiers) {
        if (keyCode == UKeyboard.KEY_ESCAPE) {
            if (overlayVisible) {
                hideOverlay();
            } else {
                NativeUiRuntimeDiagnostics.record("elementaEscape", "restore-previous-screen");
                restorePreviousScreen();
            }
            return;
        }
        super.onKeyPressed(keyCode, typedChar, modifiers);
    }

    @Override
    public void onScreenClose() {
        NativeUiRuntimeDiagnostics.record("elementaClosed", "true");
        super.onScreenClose();
    }
}
