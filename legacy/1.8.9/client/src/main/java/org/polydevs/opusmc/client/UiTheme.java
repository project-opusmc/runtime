package org.polydevs.opusmc.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Immutable visual tokens shared by the production renderer and the local
 * real-game preview. Product launches always use {@link #defaults()}; a
 * preview session may replace the current instance from a local JSON file.
 */
public final class UiTheme {
    private static volatile UiTheme current = defaults();
    private static volatile long generation;

    private final int surface;
    private final int header;
    private final int panel;
    private final int border;
    private final int text;
    private final int muted;
    private final int shellScrim;
    private final int editorScrim;
    private final int selection;
    private final int card;
    private final int mainTop;
    private final int mainBottom;
    private final int mainTopWash;
    private final int mainBottomWash;
    private final int watermarkTint;
    private final int mainStageRule;
    private final int mainFooter;
    private final int detailHeader;
    private final int errorText;
    private final int editorChrome;
    private final int editorChromeBorder;
    private final int mainFill;
    private final int mainFillHover;
    private final int mainBorder;
    private final int mainBorderHover;
    private final int mainQuietFill;
    private final int mainQuietFillHover;
    private final int mainQuietBorder;
    private final int mainQuietBorderHover;
    private final int neutralFill;
    private final int neutralFillHover;
    private final int neutralBorder;
    private final int neutralBorderHover;
    private final int primaryFill;
    private final int primaryFillHover;
    private final int primaryText;
    private final int quietFill;
    private final int quietFillHover;
    private final int quietBorder;
    private final int quietBorderHover;
    private final int disabledFill;
    private final int disabledBorder;
    private final int disabledText;
    private final float panelWidthRatio;
    private final float panelHeightRatio;
    private final float mainButtonWidthRatio;
    private final float mainLogoWidthRatio;
    private final int mainButtonRadius;

    private UiTheme(
            int surface,
            int header,
            int panel,
            int border,
            int text,
            int muted,
            int shellScrim,
            int editorScrim,
            int selection,
            int card,
            int mainTop,
            int mainBottom,
            int mainTopWash,
            int mainBottomWash,
            int watermarkTint,
            int mainStageRule,
            int mainFooter,
            int detailHeader,
            int errorText,
            int editorChrome,
            int editorChromeBorder,
            int mainFill,
            int mainFillHover,
            int mainBorder,
            int mainBorderHover,
            int mainQuietFill,
            int mainQuietFillHover,
            int mainQuietBorder,
            int mainQuietBorderHover,
            int neutralFill,
            int neutralFillHover,
            int neutralBorder,
            int neutralBorderHover,
            int primaryFill,
            int primaryFillHover,
            int primaryText,
            int quietFill,
            int quietFillHover,
            int quietBorder,
            int quietBorderHover,
            int disabledFill,
            int disabledBorder,
            int disabledText,
            float panelWidthRatio,
            float panelHeightRatio,
            float mainButtonWidthRatio,
            float mainLogoWidthRatio,
            int mainButtonRadius) {
        this.surface = surface;
        this.header = header;
        this.panel = panel;
        this.border = border;
        this.text = text;
        this.muted = muted;
        this.shellScrim = shellScrim;
        this.editorScrim = editorScrim;
        this.selection = selection;
        this.card = card;
        this.mainTop = mainTop;
        this.mainBottom = mainBottom;
        this.mainTopWash = mainTopWash;
        this.mainBottomWash = mainBottomWash;
        this.watermarkTint = watermarkTint;
        this.mainStageRule = mainStageRule;
        this.mainFooter = mainFooter;
        this.detailHeader = detailHeader;
        this.errorText = errorText;
        this.editorChrome = editorChrome;
        this.editorChromeBorder = editorChromeBorder;
        this.mainFill = mainFill;
        this.mainFillHover = mainFillHover;
        this.mainBorder = mainBorder;
        this.mainBorderHover = mainBorderHover;
        this.mainQuietFill = mainQuietFill;
        this.mainQuietFillHover = mainQuietFillHover;
        this.mainQuietBorder = mainQuietBorder;
        this.mainQuietBorderHover = mainQuietBorderHover;
        this.neutralFill = neutralFill;
        this.neutralFillHover = neutralFillHover;
        this.neutralBorder = neutralBorder;
        this.neutralBorderHover = neutralBorderHover;
        this.primaryFill = primaryFill;
        this.primaryFillHover = primaryFillHover;
        this.primaryText = primaryText;
        this.quietFill = quietFill;
        this.quietFillHover = quietFillHover;
        this.quietBorder = quietBorder;
        this.quietBorderHover = quietBorderHover;
        this.disabledFill = disabledFill;
        this.disabledBorder = disabledBorder;
        this.disabledText = disabledText;
        this.panelWidthRatio = panelWidthRatio;
        this.panelHeightRatio = panelHeightRatio;
        this.mainButtonWidthRatio = mainButtonWidthRatio;
        this.mainLogoWidthRatio = mainLogoWidthRatio;
        this.mainButtonRadius = mainButtonRadius;
    }

    public static UiTheme current() {
        return current;
    }

    public static long generation() {
        return generation;
    }

    static void install(UiTheme nextTheme) {
        if (nextTheme == null) {
            throw new IllegalArgumentException("nextTheme is required");
        }
        current = nextTheme;
        generation++;
    }

    static UiTheme defaults() {
        return new UiTheme(
                0xEE12171B, 0xF20C1014, 0xD914191D, 0xFF394149, 0xFFF7F8F8, 0xFFADB2B7,
                0x26000000, 0x18000000, 0xFF20262B, 0xD91B2025, 0xFF08131D, 0xFF152331,
                0xCC102333, 0xA0050A0F, 0x0B9AB8D0, 0x24465E74, 0xFF7D8B96, 0xE612171B,
                0xFFFFC3C3, 0xEE171C20, 0xFFE8ECEE, 0xA90C1015, 0xD9141A20, 0x2BFFFFFF,
                0x92FFFFFF, 0x00111519, 0x1FFFFFFF, 0x00374A5B, 0x48FFFFFF, 0xFF22282E,
                0xFF2D343B, 0xFF454D55, 0xFF68727B, 0xFFE4E8EB, 0xFFF5F7F8, 0xFF15191D,
                0x0014191D, 0xFF2A3036, 0x00343C44, 0xFF555E67, 0xFF1B1F23, 0xFF30363C,
                0xFF737B82, 0.82F, 0.84F, 0.43F, 0.43F, 2);
    }

    static UiTheme fromPreviewJson(JsonObject root) {
        UiTheme base = defaults();
        JsonObject colors = object(root, "colors");
        JsonObject metrics = object(root, "metrics");
        return new UiTheme(
                color(colors, "surface", base.surface), color(colors, "header", base.header),
                color(colors, "panel", base.panel), color(colors, "border", base.border),
                color(colors, "text", base.text), color(colors, "muted", base.muted),
                color(colors, "shellScrim", base.shellScrim), color(colors, "editorScrim", base.editorScrim),
                color(colors, "selection", base.selection), color(colors, "card", base.card),
                color(colors, "mainTop", base.mainTop), color(colors, "mainBottom", base.mainBottom),
                color(colors, "mainTopWash", base.mainTopWash), color(colors, "mainBottomWash", base.mainBottomWash),
                color(colors, "watermarkTint", base.watermarkTint), color(colors, "mainStageRule", base.mainStageRule),
                color(colors, "mainFooter", base.mainFooter), color(colors, "detailHeader", base.detailHeader),
                color(colors, "errorText", base.errorText), color(colors, "editorChrome", base.editorChrome),
                color(colors, "editorChromeBorder", base.editorChromeBorder),
                color(colors, "mainFill", base.mainFill), color(colors, "mainFillHover", base.mainFillHover),
                color(colors, "mainBorder", base.mainBorder), color(colors, "mainBorderHover", base.mainBorderHover),
                color(colors, "mainQuietFill", base.mainQuietFill), color(colors, "mainQuietFillHover", base.mainQuietFillHover),
                color(colors, "mainQuietBorder", base.mainQuietBorder), color(colors, "mainQuietBorderHover", base.mainQuietBorderHover),
                color(colors, "neutralFill", base.neutralFill), color(colors, "neutralFillHover", base.neutralFillHover),
                color(colors, "neutralBorder", base.neutralBorder), color(colors, "neutralBorderHover", base.neutralBorderHover),
                color(colors, "primaryFill", base.primaryFill), color(colors, "primaryFillHover", base.primaryFillHover),
                color(colors, "primaryText", base.primaryText), color(colors, "quietFill", base.quietFill),
                color(colors, "quietFillHover", base.quietFillHover), color(colors, "quietBorder", base.quietBorder),
                color(colors, "quietBorderHover", base.quietBorderHover), color(colors, "disabledFill", base.disabledFill),
                color(colors, "disabledBorder", base.disabledBorder), color(colors, "disabledText", base.disabledText),
                ratio(metrics, "panelWidthRatio", base.panelWidthRatio, 0.50F, 0.96F),
                ratio(metrics, "panelHeightRatio", base.panelHeightRatio, 0.45F, 0.96F),
                ratio(metrics, "mainButtonWidthRatio", base.mainButtonWidthRatio, 0.20F, 0.60F),
                ratio(metrics, "mainLogoWidthRatio", base.mainLogoWidthRatio, 0.18F, 0.55F),
                integer(metrics, "mainButtonRadius", base.mainButtonRadius, 0, 12));
    }

    private static JsonObject object(JsonObject root, String name) {
        if (root == null) {
            return null;
        }
        JsonElement value = root.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static int color(JsonObject object, String name, int fallback) {
        if (object == null) {
            return fallback;
        }
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            if (value.getAsJsonPrimitive().isNumber()) {
                return value.getAsInt();
            }
            String raw = value.getAsString().trim();
            if (raw.startsWith("#")) {
                raw = raw.substring(1);
            }
            if (raw.length() == 6) {
                return (int) (0xFF000000L | Long.parseLong(raw, 16));
            }
            if (raw.length() == 8) {
                return (int) Long.parseLong(raw, 16);
            }
        } catch (RuntimeException ignored) {
            // Invalid dev token leaves the last valid colour in place.
        }
        return fallback;
    }

    private static float ratio(JsonObject object, String name, float fallback, float minimum, float maximum) {
        if (object == null) {
            return fallback;
        }
        try {
            JsonElement value = object.get(name);
            if (value == null || !value.isJsonPrimitive()) {
                return fallback;
            }
            return clamp(value.getAsFloat(), minimum, maximum);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int integer(JsonObject object, String name, int fallback, int minimum, int maximum) {
        if (object == null) {
            return fallback;
        }
        try {
            JsonElement value = object.get(name);
            if (value == null || !value.isJsonPrimitive()) {
                return fallback;
            }
            return Math.max(minimum, Math.min(maximum, value.getAsInt()));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public int surface() { return surface; }
    public int header() { return header; }
    public int panel() { return panel; }
    public int border() { return border; }
    public int text() { return text; }
    public int muted() { return muted; }
    public int shellScrim() { return shellScrim; }
    public int editorScrim() { return editorScrim; }
    public int selection() { return selection; }
    public int card() { return card; }
    public int mainTop() { return mainTop; }
    public int mainBottom() { return mainBottom; }
    public int mainTopWash() { return mainTopWash; }
    public int mainBottomWash() { return mainBottomWash; }
    public int watermarkTint() { return watermarkTint; }
    public int mainStageRule() { return mainStageRule; }
    public int mainFooter() { return mainFooter; }
    public int detailHeader() { return detailHeader; }
    public int errorText() { return errorText; }
    public int editorChrome() { return editorChrome; }
    public int editorChromeBorder() { return editorChromeBorder; }
    public int mainFill() { return mainFill; }
    public int mainFillHover() { return mainFillHover; }
    public int mainBorder() { return mainBorder; }
    public int mainBorderHover() { return mainBorderHover; }
    public int mainQuietFill() { return mainQuietFill; }
    public int mainQuietFillHover() { return mainQuietFillHover; }
    public int mainQuietBorder() { return mainQuietBorder; }
    public int mainQuietBorderHover() { return mainQuietBorderHover; }
    public int neutralFill() { return neutralFill; }
    public int neutralFillHover() { return neutralFillHover; }
    public int neutralBorder() { return neutralBorder; }
    public int neutralBorderHover() { return neutralBorderHover; }
    public int primaryFill() { return primaryFill; }
    public int primaryFillHover() { return primaryFillHover; }
    public int primaryText() { return primaryText; }
    public int quietFill() { return quietFill; }
    public int quietFillHover() { return quietFillHover; }
    public int quietBorder() { return quietBorder; }
    public int quietBorderHover() { return quietBorderHover; }
    public int disabledFill() { return disabledFill; }
    public int disabledBorder() { return disabledBorder; }
    public int disabledText() { return disabledText; }
    public float panelWidthRatio() { return panelWidthRatio; }
    public float panelHeightRatio() { return panelHeightRatio; }
    public float mainButtonWidthRatio() { return mainButtonWidthRatio; }
    public float mainLogoWidthRatio() { return mainLogoWidthRatio; }
    public int mainButtonRadius() { return mainButtonRadius; }
}
