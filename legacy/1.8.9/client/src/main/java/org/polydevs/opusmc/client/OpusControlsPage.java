package org.polydevs.opusmc.client;

import java.util.List;
import org.lwjgl.input.Keyboard;
import org.polydevs.opusmc.client.ui.UiRoute;
import org.polydevs.opusmc.client.ui.UiRuntime;

/** Full keyboard/mouse binding editor rendered inside the Opus terminal. */
final class OpusControlsPage extends OpusListTerminalPage {
    private boolean listening;
    private int listeningIndex = -1;

    OpusControlsPage(ClientOverlayController controller, UiRuntime runtime) {
        super(controller, runtime, "CONTROLS");
    }

    @Override String title() { return listening ? "PRESS INPUT" : "KEY BINDINGS"; }
    @Override String menuLabel() { return "BINDINGS"; }

    @Override
    List<String[]> rows() {
        return controller.keyBindingRows();
    }

    @Override
    void activate(int index) {
        listening = true;
        listeningIndex = index;
    }

    @Override
    String[][] statusRows(int selectedIndex) {
        if (selectedIndex < 0) {
            return new String[][] {{"Bindings", "0"}};
        }
        return new String[][] {
            {"Action", selectedPrimary()},
            {"Key", selectedSecondary()},
            {"Category", controller.keyBindingCategory(selectedIndex)},
            {"Conflict", controller.keyBindingConflict(selectedIndex)},
            {"State", listening ? "PRESS KEY OR MOUSE" : "READY"},
        };
    }

    @Override
    String footerHint() {
        return listening
                ? "PRESS input   ESC cancel   BACKSPACE unbind"
                : "ARROW navigate   ENTER bind   R reset   SHIFT+R reset all   ESC back";
    }

    @Override
    UiRoute backRoute() {
        return UiRoute.gameOptions();
    }

    @Override
    public boolean keyTyped(char typedCharacter, int keyCode) {
        if (listening) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                listening = false;
                listeningIndex = -1;
                return true;
            }
            int bindingCode = keyCode == Keyboard.KEY_BACK || keyCode == Keyboard.KEY_DELETE
                    ? 0
                    : keyCode;
            controller.rebindKey(listeningIndex, bindingCode);
            listening = false;
            listeningIndex = -1;
            reload();
            return true;
        }
        if (keyCode == Keyboard.KEY_R) {
            if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
                    || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) {
                controller.resetAllKeyBindings();
            } else {
                controller.resetKeyBinding(selectedIndex());
            }
            reload();
            return true;
        }
        return super.keyTyped(typedCharacter, keyCode);
    }

    @Override
    public boolean mouseDown(int mouseX, int mouseY, int button) {
        if (listening) {
            controller.rebindKey(listeningIndex, -100 + button);
            listening = false;
            listeningIndex = -1;
            reload();
            return true;
        }
        return super.mouseDown(mouseX, mouseY, button);
    }
}
