package org.polydevs.opusmc.client;

import java.util.Locale;
import java.util.regex.Pattern;

/** Immutable visual settings for the native FPS widget. */
public final class FpsAppearance {
    public static final int MIN_BACKGROUND_WIDTH = 10;
    public static final int MAX_BACKGROUND_WIDTH = 300;
    public static final int MIN_BACKGROUND_HEIGHT = 10;
    public static final int MAX_BACKGROUND_HEIGHT = 100;
    public static final int MIN_BORDER_THICKNESS = 10;
    public static final int MAX_BORDER_THICKNESS = 500;

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{8}");

    private final boolean reverseOrder;
    private final boolean textShadow;
    private final boolean showBrackets;
    private final String bracketColor;
    private final boolean showBackground;
    private final boolean staticBackgroundWidth;
    private final int backgroundWidth;
    private final int backgroundHeight;
    private final boolean border;
    private final int borderThickness;
    private final String borderColor;
    private final String backgroundColor;
    private final String textColor;

    public FpsAppearance(
            boolean reverseOrder,
            boolean textShadow,
            boolean showBrackets,
            String bracketColor,
            boolean showBackground,
            boolean staticBackgroundWidth,
            int backgroundWidth,
            int backgroundHeight,
            boolean border,
            int borderThickness,
            String borderColor,
            String backgroundColor,
            String textColor) {
        this.reverseOrder = reverseOrder;
        this.textShadow = textShadow;
        this.showBrackets = showBrackets;
        this.bracketColor = canonicalColor(bracketColor);
        this.showBackground = showBackground;
        this.staticBackgroundWidth = staticBackgroundWidth;
        this.backgroundWidth = clamp(backgroundWidth, MIN_BACKGROUND_WIDTH, MAX_BACKGROUND_WIDTH);
        this.backgroundHeight = clamp(backgroundHeight, MIN_BACKGROUND_HEIGHT, MAX_BACKGROUND_HEIGHT);
        this.border = border;
        this.borderThickness = clamp(borderThickness, MIN_BORDER_THICKNESS, MAX_BORDER_THICKNESS);
        this.borderColor = canonicalColor(borderColor);
        this.backgroundColor = canonicalColor(backgroundColor);
        this.textColor = canonicalColor(textColor);
    }

    public static FpsAppearance defaults() {
        return new FpsAppearance(
                true,
                true,
                true,
                "#ffffffff",
                false,
                true,
                56,
                18,
                false,
                50,
                "#9f000000",
                "#6f000000",
                "#ffffffff");
    }

    public boolean reverseOrder() {
        return reverseOrder;
    }

    public boolean textShadow() {
        return textShadow;
    }

    public boolean showBrackets() {
        return showBrackets;
    }

    public String bracketColor() {
        return bracketColor;
    }

    public boolean showBackground() {
        return showBackground;
    }

    public boolean staticBackgroundWidth() {
        return staticBackgroundWidth;
    }

    public int backgroundWidth() {
        return backgroundWidth;
    }

    public int backgroundHeight() {
        return backgroundHeight;
    }

    public boolean border() {
        return border;
    }

    /** Stored in hundredths of a pixel so 50 represents 0.50 px. */
    public int borderThickness() {
        return borderThickness;
    }

    public String borderColor() {
        return borderColor;
    }

    public String backgroundColor() {
        return backgroundColor;
    }

    public String textColor() {
        return textColor;
    }

    public FpsAppearance withReverseOrder(boolean value) {
        return copy(value, textShadow, showBrackets, bracketColor, showBackground,
                staticBackgroundWidth, backgroundWidth, backgroundHeight, border,
                borderThickness, borderColor, backgroundColor, textColor);
    }

    public FpsAppearance withTextShadow(boolean value) {
        return copy(reverseOrder, value, showBrackets, bracketColor, showBackground,
                staticBackgroundWidth, backgroundWidth, backgroundHeight, border,
                borderThickness, borderColor, backgroundColor, textColor);
    }

    public FpsAppearance withShowBrackets(boolean value) {
        return copy(reverseOrder, textShadow, value, bracketColor, showBackground,
                staticBackgroundWidth, backgroundWidth, backgroundHeight, border,
                borderThickness, borderColor, backgroundColor, textColor);
    }

    public FpsAppearance withBracketColor(String value) {
        return copy(reverseOrder, textShadow, showBrackets, value, showBackground,
                staticBackgroundWidth, backgroundWidth, backgroundHeight, border,
                borderThickness, borderColor, backgroundColor, textColor);
    }

    public FpsAppearance withShowBackground(boolean value) {
        return copy(reverseOrder, textShadow, showBrackets, bracketColor, value,
                staticBackgroundWidth, backgroundWidth, backgroundHeight, border,
                borderThickness, borderColor, backgroundColor, textColor);
    }

    public FpsAppearance withStaticBackgroundWidth(boolean value) {
        return copy(reverseOrder, textShadow, showBrackets, bracketColor, showBackground,
                value, backgroundWidth, backgroundHeight, border,
                borderThickness, borderColor, backgroundColor, textColor);
    }

    public FpsAppearance withBackgroundWidth(int value) {
        return copy(reverseOrder, textShadow, showBrackets, bracketColor, showBackground,
                staticBackgroundWidth, value, backgroundHeight, border,
                borderThickness, borderColor, backgroundColor, textColor);
    }

    public FpsAppearance withBackgroundHeight(int value) {
        return copy(reverseOrder, textShadow, showBrackets, bracketColor, showBackground,
                staticBackgroundWidth, backgroundWidth, value, border,
                borderThickness, borderColor, backgroundColor, textColor);
    }

    public FpsAppearance withBorder(boolean value) {
        return copy(reverseOrder, textShadow, showBrackets, bracketColor, showBackground,
                staticBackgroundWidth, backgroundWidth, backgroundHeight, value,
                borderThickness, borderColor, backgroundColor, textColor);
    }

    public FpsAppearance withBorderThickness(int value) {
        return copy(reverseOrder, textShadow, showBrackets, bracketColor, showBackground,
                staticBackgroundWidth, backgroundWidth, backgroundHeight, border,
                value, borderColor, backgroundColor, textColor);
    }

    public FpsAppearance withBorderColor(String value) {
        return copy(reverseOrder, textShadow, showBrackets, bracketColor, showBackground,
                staticBackgroundWidth, backgroundWidth, backgroundHeight, border,
                borderThickness, value, backgroundColor, textColor);
    }

    public FpsAppearance withBackgroundColor(String value) {
        return copy(reverseOrder, textShadow, showBrackets, bracketColor, showBackground,
                staticBackgroundWidth, backgroundWidth, backgroundHeight, border,
                borderThickness, borderColor, value, textColor);
    }

    public FpsAppearance withTextColor(String value) {
        return copy(reverseOrder, textShadow, showBrackets, bracketColor, showBackground,
                staticBackgroundWidth, backgroundWidth, backgroundHeight, border,
                borderThickness, borderColor, backgroundColor, value);
    }

    static String canonicalColor(String value) {
        if (value == null || !COLOR.matcher(value).matches()) {
            throw new IllegalArgumentException("FPS colors must use #AARRGGBB");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private FpsAppearance copy(
            boolean nextReverseOrder,
            boolean nextTextShadow,
            boolean nextShowBrackets,
            String nextBracketColor,
            boolean nextShowBackground,
            boolean nextStaticBackgroundWidth,
            int nextBackgroundWidth,
            int nextBackgroundHeight,
            boolean nextBorder,
            int nextBorderThickness,
            String nextBorderColor,
            String nextBackgroundColor,
            String nextTextColor) {
        return new FpsAppearance(
                nextReverseOrder,
                nextTextShadow,
                nextShowBrackets,
                nextBracketColor,
                nextShowBackground,
                nextStaticBackgroundWidth,
                nextBackgroundWidth,
                nextBackgroundHeight,
                nextBorder,
                nextBorderThickness,
                nextBorderColor,
                nextBackgroundColor,
                nextTextColor);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
