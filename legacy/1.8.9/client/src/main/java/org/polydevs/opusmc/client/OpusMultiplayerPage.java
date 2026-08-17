package org.polydevs.opusmc.client;

import java.util.List;
import org.polydevs.opusmc.client.ui.UiRuntime;

/**
 * The Opus terminal multiplayer screen. Lists saved servers through the typed
 * ServerList API and connects with the typed GuiConnecting flow. A direct-entry
 * field allows connecting to (and saving) an arbitrary address. No vanilla
 * GuiMultiplayer is ever shown.
 */
final class OpusMultiplayerPage extends OpusListTerminalPage {
    OpusMultiplayerPage(ClientOverlayController controller, UiRuntime runtime) {
        super(controller, runtime, "MULTIPLAYER");
    }

    @Override String title() { return "SERVERS"; }
    @Override String menuLabel() { return "SERVERS"; }

    @Override
    List<String[]> rows() {
        return controller.listServers();
    }

    @Override
    void activate(int index) {
        String address = selectedSecondary();
        controller.connectServer(address);
    }

    @Override
    boolean supportsInput() {
        return true;
    }

    @Override
    String inputPrompt() {
        return "connect> ";
    }

    @Override
    void submitInput(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // Persist the entry, then connect immediately, mirroring vanilla's
        // "Add server" + "Join" without leaving the Opus terminal.
        controller.addServer(text, text);
        controller.connectServer(text);
    }

    @Override
    String[][] statusRows(int selectedIndex) {
        if (selectedIndex < 0) {
            return new String[][] {
                {"Servers", "0"},
                {"Hint", "Type an address below"},
            };
        }
        return new String[][] {
            {"Name", selectedPrimary()},
            {"Address", selectedSecondary()},
            {"Version", "1.8.9"},
        };
    }

    @Override
    String footerHint() {
        return "ARROW navigate   ENTER join   TAB address   ESC back";
    }
}
