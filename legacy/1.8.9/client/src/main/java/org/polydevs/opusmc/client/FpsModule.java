package org.polydevs.opusmc.client;

import org.polydevs.opusmc.client.hud.HudRenderContext;
import org.polydevs.opusmc.client.hud.HudWidget;
import org.polydevs.opusmc.client.module.ClientModule;
import org.polydevs.opusmc.client.ui.UiBounds;
import net.minecraft.client.Minecraft;

/** The first real module. Its settings and live widget share one state owner. */
final class FpsModule implements ClientModule {
    static final String ID = "fps";
    private final UtilitySettingsStore settingsStore;
    private final HudWidget widget = new FpsWidget();

    FpsModule(UtilitySettingsStore settingsStore) {
        this.settingsStore = settingsStore;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Performance overlay";
    }

    @Override
    public boolean isEnabled() {
        return settings().enabled();
    }

    @Override
    public boolean setEnabled(boolean enabled) {
        return settingsStore.updatePerformanceOverlay(settings().withEnabled(enabled));
    }

    PerformanceOverlaySettings settings() {
        return settingsStore.performanceOverlay();
    }

    boolean update(PerformanceOverlaySettings next) {
        return settingsStore.updatePerformanceOverlay(next);
    }

    void preview(PerformanceOverlaySettings next) {
        settingsStore.previewPerformanceOverlay(next);
    }

    boolean commitPreview() {
        return settingsStore.persistPerformanceOverlay();
    }

    HudWidget widget() {
        return widget;
    }

    private final class FpsWidget implements HudWidget {
        @Override
        public String moduleId() {
            return ID;
        }

        @Override
        public boolean isVisible() {
            return isEnabled();
        }

        @Override
        public void renderNormal(HudRenderContext context) {
            PerformanceOverlaySettings settings = settings();
            String value = "FPS: " + Minecraft.getDebugFPS();
            float scale = settings.scale() / 100.0F;
            UiBounds bounds = bounds(context);
            int alpha = settings.opacity() * 255 / 100;
            int color = alpha << 24 | 0x00FFFFFF;

            context.renderer().pushTransform();
            try {
                context.renderer().scale(scale, scale);
                context.renderer().text(
                        value,
                        Math.round(bounds.x / scale),
                        Math.round(bounds.y / scale),
                        color);
            } finally {
                context.renderer().popTransform();
            }
        }

        @Override
        public UiBounds bounds(HudRenderContext context) {
            PerformanceOverlaySettings settings = settings();
            String value = "FPS: " + Minecraft.getDebugFPS();
            float scale = settings.scale() / 100.0F;
            int width = Math.max(1, Math.round(context.renderer().measureText(value) * scale));
            int height = Math.max(1, Math.round(context.renderer().lineHeight() * scale));
            int x = isRight(settings.anchor())
                    ? context.viewport().width - settings.offsetX() - width
                    : settings.offsetX();
            int y = isBottom(settings.anchor())
                    ? context.viewport().height - settings.offsetY() - height
                    : settings.offsetY();
            return new UiBounds(x, y, width, height);
        }

        @Override
        public boolean moveBy(UiBounds viewport, int deltaX, int deltaY) {
            PerformanceOverlaySettings current = settings();
            int offsetX = isRight(current.anchor())
                    ? current.offsetX() - deltaX
                    : current.offsetX() + deltaX;
            int offsetY = isBottom(current.anchor())
                    ? current.offsetY() - deltaY
                    : current.offsetY() + deltaY;
            preview(current.withOffset(offsetX, offsetY));
            return true;
        }

        @Override
        public boolean resizeBy(UiBounds renderedBounds, int deltaX) {
            if (deltaX == 0) {
                return false;
            }
            PerformanceOverlaySettings current = settings();
            int adjustment = Math.round(deltaX * 100.0F / Math.max(1, renderedBounds.width));
            if (adjustment == 0) {
                adjustment = deltaX > 0 ? 1 : -1;
            }
            preview(current.withScale(current.scale() + adjustment));
            return true;
        }

        @Override
        public boolean disable() {
            return setEnabled(false);
        }

        @Override
        public boolean commitEditorChange() {
            return commitPreview();
        }
    }

    private static boolean isRight(PerformanceOverlaySettings.Anchor anchor) {
        return anchor == PerformanceOverlaySettings.Anchor.TOP_RIGHT
                || anchor == PerformanceOverlaySettings.Anchor.BOTTOM_RIGHT;
    }

    private static boolean isBottom(PerformanceOverlaySettings.Anchor anchor) {
        return anchor == PerformanceOverlaySettings.Anchor.BOTTOM_LEFT
                || anchor == PerformanceOverlaySettings.Anchor.BOTTOM_RIGHT;
    }
}
