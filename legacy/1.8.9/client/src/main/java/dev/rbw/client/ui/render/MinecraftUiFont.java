package dev.rbw.client.ui.render;

import dev.rbw.client.ui.UiFontWeight;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * Runtime-generated, anti-aliased sans-serif atlas for Opus product UI.
 *
 * Java 8 ships the logical SansSerif family with the same runtime used to
 * launch Minecraft, so this path is self-contained and does not depend on a
 * host operating-system font or an HTML/CSS preview renderer.
 */
final class MinecraftUiFont {
    private static final int FIRST_CHARACTER = 32;
    private static final int LAST_CHARACTER = 126;
    private static final int CHARACTER_COUNT = LAST_CHARACTER - FIRST_CHARACTER + 1;
    private static final int BASE_SIZE = 48;
    private static final int CELL_WIDTH = 64;
    private static final int CELL_HEIGHT = 64;
    private static final int COLUMNS = 32;
    private static final int ATLAS_WIDTH = 2048;
    private static final int ATLAS_HEIGHT = 512;

    private static MinecraftUiFont shared;

    private final Minecraft minecraft;
    private final ResourceLocation texture;
    private final float[][] advances = new float[UiFontWeight.values().length][CHARACTER_COUNT];

    private MinecraftUiFont(Minecraft minecraft) {
        this.minecraft = minecraft;
        BufferedImage atlas = buildAtlas();
        DynamicTexture dynamicTexture = new DynamicTexture(atlas);
        texture = minecraft.getTextureManager().getDynamicTextureLocation("rbw-ui-sans", dynamicTexture);
    }

    static synchronized MinecraftUiFont get(Minecraft minecraft) {
        if (shared == null) {
            shared = new MinecraftUiFont(minecraft);
        }
        return shared;
    }

    float measure(String value, float fontSize, UiFontWeight weight, float tracking) {
        if (value == null || value.isEmpty() || fontSize <= 0.0F) {
            return 0.0F;
        }
        float scale = fontSize / BASE_SIZE;
        float width = 0.0F;
        int charactersOnLine = 0;
        float widest = 0.0F;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\n') {
                widest = Math.max(widest, width);
                width = 0.0F;
                charactersOnLine = 0;
                continue;
            }
            int glyph = glyphIndex(character);
            if (charactersOnLine > 0) {
                width += tracking;
            }
            width += advances[weight.ordinal()][glyph] * scale;
            charactersOnLine++;
        }
        return Math.max(widest, width);
    }

    void draw(
            String value,
            float x,
            float y,
            float fontSize,
            UiFontWeight weight,
            float tracking,
            int argb) {
        if (value == null || value.isEmpty() || fontSize <= 0.0F || ((argb >>> 24) & 0xFF) == 0) {
            return;
        }
        minecraft.getTextureManager().bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA,
                GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE,
                GL11.GL_ONE_MINUS_SRC_ALPHA);

        float scale = fontSize / BASE_SIZE;
        float cellWidth = CELL_WIDTH * scale;
        float cellHeight = CELL_HEIGHT * scale;
        float cursorX = x;
        float cursorY = y;
        int charactersOnLine = 0;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        int alpha = (argb >>> 24) & 0xFF;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        renderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\n') {
                cursorX = x;
                cursorY += fontSize * 1.24F;
                charactersOnLine = 0;
                continue;
            }
            int glyph = glyphIndex(character);
            if (charactersOnLine > 0) {
                cursorX += tracking;
            }
            if (character != ' ') {
                int atlasIndex = weight.ordinal() * CHARACTER_COUNT + glyph;
                int column = atlasIndex % COLUMNS;
                int row = atlasIndex / COLUMNS;
                // Half-texel gutters prevent adjacent atlas cells bleeding
                // into the final glyph under GL_LINEAR downsampling.
                double u0 = (column * CELL_WIDTH + 0.5D) / ATLAS_WIDTH;
                double v0 = (row * CELL_HEIGHT + 0.5D) / ATLAS_HEIGHT;
                double u1 = ((column + 1) * CELL_WIDTH - 0.5D) / ATLAS_WIDTH;
                double v1 = ((row + 1) * CELL_HEIGHT - 0.5D) / ATLAS_HEIGHT;
                coloredVertex(renderer, cursorX, cursorY + cellHeight, u0, v1, red, green, blue, alpha);
                coloredVertex(renderer, cursorX + cellWidth, cursorY + cellHeight, u1, v1, red, green, blue, alpha);
                coloredVertex(renderer, cursorX + cellWidth, cursorY, u1, v0, red, green, blue, alpha);
                coloredVertex(renderer, cursorX, cursorY, u0, v0, red, green, blue, alpha);
            }
            cursorX += advances[weight.ordinal()][glyph] * scale;
            charactersOnLine++;
        }
        tessellator.draw();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private BufferedImage buildAtlas() {
        BufferedImage image = new BufferedImage(ATLAS_WIDTH, ATLAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setBackground(new Color(0, 0, 0, 0));
            graphics.clearRect(0, 0, ATLAS_WIDTH, ATLAS_HEIGHT);
            graphics.setColor(Color.WHITE);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            for (UiFontWeight weight : UiFontWeight.values()) {
                // Avenir Next is the native macOS UI face. The logical fallback
                // keeps the shipped Java 8 runtime functional on other hosts.
                Font font = new Font(
                        "Avenir Next",
                        weight == UiFontWeight.SEMIBOLD ? Font.BOLD : Font.PLAIN,
                        BASE_SIZE);
                graphics.setFont(font);
                FontMetrics metrics = graphics.getFontMetrics(font);
                int baselineOffset = (CELL_HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent();
                for (int glyph = 0; glyph < CHARACTER_COUNT; glyph++) {
                    int atlasIndex = weight.ordinal() * CHARACTER_COUNT + glyph;
                    int column = atlasIndex % COLUMNS;
                    int row = atlasIndex / COLUMNS;
                    char character = (char) (FIRST_CHARACTER + glyph);
                    int cellX = column * CELL_WIDTH;
                    int cellY = row * CELL_HEIGHT;
                    graphics.drawString(String.valueOf(character), cellX + 2, cellY + baselineOffset);
                    advances[weight.ordinal()][glyph] = Math.max(1.0F, metrics.charWidth(character));
                }
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static int glyphIndex(char character) {
        char safe = character < FIRST_CHARACTER || character > LAST_CHARACTER ? '?' : character;
        return safe - FIRST_CHARACTER;
    }

    private static void coloredVertex(
            WorldRenderer renderer,
            double x,
            double y,
            double u,
            double v,
            int red,
            int green,
            int blue,
            int alpha) {
        renderer.pos(x, y, 0.0D).tex(u, v).color(red, green, blue, alpha).endVertex();
    }
}
