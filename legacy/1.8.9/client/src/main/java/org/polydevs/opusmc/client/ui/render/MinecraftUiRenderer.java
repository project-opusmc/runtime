package org.polydevs.opusmc.client.ui.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiRenderer;

/** Minecraft/OpenGL backend used only by live HUD widgets and editor chrome. */
public final class MinecraftUiRenderer implements UiRenderer {
    private final Minecraft minecraft;
    private final GlStateGuard stateGuard = new GlStateGuard();
    private boolean frameTransformActive;

    public MinecraftUiRenderer(Minecraft minecraft) {
        if (minecraft == null) {
            throw new IllegalArgumentException("minecraft is required");
        }
        this.minecraft = minecraft;
    }

    @Override
    public void beginFrame(UiBounds viewport) {
        stateGuard.begin();
        GlStateManager.pushMatrix();
        GlStateManager.scale(
                MinecraftUiScale.matrixScaleX(minecraft, viewport),
                MinecraftUiScale.matrixScaleY(minecraft, viewport),
                1.0F);
        frameTransformActive = true;
    }

    @Override
    public void endFrame() {
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
    public void roundedRect(UiBounds bounds, int radius, int argb) {
        if (bounds.width == 0 || bounds.height == 0 || ((argb >>> 24) & 0xFF) == 0) {
            return;
        }
        int amount = Math.max(0, Math.min(radius, Math.min(bounds.width, bounds.height) / 2));
        if (amount == 0) {
            fill(bounds, argb);
            return;
        }
        int straightHeight = bounds.height - amount * 2;
        if (straightHeight > 0) {
            fill(new UiBounds(bounds.x, bounds.y + amount, bounds.width, straightHeight), argb);
        }
        for (int row = 0; row < amount; row++) {
            double vertical = amount - row - 0.5D;
            int inset = Math.max(
                    0,
                    amount - (int) Math.ceil(Math.sqrt(amount * amount - vertical * vertical)));
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

    private FontRenderer font() {
        if (minecraft.fontRendererObj == null) {
            throw new IllegalStateException("Minecraft FontRenderer is unavailable");
        }
        return minecraft.fontRendererObj;
    }

    private static void vertexColor(WorldRenderer renderer, double x, double y, int argb) {
        renderer.pos(x, y, 0.0D).color(
                (argb >>> 16) & 0xFF,
                (argb >>> 8) & 0xFF,
                argb & 0xFF,
                (argb >>> 24) & 0xFF).endVertex();
    }
}
