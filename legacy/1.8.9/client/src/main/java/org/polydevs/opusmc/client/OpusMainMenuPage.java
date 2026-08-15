package org.polydevs.opusmc.client;

import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiFontWeight;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.ui.UiRuntime;
import net.minecraft.util.ResourceLocation;

/**
 * Opus's title surface is intentionally a complete product composition, not a
 * live Minecraft scene underneath. Opus's supplied transparent lockup is the
 * sole brand asset; the menu adds no generated or reconstructed imagery.
 */
final class OpusMainMenuPage extends OpusUiPage {
    private static final ResourceLocation WORDMARK =
            new ResourceLocation("opusclient", "textures/gui/opus-wordmark-transparent.png");
    private static final int DESIGN_WIDTH = 720;
    private static final int DESIGN_HEIGHT = 450;

    private UiBounds wordmark = new UiBounds(0, 0, 0, 0);
    private UiBounds singleplayer = new UiBounds(0, 0, 0, 0);
    private UiBounds multiplayer = new UiBounds(0, 0, 0, 0);
    private UiBounds clientOptions = new UiBounds(0, 0, 0, 0);
    private UiBounds gameOptions = new UiBounds(0, 0, 0, 0);
    private UiBounds quit = new UiBounds(0, 0, 0, 0);
    private float layoutScale = 1.0F;
    private long lastFrameNanos;
    private float singleplayerHover;
    private float multiplayerHover;
    private float clientOptionsHover;
    private float gameOptionsHover;
    private float quitHover;

    OpusMainMenuPage(ClientOverlayController controller, UiRuntime runtime) {
        super(controller, runtime);
    }

    @Override
    public void layout(UiBounds nextViewport) {
        super.layout(nextViewport);
        layoutScale = Math.min(
                viewport.width / (float) DESIGN_WIDTH,
                viewport.height / (float) DESIGN_HEIGHT);
        int offsetX = Math.round((viewport.width - DESIGN_WIDTH * layoutScale) / 2.0F);
        int offsetY = Math.round((viewport.height - DESIGN_HEIGHT * layoutScale) / 2.0F);

        wordmark = rect(230, 78, 260, 92, layoutScale, offsetX, offsetY);
        singleplayer = rect(244, 204, 232, 25, layoutScale, offsetX, offsetY);
        multiplayer = rect(244, 235, 232, 25, layoutScale, offsetX, offsetY);
        clientOptions = rect(244, 269, 111, 23, layoutScale, offsetX, offsetY);
        gameOptions = rect(365, 269, 111, 23, layoutScale, offsetX, offsetY);
        quit = rect(298, 321, 124, 17, layoutScale, offsetX, offsetY);
    }

    @Override
    public void render(UiRenderer renderer, UiInput input) {
        advanceMotion(input);
        renderBackground(renderer);
        // Preserve the original transparent lockup; no recolored or rebuilt
        // logo is introduced by the menu renderer.
        renderer.textureRegion(WORDMARK, wordmark, 0.045D, 0.14D, 0.735D, 0.82D);
        renderMenuAction(renderer, singleplayer, "Singleplayer", singleplayerHover, true);
        renderMenuAction(renderer, multiplayer, "Multiplayer", multiplayerHover, true);
        renderMenuAction(renderer, clientOptions, "Client Options", clientOptionsHover, false);
        renderMenuAction(renderer, gameOptions, "Game Options", gameOptionsHover, false);
        renderQuit(renderer);
    }

    @Override
    public boolean mouseDown(int mouseX, int mouseY, int button) {
        if (button != 0) {
            return false;
        }
        if (singleplayer.contains(mouseX, mouseY)) {
            controller.openSingleplayer();
            return true;
        }
        if (multiplayer.contains(mouseX, mouseY)) {
            controller.openMultiplayer();
            return true;
        }
        if (clientOptions.contains(mouseX, mouseY)) {
            controller.openClientModHub();
            return true;
        }
        if (gameOptions.contains(mouseX, mouseY)) {
            controller.openVanillaOptions();
            return true;
        }
        if (quit.contains(mouseX, mouseY)) {
            controller.quitGame();
            return true;
        }
        return false;
    }

    private void renderBackground(UiRenderer renderer) {
        // The scene is already heavily blurred by MinecraftTitlePanorama.
        // A sober graphite veil keeps it atmospheric and removes the stock
        // title-screen look without hiding that this is a game client.
        renderer.verticalGradient(viewport, 0xC8070A0F, 0xCE10151D);
        renderer.horizontalGradient(viewport, 0x30000000, 0x240C1118);
        float drift = (float) Math.sin(lastFrameNanos / 1_000_000_000.0D * 0.28D);
        // Slow, almost imperceptible light movement gives the hero backdrop
        // depth without adding a generated image or decorative pattern.
        renderer.radialGradient(
                viewport.x + viewport.width * 0.70F + drift * 12.0F * layoutScale,
                viewport.y + viewport.height * 0.23F,
                245.0F * layoutScale,
                160.0F * layoutScale,
                white(13),
                white(0));
        renderer.radialGradient(
                viewport.x + viewport.width * 0.25F - drift * 9.0F * layoutScale,
                viewport.y + viewport.height * 0.78F,
                205.0F * layoutScale,
                135.0F * layoutScale,
                white(7),
                white(0));
        renderer.verticalGradient(
                new UiBounds(viewport.x, viewport.y, viewport.width, Math.max(1, viewport.height / 3)),
                0x12FFFFFF,
                0x00000000);
    }

    private void renderMenuAction(
            UiRenderer renderer, UiBounds bounds, String label, float progress, boolean primary) {
        int expansion = Math.round(progress * Math.max(1.0F, layoutScale * 1.5F));
        int lift = Math.round(progress * Math.max(1.0F, layoutScale * 1.75F));
        UiBounds surface = translate(expand(bounds, expansion), 0, -lift);
        int radius = Math.max(3, Math.round(4.0F * layoutScale));
        int labelColor;
        if (progress > 0.01F) {
            // A small soft shadow is the depth cue for the hover lift; it is
            // deliberately neutral so the menu remains white/graphite only.
            renderer.roundedRect(
                    translate(surface, 0, Math.max(1, lift * 2)),
                    radius,
                    Math.max(0, Math.min(64, Math.round(progress * 54.0F))) << 24);
        }
        if (primary) {
            renderer.roundedRect(surface, radius, neutral(lerp(243, 255, progress)));
            labelColor = 0xFF11161D;
        } else {
            renderer.roundedRect(surface, radius, white(lerp(42, 96, progress)));
            renderer.line(surface.x + radius, surface.y + 1.0F,
                    surface.right() - radius, surface.y + 1.0F,
                    Math.max(0.45F, layoutScale * 0.45F), white(lerp(48, 145, progress)));
            labelColor = white(lerp(208, 255, progress));
        }
        float fontSize = (primary ? 8.2F : 6.55F) * layoutScale;
        UiFontWeight weight = primary ? UiFontWeight.SEMIBOLD : UiFontWeight.REGULAR;
        float tracking = primary ? 0.04F : 0.025F;
        float textWidth = renderer.measureUiText(label, fontSize, weight, tracking);
        renderer.uiText(label, surface.x + (surface.width - textWidth) / 2.0F,
                surface.y + (surface.height - fontSize) / 2.0F - 0.5F,
                fontSize, weight, tracking, labelColor);
    }

    private void renderQuit(UiRenderer renderer) {
        float fontSize = 6.6F * layoutScale;
        String label = "Quit Game";
        float textWidth = renderer.measureUiText(label, fontSize, UiFontWeight.REGULAR, 0.08F);
        float textX = quit.x + (quit.width - textWidth) / 2.0F;
        renderer.uiText(label, textX, quit.y + (quit.height - fontSize) / 2.0F,
                fontSize, UiFontWeight.REGULAR, 0.08F, white(lerp(142, 246, quitHover)));
        if (quitHover > 0.02F) {
            renderer.line(textX, quit.bottom() - 2.0F * layoutScale,
                    textX + textWidth, quit.bottom() - 2.0F * layoutScale,
                    Math.max(0.55F, layoutScale * 0.55F), white(lerp(0, 160, quitHover)));
        }
    }

    private void advanceMotion(UiInput input) {
        long now = System.nanoTime();
        float seconds = lastFrameNanos == 0L ? 0.016F : (now - lastFrameNanos) / 1_000_000_000.0F;
        lastFrameNanos = now;
        seconds = Math.max(0.0F, Math.min(0.05F, seconds));
        float amount = Math.min(1.0F, seconds * 10.0F);
        singleplayerHover = approach(singleplayerHover, singleplayer.contains(input.mouseX, input.mouseY), amount);
        multiplayerHover = approach(multiplayerHover, multiplayer.contains(input.mouseX, input.mouseY), amount);
        clientOptionsHover = approach(clientOptionsHover, clientOptions.contains(input.mouseX, input.mouseY), amount);
        gameOptionsHover = approach(gameOptionsHover, gameOptions.contains(input.mouseX, input.mouseY), amount);
        quitHover = approach(quitHover, quit.contains(input.mouseX, input.mouseY), amount);
    }

    private static float approach(float current, boolean target, float amount) {
        float destination = target ? 1.0F : 0.0F;
        return current + (destination - current) * amount;
    }

    private static int lerp(int from, int to, float progress) {
        return Math.round(from + (to - from) * Math.max(0.0F, Math.min(1.0F, progress)));
    }

    private static int white(int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | 0x00FFFFFF;
    }

    private static int neutral(int channel) {
        int value = Math.max(0, Math.min(255, channel));
        return 0xFF000000 | (value << 16) | (value << 8) | value;
    }

    private static UiBounds expand(UiBounds bounds, int amount) {
        return new UiBounds(
                bounds.x - amount,
                bounds.y - amount,
                bounds.width + amount * 2,
                bounds.height + amount * 2);
    }

    private static UiBounds translate(UiBounds bounds, int x, int y) {
        return new UiBounds(bounds.x + x, bounds.y + y, bounds.width, bounds.height);
    }

    private static UiBounds rect(int x, int y, int width, int height, float scale, int offsetX, int offsetY) {
        return new UiBounds(
                offsetX + Math.round(x * scale),
                offsetY + Math.round(y * scale),
                Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)));
    }
}
