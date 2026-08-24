package org.polydevs.opusmc.nativeui;

import gg.essential.elementa.ElementaVersion;
import gg.essential.elementa.UIComponent;
import gg.essential.elementa.WindowScreen;
import gg.essential.elementa.components.UIBlock;
import gg.essential.elementa.components.UIRoundedRectangle;
import gg.essential.elementa.components.UIText;
import gg.essential.elementa.constraints.CenterConstraint;
import gg.essential.elementa.constraints.PixelConstraint;
import gg.essential.elementa.constraints.RelativeConstraint;
import gg.essential.elementa.constraints.animation.AnimatingConstraints;
import gg.essential.elementa.constraints.animation.Animations;
import gg.essential.elementa.effects.OutlineEffect;
import gg.essential.universal.UKeyboard;
import gg.essential.universal.UMatrixStack;
import gg.essential.universal.UResolution;
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
import org.polydevs.opusmc.nativeui.design.OpusDesign.ComponentSize;
import org.polydevs.opusmc.nativeui.design.OpusDesign.MotionToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.RadiusToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.SpacingToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.TextStyle;

/** Elementa-owned Minecraft shell for the main-menu and pause-menu routes. */
public final class OpusElementaShellScreen extends WindowScreen {
    public enum Route {
        MAIN_MENU,
        PAUSE_MENU
    }

    private static final OpusDesign DESIGN = OpusDesign.defaultDesign();
    private static final ElementaThemeAdapter THEME = new ElementaThemeAdapter(DESIGN);

    private final Route route;
    private final UIText status = new UIText("", false, THEME.color(ColorRole.MUTED));
    private int frames;

    public OpusElementaShellScreen(Route route) {
        super(ElementaVersion.V11, true, false, true);
        this.route = route;
        buildUi();
        NativeUiRuntimeDiagnostics.record("elementaShellConstructed", route.name());
    }

    private void buildUi() {
        UIBlock backdrop = new UIBlock(THEME.color(ColorRole.BACKDROP));
        backdrop.setWidth(new RelativeConstraint(1f));
        backdrop.setHeight(new RelativeConstraint(1f));
        backdrop.setChildOf(getWindow());

        UIComponent panel = new UIRoundedRectangle(THEME.radius(RadiusToken.MODAL));
        panel.setX(new CenterConstraint());
        panel.setY(new CenterConstraint());
        panel.setWidth(new RelativeConstraint(route == Route.MAIN_MENU ? 0.72f : 0.58f));
        panel.setHeight(new RelativeConstraint(route == Route.MAIN_MENU ? 0.82f : 0.72f));
        panel.setColor(THEME.color(ColorRole.SURFACE));
        panel.enableEffect(new OutlineEffect(THEME.color(ColorRole.BORDER), 1f));
        panel.setChildOf(backdrop);

        UIText title = new UIText(
                route == Route.MAIN_MENU ? "OPUS" : "OPUS PAUSE",
                false,
                THEME.color(ColorRole.TEXT));
        title.setX(new PixelConstraint(THEME.spacing(SpacingToken.PANEL_INSET)));
        title.setY(new PixelConstraint(22f));
        title.setTextScale(new PixelConstraint(THEME.textScale(TextStyle.TITLE)));
        title.setChildOf(panel);

        UIText subtitle = new UIText(
                route == Route.MAIN_MENU
                        ? "A native Minecraft shell powered by Elementa"
                        : "The world is paused; choose where to go next",
                false,
                THEME.color(ColorRole.MUTED));
        subtitle.setX(new PixelConstraint(THEME.spacing(SpacingToken.PANEL_INSET)));
        subtitle.setY(new PixelConstraint(52f));
        subtitle.setChildOf(panel);

        float firstY = 92f;
        if (route == Route.MAIN_MENU) {
            addButton("Singleplayer", 0.08f, firstY, panel, this::openSingleplayer);
            addButton("Multiplayer", 0.52f, firstY, panel, this::openMultiplayer);
            addButton("Minecraft Settings", 0.08f, firstY + 62f, panel, this::openMinecraftSettings);
            addButton("OpusConfig", 0.52f, firstY + 62f, panel, OpusNativeUiMod::openOpusConfig);
            addButton("Quit Game", 0.08f, firstY + 124f, panel, this::quitGame);
        } else {
            addButton("Resume Game", 0.12f, firstY, panel, this::resumeGame);
            addButton("OpusConfig", 0.54f, firstY, panel, OpusNativeUiMod::openOpusConfig);
            addButton("Minecraft Settings", 0.12f, firstY + 62f, panel, this::openMinecraftSettings);
            addButton("Main Menu", 0.54f, firstY + 62f, panel, this::openMainMenu);
        }

        status.setX(new PixelConstraint(THEME.spacing(SpacingToken.PANEL_INSET)));
        status.setY(new RelativeConstraint(0.88f));
        status.setWidth(new RelativeConstraint(0.84f));
        status.setChildOf(panel);
    }

    private void addButton(
            String label,
            float x,
            float y,
            UIComponent parent,
            Runnable action) {
        UIBlock button = new UIBlock(THEME.color(ColorRole.ACCENT));
        button.setX(new RelativeConstraint(x));
        button.setY(new PixelConstraint(y));
        button.setWidth(new RelativeConstraint(0.36f));
        button.setHeight(new PixelConstraint(THEME.componentSize(ComponentSize.BUTTON_HEIGHT)));
        button.setChildOf(parent);

        UIText text = new UIText(label, false, THEME.color(ColorRole.TEXT));
        text.setX(new CenterConstraint());
        text.setY(new CenterConstraint());
        text.setChildOf(button);

        button.onMouseEnterRunnable(() -> animateButton(button, true));
        button.onMouseLeaveRunnable(() -> animateButton(button, false));
        button.onMouseClickConsumer(event -> {
            status.setText(label);
            NativeUiRuntimeDiagnostics.record("shellAction", label);
            action.run();
        });
    }

    private void animateButton(UIBlock button, boolean hovered) {
        AnimatingConstraints animation = button.makeAnimation();
        animation.setWidthAnimation(
                Animations.OUT_EXP,
                THEME.motionSeconds(MotionToken.HOVER),
                new RelativeConstraint(hovered ? 0.38f : 0.36f));
        button.setColor(hovered
                ? THEME.color(ColorRole.ACCENT_HOVER)
                : THEME.color(ColorRole.ACCENT));
        button.animateTo(animation);
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
    }

    @Override
    public void onDrawScreen(UMatrixStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        int errorBefore = GL11.glGetError();
        NativeUiRuntimeDiagnostics.recordScreenDraw("elementa", "inner-before", errorBefore);
        super.onDrawScreen(matrixStack, mouseX, mouseY, partialTicks);
        int errorAfter = GL11.glGetError();
        NativeUiRuntimeDiagnostics.recordScreenDraw("elementa", "inner-after", errorAfter);
        frames++;
        String value = "GUI " + UResolution.getScaledWidth() + "x" + UResolution.getScaledHeight()
                + " / framebuffer " + UResolution.getViewportWidth() + "x" + UResolution.getViewportHeight()
                + " / window " + Display.getWidth() + "x" + Display.getHeight()
                + " / scale " + String.format("%.3f", UResolution.getScaleFactor())
                + " / GL " + errorBefore + "->" + errorAfter;
        if (frames == 1 || frames % 120 == 0 || errorBefore != GL11.GL_NO_ERROR || errorAfter != GL11.GL_NO_ERROR) {
            NativeUiRuntimeDiagnostics.record("elementaShellDiagnostics", value);
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
}
