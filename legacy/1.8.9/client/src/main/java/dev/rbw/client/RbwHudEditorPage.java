package dev.rbw.client;

import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.UiInput;
import dev.rbw.client.ui.UiRenderer;
import dev.rbw.client.ui.UiRoute;
import dev.rbw.client.ui.UiRuntime;
import dev.rbw.client.ui.component.UiActionButton;
import dev.rbw.client.hud.HudManager;
/** Live-game HUD editor for real, enabled widgets. */
final class RbwHudEditorPage extends RbwUiPage {
    private final UiActionButton mods;
    private final UiActionButton close;

    RbwHudEditorPage(ClientOverlayController controller, UiRuntime runtime) {
        super(controller, runtime);
        mods = button("MODS", UiActionButton.Tone.NEUTRAL, new Runnable() {
            @Override
            public void run() {
                runtime.navigate(UiRoute.modHub());
            }
        });
        close = button("X", UiActionButton.Tone.NEUTRAL, new Runnable() {
            @Override
            public void run() {
                runtime.requestClose();
            }
        });
    }

    @Override
    public void layout(UiBounds nextViewport) {
        super.layout(nextViewport);
        // Keep the editor actions as a compact, deliberate toolbar rather
        // than scattered labels over the live game. It remains clear of the
        // default top-left widget so every real widget stays reachable.
        layoutControl(mods, viewport.width / 2 - 46, 12, 92, 24);
        layoutControl(close, viewport.width - 42, 12, 24, 24);
    }

    @Override
    public void render(UiRenderer renderer, UiInput input) {
        renderer.fill(viewport, 0x3B000000);
        controller.renderHudEditor(renderer, viewport, input);
        renderControls(renderer, input);
    }

    @Override
    public boolean mouseDown(int mouseX, int mouseY, int button) {
        if (super.mouseDown(mouseX, mouseY, button)) {
            return true;
        }
        HudManager.EditorAction action = controller.hudEditorMouseDown(mouseX, mouseY, button);
        if (action != null && action.settingsModuleId() != null) {
            runtime.navigate(UiRoute.moduleDetail(action.settingsModuleId()));
            return true;
        }
        return action != null && action.handled();
    }

    @Override
    public boolean mouseDrag(int mouseX, int mouseY, int button, long elapsedMillis) {
        return controller.hudEditorMouseDrag(viewport, mouseX, mouseY, button);
    }

    @Override
    public boolean mouseUp(int mouseX, int mouseY, int button) {
        return controller.hudEditorMouseUp(button);
    }
}
