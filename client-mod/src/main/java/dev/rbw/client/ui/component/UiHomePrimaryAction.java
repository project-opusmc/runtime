package dev.rbw.client.ui.component;

import dev.rbw.client.UiTheme;
import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.UiFontWeight;
import dev.rbw.client.ui.UiInput;
import dev.rbw.client.ui.UiRenderer;

/**
 * A genuine game-entry action for the title screen. The hierarchy comes from
 * the destination's prominence, not from decorative cards or mock content.
 */
public final class UiHomePrimaryAction extends UiComponent {
    public enum Emphasis {
        PRIMARY,
        SECONDARY
    }

    private final String label;
    private final String description;
    private final Emphasis emphasis;
    private final Runnable action;

    public UiHomePrimaryAction(String label, String description, Emphasis emphasis, Runnable action) {
        if (label == null || description == null || emphasis == null || action == null) {
            throw new IllegalArgumentException("label, description, emphasis and action are required");
        }
        this.label = label;
        this.description = description;
        this.emphasis = emphasis;
        this.action = action;
    }

    @Override
    public void render(UiRenderer renderer, UiInput input) {
        UiBounds bounds = bounds();
        UiTheme theme = UiTheme.current();
        boolean hovered = bounds.contains(input.mouseX, input.mouseY);
        boolean primary = emphasis == Emphasis.PRIMARY;
        int fill = primary ? (hovered ? 0xEE222A31 : 0xE0181D23) : (hovered ? 0xC81A2128 : 0xAD12171D);
        int border = hovered ? 0x65FFFFFF : (primary ? 0x3CFFFFFF : 0x24FFFFFF);

        // Two related play rails read as in-game controls, not as dashboard
        // cards. The hierarchy is carried by contrast, width and the narrow
        // white launch rail rather than a large white web-style button.
        renderer.fill(bounds, fill);
        hairline(renderer, bounds, border);
        if (primary) {
            renderer.fill(new UiBounds(bounds.x, bounds.y, 3, bounds.height),
                    hovered ? 0xFFFFFFFF : 0xE6E9ECEE);
        }

        float textX = bounds.x + 16.0F;
        renderer.uiText(label, textX, bounds.y + 9.0F, 11.2F, UiFontWeight.SEMIBOLD, 0.55F,
                theme.text());
        renderer.uiText(description, textX, bounds.y + 27.0F, 7.15F, UiFontWeight.REGULAR, 0.02F,
                theme.muted());
        drawArrow(renderer, bounds.right() - 19.0F, bounds.y + bounds.height / 2.0F,
                hovered ? 0xFFFFFFFF : 0xD9E0E4E7);
    }

    @Override
    public boolean mouseDown(int mouseX, int mouseY, int button) {
        if (button == 0 && bounds().contains(mouseX, mouseY)) {
            action.run();
            return true;
        }
        return false;
    }

    private static void drawArrow(UiRenderer renderer, float x, float y, int color) {
        renderer.line(x - 7.0F, y, x + 6.0F, y, 1.0F, color);
        renderer.line(x + 2.0F, y - 4.0F, x + 6.0F, y, 1.0F, color);
        renderer.line(x + 2.0F, y + 4.0F, x + 6.0F, y, 1.0F, color);
    }

    private static void hairline(UiRenderer renderer, UiBounds bounds, int color) {
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
