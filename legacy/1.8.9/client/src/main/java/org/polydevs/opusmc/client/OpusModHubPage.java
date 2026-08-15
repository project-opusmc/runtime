package org.polydevs.opusmc.client;

import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiFontWeight;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.ui.UiRoute;
import org.polydevs.opusmc.client.ui.UiRuntime;
import org.polydevs.opusmc.client.ui.component.UiActionButton;

/** The catalog lists only modules backed by a real client data source. */
final class OpusModHubPage extends OpusPanelPage {
    private final ModuleCard performance;
    private final ModuleCard armor;
    private final UiActionButton editHud;
    private UiBounds catalog = new UiBounds(0, 0, 0, 0);

    OpusModHubPage(ClientOverlayController controller, UiRuntime runtime) {
        super(controller, runtime);
        performance = new ModuleCard("PERFORMANCE", "LIVE FPS", FpsModule.ID, new Toggle() {
            @Override public boolean enabled() { return controller.performanceOverlay().enabled(); }
            @Override public void flip() {
                PerformanceOverlaySettings s = controller.performanceOverlay();
                controller.updatePerformanceOverlay(s.withEnabled(!s.enabled()));
            }
        });
        armor = new ModuleCard("ARMOR STATUS", "WORN EQUIPMENT", ArmorStatusModule.ID, new Toggle() {
            @Override public boolean enabled() { return controller.armorStatus().enabled(); }
            @Override public void flip() {
                ArmorStatusSettings s = controller.armorStatus();
                controller.updateArmorStatus(s.withEnabled(!s.enabled()));
            }
        });
        editHud = button("Edit HUD", UiActionButton.Tone.QUIET, new Runnable() {
            @Override public void run() { OpusModHubPage.this.runtime.navigate(UiRoute.hudEditor()); }
        });
    }

    @Override public void layout(UiBounds viewport) {
        super.layout(viewport);
        catalog = content.inset(compact ? 12 : 18, compact ? 12 : 16, compact ? 12 : 18, compact ? 12 : 16);
        int cardWidth = Math.min(compact ? 124 : 116, Math.max(1, (catalog.width - (compact ? 10 : 16)) / 2));
        int cardHeight = compact ? 102 : 114;
        int gap = compact ? 10 : 16;
        // This is a catalogue, not a centered dashboard. With two shipped
        // utilities the first row retains its grid origin; unused cells stay
        // empty until a real module earns its place there.
        int start = catalog.x;
        int y = catalog.y + (compact ? 38 : 48);
        performance.layout(start, y, cardWidth, cardHeight);
        armor.layout(start + cardWidth + gap, y, cardWidth, cardHeight);
        // ModHub owns a real route into the HUD editor even on a fresh
        // profile. The editor simply has no widget chrome until a real module
        // is enabled; hiding this entry point would break the observed flow.
        editHud.setEnabled(true);
        layoutControl(editHud, catalog.x, y + cardHeight + 16, 100, 18);
    }

    @Override public void render(UiRenderer renderer, UiInput input) {
        renderShell(renderer);
        sectionLabel(renderer, "UTILITIES", catalog.x, catalog.y + 4);
        performance.render(renderer, input);
        armor.render(renderer, input);
        sectionLabel(renderer, "HUD LAYOUT", catalog.x, performance.bounds.bottom() + 6);
        renderControls(renderer, input);
    }

    @Override public boolean mouseDown(int mouseX, int mouseY, int button) {
        return super.mouseDown(mouseX, mouseY, button) || armor.mouseDown(mouseX, mouseY, button) || performance.mouseDown(mouseX, mouseY, button);
    }

    private final class ModuleCard {
        private final String title;
        private final String source;
        private final String moduleId;
        private final Toggle toggle;
        private final UiActionButton configure;
        private final UiActionButton state;
        private UiBounds bounds = new UiBounds(0, 0, 0, 0);

        ModuleCard(String title, String source, final String moduleId, Toggle toggle) {
            this.title = title;
            this.source = source;
            this.moduleId = moduleId;
            this.toggle = toggle;
            configure = button("Configure", UiActionButton.Tone.NEUTRAL, new Runnable() {
                @Override public void run() { runtime.navigate(UiRoute.moduleDetail(moduleId)); }
            });
            state = button("", UiActionButton.Tone.QUIET, new Runnable() {
                @Override public void run() { ModuleCard.this.toggle.flip(); }
            });
        }

        void layout(int x, int y, int width, int height) {
            bounds = new UiBounds(x, y, width, height);
            int inset = compact ? 7 : 8;
            int stateHeight = compact ? 17 : 18;
            int configureHeight = compact ? 17 : 18;
            layoutControl(configure, bounds.x + inset, bounds.bottom() - stateHeight - configureHeight - 6, bounds.width - inset * 2, configureHeight);
            layoutControl(state, bounds.x + inset, bounds.bottom() - stateHeight - 5, bounds.width - inset * 2, stateHeight);
            state.setLabel(toggle.enabled() ? "ENABLED" : "DISABLED");
        }

        void render(UiRenderer renderer, UiInput input) {
            state.setLabel(toggle.enabled() ? "ENABLED" : "DISABLED");
            renderer.roundedRect(bounds, 4, 0xFF1B1D23);
            drawMark(renderer, bounds.x + bounds.width / 2.0F, bounds.y + 22.0F, moduleId);
            float titleWidth = renderer.measureUiText(title, 6.3F, UiFontWeight.SEMIBOLD, 0.28F);
            renderer.uiText(title, bounds.x + (bounds.width - titleWidth) / 2.0F, bounds.y + 37, 6.3F, UiFontWeight.SEMIBOLD, 0.28F, 0xFFF1F1F4);
            float sourceWidth = renderer.measureUiText(source, 5.25F, UiFontWeight.SEMIBOLD, 0.18F);
            renderer.uiText(source, bounds.x + (bounds.width - sourceWidth) / 2.0F, bounds.y + 48, 5.25F, UiFontWeight.SEMIBOLD, 0.18F, 0xFFA7A7AA);
            renderer.fill(new UiBounds(bounds.x + 8, bounds.y + 60, bounds.width - 16, 1), 0xFF30333A);
        }

        boolean mouseDown(int mouseX, int mouseY, int button) { return false; }
    }

    private static void drawMark(UiRenderer renderer, float x, float y, String moduleId) {
        if (ArmorStatusModule.ID.equals(moduleId)) {
            renderer.roundedRect(new UiBounds(Math.round(x - 5), Math.round(y - 6), 10, 12), 2, 0xFFE6E7EB);
            renderer.fill(new UiBounds(Math.round(x - 2), Math.round(y - 3), 4, 6), 0xFF1B1D23);
            renderer.line(x - 3.5F, y - 1.5F, x + 3.5F, y - 1.5F, 0.9F, 0xFFE6E7EB);
        } else {
            renderer.ring(x, y, 6.0F, 1.0F, 0xFFE6E7EB);
            renderer.line(x - 3.2F, y + 2.0F, x - 0.8F, y - 1.0F, 1.0F, 0xFFE6E7EB);
            renderer.line(x - 0.8F, y - 1.0F, x + 1.2F, y + 1.0F, 1.0F, 0xFFE6E7EB);
            renderer.line(x + 1.2F, y + 1.0F, x + 4.0F, y - 3.0F, 1.0F, 0xFFE6E7EB);
        }
    }

    private interface Toggle { boolean enabled(); void flip(); }
}
