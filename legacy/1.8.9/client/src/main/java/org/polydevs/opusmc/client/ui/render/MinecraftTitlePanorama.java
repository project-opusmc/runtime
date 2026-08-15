package org.polydevs.opusmc.client.ui.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.Project;

/**
 * A self-contained renderer for Minecraft's built-in title panorama. It uses
 * the assets already shipped with Minecraft, so the Opus home menu gets a real
 * in-game backdrop without an AI image, an external download, or a fake DOM
 * preview. The composition above it remains wholly Opus-owned.
 */
public final class MinecraftTitlePanorama {
    private static final ResourceLocation[] PANORAMA = new ResourceLocation[] {
            new ResourceLocation("textures/gui/title/background/panorama_0.png"),
            new ResourceLocation("textures/gui/title/background/panorama_1.png"),
            new ResourceLocation("textures/gui/title/background/panorama_2.png"),
            new ResourceLocation("textures/gui/title/background/panorama_3.png"),
            new ResourceLocation("textures/gui/title/background/panorama_4.png"),
            new ResourceLocation("textures/gui/title/background/panorama_5.png")
    };

    private final Minecraft minecraft;
    @SuppressWarnings("unused")
    private final DynamicTexture viewportTexture;
    private final ResourceLocation backgroundTexture;
    private int panoramaTimer;

    public MinecraftTitlePanorama(Minecraft minecraft) {
        if (minecraft == null) {
            throw new IllegalArgumentException("minecraft is required");
        }
        this.minecraft = minecraft;
        this.viewportTexture = new DynamicTexture(256, 256);
        this.backgroundTexture = minecraft.getTextureManager().getDynamicTextureLocation(
                "opus_title_panorama", viewportTexture);
    }

    public void tick() {
        panoramaTimer++;
    }

    public void render(int width, int height, float partialTicks) {
        if (width <= 0 || height <= 0) {
            return;
        }
        minecraft.getFramebuffer().unbindFramebuffer();
        GlStateManager.viewport(0, 0, 256, 256);
        drawPanorama(partialTicks);
        // Keep the vanilla 1.8.9 skybox pipeline intact. Altering the camera
        // or blur sampling here changes the apparent orientation of cubemap
        // faces, which made the landscape look vertical in the menu.
        for (int pass = 0; pass < 7; pass++) {
            rotateAndBlurSkybox(width, height);
        }
        minecraft.getFramebuffer().bindFramebuffer(true);
        GlStateManager.viewport(0, 0, minecraft.displayWidth, minecraft.displayHeight);

        float scale = width > height ? 120.0F / width : 120.0F / height;
        float textureHeight = height * scale / 256.0F;
        float textureWidth = width * scale / 256.0F;
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        minecraft.getTextureManager().bindTexture(backgroundTexture);
        clampTextureEdges();
        renderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        // Exact GuiMainMenu 1.8.9 mapping: f1 is derived from height and
        // f2 from width. This preserves the panorama's landscape orientation.
        renderer.pos(0.0D, height, 0.0D).tex(0.5F - textureHeight, 0.5F + textureWidth).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
        renderer.pos(width, height, 0.0D).tex(0.5F - textureHeight, 0.5F - textureWidth).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
        renderer.pos(width, 0.0D, 0.0D).tex(0.5F + textureHeight, 0.5F - textureWidth).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
        renderer.pos(0.0D, 0.0D, 0.0D).tex(0.5F + textureHeight, 0.5F + textureWidth).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
        tessellator.draw();
    }

    private void drawPanorama(float partialTicks) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        Project.gluPerspective(120.0F, 1.0F, 0.05F, 10.0F);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.rotate(180.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotate(90.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        final int tiles = 8;
        for (int tile = 0; tile < tiles * tiles; tile++) {
            GlStateManager.pushMatrix();
            float offsetX = ((tile % tiles) / (float) tiles - 0.5F) / 64.0F;
            float offsetY = ((tile / tiles) / (float) tiles - 0.5F) / 64.0F;
            GlStateManager.translate(offsetX, offsetY, 0.0F);
            GlStateManager.rotate(
                    MathHelper.sin((panoramaTimer + partialTicks) / 400.0F) * 25.0F + 20.0F,
                    1.0F,
                    0.0F,
                    0.0F);
            GlStateManager.rotate(-(panoramaTimer + partialTicks) * 0.1F, 0.0F, 1.0F, 0.0F);

            for (int face = 0; face < 6; face++) {
                GlStateManager.pushMatrix();
                rotateForFace(face);
                minecraft.getTextureManager().bindTexture(PANORAMA[face]);
                renderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                int alpha = 255 / (tile + 1);
                renderer.pos(-1.0D, -1.0D, 1.0D).tex(0.0D, 0.0D).color(255, 255, 255, alpha).endVertex();
                renderer.pos(1.0D, -1.0D, 1.0D).tex(1.0D, 0.0D).color(255, 255, 255, alpha).endVertex();
                renderer.pos(1.0D, 1.0D, 1.0D).tex(1.0D, 1.0D).color(255, 255, 255, alpha).endVertex();
                renderer.pos(-1.0D, 1.0D, 1.0D).tex(0.0D, 1.0D).color(255, 255, 255, alpha).endVertex();
                tessellator.draw();
                GlStateManager.popMatrix();
            }
            GlStateManager.popMatrix();
            GlStateManager.colorMask(true, true, true, false);
        }

        renderer.setTranslation(0.0D, 0.0D, 0.0D);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();
    }

    private void rotateAndBlurSkybox(int width, int height) {
        minecraft.getTextureManager().bindTexture(backgroundTexture);
        clampTextureEdges();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, 256, 256);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.colorMask(true, true, true, false);
        GlStateManager.disableAlpha();

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        renderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        final int blurPasses = 3;
        for (int pass = 0; pass < blurPasses; pass++) {
            float alpha = 1.0F / (pass + 1);
            float offset = (pass - blurPasses / 2) / 256.0F;
            renderer.pos(width, height, 0.0D).tex(offset, 1.0D).color(1.0F, 1.0F, 1.0F, alpha).endVertex();
            renderer.pos(width, 0.0D, 0.0D).tex(1.0F + offset, 1.0D).color(1.0F, 1.0F, 1.0F, alpha).endVertex();
            renderer.pos(0.0D, 0.0D, 0.0D).tex(1.0F + offset, 0.0D).color(1.0F, 1.0F, 1.0F, alpha).endVertex();
            renderer.pos(0.0D, height, 0.0D).tex(offset, 0.0D).color(1.0F, 1.0F, 1.0F, alpha).endVertex();
        }
        tessellator.draw();
        GlStateManager.enableAlpha();
        GlStateManager.colorMask(true, true, true, true);
    }

    private static void clampTextureEdges() {
        // Blur taps intentionally sample just outside [0,1]. Repeat wrapping
        // exposed a one-pixel seam at the top/right edge of the framebuffer.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
    }

    private static void rotateForFace(int face) {
        if (face == 1) {
            GlStateManager.rotate(90.0F, 0.0F, 1.0F, 0.0F);
        } else if (face == 2) {
            GlStateManager.rotate(180.0F, 0.0F, 1.0F, 0.0F);
        } else if (face == 3) {
            GlStateManager.rotate(-90.0F, 0.0F, 1.0F, 0.0F);
        } else if (face == 4) {
            GlStateManager.rotate(90.0F, 1.0F, 0.0F, 0.0F);
        } else if (face == 5) {
            GlStateManager.rotate(-90.0F, 1.0F, 0.0F, 0.0F);
        }
    }
}
