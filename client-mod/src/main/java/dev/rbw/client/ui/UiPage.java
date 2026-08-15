package dev.rbw.client.ui;

/**
 * A logical Opus page. Pages do not access Minecraft draw APIs or GL state;
 * they receive a renderer and logical input from UiRuntime.
 */
public interface UiPage {
    void layout(UiBounds viewport);

    void render(UiRenderer renderer, UiInput input);

    boolean mouseDown(int mouseX, int mouseY, int button);

    boolean mouseUp(int mouseX, int mouseY, int button);

    boolean mouseDrag(int mouseX, int mouseY, int button, long elapsedMillis);

    boolean scroll(int amount);

    boolean keyTyped(char typedCharacter, int keyCode);

    void dispose();
}
