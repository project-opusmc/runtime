package dev.rbw.client;

import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.UiInput;
import dev.rbw.client.ui.UiRenderer;
import dev.rbw.client.ui.UiRoute;
import dev.rbw.client.ui.UiRuntime;
import dev.rbw.client.ui.component.UiActionButton;
import net.minecraft.client.Minecraft;

/** Real configuration surface for the one live FPS widget. */
final class RbwPerformanceDetailPage extends RbwPanelPage {
    private final UiActionButton back;
    private final UiActionButton enabled;
    private final UiActionButton anchor;
    private final UiActionButton scaleDown;
    private final UiActionButton scaleUp;
    private final UiActionButton opacityDown;
    private final UiActionButton opacityUp;
    private UiBounds body = new UiBounds(0, 0, 0, 0);
    private UiBounds settingsCard = new UiBounds(0, 0, 0, 0);

    RbwPerformanceDetailPage(ClientOverlayController controller, UiRuntime runtime) {
        super(controller, runtime);
        back = button("BACK", UiActionButton.Tone.NEUTRAL, new Runnable() {
            @Override
            public void run() {
                RbwPerformanceDetailPage.this.runtime.navigate(UiRoute.modHub());
            }
        });
        enabled = button("", UiActionButton.Tone.NEUTRAL, new Runnable() {
            @Override
            public void run() {
                PerformanceOverlaySettings current = RbwPerformanceDetailPage.this.controller.performanceOverlay();
                RbwPerformanceDetailPage.this.controller.updatePerformanceOverlay(current.withEnabled(!current.enabled()));
                refreshLabels();
            }
        });
        anchor = button("", UiActionButton.Tone.NEUTRAL, new Runnable() {
            @Override
            public void run() {
                PerformanceOverlaySettings current = RbwPerformanceDetailPage.this.controller.performanceOverlay();
                RbwPerformanceDetailPage.this.controller.updatePerformanceOverlay(current.withAnchor(current.anchor().next()));
                refreshLabels();
            }
        });
        scaleDown = adjustmentButton("-", new Adjustment() {
            @Override public PerformanceOverlaySettings apply(PerformanceOverlaySettings value) {
                return value.withScale(value.scale() - 5);
            }
        });
        scaleUp = adjustmentButton("+", new Adjustment() {
            @Override public PerformanceOverlaySettings apply(PerformanceOverlaySettings value) {
                return value.withScale(value.scale() + 5);
            }
        });
        opacityDown = adjustmentButton("-", new Adjustment() {
            @Override public PerformanceOverlaySettings apply(PerformanceOverlaySettings value) {
                return value.withOpacity(value.opacity() - 5);
            }
        });
        opacityUp = adjustmentButton("+", new Adjustment() {
            @Override public PerformanceOverlaySettings apply(PerformanceOverlaySettings value) {
                return value.withOpacity(value.opacity() + 5);
            }
        });
        refreshLabels();
    }

    @Override
    public void layout(UiBounds nextViewport) {
        super.layout(nextViewport);
        int bodyWidth = Math.min(content.width - (compact ? 24 : 36), compact ? 300 : 390);
        body = new UiBounds(
                content.x + (content.width - bodyWidth) / 2,
                content.y + 14,
                bodyWidth,
                content.height - 28);
        layoutControl(back, body.x, body.y, 54, 20);
        settingsCard = new UiBounds(
                body.x,
                body.y + 66,
                body.width,
                compact ? 126 : 138);
        int valueWidth = compact ? 100 : 116;
        int valueX = settingsCard.right() - valueWidth - 10;
        layoutControl(enabled, valueX, settingsCard.y + 30, valueWidth, 18);
        layoutControl(anchor, valueX, settingsCard.y + 57, valueWidth, 18);
        layoutPair(scaleDown, scaleUp, settingsCard.right() - 60, settingsCard.y + 84);
        layoutPair(opacityDown, opacityUp, settingsCard.right() - 60, settingsCard.y + 111);
    }

    @Override
    public void render(UiRenderer renderer, UiInput input) {
        PerformanceOverlaySettings settings = controller.performanceOverlay();
        renderShell(renderer);
        bodyText(renderer, "PERFORMANCE OVERLAY", body.x, body.y + 30, 8.6F, 0xFFF1F1F4);
        bodyText(renderer, "Live reading: " + Minecraft.getDebugFPS() + " FPS", body.x, body.y + 46, 6.5F, 0xFFA7A7AA);
        renderer.roundedRect(settingsCard, 4, 0xFF1B1D23);
        sectionLabel(renderer, "DISPLAY", settingsCard.x + 12, settingsCard.y + 12);
        renderer.fill(new UiBounds(settingsCard.x + 10, settingsCard.y + 22, settingsCard.width - 20, 1), 0xFF30333A);
        renderRow(renderer, "Visibility", "Render this live FPS utility in-game", settingsCard.y + 29);
        renderRow(renderer, "Anchor", "Choose the HUD corner", settingsCard.y + 56);
        renderRow(renderer, "Scale", settings.scale() + "%", settingsCard.y + 83);
        renderRow(renderer, "Opacity", settings.opacity() + "%", settingsCard.y + 110);
        String error = controller.lastSaveError();
        bodyText(renderer, error == null ? "Changes apply immediately." : error, body.x, body.bottom() - 10,
                6.4F, error == null ? 0xFFA7A7AA : theme().errorText());
        renderControls(renderer, input);
    }

    @Override
    void onEscape() {
        runtime.navigate(UiRoute.modHub());
    }

    private UiActionButton adjustmentButton(String label, final Adjustment adjustment) {
        return button(label, UiActionButton.Tone.NEUTRAL, new Runnable() {
            @Override public void run() {
                controller.updatePerformanceOverlay(adjustment.apply(controller.performanceOverlay()));
            }
        });
    }

    private void layoutPair(UiActionButton left, UiActionButton right, int x, int y) {
        layoutControl(left, x, y, 26, 18);
        layoutControl(right, x + 30, y, 26, 18);
    }

    private void renderRow(UiRenderer renderer, String label, String hint, int y) {
        bodyText(renderer, label, settingsCard.x + 12, y + 1, 6.9F, 0xFFF1F1F4);
        bodyText(renderer, hint, settingsCard.x + 12, y + 12, 5.6F, 0xFFA7A7AA);
        if (y + 27 < settingsCard.bottom()) {
            renderer.fill(new UiBounds(settingsCard.x + 10, y + 25, settingsCard.width - 20, 1), 0xFF30333A);
        }
    }

    private void refreshLabels() {
        PerformanceOverlaySettings settings = controller.performanceOverlay();
        enabled.setLabel(settings.enabled() ? "Enabled" : "Disabled");
        anchor.setLabel(settings.anchor().label());
    }

    private interface Adjustment {
        PerformanceOverlaySettings apply(PerformanceOverlaySettings value);
    }
}
