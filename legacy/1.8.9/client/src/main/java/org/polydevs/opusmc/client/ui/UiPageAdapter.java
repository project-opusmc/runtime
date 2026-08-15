package org.polydevs.opusmc.client.ui;

/** Empty interaction defaults for pages that only implement the needed events. */
public abstract class UiPageAdapter implements UiPage {
    @Override
    public boolean mouseDown(int mouseX, int mouseY, int button) {
        return false;
    }

    @Override
    public boolean mouseUp(int mouseX, int mouseY, int button) {
        return false;
    }

    @Override
    public boolean mouseDrag(int mouseX, int mouseY, int button, long elapsedMillis) {
        return false;
    }

    @Override
    public boolean scroll(int amount) {
        return false;
    }

    @Override
    public boolean keyTyped(char typedCharacter, int keyCode) {
        return false;
    }

    @Override
    public void dispose() {
    }
}
