package org.polydevs.opusmc.client.ui.render;

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
 * Runtime-generated monospace atlas so the in-game Opus UI can render a
 * terminal/TUI aesthetic (fixed cell grid, box-drawing frames) that matches the
 * desktop launcher, using only the bundled Java 8 runtime fonts.
 *
 * <p>All printable ASCII plus the box-drawing characters used by the launcher
 * panes are baked into one atlas. Every glyph shares a single advance so the
 * caller can address the surface as a character grid.</p>
 */
final class MinecraftMonoFont {
    // Printable ASCII (32..126) followed by the box-drawing glyphs the panes
    // use. Keeping them contiguous lets the atlas stay a simple grid.
    private static final char[] EXTRA_GLYPHS = {
        '\u2500', // ─ horizontal
        '\u2502', // │ vertical
        '\u250C', // ┌
        '\u2510', // ┐
        '\u2514', // └
        '\u2518', // ┘
        '\u251C', // ├
        '\u2524', // ┤
        '\u25B8', // ▸ caret
        '\u2022', // • bullet
        '\u00B7', // · middot
    };
    private static final int FIRST_ASCII = 32;
    private static final int LAST_ASCII = 126;
    private static final int ASCII_COUNT = LAST_ASCII - FIRST_ASCII + 1;
    private static final int GLYPH_COUNT = ASCII_COUNT + EXTRA_GLYPHS.length;
    private static final int BASE_SIZE = 48;
    private static final int CELL_WIDTH = 40;
    private static final int CELL_HEIGHT = 64;
    private static final int COLUMNS = 32;
    private static final int ROWS = (GLYPH_COUNT + COLUMNS - 1) / COLUMNS;
    private static final int ATLAS_WIDTH = COLUMNS * CELL_WIDTH;
    private static final int ATLAS_HEIGHT = ROWS * CELL_HEIGHT;

    private static MinecraftMonoFont shared;

    private final Minecraft minecraft;
    private final ResourceLocation texture;
    // A monospace face has a uniform advance; store it in base-size pixels.
    private float cellAdvanceBase;

    private MinecraftMonoFont(Minecraft minecraft) {
        this.minecraft = minecraft;
        BufferedImage atlas = buildAtlas();
        DynamicTexture dynamicTexture = new DynamicTexture(atlas);
        texture = minecraft.getTextureManager().getDynamicTextureLocation("opus-ui-mono", dynamicTexture);
    }

    static synchronized MinecraftMonoFont get(Minecraft minecraft) {
        if (shared == null) {
            shared = new MinecraftMonoFont(minecraft);
        }
        return shared;
    }

    /** Character cell width in pixels for the given font size. */
    float cellWidth(float fontSize) {
        return cellAdvanceBase * (fontSize / BASE_SIZE);
    }

    /** Line height in pixels for the given font size. */
    float lineHeight(float fontSize) {
        return fontSize * 1.35F;
    }

    float measure(String value, float fontSize) {
        if (value == null || value.isEmpty() || fontSize <= 0.0F) {
            return 0.0F;
        }
        int longest = 0;
        int current = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\n') {
                longest = Math.max(longest, current);
                current = 0;
            } else {
                current++;
            }
        }
        longest = Math.max(longest, current);
        return longest * cellWidth(fontSize);
    }

    void draw(String value, float x, float y, float fontSize, int argb) {
        if (value == null || value.isEmpty() || fontSize <= 0.0F || ((argb >>> 24) & 0xFF) == 0) {
            return;
        }
        minecraft.getTextureManager().bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);

        float scale = fontSize / BASE_SIZE;
        float cellW = CELL_WIDTH * scale;
        float cellH = CELL_HEIGHT * scale;
        float advance = cellWidth(fontSize);
        // Centre the fixed atlas cell over the tighter advance box so glyphs sit
        // on a true monospace grid without drifting.
        float cellOffset = (advance - cellW) / 2.0F;
        float cursorX = x;
        float cursorY = y;
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
                cursorY += lineHeight(fontSize);
                continue;
            }
            int glyph = glyphIndex(character);
            if (glyph >= 0 && character != ' ') {
                int column = glyph % COLUMNS;
                int row = glyph / COLUMNS;
                double u0 = (column * CELL_WIDTH + 0.5D) / ATLAS_WIDTH;
                double v0 = (row * CELL_HEIGHT + 0.5D) / ATLAS_HEIGHT;
                double u1 = ((column + 1) * CELL_WIDTH - 0.5D) / ATLAS_WIDTH;
                double v1 = ((row + 1) * CELL_HEIGHT - 0.5D) / ATLAS_HEIGHT;
                float gx = cursorX + cellOffset;
                coloredVertex(renderer, gx, cursorY + cellH, u0, v1, red, green, blue, alpha);
                coloredVertex(renderer, gx + cellW, cursorY + cellH, u1, v1, red, green, blue, alpha);
                coloredVertex(renderer, gx + cellW, cursorY, u1, v0, red, green, blue, alpha);
                coloredVertex(renderer, gx, cursorY, u0, v0, red, green, blue, alpha);
            }
            cursorX += advance;
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
            // Menlo is the native macOS monospace face; the logical Monospaced
            // family keeps the shipped Java 8 runtime working on other hosts.
            Font font = new Font("Menlo", Font.PLAIN, BASE_SIZE);
            if (!"Menlo".equalsIgnoreCase(font.getFamily())) {
                font = new Font(Font.MONOSPACED, Font.PLAIN, BASE_SIZE);
            }
            graphics.setFont(font);
            FontMetrics metrics = graphics.getFontMetrics(font);
            cellAdvanceBase = Math.max(1.0F, metrics.charWidth('M'));
            int baselineOffset = (CELL_HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent();
            for (int glyph = 0; glyph < GLYPH_COUNT; glyph++) {
                char character = glyphCharacter(glyph);
                int column = glyph % COLUMNS;
                int row = glyph / COLUMNS;
                int cellX = column * CELL_WIDTH;
                int cellY = row * CELL_HEIGHT;
                int glyphWidth = metrics.charWidth(character);
                int drawX = cellX + Math.max(0, (CELL_WIDTH - glyphWidth) / 2);
                graphics.drawString(String.valueOf(character), drawX, cellY + baselineOffset);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static char glyphCharacter(int glyphIndex) {
        if (glyphIndex < ASCII_COUNT) {
            return (char) (FIRST_ASCII + glyphIndex);
        }
        return EXTRA_GLYPHS[glyphIndex - ASCII_COUNT];
    }

    private static int glyphIndex(char character) {
        if (character >= FIRST_ASCII && character <= LAST_ASCII) {
            return character - FIRST_ASCII;
        }
        for (int index = 0; index < EXTRA_GLYPHS.length; index++) {
            if (EXTRA_GLYPHS[index] == character) {
                return ASCII_COUNT + index;
            }
        }
        return glyphIndex('?');
    }

    private static void coloredVertex(
            WorldRenderer renderer, double x, double y, double u, double v,
            int red, int green, int blue, int alpha) {
        renderer.pos(x, y, 0.0D).tex(u, v).color(red, green, blue, alpha).endVertex();
    }
}
