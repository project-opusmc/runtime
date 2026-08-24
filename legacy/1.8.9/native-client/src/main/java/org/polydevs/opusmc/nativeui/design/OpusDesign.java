package org.polydevs.opusmc.nativeui.design;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Framework-neutral design contract for the native UI compatibility lane.
 *
 * <p>This first production baseline preserves the visual values already
 * exercised by the Elementa lab while moving them out of page code. The contract is
 * intentionally made only from Java 8 values and enums; renderer-specific
 * conversion belongs in {@link ElementaThemeAdapter} and
 * {@link OneConfigThemeAdapter}.</p>
 */
public final class OpusDesign {
    public static final String VERSION = "opus-design.1";

    public enum ColorRole {
        BACKDROP,
        SURFACE,
        SURFACE_RAISED,
        BORDER,
        TEXT,
        MUTED,
        ACCENT,
        ACCENT_HOVER,
        ACCENT_PRESSED,
        OVERLAY,
        SUCCESS,
        WARNING,
        DANGER,
        DISABLED,
        FOCUS,
        SELECTION
    }

    public enum SpacingToken {
        NONE(0f),
        XS(4f),
        SM(8f),
        MD(12f),
        LG(16f),
        PANEL_INSET(18f),
        XL(24f),
        XXL(32f);

        private final float value;

        SpacingToken(float value) {
            this.value = value;
        }

        public float getValue() {
            return value;
        }
    }

    public enum RadiusToken {
        CONTROL(5f),
        CARD(5f),
        MODAL(8f),
        PILL(999f);

        private final float value;

        RadiusToken(float value) {
            this.value = value;
        }

        public float getValue() {
            return value;
        }
    }

    public enum FontFamily {
        UI_SANS("Inter Variable, Inter, system sans-serif"),
        UI_MONO("SFMono-Regular, Menlo, monospace");

        private final String cssLikeName;

        FontFamily(String cssLikeName) {
            this.cssLikeName = cssLikeName;
        }

        public String getCssLikeName() {
            return cssLikeName;
        }
    }

    public enum FontWeight {
        REGULAR(400),
        MEDIUM(500),
        SEMIBOLD(600);

        private final int numericWeight;

        FontWeight(int numericWeight) {
            this.numericWeight = numericWeight;
        }

        public int getNumericWeight() {
            return numericWeight;
        }
    }

    public enum TextStyle {
        METADATA(FontFamily.UI_SANS, FontWeight.REGULAR, 11f, 1.45f, 0f, 1f),
        SECONDARY(FontFamily.UI_SANS, FontWeight.REGULAR, 12f, 1.45f, 0f, 1f),
        BODY(FontFamily.UI_SANS, FontWeight.REGULAR, 13f, 1.45f, 0f, 1f),
        CONTROL(FontFamily.UI_SANS, FontWeight.MEDIUM, 14f, 1.25f, 0f, 1f),
        SECTION(FontFamily.UI_SANS, FontWeight.SEMIBOLD, 16f, 1.25f, 0f, 1f),
        TITLE(FontFamily.UI_SANS, FontWeight.SEMIBOLD, 20f, 1.25f, 0f, 1.4f),
        PAGE_TITLE(FontFamily.UI_SANS, FontWeight.SEMIBOLD, 20f, 1.25f, 0f, 1.4f),
        HERO(FontFamily.UI_SANS, FontWeight.SEMIBOLD, 24f, 1.25f, 0f, 1.6f),
        HERO_LARGE(FontFamily.UI_SANS, FontWeight.SEMIBOLD, 28f, 1.25f, 0f, 1.8f),
        MODAL_TITLE(FontFamily.UI_SANS, FontWeight.SEMIBOLD, 16f, 1.25f, 0f, 1.25f),
        MONO(FontFamily.UI_MONO, FontWeight.REGULAR, 13f, 1.45f, 0f, 1f);

        private final FontFamily family;
        private final FontWeight weight;
        private final float size;
        private final float lineHeight;
        private final float letterSpacing;
        private final float scale;

        TextStyle(
                FontFamily family,
                FontWeight weight,
                float size,
                float lineHeight,
                float letterSpacing,
                float scale) {
            this.family = family;
            this.weight = weight;
            this.size = size;
            this.lineHeight = lineHeight;
            this.letterSpacing = letterSpacing;
            this.scale = scale;
        }

        public FontFamily getFamily() {
            return family;
        }

        public FontWeight getWeight() {
            return weight;
        }

        public float getSize() {
            return size;
        }

        public float getLineHeight() {
            return lineHeight;
        }

        public float getLetterSpacing() {
            return letterSpacing;
        }

        public float getScale() {
            return scale;
        }
    }

    public enum MotionToken {
        MICRO(0.12f),
        FAST(0.16f),
        NORMAL(0.20f),
        HOVER(0.25f),
        MEDIUM(0.28f),
        LARGE(0.40f);

        private final float seconds;

        MotionToken(float seconds) {
            this.seconds = seconds;
        }

        public float getSeconds() {
            return seconds;
        }
    }

    public enum ComponentSize {
        INPUT_HEIGHT(34f),
        BUTTON_HEIGHT(34f),
        ROW_HEIGHT(28f),
        CHIP_HEIGHT(20f),
        MODAL_BUTTON_HEIGHT(34f),
        MIN_HIT_TARGET(44f);

        private final float value;

        ComponentSize(float value) {
            this.value = value;
        }

        public float getValue() {
            return value;
        }
    }

    public enum IconMetric {
        SMALL(16f, 1.5f, 0f),
        MEDIUM(20f, 1.75f, 0f),
        LARGE(24f, 2f, 0f);

        private final float size;
        private final float strokeWidth;
        private final float opticalInset;

        IconMetric(float size, float strokeWidth, float opticalInset) {
            this.size = size;
            this.strokeWidth = strokeWidth;
            this.opticalInset = opticalInset;
        }

        public float getSize() {
            return size;
        }

        public float getStrokeWidth() {
            return strokeWidth;
        }

        public float getOpticalInset() {
            return opticalInset;
        }
    }

    public enum ElevationToken {
        NONE(0f, 0f, 0f, 0f),
        PANEL(0f, 4f, 12f, 0.24f),
        MODAL(0f, 8f, 24f, 0.42f);

        private final float offsetX;
        private final float offsetY;
        private final float blurRadius;
        private final float opacity;

        ElevationToken(float offsetX, float offsetY, float blurRadius, float opacity) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.blurRadius = blurRadius;
            this.opacity = opacity;
        }

        public float getOffsetX() {
            return offsetX;
        }

        public float getOffsetY() {
            return offsetY;
        }

        public float getBlurRadius() {
            return blurRadius;
        }

        public float getOpacity() {
            return opacity;
        }
    }

    public enum DensityPolicy {
        NEAREST_FRAMEBUFFER_PIXEL
    }

    public enum AccessibilityMetric {
        FOCUS_RING_WIDTH(2f),
        FOCUS_RING_GAP(2f),
        MINIMUM_HIT_TARGET(44f);

        private final float value;

        AccessibilityMetric(float value) {
            this.value = value;
        }

        public float getValue() {
            return value;
        }
    }

    public enum InteractionState {
        NORMAL,
        HOVER,
        PRESSED,
        FOCUSED,
        DISABLED,
        SELECTED,
        LOCKED,
        ERROR
    }

    private final Map<ColorRole, OpusColor> colors;

    private OpusDesign(Map<ColorRole, OpusColor> colors) {
        EnumMap<ColorRole, OpusColor> copy = new EnumMap<>(ColorRole.class);
        copy.putAll(colors);
        for (ColorRole role : ColorRole.values()) {
            if (!copy.containsKey(role) || copy.get(role) == null) {
                throw new IllegalArgumentException("Missing design color role: " + role);
            }
        }
        this.colors = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns the initial Opus palette. Values are immutable after
     * construction and all roles are present.
     */
    public static OpusDesign defaultDesign() {
        EnumMap<ColorRole, OpusColor> colors = new EnumMap<>(ColorRole.class);
        colors.put(ColorRole.BACKDROP, new OpusColor(12, 14, 20, 245));
        colors.put(ColorRole.SURFACE, new OpusColor(28, 31, 42, 255));
        colors.put(ColorRole.SURFACE_RAISED, new OpusColor(39, 43, 57, 255));
        colors.put(ColorRole.BORDER, new OpusColor(76, 83, 108, 255));
        colors.put(ColorRole.TEXT, new OpusColor(239, 241, 247, 255));
        colors.put(ColorRole.MUTED, new OpusColor(166, 172, 191, 255));
        colors.put(ColorRole.ACCENT, new OpusColor(119, 95, 255, 255));
        colors.put(ColorRole.ACCENT_HOVER, new OpusColor(143, 124, 255, 255));
        colors.put(ColorRole.ACCENT_PRESSED, new OpusColor(91, 70, 210, 255));
        colors.put(ColorRole.OVERLAY, new OpusColor(0, 0, 0, 188));
        colors.put(ColorRole.SUCCESS, new OpusColor(77, 181, 113, 255));
        colors.put(ColorRole.WARNING, new OpusColor(236, 184, 70, 255));
        colors.put(ColorRole.DANGER, new OpusColor(224, 91, 91, 255));
        colors.put(ColorRole.DISABLED, new OpusColor(76, 83, 108, 160));
        colors.put(ColorRole.FOCUS, new OpusColor(143, 124, 255, 255));
        colors.put(ColorRole.SELECTION, new OpusColor(119, 95, 255, 96));
        return new OpusDesign(colors);
    }

    public OpusColor color(ColorRole role) {
        if (role == null) throw new IllegalArgumentException("role cannot be null");
        return colors.get(role);
    }

    public float spacing(SpacingToken token) {
        if (token == null) throw new IllegalArgumentException("spacing token cannot be null");
        return token.getValue();
    }

    public float radius(RadiusToken token) {
        if (token == null) throw new IllegalArgumentException("radius token cannot be null");
        return token.getValue();
    }

    public float textScale(TextStyle style) {
        if (style == null) throw new IllegalArgumentException("text style cannot be null");
        return style.getScale();
    }

    public FontFamily fontFamily(TextStyle style) {
        if (style == null) throw new IllegalArgumentException("text style cannot be null");
        return style.getFamily();
    }

    public FontWeight fontWeight(TextStyle style) {
        if (style == null) throw new IllegalArgumentException("text style cannot be null");
        return style.getWeight();
    }

    public float textSize(TextStyle style) {
        if (style == null) throw new IllegalArgumentException("text style cannot be null");
        return style.getSize();
    }

    public float lineHeight(TextStyle style) {
        if (style == null) throw new IllegalArgumentException("text style cannot be null");
        return style.getLineHeight();
    }

    public float letterSpacing(TextStyle style) {
        if (style == null) throw new IllegalArgumentException("text style cannot be null");
        return style.getLetterSpacing();
    }

    public float motionSeconds(MotionToken token) {
        if (token == null) throw new IllegalArgumentException("motion token cannot be null");
        return token.getSeconds();
    }

    public float motionSeconds(MotionToken token, boolean reducedMotion) {
        if (token == null) throw new IllegalArgumentException("motion token cannot be null");
        return reducedMotion ? 0.01f : token.getSeconds();
    }

    public float componentSize(ComponentSize token) {
        if (token == null) throw new IllegalArgumentException("component size cannot be null");
        return token.getValue();
    }

    public float iconSize(IconMetric metric) {
        if (metric == null) throw new IllegalArgumentException("icon metric cannot be null");
        return metric.getSize();
    }

    public float iconStrokeWidth(IconMetric metric) {
        if (metric == null) throw new IllegalArgumentException("icon metric cannot be null");
        return metric.getStrokeWidth();
    }

    public float iconOpticalInset(IconMetric metric) {
        if (metric == null) throw new IllegalArgumentException("icon metric cannot be null");
        return metric.getOpticalInset();
    }

    public float elevationOffsetX(ElevationToken token) {
        if (token == null) throw new IllegalArgumentException("elevation token cannot be null");
        return token.getOffsetX();
    }

    public float elevationOffsetY(ElevationToken token) {
        if (token == null) throw new IllegalArgumentException("elevation token cannot be null");
        return token.getOffsetY();
    }

    public float elevationBlurRadius(ElevationToken token) {
        if (token == null) throw new IllegalArgumentException("elevation token cannot be null");
        return token.getBlurRadius();
    }

    public float elevationOpacity(ElevationToken token) {
        if (token == null) throw new IllegalArgumentException("elevation token cannot be null");
        return token.getOpacity();
    }

    public float accessibilityMetric(AccessibilityMetric metric) {
        if (metric == null) throw new IllegalArgumentException("accessibility metric cannot be null");
        return metric.getValue();
    }

    public DensityPolicy densityPolicy() {
        return DensityPolicy.NEAREST_FRAMEBUFFER_PIXEL;
    }

    /**
     * Converts logical UI pixels to framebuffer pixels using the one rounding
     * rule shared by drawing and hit testing.
     */
    public int toFramebufferPixels(float logicalPixels, float framebufferScale) {
        if (!Float.isFinite(logicalPixels) || logicalPixels < 0f) {
            throw new IllegalArgumentException("logicalPixels must be finite and non-negative: " + logicalPixels);
        }
        if (!Float.isFinite(framebufferScale) || framebufferScale <= 0f) {
            throw new IllegalArgumentException("framebufferScale must be finite and positive: " + framebufferScale);
        }
        return Math.round(logicalPixels * framebufferScale);
    }

    public int toLayoutFramebufferPixels(float logicalPixels, float framebufferScale) {
        return toFramebufferPixels(logicalPixels, framebufferScale);
    }

    public int toHitTestFramebufferPixels(float logicalPixels, float framebufferScale) {
        return toFramebufferPixels(logicalPixels, framebufferScale);
    }

    public ColorRole backgroundRole(InteractionState state) {
        if (state == null) throw new IllegalArgumentException("interaction state cannot be null");
        switch (state) {
            case HOVER:
                return ColorRole.ACCENT_HOVER;
            case PRESSED:
                return ColorRole.ACCENT_PRESSED;
            case DISABLED:
            case LOCKED:
                return ColorRole.DISABLED;
            case SELECTED:
                return ColorRole.SELECTION;
            case ERROR:
                return ColorRole.DANGER;
            case NORMAL:
            case FOCUSED:
            default:
                return ColorRole.ACCENT;
        }
    }

    public ColorRole foregroundRole(InteractionState state) {
        if (state == null) throw new IllegalArgumentException("interaction state cannot be null");
        switch (state) {
            case DISABLED:
            case LOCKED:
                return ColorRole.MUTED;
            case ERROR:
                return ColorRole.TEXT;
            case NORMAL:
            case HOVER:
            case PRESSED:
            case FOCUSED:
            case SELECTED:
            default:
                return ColorRole.TEXT;
        }
    }

    /**
     * Stable, renderer-independent representation used by parity tests and
     * the generated development report.
     */
    public String canonicalSignature() {
        StringBuilder signature = new StringBuilder(VERSION + "|");
        for (ColorRole role : ColorRole.values()) {
            signature.append(role.name()).append('=').append(color(role).toHexRgba()).append(';');
        }
        for (SpacingToken token : SpacingToken.values()) {
            signature.append("spacing.").append(token.name()).append('=').append(token.getValue()).append(';');
        }
        for (RadiusToken token : RadiusToken.values()) {
            signature.append("radius.").append(token.name()).append('=').append(token.getValue()).append(';');
        }
        for (TextStyle style : TextStyle.values()) {
            signature.append("text.").append(style.name())
                    .append('=').append(style.getFamily().name())
                    .append(',').append(style.getWeight().getNumericWeight())
                    .append(',').append(style.getSize())
                    .append(',').append(style.getLineHeight())
                    .append(',').append(style.getLetterSpacing())
                    .append(',').append(style.getScale()).append(';');
        }
        for (MotionToken token : MotionToken.values()) {
            signature.append("motion.").append(token.name()).append('=').append(token.getSeconds()).append(';');
        }
        for (ComponentSize token : ComponentSize.values()) {
            signature.append("size.").append(token.name()).append('=').append(token.getValue()).append(';');
        }
        for (IconMetric metric : IconMetric.values()) {
            signature.append("icon.").append(metric.name())
                    .append('=').append(metric.getSize())
                    .append(',').append(metric.getStrokeWidth())
                    .append(',').append(metric.getOpticalInset()).append(';');
        }
        for (ElevationToken token : ElevationToken.values()) {
            signature.append("elevation.").append(token.name())
                    .append('=').append(token.getOffsetX())
                    .append(',').append(token.getOffsetY())
                    .append(',').append(token.getBlurRadius())
                    .append(',').append(token.getOpacity()).append(';');
        }
        for (AccessibilityMetric metric : AccessibilityMetric.values()) {
            signature.append("accessibility.").append(metric.name())
                    .append('=').append(metric.getValue()).append(';');
        }
        signature.append("density=").append(densityPolicy().name()).append(';');
        return signature.toString();
    }
}
