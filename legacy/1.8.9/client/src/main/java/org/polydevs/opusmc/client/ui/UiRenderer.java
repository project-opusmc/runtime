package org.polydevs.opusmc.client.ui;

/**
 * Native drawing boundary reserved for live HUD widgets and their editor
 * affordances. Product screens are rendered exclusively by Svelte/CEF.
 */
public interface UiRenderer {
    void beginFrame(UiBounds viewport);

    void endFrame();

    void fill(UiBounds bounds, int argb);

    void roundedRect(UiBounds bounds, int radius, int argb);

    void border(UiBounds bounds, int thickness, int argb);

    void text(String value, int x, int y, int argb);

    default void text(String value, int x, int y, int argb, boolean shadow) {
        text(value, x, y, argb);
    }

    int measureText(String value);

    int lineHeight();

    void line(float x0, float y0, float x1, float y1, float thickness, int argb);

    void ring(float centerX, float centerY, float radius, float thickness, int argb);

    void pushTransform();

    void popTransform();

    void scale(float x, float y);

    default void translate(float x, float y) { }

}
