package org.polydevs.opusmc.client.ui.component;

import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiFontWeight;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.UiTheme;

/** Opus-owned button used inside the product overlay, never a vanilla GuiButton. */
public final class UiActionButton extends UiComponent {
    public enum Tone {
        PRIMARY,
        NEUTRAL,
        QUIET,
        MAIN,
        MAIN_QUIET
    }

    private String label;
    private final Tone tone;
    private final Runnable action;
    private boolean enabled = true;
    private boolean hovered;

    public UiActionButton(String label, Tone tone, Runnable action) {
        if (label == null || tone == null || action == null) {
            throw new IllegalArgumentException("label, tone and action are required");
        }
        this.label = label;
        this.tone = tone;
        this.action = action;
    }

    public void setEnabled(boolean nextEnabled) {
        enabled = nextEnabled;
    }

    public void setLabel(String nextLabel) {
        if (nextLabel == null) {
            throw new IllegalArgumentException("label is required");
        }
        label = nextLabel;
    }

    @Override
    public void render(UiRenderer renderer, UiInput input) {
        UiTheme theme = UiTheme.current();
        hovered = enabled && bounds().contains(input.mouseX, input.mouseY);
        int fill;
        int border;
        int text;
        if (!enabled) {
            fill = theme.disabledFill();
            border = theme.disabledBorder();
            text = theme.disabledText();
        } else if (tone == Tone.MAIN) {
            fill = hovered ? theme.mainFillHover() : theme.mainFill();
            border = hovered ? theme.mainBorderHover() : theme.mainBorder();
            text = theme.text();
        } else if (tone == Tone.MAIN_QUIET) {
            fill = hovered ? theme.mainQuietFillHover() : theme.mainQuietFill();
            border = hovered ? theme.mainQuietBorderHover() : theme.mainQuietBorder();
            text = theme.text();
        } else if (tone == Tone.PRIMARY) {
            fill = hovered ? theme.primaryFillHover() : theme.primaryFill();
            border = theme.primaryFillHover();
            text = theme.primaryText();
        } else if (tone == Tone.QUIET) {
            fill = hovered ? theme.quietFillHover() : theme.quietFill();
            border = hovered ? theme.quietBorderHover() : theme.quietBorder();
            text = theme.text();
        } else {
            fill = hovered ? theme.neutralFillHover() : theme.neutralFill();
            border = hovered ? theme.neutralBorderHover() : theme.neutralBorder();
            text = theme.text();
        }
        // Product controls share the same compact, gently rounded treatment
        // as the home screen. GuiButton-like square chrome made the overlay
        // read as a Minecraft settings prototype rather than one client UI.
        int radius = Math.max(2, Math.min(4, bounds().height / 3));
        // Lunar-style controls separate states through the fill, not a hard
        // stroke. This also keeps the selected tab from growing square black
        // corners at Minecraft's pixel-scaled render resolution.
        renderer.roundedRect(bounds(), radius, fill);
        float size = Math.min(7.4F, Math.max(5.8F, bounds().height * 0.39F));
        float width = renderer.measureUiText(label, size, UiFontWeight.SEMIBOLD, 0.0F);
        renderer.uiText(
                label,
                bounds().x + (bounds().width - width) / 2.0F,
                bounds().y + (bounds().height - size) / 2.0F - 0.7F,
                size,
                UiFontWeight.SEMIBOLD,
                0.0F,
                text);
    }

    @Override
    public boolean mouseDown(int mouseX, int mouseY, int button) {
        if (enabled && button == 0 && bounds().contains(mouseX, mouseY)) {
            action.run();
            return true;
        }
        return false;
    }
}
