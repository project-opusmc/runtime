package org.polydevs.opusmc.forge;

import org.lwjgl.opengl.Display;

/** Retina-aware backing-store dimensions for the legacy Minecraft renderer. */
public final class RetinaDisplay {
    private RetinaDisplay() {}

    public static int backingWidth() {
        return backingDimension(Display.getWidth(), Display.getPixelScaleFactor());
    }

    public static int backingHeight() {
        return backingDimension(Display.getHeight(), Display.getPixelScaleFactor());
    }

    static int backingDimension(int logical, float scale) {
        int safeLogical = Math.max(1, logical);
        float safeScale = Float.isNaN(scale) || Float.isInfinite(scale)
                ? 1.0F
                : Math.max(1.0F, scale);
        long pixels = Math.round(safeLogical * (double) safeScale);
        return (int) Math.max(1L, Math.min((long) Integer.MAX_VALUE, pixels));
    }
}
