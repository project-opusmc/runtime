package org.polydevs.opusmc.client.hud;

import org.polydevs.opusmc.client.ui.UiBounds;

/** A real live-game HUD surface owned by one module. */
public interface HudWidget {
    String moduleId();

    boolean isVisible();

    void renderNormal(HudRenderContext context);

    /** Uses the same bounds in normal HUD and editor modes. */
    UiBounds bounds(HudRenderContext context);

    /** Applies an in-memory movement preview; persistence happens on mouse-up. */
    boolean moveBy(UiBounds viewport, UiBounds renderedBounds, int deltaX, int deltaY);

    /** Applies an in-memory size preview; persistence happens on mouse-up. */
    boolean resizeBy(UiBounds renderedBounds, int deltaX);

    boolean disable();

    boolean commitEditorChange();

    /** Restores the last persisted layout when an editor drag is abandoned. */
    void cancelEditorChange();
}
