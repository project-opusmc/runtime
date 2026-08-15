package dev.rbw.client;

import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.UiInput;
import dev.rbw.client.ui.UiRenderer;
import dev.rbw.client.ui.UiRoute;
import dev.rbw.client.ui.UiRuntime;
import dev.rbw.client.ui.component.UiActionButton;

/** Real options for the inventory-backed Armor Status widget. */
final class RbwArmorStatusDetailPage extends RbwPanelPage {
    private final UiActionButton back;
    private final UiActionButton enabled;
    private final UiActionButton durability;
    private final UiActionButton anchor;
    private final UiActionButton scaleDown;
    private final UiActionButton scaleUp;
    private final UiActionButton opacityDown;
    private final UiActionButton opacityUp;
    private UiBounds body = new UiBounds(0, 0, 0, 0);
    private UiBounds settingsCard = new UiBounds(0, 0, 0, 0);

    RbwArmorStatusDetailPage(ClientOverlayController controller, UiRuntime runtime) {
        super(controller, runtime);
        back = button("BACK", UiActionButton.Tone.NEUTRAL, new Runnable() { @Override public void run() { runtime.navigate(UiRoute.modHub()); } });
        enabled = settingButton(new Runnable() { @Override public void run() { ArmorStatusSettings s = controller.armorStatus(); controller.updateArmorStatus(s.withEnabled(!s.enabled())); refresh(); } });
        durability = settingButton(new Runnable() { @Override public void run() { ArmorStatusSettings s = controller.armorStatus(); controller.updateArmorStatus(s.withShowDurability(!s.showDurability())); refresh(); } });
        anchor = settingButton(new Runnable() { @Override public void run() { ArmorStatusSettings s = controller.armorStatus(); controller.updateArmorStatus(s.withAnchor(s.anchor().next())); refresh(); } });
        scaleDown = adjustment("-", new Adjustment() { @Override public ArmorStatusSettings apply(ArmorStatusSettings s) { return s.withScale(s.scale() - 5); } });
        scaleUp = adjustment("+", new Adjustment() { @Override public ArmorStatusSettings apply(ArmorStatusSettings s) { return s.withScale(s.scale() + 5); } });
        opacityDown = adjustment("-", new Adjustment() { @Override public ArmorStatusSettings apply(ArmorStatusSettings s) { return s.withOpacity(s.opacity() - 5); } });
        opacityUp = adjustment("+", new Adjustment() { @Override public ArmorStatusSettings apply(ArmorStatusSettings s) { return s.withOpacity(s.opacity() + 5); } });
        refresh();
    }

    @Override public void layout(UiBounds viewport) {
        super.layout(viewport);
        int width = Math.min(content.width - (compact ? 24 : 36), compact ? 300 : 390);
        body = new UiBounds(content.x + (content.width - width) / 2, content.y + 14, width, content.height - 28);
        layoutControl(back, body.x, body.y, 54, 20);
        settingsCard = new UiBounds(body.x, body.y + 66, body.width, compact ? 150 : 164);
        int valueX = settingsCard.right() - (compact ? 100 : 116) - 10;
        int valueW = settingsCard.right() - valueX - 10;
        layoutControl(enabled, valueX, settingsCard.y + 30, valueW, 18);
        layoutControl(durability, valueX, settingsCard.y + 57, valueW, 18);
        layoutControl(anchor, valueX, settingsCard.y + 84, valueW, 18);
        pair(scaleDown, scaleUp, settingsCard.right() - 60, settingsCard.y + 111);
        pair(opacityDown, opacityUp, settingsCard.right() - 60, settingsCard.y + 138);
    }

    @Override public void render(UiRenderer renderer, UiInput input) {
        ArmorStatusSettings settings = controller.armorStatus();
        renderShell(renderer);
        bodyText(renderer, "ARMOR STATUS", body.x, body.y + 30, 8.6F, 0xFFF1F1F4);
        bodyText(renderer, "Reads only equipment worn by this player", body.x, body.y + 46, 6.5F, 0xFFA7A7AA);
        renderer.roundedRect(settingsCard, 4, 0xFF1B1D23);
        sectionLabel(renderer, "DISPLAY", settingsCard.x + 12, settingsCard.y + 12);
        renderer.fill(new UiBounds(settingsCard.x + 10, settingsCard.y + 22, settingsCard.width - 20, 1), 0xFF30333A);
        row(renderer, "Visibility", "Render equipped armor only", settingsCard.y + 29);
        row(renderer, "Durability", "Show remaining durability percent", settingsCard.y + 56);
        row(renderer, "Anchor", "Choose the HUD corner", settingsCard.y + 83);
        row(renderer, "Scale", settings.scale() + "%", settingsCard.y + 110);
        row(renderer, "Opacity", settings.opacity() + "%", settingsCard.y + 137);
        bodyText(renderer, controller.lastSaveError() == null ? "Changes apply immediately." : controller.lastSaveError(), body.x, body.bottom() - 10, 6.4F, controller.lastSaveError() == null ? 0xFFA7A7AA : theme().errorText());
        renderControls(renderer, input);
    }

    @Override void onEscape() { runtime.navigate(UiRoute.modHub()); }
    private UiActionButton settingButton(Runnable action) { return button("", UiActionButton.Tone.NEUTRAL, action); }
    private UiActionButton adjustment(String label, final Adjustment adjustment) { return button(label, UiActionButton.Tone.NEUTRAL, new Runnable() { @Override public void run() { controller.updateArmorStatus(adjustment.apply(controller.armorStatus())); } }); }
    private void pair(UiActionButton left, UiActionButton right, int x, int y) { layoutControl(left, x, y, 26, 18); layoutControl(right, x + 30, y, 26, 18); }
    private void row(UiRenderer renderer, String label, String hint, int y) { bodyText(renderer, label, settingsCard.x + 12, y + 1, 6.9F, 0xFFF1F1F4); bodyText(renderer, hint, settingsCard.x + 12, y + 12, 5.6F, 0xFFA7A7AA); if (y + 27 < settingsCard.bottom()) renderer.fill(new UiBounds(settingsCard.x + 10, y + 25, settingsCard.width - 20, 1), 0xFF30333A); }
    private void refresh() { ArmorStatusSettings s = controller.armorStatus(); enabled.setLabel(s.enabled() ? "Enabled" : "Disabled"); durability.setLabel(s.showDurability() ? "Shown" : "Hidden"); anchor.setLabel(s.anchor().label()); }
    private interface Adjustment { ArmorStatusSettings apply(ArmorStatusSettings settings); }
}
