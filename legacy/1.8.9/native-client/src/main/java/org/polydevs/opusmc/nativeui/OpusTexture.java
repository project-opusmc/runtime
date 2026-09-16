package org.polydevs.opusmc.nativeui;

import gg.essential.elementa.UIComponent;
import gg.essential.universal.UMatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.util.ResourceLocation;

/**
 * Small Elementa component for drawing a Minecraft resource-backed texture.
 *
 * <p>The component deliberately uses Minecraft's established GUI texture path
 * instead of creating a second image loader. This keeps resource loading on
 * the render thread, avoids AWT/BufferedImage initialization, and lets the
 * texture manager own resource-pack reload behavior.</p>
 */
final class OpusTexture extends UIComponent {
    enum Fit {
        COVER,
        CONTAIN
    }

    private final ResourceLocation resource;
    private final int textureWidth;
    private final int textureHeight;
    private final Fit fit;
    private final float alpha;

    OpusTexture(
            ResourceLocation resource,
            int textureWidth,
            int textureHeight,
            Fit fit,
            float alpha) {
        if (resource == null) {
            throw new IllegalArgumentException("resource cannot be null");
        }
        if (textureWidth <= 0 || textureHeight <= 0) {
            throw new IllegalArgumentException(
                    "texture dimensions must be positive: " + textureWidth + "x" + textureHeight);
        }
        if (fit == null) {
            throw new IllegalArgumentException("fit cannot be null");
        }
        if (alpha < 0f || alpha > 1f) {
            throw new IllegalArgumentException("alpha must be between 0 and 1: " + alpha);
        }
        this.resource = resource;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.fit = fit;
        this.alpha = alpha;
    }

    @Override
    public void draw(UMatrixStack matrixStack) {
        beforeDrawCompat(matrixStack);

        float width = getWidth();
        float height = getHeight();
        if (width > 0f && height > 0f && alpha > 0f) {
            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.getTextureManager().bindTexture(resource);

            /*
             * Keep the panorama pixels crisp to remove the old over-blurred
             * look, while still using linear sampling for the transparent
             * lockup so its diagonal edges resize cleanly.
             */
            ITextureObject texture = minecraft.getTextureManager().getTexture(resource);
            if (texture instanceof AbstractTexture) {
                ((AbstractTexture) texture).setBlurMipmap(fit == Fit.CONTAIN, false);
            }

            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                    770,
                    771,
                    1,
                    0);
            GlStateManager.color(1f, 1f, 1f, alpha);

            if (fit == Fit.COVER) {
                drawCover(width, height);
            } else {
                drawContain(width, height);
            }

            GlStateManager.color(1f, 1f, 1f, 1f);
        }

        super.draw(matrixStack);
    }

    private void drawCover(float width, float height) {
        float sourceX = 0f;
        float sourceY = 0f;
        float sourceWidth = textureWidth;
        float sourceHeight = textureHeight;
        float sourceAspect = textureWidth / (float) textureHeight;
        float targetAspect = width / height;

        if (targetAspect > sourceAspect) {
            sourceHeight = textureWidth / targetAspect;
            sourceY = (textureHeight - sourceHeight) / 2f;
        } else if (targetAspect < sourceAspect) {
            sourceWidth = textureHeight * targetAspect;
            sourceX = (textureWidth - sourceWidth) / 2f;
        }

        Gui.drawScaledCustomSizeModalRect(
                Math.round(getLeft()),
                Math.round(getTop()),
                sourceX,
                sourceY,
                Math.max(1, Math.round(sourceWidth)),
                Math.max(1, Math.round(sourceHeight)),
                Math.max(1, Math.round(width)),
                Math.max(1, Math.round(height)),
                textureWidth,
                textureHeight);
    }

    private void drawContain(float width, float height) {
        float scale = Math.min(width / textureWidth, height / textureHeight);
        float drawWidth = textureWidth * scale;
        float drawHeight = textureHeight * scale;
        float left = getLeft() + (width - drawWidth) / 2f;
        float top = getTop() + (height - drawHeight) / 2f;

        Gui.drawScaledCustomSizeModalRect(
                Math.round(left),
                Math.round(top),
                0f,
                0f,
                textureWidth,
                textureHeight,
                Math.max(1, Math.round(drawWidth)),
                Math.max(1, Math.round(drawHeight)),
                textureWidth,
                textureHeight);
    }
}
