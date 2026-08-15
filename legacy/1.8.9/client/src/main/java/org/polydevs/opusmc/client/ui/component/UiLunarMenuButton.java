package org.polydevs.opusmc.client.ui.component;

import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiFontWeight;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;

/** A centered icon-and-label destination used by the compact home menu. */
public final class UiLunarMenuButton extends UiComponent {
    public enum Icon {
        SINGLEPLAYER,
        MULTIPLAYER,
        CLIENT_OPTIONS
    }

    private final String label;
    private final Icon icon;
    private final Runnable action;
    private float visualScale = 1.0F;

    public UiLunarMenuButton(String label, Icon icon, Runnable action) {
        if (label == null || icon == null || action == null) {
            throw new IllegalArgumentException("label, icon and action are required");
        }
        this.label = label;
        this.icon = icon;
        this.action = action;
    }

    /** Keeps type and icon mass aligned with the reference canvas on resize. */
    public void setVisualScale(float nextScale) {
        visualScale = Math.max(0.60F, Math.min(1.0F, nextScale));
    }

    @Override
    public void render(UiRenderer renderer, UiInput input) {
        UiBounds bounds = bounds();
        boolean hovered = bounds.contains(input.mouseX, input.mouseY);
        int fill = hovered ? 0xFF191B22 : 0xFF13141A;
        renderer.roundedRect(bounds, 4, fill);

        float iconSize = 8.0F * visualScale;
        float gap = 4.0F * visualScale;
        float fontSize = 8.7F * visualScale;
        float labelWidth = renderer.measureUiText(label, fontSize, UiFontWeight.SEMIBOLD, 0.0F);
        float groupWidth = iconSize + gap + labelWidth;
        float iconX = bounds.x + (bounds.width - groupWidth) / 2.0F + iconSize / 2.0F;
        float iconY = bounds.y + bounds.height / 2.0F;
        int color = hovered ? 0xFFE6E7EB : 0xFFC9CBD2;
        drawIcon(renderer, icon, iconX, iconY, iconSize / 8.0F, color);
        renderer.uiText(label, iconX + iconSize / 2.0F + gap, bounds.y + (bounds.height - fontSize) / 2.0F - 0.2F,
                fontSize, UiFontWeight.SEMIBOLD, 0.0F, 0xFFF1F1F4);
    }

    @Override
    public boolean mouseDown(int mouseX, int mouseY, int button) {
        if (button == 0 && bounds().contains(mouseX, mouseY)) {
            action.run();
            return true;
        }
        return false;
    }

    private static void drawIcon(UiRenderer renderer, Icon icon, float x, float y, float scale, int color) {
        if (icon == Icon.SINGLEPLAYER) {
            renderer.ring(x, y - 2.0F * scale, 1.5F * scale, Math.max(0.7F, 1.1F * scale), color);
            renderer.roundedRect(new UiBounds(Math.round(x - 3.0F * scale), Math.round(y + 0.2F * scale),
                    Math.max(2, Math.round(6 * scale)), Math.max(2, Math.round(4 * scale))), 1, color);
        } else if (icon == Icon.MULTIPLAYER) {
            renderer.ring(x - 2.2F * scale, y - 1.7F * scale, 1.35F * scale, Math.max(0.7F, scale), color);
            renderer.ring(x + 2.2F * scale, y - 1.7F * scale, 1.35F * scale, Math.max(0.7F, scale), color);
            renderer.roundedRect(new UiBounds(Math.round(x - 5.0F * scale), Math.round(y + 0.2F * scale),
                    Math.max(2, Math.round(4 * scale)), Math.max(2, Math.round(4 * scale))), 1, color);
            renderer.roundedRect(new UiBounds(Math.round(x + 1.0F * scale), Math.round(y + 0.2F * scale),
                    Math.max(2, Math.round(4 * scale)), Math.max(2, Math.round(4 * scale))), 1, color);
        } else {
            renderer.ring(x, y, 3.1F * scale, Math.max(0.7F, scale), color);
            renderer.ring(x, y, 0.9F * scale, Math.max(0.7F, scale), color);
            renderer.line(x, y - 5.0F * scale, x, y - 3.8F * scale, Math.max(0.7F, scale), color);
            renderer.line(x, y + 3.8F * scale, x, y + 5.0F * scale, Math.max(0.7F, scale), color);
            renderer.line(x - 5.0F * scale, y, x - 3.8F * scale, y, Math.max(0.7F, scale), color);
            renderer.line(x + 3.8F * scale, y, x + 5.0F * scale, y, Math.max(0.7F, scale), color);
        }
    }
}
