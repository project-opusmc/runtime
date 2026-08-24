package org.polydevs.opusmc.nativeui.design;

import java.util.Objects;

/**
 * Renderer-neutral immutable RGBA color.
 *
 * <p>The native proof lane deliberately keeps this type independent of
 * Elementa, OneConfig, Minecraft, and LWJGL. Renderers convert it at their
 * boundary through their theme adapters.</p>
 */
public final class OpusColor {
    private final int red;
    private final int green;
    private final int blue;
    private final int alpha;

    public OpusColor(int red, int green, int blue, int alpha) {
        this.red = channel(red, "red");
        this.green = channel(green, "green");
        this.blue = channel(blue, "blue");
        this.alpha = channel(alpha, "alpha");
    }

    public int getRed() {
        return red;
    }

    public int getGreen() {
        return green;
    }

    public int getBlue() {
        return blue;
    }

    public int getAlpha() {
        return alpha;
    }

    public int toArgb() {
        return ((alpha & 0xff) << 24)
                | ((red & 0xff) << 16)
                | ((green & 0xff) << 8)
                | (blue & 0xff);
    }

    public String toHexRgba() {
        return String.format("#%02X%02X%02X%02X", red, green, blue, alpha);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OpusColor)) return false;
        OpusColor color = (OpusColor) other;
        return red == color.red
                && green == color.green
                && blue == color.blue
                && alpha == color.alpha;
    }

    @Override
    public int hashCode() {
        return Objects.hash(red, green, blue, alpha);
    }

    @Override
    public String toString() {
        return toHexRgba();
    }

    private static int channel(int value, String name) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException(name + " must be between 0 and 255: " + value);
        }
        return value;
    }
}
