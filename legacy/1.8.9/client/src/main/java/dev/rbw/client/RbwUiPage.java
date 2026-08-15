package dev.rbw.client;

import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.UiPageAdapter;
import dev.rbw.client.ui.UiRuntime;
import dev.rbw.client.ui.component.UiActionButton;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.input.Keyboard;

/** Shared page behavior: Opus-owned controls and direct route navigation. */
abstract class RbwUiPage extends UiPageAdapter {
    final ClientOverlayController controller;
    final UiRuntime runtime;
    final List<UiActionButton> controls = new ArrayList<UiActionButton>();
    UiBounds viewport = new UiBounds(0, 0, 0, 0);

    RbwUiPage(ClientOverlayController controller, UiRuntime runtime) {
        this.controller = controller;
        this.runtime = runtime;
    }

    /** The same tokens are read by preview and packaged product rendering. */
    final UiTheme theme() {
        return UiTheme.current();
    }

    final UiActionButton button(String label, UiActionButton.Tone tone, Runnable action) {
        UiActionButton control = new UiActionButton(label, tone, action);
        controls.add(control);
        return control;
    }

    final void layoutControl(UiActionButton control, int x, int y, int width, int height) {
        control.layout(new UiBounds(x, y, width, height));
    }

    final void renderControls(dev.rbw.client.ui.UiRenderer renderer, dev.rbw.client.ui.UiInput input) {
        for (UiActionButton control : controls) {
            control.render(renderer, input);
        }
    }

    @Override
    public void layout(UiBounds nextViewport) {
        viewport = nextViewport;
    }

    @Override
    public boolean mouseDown(int mouseX, int mouseY, int button) {
        for (int index = controls.size() - 1; index >= 0; index--) {
            if (controls.get(index).mouseDown(mouseX, mouseY, button)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyTyped(char typedCharacter, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            onEscape();
            return true;
        }
        return false;
    }

    void onEscape() {
        runtime.requestClose();
    }
}
