package org.polydevs.opusmc.client.ui.render;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;

/** Checks the actual HUD renderer boundary without starting OpenGL or a game. */
public final class MinecraftFontColorHarness {
    public static void main(String[] args) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Minecraft minecraft = (Minecraft) ((sun.misc.Unsafe) field.get(null)).allocateInstance(Minecraft.class);
        MinecraftUiRenderer renderer = new MinecraftUiRenderer(minecraft);
        // No FontRenderer exists: transparent text must never ask it to draw.
        renderer.text("invisible", 0, 0, 0x00FFFFFF, true);
        renderer.text("invisible", 0, 0, 0x00123456);
        java.lang.reflect.Method normalize = MinecraftUiRenderer.class.getDeclaredMethod("normalizeFontColor", int.class);
        normalize.setAccessible(true);
        int[][] cases = {{0x00FFFFFF, 0}, {0x00123456, 0}, {0x01ABCDEF, 0x04ABCDEF},
                {0x03ABCDEF, 0x04ABCDEF}, {0x04ABCDEF, 0x04ABCDEF}, {0xFFABCDEF, 0xFFABCDEF}};
        for (int[] test : cases) {
            int color = (Integer) normalize.invoke(null, test[0]);
            if (color != test[1] || (color != 0 && (color & 0xFC000000) == 0)) {
                throw new AssertionError("FPS alpha was promoted to opaque or lost its RGB");
            }
        }
        System.out.println("MINECRAFT FONT COLOR: ALL PASSED (zero skipped; low nonzero alpha safely clamped)");
    }
}
