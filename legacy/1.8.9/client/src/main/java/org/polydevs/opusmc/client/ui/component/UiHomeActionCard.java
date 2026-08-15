package org.polydevs.opusmc.client.ui.component;

import org.polydevs.opusmc.client.UiTheme;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiFontWeight;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;

/**
 * A title-menu action with a genuine destination. Its icons are Opus-owned
 * vector primitives rendered by Minecraft, rather than placeholder glyphs.
 */
public final class UiHomeActionCard extends UiComponent {
    public enum Icon {
        SINGLEPLAYER,
        MULTIPLAYER,
        CLIENT_OPTIONS,
        GAME_OPTIONS
    }

    private final String label;
    private final String description;
    private final Icon icon;
    private final Runnable action;
    private boolean hovered;

    public UiHomeActionCard(String label, String description, Icon icon, Runnable action) {
        if (label == null || description == null || icon == null || action == null) {
            throw new IllegalArgumentException("label, description, icon and action are required");
        }
        this.label = label;
        this.description = description;
        this.icon = icon;
        this.action = action;
    }

    @Override
    public void render(UiRenderer renderer, UiInput input) {
        UiTheme theme = UiTheme.current();
        UiBounds bounds = bounds();
        hovered = bounds.contains(input.mouseX, input.mouseY);
        int fill = hovered ? theme.mainFillHover() : theme.mainFill();
        int border = hovered ? theme.mainBorderHover() : theme.mainBorder();
        // The small offset sits under the card as a real depth cue. It is
        // intentionally neutral and carries no extra product state.
        renderer.roundedRect(new UiBounds(bounds.x + 2, bounds.y + 2, bounds.width, bounds.height), 3, 0x38000000);
        renderer.roundedRect(bounds, 3, fill);
        drawHairlineBorder(renderer, bounds, border);
        if (hovered) {
            renderer.line(bounds.x + 12.0F, bounds.y + 0.35F, bounds.right() - 12.0F, bounds.y + 0.35F,
                    0.65F, 0x75FFFFFF);
        }

        int iconColor = hovered ? theme.text() : theme.muted();
        float iconX = bounds.x + 15.0F;
        float iconY = bounds.y + (bounds.height - 18.0F) / 2.0F;
        drawIcon(renderer, iconX, iconY, iconColor);
        renderer.line(bounds.x + 43.0F, bounds.y + 10.0F, bounds.x + 43.0F, bounds.bottom() - 10.0F,
                0.45F, 0x2BFFFFFF);

        float textX = bounds.x + 55.0F;
        renderer.uiText(label, textX, bounds.y + 6.0F, 10.2F, UiFontWeight.SEMIBOLD, 0.7F, theme.text());
        renderer.uiText(description, textX, bounds.y + 24.0F, 7.45F, UiFontWeight.REGULAR, 0.06F,
                theme.muted());
    }

    @Override
    public boolean mouseDown(int mouseX, int mouseY, int button) {
        if (button == 0 && bounds().contains(mouseX, mouseY)) {
            action.run();
            return true;
        }
        return false;
    }

    private void drawIcon(UiRenderer renderer, float x, float y, int color) {
        // Icons occupy an 18x18 product-unit box and stay intentionally quiet.
        // The card label remains the primary recognition surface.
        if (icon == Icon.SINGLEPLAYER) {
            renderer.line(x + 3.0F, y + 3.0F, x + 15.0F, y + 15.0F, 1.1F, color);
            renderer.line(x + 15.0F, y + 3.0F, x + 3.0F, y + 15.0F, 1.1F, color);
            renderer.line(x + 2.0F, y + 5.0F, x + 5.0F, y + 2.0F, 1.0F, color);
            renderer.line(x + 13.0F, y + 2.0F, x + 16.0F, y + 5.0F, 1.0F, color);
        } else if (icon == Icon.MULTIPLAYER) {
            renderer.ring(x + 9.0F, y + 5.0F, 2.25F, 1.0F, color);
            renderer.ring(x + 3.5F, y + 8.0F, 1.65F, 0.9F, color);
            renderer.ring(x + 14.5F, y + 8.0F, 1.65F, 0.9F, color);
            renderer.line(x + 4.5F, y + 14.5F, x + 13.5F, y + 14.5F, 1.0F, color);
            renderer.line(x + 2.0F, y + 15.0F, x + 2.0F, y + 12.0F, 0.9F, color);
            renderer.line(x + 16.0F, y + 15.0F, x + 16.0F, y + 12.0F, 0.9F, color);
        } else if (icon == Icon.CLIENT_OPTIONS) {
            renderer.line(x + 2.0F, y + 4.0F, x + 16.0F, y + 4.0F, 0.8F, color);
            renderer.line(x + 2.0F, y + 9.0F, x + 16.0F, y + 9.0F, 0.8F, color);
            renderer.line(x + 2.0F, y + 14.0F, x + 16.0F, y + 14.0F, 0.8F, color);
            renderer.ring(x + 6.0F, y + 4.0F, 1.35F, 0.9F, color);
            renderer.ring(x + 12.0F, y + 9.0F, 1.35F, 0.9F, color);
            renderer.ring(x + 8.0F, y + 14.0F, 1.35F, 0.9F, color);
        } else {
            renderer.ring(x + 9.0F, y + 9.0F, 4.0F, 1.0F, color);
            renderer.ring(x + 9.0F, y + 9.0F, 1.15F, 1.0F, color);
            renderer.line(x + 9.0F, y + 1.5F, x + 9.0F, y + 3.5F, 1.0F, color);
            renderer.line(x + 9.0F, y + 14.5F, x + 9.0F, y + 16.5F, 1.0F, color);
            renderer.line(x + 1.5F, y + 9.0F, x + 3.5F, y + 9.0F, 1.0F, color);
            renderer.line(x + 14.5F, y + 9.0F, x + 16.5F, y + 9.0F, 1.0F, color);
        }
    }

    private static void drawHairlineBorder(UiRenderer renderer, UiBounds bounds, int color) {
        float left = bounds.x + 0.25F;
        float top = bounds.y + 0.25F;
        float right = bounds.right() - 0.25F;
        float bottom = bounds.bottom() - 0.25F;
        renderer.line(left, top, right, top, 0.5F, color);
        renderer.line(right, top, right, bottom, 0.5F, color);
        renderer.line(right, bottom, left, bottom, 0.5F, color);
        renderer.line(left, bottom, left, top, 0.5F, color);
    }
}
