package org.polydevs.opusmc.client;

/**
 * The complete, deliberately small configuration surface for the first
 * shipped in-game utility. Values are immutable so a render event can take a
 * consistent snapshot while the options screen applies a change.
 */
public final class PerformanceOverlaySettings {
    public enum Anchor {
        TOP_LEFT("top-left", "Top left"),
        TOP_RIGHT("top-right", "Top right"),
        BOTTOM_LEFT("bottom-left", "Bottom left"),
        BOTTOM_RIGHT("bottom-right", "Bottom right");

        private final String storageValue;
        private final String label;

        Anchor(String storageValue, String label) {
            this.storageValue = storageValue;
            this.label = label;
        }

        public String storageValue() {
            return storageValue;
        }

        public String label() {
            return label;
        }

        public Anchor next() {
            Anchor[] anchors = values();
            return anchors[(ordinal() + 1) % anchors.length];
        }

        public static Anchor fromStorage(String value) {
            for (Anchor anchor : values()) {
                if (anchor.storageValue.equals(value)) {
                    return anchor;
                }
            }
            return TOP_LEFT;
        }
    }

    public static final int MIN_SCALE = 50;
    public static final int MAX_SCALE = 400;
    public static final int MIN_OPACITY = 25;
    public static final int MAX_OPACITY = 100;

    private final boolean enabled;
    private final Anchor anchor;
    private final int offsetX;
    private final int offsetY;
    private final int scale;
    private final int opacity;
    private final FpsAppearance appearance;

    public PerformanceOverlaySettings(
            boolean enabled,
            Anchor anchor,
            int offsetX,
            int offsetY,
            int scale,
            int opacity) {
        this(enabled, anchor, offsetX, offsetY, scale, opacity, FpsAppearance.defaults());
    }

    public PerformanceOverlaySettings(
            boolean enabled,
            Anchor anchor,
            int offsetX,
            int offsetY,
            int scale,
            int opacity,
            FpsAppearance appearance) {
        this.enabled = enabled;
        this.anchor = anchor;
        this.offsetX = Math.max(0, offsetX);
        this.offsetY = Math.max(0, offsetY);
        this.scale = clamp(scale, MIN_SCALE, MAX_SCALE);
        this.opacity = clamp(opacity, MIN_OPACITY, MAX_OPACITY);
        this.appearance = appearance == null ? FpsAppearance.defaults() : appearance;
    }

    static PerformanceOverlaySettings defaults() {
        // A real metric is still opt-in: the client never creates a HUD item
        // merely to make a new installation look populated.
        return new PerformanceOverlaySettings(false, Anchor.TOP_LEFT, 12, 12, 100, 100);
    }

    public boolean enabled() {
        return enabled;
    }

    public Anchor anchor() {
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

    public FpsAppearance appearance() {
        return appearance;
    }

    public PerformanceOverlaySettings withEnabled(boolean value) {
        return new PerformanceOverlaySettings(value, anchor, offsetX, offsetY, scale, opacity, appearance);
    }

    public PerformanceOverlaySettings withAnchor(Anchor value) {
        return new PerformanceOverlaySettings(enabled, value, offsetX, offsetY, scale, opacity, appearance);
    }

    public PerformanceOverlaySettings withScale(int value) {
        return new PerformanceOverlaySettings(enabled, anchor, offsetX, offsetY, value, opacity, appearance);
    }

    public PerformanceOverlaySettings withOffset(int nextOffsetX, int nextOffsetY) {
        return new PerformanceOverlaySettings(
                enabled, anchor, nextOffsetX, nextOffsetY, scale, opacity, appearance);
    }

    public PerformanceOverlaySettings withOpacity(int value) {
        return new PerformanceOverlaySettings(enabled, anchor, offsetX, offsetY, scale, value, appearance);
    }

    public PerformanceOverlaySettings withAppearance(FpsAppearance value) {
        return new PerformanceOverlaySettings(enabled, anchor, offsetX, offsetY, scale, opacity, value);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
