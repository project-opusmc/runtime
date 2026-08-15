package dev.rbw.client;

/** Persisted display choices for the live inventory-backed Armor Status HUD. */
public final class ArmorStatusSettings {
    private final boolean enabled;
    private final PerformanceOverlaySettings.Anchor anchor;
    private final int offsetX;
    private final int offsetY;
    private final int scale;
    private final int opacity;
    private final boolean showDurability;

    public ArmorStatusSettings(
            boolean enabled,
            PerformanceOverlaySettings.Anchor anchor,
            int offsetX,
            int offsetY,
            int scale,
            int opacity,
            boolean showDurability) {
        this.enabled = enabled;
        this.anchor = anchor == null ? PerformanceOverlaySettings.Anchor.TOP_RIGHT : anchor;
        this.offsetX = Math.max(0, offsetX);
        this.offsetY = Math.max(0, offsetY);
        this.scale = clamp(scale, PerformanceOverlaySettings.MIN_SCALE, PerformanceOverlaySettings.MAX_SCALE);
        this.opacity = clamp(opacity, PerformanceOverlaySettings.MIN_OPACITY, PerformanceOverlaySettings.MAX_OPACITY);
        this.showDurability = showDurability;
    }

    static ArmorStatusSettings defaults() {
        return new ArmorStatusSettings(false, PerformanceOverlaySettings.Anchor.TOP_RIGHT, 12, 12, 100, 100, true);
    }

    public boolean enabled() { return enabled; }
    public PerformanceOverlaySettings.Anchor anchor() { return anchor; }
    public int offsetX() { return offsetX; }
    public int offsetY() { return offsetY; }
    public int scale() { return scale; }
    public int opacity() { return opacity; }
    public boolean showDurability() { return showDurability; }

    public ArmorStatusSettings withEnabled(boolean value) {
        return new ArmorStatusSettings(value, anchor, offsetX, offsetY, scale, opacity, showDurability);
    }

    public ArmorStatusSettings withAnchor(PerformanceOverlaySettings.Anchor value) {
        return new ArmorStatusSettings(enabled, value, offsetX, offsetY, scale, opacity, showDurability);
    }

    public ArmorStatusSettings withScale(int value) {
        return new ArmorStatusSettings(enabled, anchor, offsetX, offsetY, value, opacity, showDurability);
    }

    public ArmorStatusSettings withOpacity(int value) {
        return new ArmorStatusSettings(enabled, anchor, offsetX, offsetY, scale, value, showDurability);
    }

    public ArmorStatusSettings withOffset(int valueX, int valueY) {
        return new ArmorStatusSettings(enabled, anchor, valueX, valueY, scale, opacity, showDurability);
    }

    public ArmorStatusSettings withShowDurability(boolean value) {
        return new ArmorStatusSettings(enabled, anchor, offsetX, offsetY, scale, opacity, value);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
