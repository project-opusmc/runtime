package dev.rbw.client.ui.component;

import dev.rbw.client.UiTheme;
import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.UiFontWeight;
import dev.rbw.client.ui.UiInput;
import dev.rbw.client.ui.UiRenderer;

/** Minimal top-navigation action used only by the Opus game-entry menu. */
public final class UiHomeNavButton extends UiComponent {
    public enum Icon {
        SETTINGS,
        EXIT
    }

    private final String label;
    private final Icon icon;
    private final Runnable action;

    public UiHomeNavButton(String label, Icon icon, Runnable action) {
        if (label == null || icon == null || action == null) {
            throw new IllegalArgumentException("label, icon and action are required");
        }
        this.label = label;
        this.icon = icon;
        this.action = action;
    }

    @Override
    public void render(UiRenderer renderer, UiInput input) {
        UiTheme theme = UiTheme.current();
        UiBounds bounds = bounds();
        boolean hovered = bounds.contains(input.mouseX, input.mouseY);
        if (hovered) {
            renderer.fill(bounds, 0x16FFFFFF);
            renderer.line(bounds.x, bounds.bottom() - 0.35F, bounds.right(), bounds.bottom() - 0.35F,
                    0.7F, 0xA8FFFFFF);
        }

        float iconX = bounds.x + 7.0F;
        float centerY = bounds.y + bounds.height / 2.0F;
        int color = hovered ? theme.text() : theme.muted();
        if (icon == Icon.SETTINGS) {
            renderer.ring(iconX, centerY, 3.0F, 0.8F, color);
            renderer.ring(iconX, centerY, 0.75F, 0.8F, color);
            renderer.line(iconX, centerY - 5.0F, iconX, centerY - 3.8F, 0.8F, color);
            renderer.line(iconX, centerY + 3.8F, iconX, centerY + 5.0F, 0.8F, color);
            renderer.line(iconX - 5.0F, centerY, iconX - 3.8F, centerY, 0.8F, color);
            renderer.line(iconX + 3.8F, centerY, iconX + 5.0F, centerY, 0.8F, color);
        } else {
            renderer.line(iconX - 3.5F, centerY - 3.5F, iconX + 3.5F, centerY + 3.5F, 0.9F, color);
            renderer.line(iconX + 3.5F, centerY - 3.5F, iconX - 3.5F, centerY + 3.5F, 0.9F, color);
        }
        renderer.uiText(label, bounds.x + 17.0F, bounds.y + 5.0F, 7.2F, UiFontWeight.REGULAR, 0.85F, color);
    }

    @Override
    public boolean mouseDown(int mouseX, int mouseY, int button) {
        if (button == 0 && bounds().contains(mouseX, mouseY)) {
            action.run();
            return true;
        }
        return false;
    }
}
