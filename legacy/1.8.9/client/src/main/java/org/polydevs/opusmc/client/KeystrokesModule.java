package org.polydevs.opusmc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import org.polydevs.opusmc.client.hud.HudRenderContext;
import org.polydevs.opusmc.client.hud.HudWidget;
import org.polydevs.opusmc.client.module.ClientModule;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiRenderer;

/** Native HUD cells backed only by the player's assigned physical bindings. */
final class KeystrokesModule implements ClientModule {
    static final String ID = "keystrokes";

    interface SettingsAccess {
        KeystrokesSettings settings();

        boolean update(KeystrokesSettings next);

        void preview(KeystrokesSettings next);

        boolean persist();

        void cancelPreview();
    }

    private static final int RELEASED_BACKGROUND = 0x001B1D21;
    private static final int RELEASED_BORDER = 0x004A4E54;
    private static final int RELEASED_TEXT = 0x00F2F3F5;
    private static final int PRESSED_BACKGROUND = 0x00D9DCE1;
    private static final int PRESSED_BORDER = 0x00BEC2C8;
    private static final int PRESSED_TEXT = 0x00202226;

    private final SettingsAccess settingsAccess;
    private final KeystrokesInputReader inputReader;
    private final HudWidget widget = new KeystrokesWidget();

    KeystrokesModule(UtilitySettingsStore settingsStore) {
        this(new UtilityStoreSettingsAccess(settingsStore), new KeystrokesInputReader());
    }

    KeystrokesModule(SettingsAccess settingsAccess, KeystrokesInputReader inputReader) {
        if (settingsAccess == null) {
            throw new IllegalArgumentException("settingsAccess");
        }
        if (inputReader == null) {
            throw new IllegalArgumentException("inputReader");
        }
        this.settingsAccess = settingsAccess;
        this.inputReader = inputReader;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Keystrokes";
    }

    @Override
    public boolean isEnabled() {
        return settings().enabled();
    }

    @Override
    public boolean setEnabled(boolean enabled) {
        return update(settings().withEnabled(enabled));
    }

    KeystrokesSettings settings() {
        return settingsAccess.settings();
    }

    boolean update(KeystrokesSettings next) {
        return settingsAccess.update(next);
    }

    void preview(KeystrokesSettings next) {
        settingsAccess.preview(next);
    }

    boolean commitPreview() {
        return settingsAccess.persist();
    }

    HudWidget widget() {
        return widget;
    }

    private final class KeystrokesWidget implements HudWidget {
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
            if (!isVisible()) {
                return;
            }
            KeystrokesSettings settings = settings();
            KeystrokesInputReader.Snapshot input = input(context);
            KeystrokesLayout layout = layout(context.renderer(), input, settings);
            if (layout.cells().isEmpty()) {
                return;
            }

            float scale = settings.scale() / 100.0F;
            UiBounds renderedBounds = KeystrokesModule.bounds(context, layout, settings);
            int originX = Math.round(renderedBounds.x / scale);
            int originY = Math.round(renderedBounds.y / scale);
            UiRenderer renderer = context.renderer();
            renderer.pushTransform();
            try {
                renderer.scale(scale, scale);
                for (KeystrokesLayout.Cell cell : layout.cells()) {
                    renderCell(renderer, cell, originX, originY, settings.opacity());
                }
            } finally {
                renderer.popTransform();
            }
        }

        @Override
        public UiBounds bounds(HudRenderContext context) {
            KeystrokesSettings settings = settings();
            KeystrokesLayout layout = layout(context.renderer(), input(context), settings);
            return KeystrokesModule.bounds(context, layout, settings);
        }

        @Override
        public boolean moveBy(UiBounds viewport, UiBounds renderedBounds, int deltaX, int deltaY) {
            KeystrokesSettings current = settings();
            int offsetX = isRight(current.anchor())
                    ? current.offsetX() - deltaX
                    : current.offsetX() + deltaX;
            int offsetY = isBottom(current.anchor())
                    ? current.offsetY() - deltaY
                    : current.offsetY() + deltaY;
            int maxX = Math.max(0, viewport.width - renderedBounds.width);
            int maxY = Math.max(0, viewport.height - renderedBounds.height);
            preview(current.withOffset(
                    clamp(offsetX, 0, maxX),
                    clamp(offsetY, 0, maxY)));
            return true;
        }

        @Override
        public int scale() {
            return settings().scale();
        }

        @Override public int defaultY() { return KeystrokesSettings.defaults().offsetY(); }

        @Override
        public boolean resizeToScale(int scale) {
            KeystrokesSettings current = settings();
            int clamped = Math.max(PerformanceOverlaySettings.MIN_SCALE,
                    Math.min(PerformanceOverlaySettings.MAX_SCALE, scale));
            if (clamped == current.scale()) {
                return false;
            }
            preview(current.withScale(clamped));
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

        @Override
        public void cancelEditorChange() {
            settingsAccess.cancelPreview();
        }
    }

    private KeystrokesInputReader.Snapshot input(HudRenderContext context) {
        Minecraft minecraft = context.minecraft();
        GameSettings gameSettings = minecraft == null ? null : minecraft.gameSettings;
        return inputReader.read(gameSettings);
    }

    private static KeystrokesLayout layout(
            UiRenderer renderer,
            KeystrokesInputReader.Snapshot input,
            KeystrokesSettings settings) {
        return KeystrokesLayout.create(
                renderer, input, settings.showMouseButtons(), settings.showJump());
    }

    private static UiBounds bounds(
            HudRenderContext context,
            KeystrokesLayout layout,
            KeystrokesSettings settings) {
        float scale = settings.scale() / 100.0F;
        int width = Math.round(layout.width() * scale);
        int height = Math.round(layout.height() * scale);
        int x = isRight(settings.anchor())
                ? context.viewport().width - settings.offsetX() - width
                : settings.offsetX();
        int y = isBottom(settings.anchor())
                ? context.viewport().height - settings.offsetY() - height
                : settings.offsetY();
        return new UiBounds(x, y, width, height);
    }

    private static void renderCell(
            UiRenderer renderer,
            KeystrokesLayout.Cell cell,
            int originX,
            int originY,
            int opacity) {
        UiBounds local = cell.bounds();
        UiBounds placed = new UiBounds(
                originX + local.x, originY + local.y, local.width, local.height);
        boolean pressed = cell.state().pressed();
        renderer.roundedRect(
                placed,
                3,
                withOpacity(pressed ? PRESSED_BACKGROUND : RELEASED_BACKGROUND, opacity));
        renderer.border(
                placed,
                1,
                withOpacity(pressed ? PRESSED_BORDER : RELEASED_BORDER, opacity));
        String label = cell.state().label();
        int textX = placed.x + Math.max(0, (placed.width - renderer.measureText(label)) / 2);
        int textY = placed.y + Math.max(0, (placed.height - renderer.lineHeight()) / 2);
        renderer.text(
                label,
                textX,
                textY,
                withOpacity(pressed ? PRESSED_TEXT : RELEASED_TEXT, opacity));
    }

    private static int withOpacity(int rgb, int opacity) {
        return opacity * 255 / 100 << 24 | rgb;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean isRight(PerformanceOverlaySettings.Anchor anchor) {
        return anchor == PerformanceOverlaySettings.Anchor.TOP_RIGHT
                || anchor == PerformanceOverlaySettings.Anchor.BOTTOM_RIGHT;
    }

    private static boolean isBottom(PerformanceOverlaySettings.Anchor anchor) {
        return anchor == PerformanceOverlaySettings.Anchor.BOTTOM_LEFT
                || anchor == PerformanceOverlaySettings.Anchor.BOTTOM_RIGHT;
    }

    private static final class UtilityStoreSettingsAccess implements SettingsAccess {
        private final UtilitySettingsStore settingsStore;

        private UtilityStoreSettingsAccess(UtilitySettingsStore settingsStore) {
            if (settingsStore == null) {
                throw new IllegalArgumentException("settingsStore");
            }
            this.settingsStore = settingsStore;
        }

        @Override
        public KeystrokesSettings settings() {
            return settingsStore.keystrokes();
        }

        @Override
        public boolean update(KeystrokesSettings next) {
            return settingsStore.updateKeystrokes(next);
        }

        @Override
        public void preview(KeystrokesSettings next) {
            settingsStore.previewKeystrokes(next);
        }

        @Override
        public boolean persist() {
            return settingsStore.persistKeystrokes();
        }

        @Override
        public void cancelPreview() {
            settingsStore.cancelKeystrokesPreview();
        }
    }
}
