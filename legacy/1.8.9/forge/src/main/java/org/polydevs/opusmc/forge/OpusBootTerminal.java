package org.polydevs.opusmc.forge;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/**
 * Draws the Opus boot screen in place of the vanilla Mojang splash.
 *
 * <p>This runs at the exact point Minecraft used to blit
 * {@code textures/gui/title/mojang.png}, which is before the game's own font
 * renderer exists. It therefore paints a self-contained terminal (TUI) frame
 * with plain OpenGL plus an AWT-baked texture: an opaque black surface, a
 * header rule, a boot-log pane, and a footer, matching the Opus launcher and
 * the in-game title aesthetic. No Minecraft rendering classes are required, so
 * the screen is safe to draw during early Forge/OptiFine startup.</p>
 *
 * <p>Every operation is guarded. If anything fails (no GL context, AWT
 * unavailable, and so on) the method returns quietly and the game keeps
 * loading. Replacing the Mojang draw never blocks or crashes the managed
 * launch.</p>
 */
public final class OpusBootTerminal {
    private static final int BACKGROUND = 0xFF000000;
    private static final int SURFACE = 0xFF050505;
    private static final int BORDER_STRONG = 0xFF565656;
    private static final int BORDER_SOFT = 0xFF262626;
    private static final int ACCENT = 0xFF55A7FF;
    private static final int FOREGROUND = 0xFFF5F5F5;
    private static final int MUTED = 0xFFB5B5B5;
    private static final int QUIET = 0xFF707070;
    private static final int OK = 0xFF64C98A;
    private static long lastLoadingFrame;

    private OpusBootTerminal() {
    }

    /**
     * Invoked by the injected bytecode that replaced
     * {@code Minecraft.drawSplashScreen}. Intentionally parameterless and
     * static so the transformer can drop the whole vanilla body and call this.
     */
    public static void render() {
        try {
            int width = Display.getWidth();
            int height = Display.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            BufferedImage frame = paint(width, height);
            int texture = uploadTexture(frame);
            if (texture == 0) {
                return;
            }
            try {
                blitFullscreen(texture, width, height);
                Display.update();
            } finally {
                GL11.glDeleteTextures(texture);
            }
        } catch (Throwable failure) {
            // Never let the boot screen break the managed launch.
            System.out.println("[OPUS/BOOT] terminal splash skipped: " + failure);
        }
    }

    /**
     * Replaces Minecraft's dirt-texture progress renderer while worlds,
     * resource packs and server terrain are being prepared.
     */
    public static void renderLoading(String headline, String detail, int progress) {
        try {
            long now = System.currentTimeMillis();
            if (progress < 100 && now - lastLoadingFrame < 75L) {
                return;
            }
            lastLoadingFrame = now;
            int width = Display.getWidth();
            int height = Display.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            BufferedImage frame = paintLoading(width, height, headline, detail, progress);
            int texture = uploadTexture(frame);
            if (texture == 0) {
                return;
            }
            try {
                blitFullscreen(texture, width, height);
                Display.update();
            } finally {
                GL11.glDeleteTextures(texture);
            }
        } catch (Throwable failure) {
            System.out.println("[OPUS/LOAD] terminal progress skipped: " + failure);
        }
    }

    private static BufferedImage paintLoading(
            int width, int height, String headline, String detail, int progress) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(
                    RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            fill(graphics, 0, 0, width, height, BACKGROUND);

            float scale = Math.max(1.0F, height / 320.0F);
            Font titleFont = monospace(Math.round(15 * scale));
            Font bodyFont = monospace(Math.round(12 * scale));
            Font smallFont = monospace(Math.round(11 * scale));
            int pad = Math.round(18 * scale);
            int headerHeight = Math.round(42 * scale);
            int footerHeight = Math.round(30 * scale);
            int rule = Math.max(1, Math.round(scale));

            fill(graphics, 0, 0, width, headerHeight, SURFACE);
            fill(graphics, 0, headerHeight - rule, width, rule, BORDER_STRONG);
            int baseline = (headerHeight + titleFont.getSize()) / 2 - Math.round(2 * scale);
            drawText(graphics, "OPUS CLIENT", titleFont, pad, baseline, FOREGROUND);
            int titleWidth = stringWidth(graphics, "OPUS CLIENT", titleFont);
            int cell = Math.max(1, Math.round(charWidth(graphics, titleFont)));
            drawText(graphics, "|", titleFont, pad + titleWidth + cell, baseline, BORDER_STRONG);
            drawText(graphics, "RUNTIME", titleFont, pad + titleWidth + cell * 3, baseline, ACCENT);
            String right = "FORGE 1.8.9 + OPTIFINE";
            drawText(
                    graphics,
                    right,
                    bodyFont,
                    width - pad - stringWidth(graphics, right, bodyFont),
                    baseline,
                    MUTED);

            int bodyTop = headerHeight + pad;
            int bodyBottom = height - footerHeight - pad;
            int paneWidth = Math.max(1, width - pad * 2);
            int paneHeight = Math.max(1, bodyBottom - bodyTop);
            fill(graphics, pad, bodyTop, paneWidth, paneHeight, SURFACE);
            drawBorder(graphics, pad, bodyTop, paneWidth, paneHeight, rule, BORDER_STRONG);
            int paneHeader = Math.round(26 * scale);
            drawText(
                    graphics,
                    "SESSION PIPELINE",
                    smallFont,
                    pad + cell,
                    bodyTop + paneHeader - Math.round(7 * scale),
                    QUIET);
            fill(
                    graphics,
                    pad + rule,
                    bodyTop + paneHeader,
                    paneWidth - rule * 2,
                    rule,
                    BORDER_SOFT);

            String safeHeadline = concise(headline, 72, "Preparing game session");
            String safeDetail = concise(detail, 84, "Waiting for Minecraft runtime");
            String[][] lines = {
                {"[ OK ]", "Forge 11.15.1.2318 attached"},
                {"[ OK ]", "OptiFine HD U M5 attached"},
                {"[ RUN]", safeHeadline},
                {"[ .. ]", safeDetail},
            };
            int lineHeight = Math.round(bodyFont.getSize() * 1.7F);
            int textLeft = pad + cell * 2;
            int y = bodyTop + paneHeader + lineHeight;
            int tagWidth = stringWidth(graphics, "[ OK ] ", bodyFont);
            for (int index = 0; index < lines.length; index++) {
                drawText(
                        graphics,
                        lines[index][0],
                        bodyFont,
                        textLeft,
                        y,
                        index < 2 ? OK : ACCENT);
                drawText(
                        graphics,
                        lines[index][1],
                        bodyFont,
                        textLeft + tagWidth,
                        y,
                        index < 2 ? MUTED : FOREGROUND);
                y += lineHeight;
            }

            int barX = textLeft;
            int barY = y + Math.round(4 * scale);
            int barWidth = Math.max(80, paneWidth - cell * 4);
            int barHeight = Math.max(4, Math.round(6 * scale));
            fill(graphics, barX, barY, barWidth, barHeight, BORDER_SOFT);
            if (progress >= 0) {
                int bounded = Math.max(0, Math.min(100, progress));
                fill(graphics, barX, barY, Math.round(barWidth * bounded / 100.0F), barHeight, ACCENT);
                String percent = bounded + "%";
                drawText(
                        graphics,
                        percent,
                        smallFont,
                        barX + barWidth - stringWidth(graphics, percent, smallFont),
                        barY + barHeight + smallFont.getSize() + Math.round(4 * scale),
                        MUTED);
            } else {
                int pulseWidth = Math.max(16, barWidth / 6);
                int travel = Math.max(1, barWidth - pulseWidth);
                int pulse = (int) ((System.currentTimeMillis() / 8L) % (travel * 2L));
                if (pulse > travel) {
                    pulse = travel * 2 - pulse;
                }
                fill(graphics, barX + pulse, barY, pulseWidth, barHeight, ACCENT);
            }

            int footerTop = height - footerHeight;
            fill(graphics, 0, footerTop, width, footerHeight, SURFACE);
            fill(graphics, 0, footerTop, width, rule, BORDER_STRONG);
            drawText(
                    graphics,
                    "Do not close this window   OPUS is preparing the session",
                    smallFont,
                    pad,
                    height - Math.round(10 * scale),
                    QUIET);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static String concise(String value, int maxLength, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        String clean = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return clean.length() <= maxLength
                ? clean
                : clean.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static BufferedImage paint(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(
                    RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

            fill(graphics, 0, 0, width, height, BACKGROUND);

            // Scale the terminal to the window so cells stay readable on Retina.
            float scale = Math.max(1.0F, height / 320.0F);
            Font titleFont = monospace(Math.round(15 * scale));
            Font bodyFont = monospace(Math.round(12 * scale));
            Font smallFont = monospace(Math.round(11 * scale));

            int pad = Math.round(18 * scale);
            int headerHeight = Math.round(42 * scale);
            int footerHeight = Math.round(30 * scale);
            int ruleThickness = Math.max(1, Math.round(scale));

            // Header.
            fill(graphics, 0, 0, width, headerHeight, SURFACE);
            fill(graphics, 0, headerHeight - ruleThickness, width, ruleThickness, BORDER_STRONG);
            int titleBaseline = (headerHeight + titleFont.getSize()) / 2 - Math.round(2 * scale);
            drawText(graphics, "OPUS CLIENT", titleFont, pad, titleBaseline, FOREGROUND);
            int titleWidth = stringWidth(graphics, "OPUS CLIENT", titleFont);
            int cell = Math.max(1, Math.round(charWidth(graphics, titleFont)));
            drawText(graphics, "|", titleFont, pad + titleWidth + cell, titleBaseline, BORDER_STRONG);
            drawText(graphics, "BOOT", titleFont, pad + titleWidth + cell * 3, titleBaseline, ACCENT);
            String headerRight = "UNIFIED CLIENT v0.0.1";
            drawText(
                    graphics,
                    headerRight,
                    bodyFont,
                    width - pad - stringWidth(graphics, headerRight, bodyFont),
                    titleBaseline,
                    MUTED);

            // Body pane.
            int bodyTop = headerHeight + pad;
            int bodyBottom = height - footerHeight - pad;
            int paneLeft = pad;
            int paneRight = width - pad;
            int paneWidth = Math.max(1, paneRight - paneLeft);
            int paneHeight = Math.max(1, bodyBottom - bodyTop);
            fill(graphics, paneLeft, bodyTop, paneWidth, paneHeight, SURFACE);
            drawBorder(graphics, paneLeft, bodyTop, paneWidth, paneHeight, ruleThickness, BORDER_STRONG);
            int paneHeaderHeight = Math.round(26 * scale);
            drawText(
                    graphics,
                    "LOADING",
                    smallFont,
                    paneLeft + cell,
                    bodyTop + paneHeaderHeight - Math.round(7 * scale),
                    QUIET);
            fill(
                    graphics,
                    paneLeft + ruleThickness,
                    bodyTop + paneHeaderHeight,
                    paneWidth - ruleThickness * 2,
                    ruleThickness,
                    BORDER_SOFT);

            // Boot log lines describing the real managed launch pipeline.
            String[][] lines = {
                {"ok", "Opus Forge coremod registered"},
                {"ok", "OptiFine HD U M5 detected"},
                {"ok", "Minecraft 1.8.9 runtime mounted"},
                {"run", "Loading Forge mod containers"},
                {"run", "Baking resources and textures"},
                {"run", "Preparing render pipeline"},
            };
            int lineHeight = Math.round(bodyFont.getSize() * 1.7F);
            int textLeft = paneLeft + cell * 2;
            int lineY = bodyTop + paneHeaderHeight + lineHeight;
            int tagWidth = stringWidth(graphics, "[ OK ] ", bodyFont);
            for (String[] line : lines) {
                boolean done = "ok".equals(line[0]);
                String tag = done ? "[ OK ]" : "[ .. ]";
                drawText(graphics, tag, bodyFont, textLeft, lineY, done ? OK : ACCENT);
                drawText(graphics, line[1], bodyFont, textLeft + tagWidth, lineY, done ? MUTED : FOREGROUND);
                lineY += lineHeight;
            }

            // Prompt line with a block cursor.
            drawText(graphics, ">", bodyFont, textLeft, lineY, ACCENT);
            int caretX = textLeft + stringWidth(graphics, "> ", bodyFont);
            fill(graphics, caretX, lineY - bodyFont.getSize(), cell, bodyFont.getSize(), FOREGROUND);

            // Footer.
            int footerTop = height - footerHeight;
            fill(graphics, 0, footerTop, width, footerHeight, SURFACE);
            fill(graphics, 0, footerTop, width, ruleThickness, BORDER_STRONG);
            drawText(
                    graphics,
                    "Preparing game session   please wait",
                    smallFont,
                    pad,
                    height - Math.round(10 * scale),
                    QUIET);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static Font monospace(int size) {
        int safeSize = Math.max(8, size);
        Font font = new Font("Menlo", Font.PLAIN, safeSize);
        if (!"Menlo".equalsIgnoreCase(font.getFamily())) {
            font = new Font(Font.MONOSPACED, Font.PLAIN, safeSize);
        }
        return font;
    }

    private static void fill(Graphics2D graphics, int x, int y, int width, int height, int argb) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.setColor(new Color(argb, true));
        graphics.fillRect(x, y, width, height);
    }

    private static void drawBorder(
            Graphics2D graphics, int x, int y, int width, int height, int thickness, int argb) {
        fill(graphics, x, y, width, thickness, argb);
        fill(graphics, x, y + height - thickness, width, thickness, argb);
        fill(graphics, x, y, thickness, height, argb);
        fill(graphics, x + width - thickness, y, thickness, height, argb);
    }

    private static void drawText(Graphics2D graphics, String value, Font font, int x, int baseline, int argb) {
        graphics.setFont(font);
        graphics.setColor(new Color(argb, true));
        graphics.drawString(value, x, baseline);
    }

    private static int stringWidth(Graphics2D graphics, String value, Font font) {
        FontMetrics metrics = graphics.getFontMetrics(font);
        return metrics.stringWidth(value);
    }

    private static float charWidth(Graphics2D graphics, Font font) {
        return graphics.getFontMetrics(font).charWidth('M');
    }

    private static int uploadTexture(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        for (int index = 0; index < pixels.length; index++) {
            int argb = pixels[index];
            buffer.put((byte) ((argb >> 16) & 0xFF)); // R
            buffer.put((byte) ((argb >> 8) & 0xFF)); // G
            buffer.put((byte) (argb & 0xFF)); // B
            buffer.put((byte) ((argb >> 24) & 0xFF)); // A
        }
        buffer.flip();

        int texture = GL11.glGenTextures();
        if (texture == 0) {
            return 0;
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                width,
                height,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                buffer);
        return texture;
    }

    private static void blitFullscreen(int texture, int width, int height) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        // Top-left origin so the AWT-painted frame maps 1:1 to the window.
        GL11.glOrtho(0.0D, width, height, 0.0D, -1.0D, 1.0D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0.0F, 0.0F);
        GL11.glVertex2f(0.0F, 0.0F);
        GL11.glTexCoord2f(0.0F, 1.0F);
        GL11.glVertex2f(0.0F, height);
        GL11.glTexCoord2f(1.0F, 1.0F);
        GL11.glVertex2f(width, height);
        GL11.glTexCoord2f(1.0F, 0.0F);
        GL11.glVertex2f(width, 0.0F);
        GL11.glEnd();

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }
}
