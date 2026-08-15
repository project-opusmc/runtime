package org.polydevs.opusmc.client.ui;

/** Immutable input snapshot for a single UI frame. */
public final class UiInput {
    public final int mouseX;
    public final int mouseY;
    public final float partialTicks;

    public UiInput(int mouseX, int mouseY, float partialTicks) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.partialTicks = partialTicks;
    }
}
