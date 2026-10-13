package org.polydevs.opusmc.client;

import org.polydevs.opusmc.client.hud.HudRenderContext;
import org.polydevs.opusmc.client.hud.HudWidget;
import org.polydevs.opusmc.client.module.ClientModule;
import org.polydevs.opusmc.client.ui.UiBounds;
import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.List;

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
        return "FPS";
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

    List<String[]> moduleSettings() {
        PerformanceOverlaySettings settings = settings();
        FpsAppearance appearance = settings.appearance();
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(settingRow("enabled", "Enabled", "boolean", booleanValue(settings.enabled()),
                "0", "1", "1", ""));
        rows.add(settingRow("scale", "Scale", "integer", String.valueOf(settings.scale()),
                String.valueOf(PerformanceOverlaySettings.MIN_SCALE),
                String.valueOf(PerformanceOverlaySettings.MAX_SCALE), "1", ""));
        rows.add(settingRow("opacity", "Opacity", "integer", String.valueOf(settings.opacity()),
                String.valueOf(PerformanceOverlaySettings.MIN_OPACITY),
                String.valueOf(PerformanceOverlaySettings.MAX_OPACITY), "1", ""));
        rows.add(settingRow("anchor", "Anchor", "enum", settings.anchor().storageValue(),
                "0", "0", "1", "top-left,top-right,bottom-left,bottom-right"));
        rows.add(settingRow("offsetX", "Offset X", "integer", String.valueOf(settings.offsetX()),
                "0", "4096", "1", ""));
        rows.add(settingRow("offsetY", "Offset Y", "integer", String.valueOf(settings.offsetY()),
                "0", "4096", "1", ""));
        rows.add(settingRow("reverseOrder", "Reverse Order", "boolean",
                booleanValue(appearance.reverseOrder()), "0", "1", "1", ""));
        rows.add(settingRow("textShadow", "Text Shadow", "boolean",
                booleanValue(appearance.textShadow()), "0", "1", "1", ""));
        rows.add(settingRow("showBrackets", "Show Brackets", "boolean",
                booleanValue(appearance.showBrackets()), "0", "1", "1", ""));
        rows.add(settingRow("bracketColor", "Bracket Color", "color",
                appearance.bracketColor(), "", "", "", ""));
        rows.add(settingRow("showBackground", "Show Background", "boolean",
                booleanValue(appearance.showBackground()), "0", "1", "1", ""));
        rows.add(settingRow("staticBackgroundWidth", "Static Background Width", "boolean",
                booleanValue(appearance.staticBackgroundWidth()), "0", "1", "1", ""));
        rows.add(settingRow("backgroundWidth", "Background Width", "integer",
                String.valueOf(appearance.backgroundWidth()),
                String.valueOf(FpsAppearance.MIN_BACKGROUND_WIDTH),
                String.valueOf(FpsAppearance.MAX_BACKGROUND_WIDTH), "1", ""));
        rows.add(settingRow("backgroundHeight", "Background Height", "integer",
                String.valueOf(appearance.backgroundHeight()),
                String.valueOf(FpsAppearance.MIN_BACKGROUND_HEIGHT),
                String.valueOf(FpsAppearance.MAX_BACKGROUND_HEIGHT), "1", ""));
        rows.add(settingRow("border", "Border", "boolean", booleanValue(appearance.border()),
                "0", "1", "1", ""));
        rows.add(settingRow("borderThickness", "Border Thickness", "integer",
                String.valueOf(appearance.borderThickness()),
                String.valueOf(FpsAppearance.MIN_BORDER_THICKNESS),
                String.valueOf(FpsAppearance.MAX_BORDER_THICKNESS), "1", ""));
        rows.add(settingRow("borderColor", "Border Color", "color",
                appearance.borderColor(), "", "", "", ""));
        rows.add(settingRow("backgroundColor", "Background Color", "color",
                appearance.backgroundColor(), "", "", "", ""));
        rows.add(settingRow("textColor", "Text Color", "color",
                appearance.textColor(), "", "", "", ""));
        return rows;
    }

    boolean applySetting(String key, String value) {
        if (key == null || value == null) {
            return false;
        }
        PerformanceOverlaySettings current = settings();
        FpsAppearance appearance = current.appearance();
        try {
            PerformanceOverlaySettings next;
            if ("resetAppearance".equals(key)) {
                return "1".equals(value) && settingsStore.resetPerformanceOverlayAppearance();
            } else if ("enabled".equals(key)) {
                next = current.withEnabled(parseBoolean(value));
            } else if ("scale".equals(key)) {
                next = current.withScale(parseInteger(
                        value, PerformanceOverlaySettings.MIN_SCALE, PerformanceOverlaySettings.MAX_SCALE));
            } else if ("opacity".equals(key)) {
                next = current.withOpacity(parseInteger(
                        value, PerformanceOverlaySettings.MIN_OPACITY, PerformanceOverlaySettings.MAX_OPACITY));
            } else if ("anchor".equals(key)) {
                next = current.withAnchor(parseAnchor(value));
            } else if ("offsetX".equals(key)) {
                next = current.withOffset(parseInteger(value, 0, 4096), current.offsetY());
            } else if ("offsetY".equals(key)) {
                next = current.withOffset(current.offsetX(), parseInteger(value, 0, 4096));
            } else if ("reverseOrder".equals(key)) {
                next = current.withAppearance(appearance.withReverseOrder(parseBoolean(value)));
            } else if ("textShadow".equals(key)) {
                next = current.withAppearance(appearance.withTextShadow(parseBoolean(value)));
            } else if ("showBrackets".equals(key)) {
                next = current.withAppearance(appearance.withShowBrackets(parseBoolean(value)));
            } else if ("bracketColor".equals(key)) {
                next = current.withAppearance(appearance.withBracketColor(value));
            } else if ("showBackground".equals(key)) {
                next = current.withAppearance(appearance.withShowBackground(parseBoolean(value)));
            } else if ("staticBackgroundWidth".equals(key)) {
                next = current.withAppearance(appearance.withStaticBackgroundWidth(parseBoolean(value)));
            } else if ("backgroundWidth".equals(key)) {
                next = current.withAppearance(appearance.withBackgroundWidth(parseInteger(
                        value, FpsAppearance.MIN_BACKGROUND_WIDTH, FpsAppearance.MAX_BACKGROUND_WIDTH)));
            } else if ("backgroundHeight".equals(key)) {
                next = current.withAppearance(appearance.withBackgroundHeight(parseInteger(
                        value, FpsAppearance.MIN_BACKGROUND_HEIGHT, FpsAppearance.MAX_BACKGROUND_HEIGHT)));
            } else if ("border".equals(key)) {
                next = current.withAppearance(appearance.withBorder(parseBoolean(value)));
            } else if ("borderThickness".equals(key)) {
                next = current.withAppearance(appearance.withBorderThickness(parseInteger(
                        value, FpsAppearance.MIN_BORDER_THICKNESS, FpsAppearance.MAX_BORDER_THICKNESS)));
            } else if ("borderColor".equals(key)) {
                next = current.withAppearance(appearance.withBorderColor(value));
            } else if ("backgroundColor".equals(key)) {
                next = current.withAppearance(appearance.withBackgroundColor(value));
            } else if ("textColor".equals(key)) {
                next = current.withAppearance(appearance.withTextColor(value));
            } else {
                return false;
            }
            return update(next);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
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
            FpsLayout layout = layout(context, settings);
            FpsAppearance appearance = settings.appearance();
            float scale = settings.scale() / 100.0F;

            context.renderer().pushTransform();
            try {
                context.renderer().scale(scale, scale);
                context.renderer().translate(layout.bounds.x / scale - layout.frame.x,
                        layout.bounds.y / scale - layout.frame.y);
                if (appearance.showBackground()) {
                    context.renderer().fill(layout.frame, color(appearance.backgroundColor(), settings.opacity()));
                    if (appearance.border()) {
                        float thickness = appearance.borderThickness() / 100.0F;
                        float inset = thickness / 2.0F;
                        float left = layout.frame.x + inset;
                        float top = layout.frame.y + inset;
                        float right = layout.frame.right() - inset;
                        float bottom = layout.frame.bottom() - inset;
                        int borderColor = color(appearance.borderColor(), settings.opacity());
                        context.renderer().line(left, top, right, top, thickness, borderColor);
                        context.renderer().line(left, bottom, right, bottom, thickness, borderColor);
                        context.renderer().line(left, top, left, bottom, thickness, borderColor);
                        context.renderer().line(right, top, right, bottom, thickness, borderColor);
                    }
                }
                int textColor = color(appearance.textColor(), settings.opacity());
                if (appearance.showBrackets()) {
                    int bracketColor = color(appearance.bracketColor(), settings.opacity());
                    context.renderer().text("[", layout.textX, layout.textY,
                            bracketColor, appearance.textShadow());
                    context.renderer().text(layout.value, layout.textX + layout.leftBracketWidth,
                            layout.textY, textColor, appearance.textShadow());
                    context.renderer().text("]",
                            layout.textX + layout.leftBracketWidth + layout.valueWidth,
                            layout.textY, bracketColor, appearance.textShadow());
                } else {
                    context.renderer().text(layout.value, layout.textX, layout.textY,
                            textColor, appearance.textShadow());
                }
            } finally {
                context.renderer().popTransform();
            }
        }

        @Override
        public UiBounds bounds(HudRenderContext context) {
            PerformanceOverlaySettings settings = settings();
            return layout(context, settings).bounds;
        }

        @Override
        public boolean moveBy(UiBounds viewport, UiBounds renderedBounds, int deltaX, int deltaY) {
            PerformanceOverlaySettings current = settings();
            int offsetX = isRight(current.anchor())
                    ? current.offsetX() - deltaX
                    : current.offsetX() + deltaX;
            int offsetY = isBottom(current.anchor())
                    ? current.offsetY() - deltaY
                    : current.offsetY() + deltaY;
            int maxX = Math.max(0, viewport.width - renderedBounds.width);
            int maxY = Math.max(0, viewport.height - renderedBounds.height);
            preview(current.withOffset(
                    Math.max(0, Math.min(maxX, offsetX)),
                    Math.max(0, Math.min(maxY, offsetY))));
            return true;
        }

        @Override
        public int scale() {
            return settings().scale();
        }

        @Override
        public boolean resizeToScale(int scale) {
            PerformanceOverlaySettings current = settings();
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
            settingsStore.cancelPerformanceOverlayPreview();
        }
    }

    private static FpsLayout layout(HudRenderContext context, PerformanceOverlaySettings settings) {
        FpsAppearance appearance = settings.appearance();
        String value = appearance.reverseOrder()
                ? "FPS " + Minecraft.getDebugFPS()
                : Minecraft.getDebugFPS() + " FPS";
        int valueWidth = context.renderer().measureText(value);
        int leftBracketWidth = appearance.showBrackets()
                ? context.renderer().measureText("[") : 0;
        int rightBracketWidth = appearance.showBrackets()
                ? context.renderer().measureText("]") : 0;
        int shadowExtent = appearance.textShadow() ? 1 : 0;
        int contentWidth = valueWidth + leftBracketWidth + rightBracketWidth + shadowExtent;
        int contentHeight = context.renderer().lineHeight() + shadowExtent;
        int frameWidth = contentWidth;
        int frameHeight = contentHeight;
        if (appearance.showBackground()) {
            int paddedWidth = contentWidth + 8;
            frameWidth = appearance.staticBackgroundWidth()
                    ? Math.max(appearance.backgroundWidth(), paddedWidth)
                    : paddedWidth;
            frameHeight = Math.max(appearance.backgroundHeight(), contentHeight + 4);
        }
        float scale = settings.scale() / 100.0F;
        int width = Math.max(1, Math.round(frameWidth * scale));
        int height = Math.max(1, Math.round(frameHeight * scale));
        int x = isRight(settings.anchor())
                ? context.viewport().width - settings.offsetX() - width
                : settings.offsetX();
        int y = isBottom(settings.anchor())
                ? context.viewport().height - settings.offsetY() - height
                : settings.offsetY();
        int frameX = Math.round(x / scale);
        int frameY = Math.round(y / scale);
        UiBounds frame = new UiBounds(frameX, frameY, frameWidth, frameHeight);
        int textX = frameX + (frameWidth - contentWidth) / 2;
        int textY = frameY + (frameHeight - contentHeight) / 2;
        return new FpsLayout(
                new UiBounds(x, y, width, height), frame, value, valueWidth,
                leftBracketWidth, textX, textY);
    }

    private static int color(String value, int opacity) {
        int argb = (int) Long.parseLong(value.substring(1), 16);
        int alpha = (argb >>> 24) * opacity / 100;
        return alpha << 24 | argb & 0x00FFFFFF;
    }

    private static String booleanValue(boolean value) {
        return value ? "1" : "0";
    }

    private static boolean parseBoolean(String value) {
        if ("1".equals(value)) {
            return true;
        }
        if ("0".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("boolean");
    }

    private static int parseInteger(String value, int minimum, int maximum) {
        int parsed = Integer.parseInt(value);
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException("integer range");
        }
        return parsed;
    }

    private static PerformanceOverlaySettings.Anchor parseAnchor(String value) {
        for (PerformanceOverlaySettings.Anchor anchor : PerformanceOverlaySettings.Anchor.values()) {
            if (anchor.storageValue().equals(value)) {
                return anchor;
            }
        }
        throw new IllegalArgumentException("anchor");
    }

    private static String[] settingRow(
            String key,
            String label,
            String type,
            String value,
            String minimum,
            String maximum,
            String step,
            String enumOptions) {
        return new String[] {key, label, type, value, minimum, maximum, step, enumOptions};
    }

    private static final class FpsLayout {
        final UiBounds bounds;
        final UiBounds frame;
        final String value;
        final int valueWidth;
        final int leftBracketWidth;
        final int textX;
        final int textY;

        FpsLayout(
                UiBounds bounds,
                UiBounds frame,
                String value,
                int valueWidth,
                int leftBracketWidth,
                int textX,
                int textY) {
            this.bounds = bounds;
            this.frame = frame;
            this.value = value;
            this.valueWidth = valueWidth;
            this.leftBracketWidth = leftBracketWidth;
            this.textX = textX;
            this.textY = textY;
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
