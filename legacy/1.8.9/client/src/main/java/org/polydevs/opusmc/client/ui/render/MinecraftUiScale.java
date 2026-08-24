package org.polydevs.opusmc.client.ui.render;

import org.polydevs.opusmc.client.ui.UiBounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

/**
 * Defines the one logical coordinate space shared by CEF, GuiScreen input, and
 * the native HUD editor. Logical units are the live Minecraft scaled-screen
 * units; framebuffer/device scale affects raster density only.
 */
public final class MinecraftUiScale {
    private MinecraftUiScale() {
    }

    public static UiBounds viewport(Minecraft minecraft) {
        if (minecraft == null) {
            return new UiBounds(0, 0, 1, 1);
        }
        ScaledResolution resolution = new ScaledResolution(minecraft);
        return new UiBounds(
                0,
                0,
                Math.max(1, resolution.getScaledWidth()),
                Math.max(1, resolution.getScaledHeight()));
    }

    /**
     * Maps OPUS logical units into the coordinates used by GuiScreen and the
     * Forge overlay pass. CEF and native HUD therefore share the exact same
     * transform at every Minecraft GUI scale.
     */
    public static float matrixScaleX(Minecraft minecraft, UiBounds viewport) {
        if (minecraft == null || viewport == null || viewport.width <= 0) {
            return 1.0F;
        }
        ScaledResolution resolution = new ScaledResolution(minecraft);
        return resolution.getScaledWidth() / (float) viewport.width;
    }

    public static float matrixScaleY(Minecraft minecraft, UiBounds viewport) {
        if (minecraft == null || viewport == null || viewport.height <= 0) {
            return 1.0F;
        }
        ScaledResolution resolution = new ScaledResolution(minecraft);
        return resolution.getScaledHeight() / (float) viewport.height;
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

}
