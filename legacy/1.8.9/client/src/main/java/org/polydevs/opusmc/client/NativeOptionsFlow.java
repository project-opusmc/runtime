package org.polydevs.opusmc.client;

import net.minecraft.client.gui.GuiOptions;

/** Owns only the explicitly opened vanilla options root and its return path. */
final class NativeOptionsFlow {
    private GuiOptions root;

    void open(GuiOptions screen) {
        if (screen == null) throw new IllegalArgumentException("Native options root is required");
        root = screen;
    }

    boolean owns(GuiOptions screen) {
        return screen != null && screen == root;
    }

    void close() {
        root = null;
    }
}
