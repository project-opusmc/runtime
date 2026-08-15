package dev.rbw.client;

import dev.rbw.client.hud.HudRenderContext;
import dev.rbw.client.hud.HudWidget;
import dev.rbw.client.module.ClientModule;
import dev.rbw.client.ui.UiBounds;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.item.ItemStack;

/** Displays only armor actually equipped by the local player. */
final class ArmorStatusModule implements ClientModule {
    static final String ID = "armor-status";
    private final UtilitySettingsStore settingsStore;
    private final HudWidget widget = new ArmorStatusWidget();

    ArmorStatusModule(UtilitySettingsStore settingsStore) {
        this.settingsStore = settingsStore;
    }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Armor Status"; }
    @Override public boolean isEnabled() { return settings().enabled(); }
    @Override public boolean setEnabled(boolean enabled) { return update(settings().withEnabled(enabled)); }
    ArmorStatusSettings settings() { return settingsStore.armorStatus(); }
    boolean update(ArmorStatusSettings next) { return settingsStore.updateArmorStatus(next); }
    void preview(ArmorStatusSettings next) { settingsStore.previewArmorStatus(next); }
    boolean commitPreview() { return settingsStore.persistArmorStatus(); }
    HudWidget widget() { return widget; }

    private final class ArmorStatusWidget implements HudWidget {
        @Override public String moduleId() { return ID; }
        @Override public boolean isVisible() { return isEnabled() && !rows().isEmpty(); }

        @Override
        public void renderNormal(HudRenderContext context) {
            ArmorStatusSettings settings = settings();
            List<String> rows = rows();
            if (rows.isEmpty()) {
                return;
            }
            float scale = settings.scale() / 100.0F;
            UiBounds bounds = bounds(context);
            int color = settings.opacity() * 255 / 100 << 24 | 0x00FFFFFF;
            context.renderer().pushTransform();
            try {
                context.renderer().scale(scale, scale);
                for (int index = 0; index < rows.size(); index++) {
                    String row = rows.get(index);
                    int width = context.renderer().measureText(row);
                    int x = isRight(settings.anchor())
                            ? Math.round((bounds.x + bounds.width - width * scale) / scale)
                            : Math.round(bounds.x / scale);
                    context.renderer().text(row, x, Math.round((bounds.y + index * 10 * scale) / scale), color);
                }
            } finally {
                context.renderer().popTransform();
            }
        }

        @Override
        public UiBounds bounds(HudRenderContext context) {
            ArmorStatusSettings settings = settings();
            List<String> rows = rows();
            float scale = settings.scale() / 100.0F;
            int widest = 1;
            for (String row : rows) {
                widest = Math.max(widest, context.renderer().measureText(row));
            }
            int width = Math.max(1, Math.round(widest * scale));
            int height = Math.max(1, Math.round(Math.max(1, rows.size()) * 10 * scale));
            int x = isRight(settings.anchor()) ? context.viewport().width - settings.offsetX() - width : settings.offsetX();
            int y = isBottom(settings.anchor()) ? context.viewport().height - settings.offsetY() - height : settings.offsetY();
            return new UiBounds(x, y, width, height);
        }

        @Override
        public boolean moveBy(UiBounds viewport, int deltaX, int deltaY) {
            ArmorStatusSettings current = settings();
            int x = isRight(current.anchor()) ? current.offsetX() - deltaX : current.offsetX() + deltaX;
            int y = isBottom(current.anchor()) ? current.offsetY() - deltaY : current.offsetY() + deltaY;
            preview(current.withOffset(x, y));
            return true;
        }

        @Override
        public boolean resizeBy(UiBounds bounds, int deltaX) {
            if (deltaX == 0) return false;
            int change = Math.round(deltaX * 100.0F / Math.max(1, bounds.width));
            preview(settings().withScale(settings().scale() + (change == 0 ? (deltaX > 0 ? 1 : -1) : change)));
            return true;
        }

        @Override public boolean disable() { return setEnabled(false); }
        @Override public boolean commitEditorChange() { return commitPreview(); }
    }

    private List<String> rows() {
        List<String> result = new ArrayList<String>();
        if (net.minecraft.client.Minecraft.getMinecraft().thePlayer == null) return result;
        ArmorStatusSettings settings = settings();
        ItemStack[] armor = net.minecraft.client.Minecraft.getMinecraft().thePlayer.inventory.armorInventory;
        String[] labels = {"BOOTS", "LEGS", "CHEST", "HELMET"};
        for (int index = 0; index < armor.length; index++) {
            ItemStack stack = armor[index];
            if (stack == null) continue;
            String row = labels[index];
            if (settings.showDurability() && stack.isItemStackDamageable()) {
                int maximum = Math.max(1, stack.getMaxDamage());
                int percent = Math.max(0, Math.round((maximum - stack.getItemDamage()) * 100.0F / maximum));
                row += " " + percent + "%";
            }
            result.add(row);
        }
        return result;
    }

    private static boolean isRight(PerformanceOverlaySettings.Anchor anchor) {
        return anchor == PerformanceOverlaySettings.Anchor.TOP_RIGHT || anchor == PerformanceOverlaySettings.Anchor.BOTTOM_RIGHT;
    }

    private static boolean isBottom(PerformanceOverlaySettings.Anchor anchor) {
        return anchor == PerformanceOverlaySettings.Anchor.BOTTOM_LEFT || anchor == PerformanceOverlaySettings.Anchor.BOTTOM_RIGHT;
    }
}
