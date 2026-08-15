package dev.rbw.client.ui.component;

import dev.rbw.client.UiTheme;
import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.UiFontWeight;
import dev.rbw.client.ui.UiInput;
import dev.rbw.client.ui.UiRenderer;

/** A compact, real destination link for secondary game-client utilities. */
public final class UiHomeUtilityLink extends UiComponent {
    private final String label;
    private final Runnable action;

    public UiHomeUtilityLink(String label, Runnable action) {
        if (label == null || action == null) {
            throw new IllegalArgumentException("label and action are required");
        }
        this.label = label;
        this.action = action;
    }

    @Override
    public void render(UiRenderer renderer, UiInput input) {
        UiBounds bounds = bounds();
        boolean hovered = bounds.contains(input.mouseX, input.mouseY);
        int color = hovered ? UiTheme.current().text() : UiTheme.current().muted();
        renderer.uiText(label, bounds.x, bounds.y + 2.0F, 7.2F, UiFontWeight.REGULAR, 0.85F, color);
        if (hovered) {
            renderer.line(bounds.x, bounds.bottom() - 1.0F, bounds.right(), bounds.bottom() - 1.0F, 0.7F, color);
        }
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
