package dev.rbw.client;

import dev.rbw.client.ui.UiPage;
import dev.rbw.client.ui.UiRoute;
import dev.rbw.client.ui.UiRuntime;

/** Creates the one page tree selected by UiRuntime's route state. */
final class RbwUiPageFactory implements UiRuntime.PageFactory {
    private final ClientOverlayController controller;

    RbwUiPageFactory(ClientOverlayController controller) {
        this.controller = controller;
    }

    @Override
    public UiPage create(UiRoute route, UiRuntime runtime) {
        switch (route.kind()) {
            case HUD_EDITOR:
                return new RbwHudEditorPage(controller, runtime);
            case MOD_HUB:
                return new RbwModHubPage(controller, runtime);
            case MODULE_DETAIL:
                if (FpsModule.ID.equals(route.moduleId())) {
                    return new RbwPerformanceDetailPage(controller, runtime);
                }
                if (ArmorStatusModule.ID.equals(route.moduleId())) {
                    return new RbwArmorStatusDetailPage(controller, runtime);
                }
                throw new IllegalArgumentException("unknown Opus module: " + route.moduleId());
            case MAIN_MENU:
                return new RbwMainMenuPage(controller, runtime);
            default:
                throw new IllegalStateException("unsupported route: " + route.kind());
        }
    }
}
