package org.polydevs.opusmc.client.ui.component;

import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;

/**
 * Base node for Opus product UI. Components never call Minecraft or GL drawing
 * APIs directly; all rendering goes through UiRenderer.
 */
public abstract class UiComponent {
    private UiBounds bounds = new UiBounds(0, 0, 0, 0);

    public UiBounds bounds() {
        return bounds;
    }

    public void layout(UiBounds nextBounds) {
        bounds = nextBounds;
    }

    public int preferredWidth() {
        return 0;
    }

    public int preferredHeight() {
        return 0;
    }

    public boolean mouseDown(int mouseX, int mouseY, int button) {
        return false;
    }

    public boolean mouseUp(int mouseX, int mouseY, int button) {
        return false;
    }

    public abstract void render(UiRenderer renderer, UiInput input);
}
