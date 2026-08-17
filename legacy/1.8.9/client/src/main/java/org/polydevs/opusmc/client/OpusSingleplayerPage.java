package org.polydevs.opusmc.client;

import java.util.List;
import org.polydevs.opusmc.client.ui.UiRuntime;

/**
 * The Opus terminal singleplayer screen. Lists the real saves read through the
 * typed save-format API and loads the selected world through FML's loader. No
 * vanilla GuiSelectWorld is ever shown.
 */
final class OpusSingleplayerPage extends OpusListTerminalPage {
    OpusSingleplayerPage(ClientOverlayController controller, UiRuntime runtime) {
        super(controller, runtime, "SINGLEPLAYER");
    }

    @Override String title() { return "SELECT WORLD"; }
    @Override String menuLabel() { return "WORLDS"; }

    @Override
    List<String[]> rows() {
        return controller.listWorlds();
    }

    @Override
    void activate(int index) {
        String fileName = selectedPrimary();
        String displayName = selectedSecondary();
        controller.loadWorld(fileName, displayName);
    }

    @Override
    String[][] statusRows(int selectedIndex) {
        if (selectedIndex < 0) {
            return new String[][] {
                {"Worlds", "0"},
                {"Hint", "No saves yet"},
            };
        }
        return new String[][] {
            {"World", selectedPrimary()},
            {"Mode", "Singleplayer"},
            {"Minecraft", "1.8.9"},
            {"Forge", "11.15.1.2318"},
            {"OptiFine", "HD U M5"},
        };
    }

    @Override
    String footerHint() {
        return "ARROW navigate   ENTER load   ESC back";
    }
}
