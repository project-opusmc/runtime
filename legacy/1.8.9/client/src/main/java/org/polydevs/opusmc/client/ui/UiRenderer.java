package org.polydevs.opusmc.client.ui;

import net.minecraft.util.ResourceLocation;

/**
 * The only drawing API exposed to Opus pages/components. Minecraft and OpenGL
 * details stay in the backend so module code cannot leak GL state.
 */
public interface UiRenderer {
    void beginFrame(UiBounds viewport);

    void endFrame();

    void fill(UiBounds bounds, int argb);

    void verticalGradient(UiBounds bounds, int topArgb, int bottomArgb);

    void horizontalGradient(UiBounds bounds, int leftArgb, int rightArgb);

    /** Draws a soft elliptical light field for product backdrops. */
    void radialGradient(float centerX, float centerY, float radiusX, float radiusY, int innerArgb, int outerArgb);

    void roundedRect(UiBounds bounds, int radius, int argb);

    void border(UiBounds bounds, int thickness, int argb);

    void text(String value, int x, int y, int argb);

    /**
     * Draws anti-aliased product typography. Font size and tracking are in
     * product UI units, so preview and packaged Minecraft render identically.
     */
    void uiText(
            String value,
            float x,
            float y,
            float fontSize,
            UiFontWeight weight,
            float tracking,
            int argb);

    float measureUiText(String value, float fontSize, UiFontWeight weight, float tracking);

    /**
     * Draws fixed-pitch monospace text for the terminal/TUI surfaces. Uses a
     * dedicated monospace atlas so panes line up on a character grid.
     */
    void monoText(String value, float x, float y, float fontSize, int argb);

    /** Width in pixels of {@code value} rendered by {@link #monoText}. */
    float measureMonoText(String value, float fontSize);

    /** Width of a single monospace character cell at {@code fontSize}. */
    float monoCellWidth(float fontSize);

    /** Line height of monospace text at {@code fontSize}. */
    float monoLineHeight(float fontSize);

    void centeredText(String value, UiBounds bounds, int argb);

    int measureText(String value);

    int lineHeight();

    void line(float x0, float y0, float x1, float y1, float thickness, int argb);

    void ring(float centerX, float centerY, float radius, float thickness, int argb);

    void pushTransform();

    void popTransform();

    void scale(float x, float y);

    void texture(ResourceLocation texture, UiBounds destination, int sourceWidth, int sourceHeight);

    void textureCover(ResourceLocation texture, UiBounds destination, int sourceWidth, int sourceHeight);

    void textureRegion(
            ResourceLocation texture,
            UiBounds destination,
            double u0,
            double v0,
            double u1,
            double v1);

    void textureTint(
            ResourceLocation texture,
            UiBounds destination,
            int sourceWidth,
            int sourceHeight,
            int tintArgb);

    void pushClip(UiBounds bounds);

    void popClip();
}
