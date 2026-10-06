package org.polydevs.opusmc.forge;

import org.lwjgl.LWJGLUtil;
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

    /** Converts LWJGL's absolute AppKit point coordinate into backing pixels. */
    public static int mouseCoordinate(int logical) {
        int platform = LWJGLUtil.getPlatform();
        if (platform != LWJGLUtil.PLATFORM_MACOSX) {
            return logical;
        }
        return mouseCoordinate(logical, platform, Display.getPixelScaleFactor());
    }

    static int mouseCoordinate(int logical, int platform, float scale) {
        if (platform != LWJGLUtil.PLATFORM_MACOSX) {
            return logical;
        }
        return saturatingScale(logical, scale);
    }

    static int backingDimension(int logical, float scale) {
        int safeLogical = Math.max(1, logical);
        long pixels = Math.round(safeLogical * safeScale(scale));
        return (int) Math.max(1L, Math.min((long) Integer.MAX_VALUE, pixels));
    }

    private static int saturatingScale(int logical, float scale) {
        long pixels = Math.round(logical * safeScale(scale));
        return (int) Math.max(
                (long) Integer.MIN_VALUE,
                Math.min((long) Integer.MAX_VALUE, pixels));
    }

    private static double safeScale(float scale) {
        return Float.isNaN(scale) || Float.isInfinite(scale) || scale < 1.0F
                ? 1.0D
                : (double) scale;
    }
}
