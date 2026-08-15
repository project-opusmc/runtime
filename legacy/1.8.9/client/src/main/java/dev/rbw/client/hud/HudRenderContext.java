package dev.rbw.client.hud;

import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.UiRenderer;
import net.minecraft.client.Minecraft;

/** Shared context for all normal HUD widgets. */
public final class HudRenderContext {
    private final Minecraft minecraft;
    private final UiRenderer renderer;
    private final UiBounds viewport;

    public HudRenderContext(Minecraft minecraft, UiRenderer renderer, UiBounds viewport) {
        this.minecraft = minecraft;
        this.renderer = renderer;
        this.viewport = viewport;
    }

    public Minecraft minecraft() {
        return minecraft;
    }

    public UiRenderer renderer() {
        return renderer;
    }

    public UiBounds viewport() {
        return viewport;
    }
}
