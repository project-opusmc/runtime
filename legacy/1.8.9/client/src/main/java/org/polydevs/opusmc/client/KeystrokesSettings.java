package org.polydevs.opusmc.client;

/** Persisted display choices for the real key-binding-backed Keystrokes HUD. */
public final class KeystrokesSettings {
    private final boolean enabled;
    private final PerformanceOverlaySettings.Anchor anchor;
    private final int offsetX;
    private final int offsetY;
    private final int scale;
    private final int opacity;
    private final boolean showMouseButtons;
    private final boolean showJump;

    public KeystrokesSettings(
            boolean enabled,
            PerformanceOverlaySettings.Anchor anchor,
            int offsetX,
            int offsetY,
            int scale,
            int opacity,
            boolean showMouseButtons,
            boolean showJump) {
        this.enabled = enabled;
        this.anchor = anchor == null ? PerformanceOverlaySettings.Anchor.TOP_LEFT : anchor;
        this.offsetX = Math.max(0, offsetX);
        this.offsetY = Math.max(0, offsetY);
        this.scale = clamp(scale, PerformanceOverlaySettings.MIN_SCALE, PerformanceOverlaySettings.MAX_SCALE);
        this.opacity = clamp(opacity, PerformanceOverlaySettings.MIN_OPACITY, PerformanceOverlaySettings.MAX_OPACITY);
        this.showMouseButtons = showMouseButtons;
        this.showJump = showJump;
    }

    static KeystrokesSettings defaults() {
        return new KeystrokesSettings(
                false,
                PerformanceOverlaySettings.Anchor.TOP_LEFT,
                12,
                50,
                100,
                100,
                true,
                true);
    }

    public boolean enabled() {
        return enabled;
    }

    public PerformanceOverlaySettings.Anchor anchor() {
        return anchor;
    }

    public int offsetX() {
        return offsetX;
    }

    public int offsetY() {
        return offsetY;
    }

    public int scale() {
        return scale;
    }

    public int opacity() {
        return opacity;
    }

    public boolean showMouseButtons() {
        return showMouseButtons;
    }

    public boolean showJump() {
        return showJump;
    }

    public KeystrokesSettings withEnabled(boolean value) {
        return new KeystrokesSettings(
                value, anchor, offsetX, offsetY, scale, opacity, showMouseButtons, showJump);
    }

    public KeystrokesSettings withAnchor(PerformanceOverlaySettings.Anchor value) {
        return new KeystrokesSettings(
                enabled, value, offsetX, offsetY, scale, opacity, showMouseButtons, showJump);
    }

    public KeystrokesSettings withOffset(int valueX, int valueY) {
        return new KeystrokesSettings(
                enabled, anchor, valueX, valueY, scale, opacity, showMouseButtons, showJump);
    }

    public KeystrokesSettings withScale(int value) {
        return new KeystrokesSettings(
                enabled, anchor, offsetX, offsetY, value, opacity, showMouseButtons, showJump);
    }

    public KeystrokesSettings withOpacity(int value) {
        return new KeystrokesSettings(
                enabled, anchor, offsetX, offsetY, scale, value, showMouseButtons, showJump);
    }

    public KeystrokesSettings withShowMouseButtons(boolean value) {
        return new KeystrokesSettings(
                enabled, anchor, offsetX, offsetY, scale, opacity, value, showJump);
    }

    public KeystrokesSettings withShowJump(boolean value) {
        return new KeystrokesSettings(
                enabled, anchor, offsetX, offsetY, scale, opacity, showMouseButtons, value);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
