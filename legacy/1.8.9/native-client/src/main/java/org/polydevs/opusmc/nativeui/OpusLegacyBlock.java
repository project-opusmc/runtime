package org.polydevs.opusmc.nativeui;

import java.awt.Color;

import gg.essential.elementa.UIComponent;
import gg.essential.universal.UMatrixStack;
import net.minecraft.client.gui.Gui;

/**
 * Elementa layout component backed by Minecraft 1.8.9's legacy GUI fill path.
 *
 * <p>Elementa remains responsible for constraints, hierarchy, hit testing,
 * events, animation, and child rendering. Only the surface primitive is kept
 * on the legacy path because UniversalCraft's modern buffer-backed rectangle
 * pipeline is unstable on the macOS Metal-backed OpenGL compatibility driver
 * used by the supported 1.8.9 runtime.</p>
 */
public final class OpusLegacyBlock extends UIComponent {
    private final float radius;
    private Color outlineColor;
    private float outlineWidth;

    public OpusLegacyBlock(Color color) {
        this(color, 0f);
    }

    public OpusLegacyBlock(Color color, float radius) {
        if (color == null) {
            throw new IllegalArgumentException("color cannot be null");
        }
        if (radius < 0f) {
            throw new IllegalArgumentException("radius cannot be negative: " + radius);
        }
        this.radius = radius;
        setColor(color);
    }

    public OpusLegacyBlock setOutline(Color color, float width) {
        if (color == null) {
            throw new IllegalArgumentException("outline color cannot be null");
        }
        if (width < 0f) {
            throw new IllegalArgumentException("outline width cannot be negative: " + width);
        }
        this.outlineColor = color;
        this.outlineWidth = width;
        return this;
    }

    @Override
    public void draw(UMatrixStack matrixStack) {
        beforeDrawCompat(matrixStack);

        float left = getLeft();
        float top = getTop();
        float right = getRight();
        float bottom = getBottom();
        if (right > left && bottom > top) {
            if (outlineColor != null && outlineWidth > 0f) {
                drawRounded(left, top, right, bottom, radius, outlineColor);
                float inset = Math.min(outlineWidth, Math.min(right - left, bottom - top) / 2f);
                if (right - left > inset * 2f && bottom - top > inset * 2f) {
                    drawRounded(
                            left + inset,
                            top + inset,
                            right - inset,
                            bottom - inset,
                            Math.max(0f, radius - inset),
                            getColor());
                }
            } else {
                drawRounded(left, top, right, bottom, radius, getColor());
            }
        }

        super.draw(matrixStack);
    }

    private static void drawRounded(float left, float top, float right, float bottom, float radius, Color color) {
        if (color == null || color.getAlpha() == 0) {
            return;
        }

        int x0 = floor(left);
        int y0 = floor(top);
        int x1 = ceil(right);
        int y1 = ceil(bottom);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }

        int amount = Math.max(0, Math.min(round(radius), Math.min(x1 - x0, y1 - y0) / 2));
        int argb = color.getRGB();
        if (amount == 0) {
            Gui.drawRect(x0, y0, x1, y1, argb);
            return;
        }

        Gui.drawRect(x0 + amount, y0, x1 - amount, y1, argb);
        Gui.drawRect(x0, y0 + amount, x1, y1 - amount, argb);
        for (int row = 0; row < amount; row++) {
            double vertical = amount - row - 0.5D;
            int inset = Math.max(
                    0,
                    amount - (int) Math.ceil(Math.sqrt(amount * amount - vertical * vertical)));
            int rowWidth = x1 - x0 - inset * 2;
            if (rowWidth <= 0) {
                continue;
            }
            Gui.drawRect(x0 + inset, y0 + row, x1 - inset, y0 + row + 1, argb);
            Gui.drawRect(x0 + inset, y1 - row - 1, x1 - inset, y1 - row, argb);
        }
    }

    private static int floor(float value) {
        return (int) Math.floor(value);
    }

    private static int ceil(float value) {
        return (int) Math.ceil(value);
    }

    private static int round(float value) {
        return Math.round(value);
    }
}
