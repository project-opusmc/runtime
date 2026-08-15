package org.polydevs.opusmc.client.ui.render;

import org.polydevs.opusmc.client.ui.UiBounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

/**
 * Keeps Opus surfaces at a stable physical size instead of inheriting the
 * player's Minecraft GUI scale. Product coordinates target two framebuffer
 * pixels per unit, which is readable on both standard and Retina displays.
 */
public final class MinecraftUiScale {
    private static final int TARGET_FRAMEBUFFER_SCALE = 2;

    private MinecraftUiScale() {
    }

    public static UiBounds viewport(Minecraft minecraft) {
        return new UiBounds(
                0,
                0,
                Math.max(1, divideRoundUp(minecraft.displayWidth, TARGET_FRAMEBUFFER_SCALE)),
                Math.max(1, divideRoundUp(minecraft.displayHeight, TARGET_FRAMEBUFFER_SCALE)));
    }

    static float matrixScale(Minecraft minecraft) {
        int minecraftScale = new ScaledResolution(minecraft).getScaleFactor();
        return TARGET_FRAMEBUFFER_SCALE / (float) Math.max(1, minecraftScale);
    }

    public static int convertX(int minecraftGuiX, int minecraftGuiWidth, UiBounds viewport) {
        return convert(minecraftGuiX, minecraftGuiWidth, viewport.width);
    }

    public static int convertY(int minecraftGuiY, int minecraftGuiHeight, UiBounds viewport) {
        return convert(minecraftGuiY, minecraftGuiHeight, viewport.height);
    }

    private static int convert(int value, int sourceExtent, int targetExtent) {
        if (sourceExtent <= 0) {
            return value;
        }
        return Math.round(value * targetExtent / (float) sourceExtent);
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
