package org.polydevs.opusmc.client;

import java.util.List;
import org.polydevs.opusmc.client.ui.UiRoute;
import org.polydevs.opusmc.client.ui.UiRuntime;

/** Language selector rendered as an Opus terminal list. */
final class OpusLanguagePage extends OpusListTerminalPage {
    OpusLanguagePage(ClientOverlayController controller, UiRuntime runtime) {
        super(controller, runtime, "LANGUAGE");
    }

    @Override String title() { return "LANGUAGES"; }
    @Override String menuLabel() { return "AVAILABLE"; }

    @Override
    List<String[]> rows() {
        return controller.languageRows();
    }

    @Override
    void activate(int index) {
        controller.selectLanguage(index);
        reload();
    }

    @Override
    String[][] statusRows(int selectedIndex) {
        if (selectedIndex < 0) {
            return new String[][] {{"Languages", "0"}};
        }
        return new String[][] {
            {"Language", selectedPrimary()},
            {"Code", selectedSecondary()},
            {"Current", controller.currentLanguageCode()},
            {"Render", "OPUS monospace"},
        };
    }

    @Override
    String footerHint() {
        return "ARROW navigate   ENTER apply   MOUSE select   ESC back";
    }

    @Override
    UiRoute backRoute() {
        return UiRoute.gameOptions();
    }
}
