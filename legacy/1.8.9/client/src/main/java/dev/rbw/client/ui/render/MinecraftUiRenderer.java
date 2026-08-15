package dev.rbw.client.ui.render;

import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.UiFontWeight;
import dev.rbw.client.ui.UiRenderer;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/** Minecraft/OpenGL backend for UiRenderer. */
public final class MinecraftUiRenderer implements UiRenderer {
    private final Minecraft minecraft;
    private final GlStateGuard stateGuard = new GlStateGuard();
    private final Deque<UiBounds> clips = new ArrayDeque<UiBounds>();
    private UiBounds viewport = new UiBounds(0, 0, 0, 0);
    private boolean frameTransformActive;
    private MinecraftUiFont uiFont;

    public MinecraftUiRenderer(Minecraft minecraft) {
        if (minecraft == null) {
            throw new IllegalArgumentException("minecraft is required");
        }
        this.minecraft = minecraft;
    }

    @Override
    public void beginFrame(UiBounds nextViewport) {
        viewport = nextViewport;
        clips.clear();
        stateGuard.begin();
        GlStateManager.pushMatrix();
        float scale = MinecraftUiScale.matrixScale(minecraft);
        GlStateManager.scale(scale, scale, 1.0F);
        frameTransformActive = true;
    }

    @Override
    public void endFrame() {
        clips.clear();
        if (frameTransformActive) {
            GlStateManager.popMatrix();
            frameTransformActive = false;
        }
        stateGuard.end();
    }

    @Override
    public void fill(UiBounds bounds, int argb) {
        Gui.drawRect(bounds.x, bounds.y, bounds.right(), bounds.bottom(), argb);
    }

    @Override
    public void verticalGradient(UiBounds bounds, int topArgb, int bottomArgb) {
        if (bounds.width == 0 || bounds.height == 0) {
            return;
        }
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        worldRenderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        vertexColor(worldRenderer, bounds.x, bounds.bottom(), bottomArgb);
        vertexColor(worldRenderer, bounds.right(), bounds.bottom(), bottomArgb);
        vertexColor(worldRenderer, bounds.right(), bounds.y, topArgb);
        vertexColor(worldRenderer, bounds.x, bounds.y, topArgb);
        tessellator.draw();
        GlStateManager.enableTexture2D();
    }

    @Override
    public void horizontalGradient(UiBounds bounds, int leftArgb, int rightArgb) {
        if (bounds.width == 0 || bounds.height == 0) {
            return;
        }
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        worldRenderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        vertexColor(worldRenderer, bounds.x, bounds.bottom(), leftArgb);
        vertexColor(worldRenderer, bounds.right(), bounds.bottom(), rightArgb);
        vertexColor(worldRenderer, bounds.right(), bounds.y, rightArgb);
        vertexColor(worldRenderer, bounds.x, bounds.y, leftArgb);
        tessellator.draw();
        GlStateManager.enableTexture2D();
    }

    @Override
    public void radialGradient(
            float centerX,
            float centerY,
            float radiusX,
            float radiusY,
            int innerArgb,
            int outerArgb) {
        if (radiusX <= 0.0F || radiusY <= 0.0F) {
            return;
        }
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        renderer.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        vertexColor(renderer, centerX, centerY, innerArgb);
        final int segments = 48;
        for (int segment = 0; segment <= segments; segment++) {
            double angle = Math.PI * 2.0D * segment / segments;
            vertexColor(
                    renderer,
                    centerX + Math.cos(angle) * radiusX,
                    centerY + Math.sin(angle) * radiusY,
                    outerArgb);
        }
        tessellator.draw();
        GlStateManager.enableTexture2D();
    }

    @Override
    public void roundedRect(UiBounds bounds, int radius, int argb) {
        if (bounds.width == 0 || bounds.height == 0 || ((argb >>> 24) & 0xFF) == 0) {
            return;
        }
        int amount = Math.max(0, Math.min(radius, Math.min(bounds.width, bounds.height) / 2));
        if (amount == 0) {
            fill(bounds, argb);
            return;
        }
        // Rasterize a single non-overlapping silhouette. Multiple translucent
        // quads darken one another in Minecraft's blend pipeline, while an
        // OpenGL fan is vulnerable to the game's active cull state.
        int straightHeight = bounds.height - amount * 2;
        if (straightHeight > 0) {
            // The straight middle is exactly one quad. Splitting it into
            // central and side strips leaves translucent seams at the joins.
            fill(new UiBounds(bounds.x, bounds.y + amount, bounds.width, straightHeight), argb);
        }
        for (int row = 0; row < amount; row++) {
            double vertical = amount - row - 0.5D;
            int inset = Math.max(0, amount - (int) Math.ceil(Math.sqrt(amount * amount - vertical * vertical)));
            int rowWidth = bounds.width - inset * 2;
            if (rowWidth <= 0) {
                continue;
            }
            fill(new UiBounds(bounds.x + inset, bounds.y + row, rowWidth, 1), argb);
            fill(new UiBounds(bounds.x + inset, bounds.bottom() - row - 1, rowWidth, 1), argb);
        }
    }

    @Override
    public void border(UiBounds bounds, int thickness, int argb) {
        int amount = Math.max(1, thickness);
        fill(new UiBounds(bounds.x, bounds.y, bounds.width, amount), argb);
        fill(new UiBounds(bounds.x, bounds.bottom() - amount, bounds.width, amount), argb);
        fill(new UiBounds(bounds.x, bounds.y, amount, bounds.height), argb);
        fill(new UiBounds(bounds.right() - amount, bounds.y, amount, bounds.height), argb);
    }

    @Override
    public void text(String value, int x, int y, int argb) {
        font().drawString(value, x, y, argb);
    }

    @Override
    public void uiText(
            String value,
            float x,
            float y,
            float fontSize,
            UiFontWeight weight,
            float tracking,
            int argb) {
        productFont().draw(value, x, y, fontSize, weight, tracking, argb);
    }

    @Override
    public float measureUiText(String value, float fontSize, UiFontWeight weight, float tracking) {
        return productFont().measure(value, fontSize, weight, tracking);
    }

    @Override
    public void centeredText(String value, UiBounds bounds, int argb) {
        int x = bounds.x + (bounds.width - measureText(value)) / 2;
        int y = bounds.y + (bounds.height - lineHeight()) / 2;
        text(value, x, y, argb);
    }

    @Override
    public int measureText(String value) {
        return font().getStringWidth(value);
    }

    @Override
    public int lineHeight() {
        return font().FONT_HEIGHT;
    }

    @Override
    public void line(float x0, float y0, float x1, float y1, float thickness, int argb) {
        if (thickness <= 0.0F || ((argb >>> 24) & 0xFF) == 0) {
            return;
        }
        double dx = x1 - x0;
        double dy = y1 - y0;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 0.001D) {
            return;
        }
        double offsetX = -dy / length * thickness / 2.0D;
        double offsetY = dx / length * thickness / 2.0D;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        renderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        vertexColor(renderer, x0 + offsetX, y0 + offsetY, argb);
        vertexColor(renderer, x1 + offsetX, y1 + offsetY, argb);
        vertexColor(renderer, x1 - offsetX, y1 - offsetY, argb);
        vertexColor(renderer, x0 - offsetX, y0 - offsetY, argb);
        tessellator.draw();
        GlStateManager.enableTexture2D();
    }

    @Override
    public void ring(float centerX, float centerY, float radius, float thickness, int argb) {
        if (radius <= 0.0F || thickness <= 0.0F || ((argb >>> 24) & 0xFF) == 0) {
            return;
        }
        float outer = radius + thickness / 2.0F;
        float inner = Math.max(0.0F, radius - thickness / 2.0F);
        final int segments = 32;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        renderer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int segment = 0; segment <= segments; segment++) {
            double angle = Math.PI * 2.0D * segment / segments;
            double cosine = Math.cos(angle);
            double sine = Math.sin(angle);
            vertexColor(renderer, centerX + cosine * outer, centerY + sine * outer, argb);
            vertexColor(renderer, centerX + cosine * inner, centerY + sine * inner, argb);
        }
        tessellator.draw();
        GlStateManager.enableTexture2D();
    }

    @Override
    public void pushTransform() {
        GlStateManager.pushMatrix();
    }

    @Override
    public void popTransform() {
        GlStateManager.popMatrix();
    }

    @Override
    public void scale(float x, float y) {
        GlStateManager.scale(x, y, 1.0F);
    }

    @Override
    public void texture(ResourceLocation texture, UiBounds destination, int sourceWidth, int sourceHeight) {
        drawTexture(texture, destination, 0.0D, 0.0D, 1.0D, 1.0D, 0xFFFFFFFF);
    }

    @Override
    public void textureCover(ResourceLocation texture, UiBounds destination, int sourceWidth, int sourceHeight) {
        double sourceAspect = sourceWidth / (double) Math.max(1, sourceHeight);
        double destinationAspect = destination.width / (double) Math.max(1, destination.height);
        double u0 = 0.0D;
        double v0 = 0.0D;
        double u1 = 1.0D;
        double v1 = 1.0D;
        if (destinationAspect > sourceAspect) {
            double visibleHeight = sourceAspect / destinationAspect;
            v0 = (1.0D - visibleHeight) / 2.0D;
            v1 = 1.0D - v0;
        } else if (destinationAspect < sourceAspect) {
            double visibleWidth = destinationAspect / sourceAspect;
            u0 = (1.0D - visibleWidth) / 2.0D;
            u1 = 1.0D - u0;
        }
        drawTexture(texture, destination, u0, v0, u1, v1, 0xFFFFFFFF);
    }

    @Override
    public void textureRegion(
            ResourceLocation texture,
            UiBounds destination,
            double u0,
            double v0,
            double u1,
            double v1) {
        drawTexture(texture, destination, u0, v0, u1, v1, 0xFFFFFFFF);
    }

    @Override
    public void textureTint(
            ResourceLocation texture,
            UiBounds destination,
            int sourceWidth,
            int sourceHeight,
            int tintArgb) {
        drawTexture(texture, destination, 0.0D, 0.0D, 1.0D, 1.0D, tintArgb);
    }

    private void drawTexture(
            ResourceLocation texture,
            UiBounds destination,
            double u0,
            double v0,
            double u1,
            double v1,
            int tintArgb) {
        minecraft.getTextureManager().bindTexture(texture);
        GlStateManager.color(
                ((tintArgb >>> 16) & 0xFF) / 255.0F,
                ((tintArgb >>> 8) & 0xFF) / 255.0F,
                (tintArgb & 0xFF) / 255.0F,
                ((tintArgb >>> 24) & 0xFF) / 255.0F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        worldRenderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        worldRenderer.pos(destination.x, destination.bottom(), 0.0D).tex(u0, v1).endVertex();
        worldRenderer.pos(destination.right(), destination.bottom(), 0.0D).tex(u1, v1).endVertex();
        worldRenderer.pos(destination.right(), destination.y, 0.0D).tex(u1, v0).endVertex();
        worldRenderer.pos(destination.x, destination.y, 0.0D).tex(u0, v0).endVertex();
        tessellator.draw();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void pushClip(UiBounds requested) {
        UiBounds effective = clips.isEmpty() ? requested.intersect(viewport) : requested.intersect(clips.peek());
        clips.push(effective);
        applyScissor(effective);
    }

    @Override
    public void popClip() {
        if (clips.isEmpty()) {
            throw new IllegalStateException("clip stack underflow");
        }
        clips.pop();
        if (clips.isEmpty()) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        } else {
            applyScissor(clips.peek());
        }
    }

    private FontRenderer font() {
        if (minecraft.fontRendererObj == null) {
            throw new IllegalStateException("Minecraft FontRenderer is unavailable");
        }
        return minecraft.fontRendererObj;
    }

    private MinecraftUiFont productFont() {
        if (uiFont == null) {
            uiFont = MinecraftUiFont.get(minecraft);
        }
        return uiFont;
    }

    private static void vertexColor(WorldRenderer renderer, int x, int y, int argb) {
        vertexColor(renderer, (double) x, (double) y, argb);
    }

    private static void vertexColor(WorldRenderer renderer, double x, double y, int argb) {
        renderer.pos(x, y, 0.0D).color(
                (argb >>> 16) & 0xFF,
                (argb >>> 8) & 0xFF,
                argb & 0xFF,
                (argb >>> 24) & 0xFF).endVertex();
    }

    private void applyScissor(UiBounds bounds) {
        float scaleX = viewport.width == 0 ? 1.0F : (float) Display.getWidth() / viewport.width;
        float scaleY = viewport.height == 0 ? 1.0F : (float) Display.getHeight() / viewport.height;
        int x = Math.round(bounds.x * scaleX);
        int y = Display.getHeight() - Math.round(bounds.bottom() * scaleY);
        int width = Math.round(bounds.width * scaleX);
        int height = Math.round(bounds.height * scaleY);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x, y, Math.max(0, width), Math.max(0, height));
    }
}
