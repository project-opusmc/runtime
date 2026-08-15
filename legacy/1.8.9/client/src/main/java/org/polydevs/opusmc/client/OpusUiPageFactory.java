package org.polydevs.opusmc.client;

import org.polydevs.opusmc.client.ui.UiPage;
import org.polydevs.opusmc.client.ui.UiRoute;
import org.polydevs.opusmc.client.ui.UiRuntime;

/** Creates the one page tree selected by UiRuntime's route state. */
final class OpusUiPageFactory implements UiRuntime.PageFactory {
    private final ClientOverlayController controller;

    OpusUiPageFactory(ClientOverlayController controller) {
        this.controller = controller;
    }

    @Override
    public UiPage create(UiRoute route, UiRuntime runtime) {
        switch (route.kind()) {
            case HUD_EDITOR:
                return new OpusHudEditorPage(controller, runtime);
            case MOD_HUB:
                return new OpusModHubPage(controller, runtime);
            case MODULE_DETAIL:
                if (FpsModule.ID.equals(route.moduleId())) {
                    return new OpusPerformanceDetailPage(controller, runtime);
                }
                if (ArmorStatusModule.ID.equals(route.moduleId())) {
                    return new OpusArmorStatusDetailPage(controller, runtime);
                }
                throw new IllegalArgumentException("unknown Opus module: " + route.moduleId());
            case MAIN_MENU:
                return new OpusMainMenuPage(controller, runtime);
            default:
                throw new IllegalStateException("unsupported route: " + route.kind());
        }
    }
}
