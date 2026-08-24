package org.polydevs.opusmc.nativeui.design;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cc.polyfrost.oneconfig.config.core.OneColor;
import org.polydevs.opusmc.nativeui.design.OpusDesign.AccessibilityMetric;
import org.polydevs.opusmc.nativeui.design.OpusDesign.ColorRole;
import org.polydevs.opusmc.nativeui.design.OpusDesign.ComponentSize;
import org.polydevs.opusmc.nativeui.design.OpusDesign.ElevationToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.IconMetric;
import org.polydevs.opusmc.nativeui.design.OpusDesign.InteractionState;
import org.polydevs.opusmc.nativeui.design.OpusDesign.MotionToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.RadiusToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.SpacingToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.TextStyle;

/** Cross-adapter invariants for the shared design contract. */
public final class OpusDesignParity {
    private OpusDesignParity() {
    }

    public static void verify(
            OpusDesign design,
            OneConfigThemeAdapter oneConfig,
            ElementaThemeAdapter elementa) {
        if (design == null || oneConfig == null || elementa == null) {
            throw new IllegalArgumentException("design and both adapters are required");
        }
        if (oneConfig.getDesign() != design || elementa.getDesign() != design) {
            throw new IllegalStateException("theme adapters must share the same design instance");
        }

        for (ColorRole role : ColorRole.values()) {
            OpusColor expected = design.color(role);
            Color elementaColor = elementa.color(role);
            OneColor oneConfigColor = oneConfig.oneColor(role);
            requireChannel("red", expected.getRed(), elementaColor.getRed(), oneConfigColor.getRed(), role);
            requireChannel("green", expected.getGreen(), elementaColor.getGreen(), oneConfigColor.getGreen(), role);
            requireChannel("blue", expected.getBlue(), elementaColor.getBlue(), oneConfigColor.getBlue(), role);
            requireChannel("alpha", expected.getAlpha(), elementaColor.getAlpha(), oneConfigColor.getAlpha(), role);
        }

        for (SpacingToken token : SpacingToken.values()) {
            requireFloat("spacing " + token, design.spacing(token), elementa.spacing(token));
            requireFloat("spacing " + token, design.spacing(token), oneConfig.spacing(token));
        }
        for (RadiusToken token : RadiusToken.values()) {
            requireFloat("radius " + token, design.radius(token), elementa.radius(token));
            requireFloat("radius " + token, design.radius(token), oneConfig.radius(token));
        }
        for (TextStyle style : TextStyle.values()) {
            requireObject("font family " + style, design.fontFamily(style), elementa.fontFamily(style));
            requireObject("font family " + style, design.fontFamily(style), oneConfig.fontFamily(style));
            requireObject("font weight " + style, design.fontWeight(style), elementa.fontWeight(style));
            requireObject("font weight " + style, design.fontWeight(style), oneConfig.fontWeight(style));
            requireFloat("text size " + style, design.textSize(style), elementa.textSize(style));
            requireFloat("text size " + style, design.textSize(style), oneConfig.textSize(style));
            requireFloat("line height " + style, design.lineHeight(style), elementa.lineHeight(style));
            requireFloat("line height " + style, design.lineHeight(style), oneConfig.lineHeight(style));
            requireFloat("letter spacing " + style, design.letterSpacing(style), elementa.letterSpacing(style));
            requireFloat("letter spacing " + style, design.letterSpacing(style), oneConfig.letterSpacing(style));
            requireFloat("text scale " + style, design.textScale(style), elementa.textScale(style));
            requireFloat("text scale " + style, design.textScale(style), oneConfig.textScale(style));
        }
        for (MotionToken token : MotionToken.values()) {
            requireFloat("motion " + token, design.motionSeconds(token), elementa.motionSeconds(token));
            requireFloat("motion " + token, design.motionSeconds(token), oneConfig.motionSeconds(token));
            requireFloat(
                    "reduced motion " + token,
                    design.motionSeconds(token, true),
                    elementa.motionSeconds(token, true));
            requireFloat(
                    "reduced motion " + token,
                    design.motionSeconds(token, true),
                    oneConfig.motionSeconds(token, true));
        }
        for (ComponentSize token : ComponentSize.values()) {
            requireFloat("component size " + token, design.componentSize(token), elementa.componentSize(token));
            requireFloat("component size " + token, design.componentSize(token), oneConfig.componentSize(token));
        }
        for (IconMetric metric : IconMetric.values()) {
            requireFloat("icon size " + metric, design.iconSize(metric), elementa.iconSize(metric));
            requireFloat("icon size " + metric, design.iconSize(metric), oneConfig.iconSize(metric));
            requireFloat(
                    "icon stroke " + metric,
                    design.iconStrokeWidth(metric),
                    elementa.iconStrokeWidth(metric));
            requireFloat(
                    "icon stroke " + metric,
                    design.iconStrokeWidth(metric),
                    oneConfig.iconStrokeWidth(metric));
            requireFloat(
                    "icon inset " + metric,
                    design.iconOpticalInset(metric),
                    elementa.iconOpticalInset(metric));
            requireFloat(
                    "icon inset " + metric,
                    design.iconOpticalInset(metric),
                    oneConfig.iconOpticalInset(metric));
        }
        for (ElevationToken token : ElevationToken.values()) {
            requireFloat(
                    "elevation offset x " + token,
                    design.elevationOffsetX(token),
                    elementa.elevationOffsetX(token));
            requireFloat(
                    "elevation offset x " + token,
                    design.elevationOffsetX(token),
                    oneConfig.elevationOffsetX(token));
            requireFloat(
                    "elevation offset y " + token,
                    design.elevationOffsetY(token),
                    elementa.elevationOffsetY(token));
            requireFloat(
                    "elevation offset y " + token,
                    design.elevationOffsetY(token),
                    oneConfig.elevationOffsetY(token));
            requireFloat(
                    "elevation blur " + token,
                    design.elevationBlurRadius(token),
                    elementa.elevationBlurRadius(token));
            requireFloat(
                    "elevation blur " + token,
                    design.elevationBlurRadius(token),
                    oneConfig.elevationBlurRadius(token));
            requireFloat(
                    "elevation opacity " + token,
                    design.elevationOpacity(token),
                    elementa.elevationOpacity(token));
            requireFloat(
                    "elevation opacity " + token,
                    design.elevationOpacity(token),
                    oneConfig.elevationOpacity(token));
        }
        for (AccessibilityMetric metric : AccessibilityMetric.values()) {
            requireFloat(
                    "accessibility " + metric,
                    design.accessibilityMetric(metric),
                    elementa.accessibilityMetric(metric));
            requireFloat(
                    "accessibility " + metric,
                    design.accessibilityMetric(metric),
                    oneConfig.accessibilityMetric(metric));
        }
        requireObject("density policy", design.densityPolicy(), elementa.densityPolicy());
        requireObject("density policy", design.densityPolicy(), oneConfig.densityPolicy());
        float[] logicalSamples = {0f, 0.5f, 1f, 12.5f, 44f};
        float[] scaleSamples = {1f, 1.25f, 1.5f, 2f};
        for (float logical : logicalSamples) {
            for (float scale : scaleSamples) {
                int expectedLayout = design.toLayoutFramebufferPixels(logical, scale);
                int expectedHitTest = design.toHitTestFramebufferPixels(logical, scale);
                requireInt("design layout/hit rounding", expectedLayout, expectedHitTest);
                requireInt(
                        "Elementa layout rounding",
                        expectedLayout,
                        elementa.toLayoutFramebufferPixels(logical, scale));
                requireInt(
                        "Elementa hit rounding",
                        expectedHitTest,
                        elementa.toHitTestFramebufferPixels(logical, scale));
                requireInt(
                        "OneConfig layout rounding",
                        expectedLayout,
                        oneConfig.toLayoutFramebufferPixels(logical, scale));
                requireInt(
                        "OneConfig hit rounding",
                        expectedHitTest,
                        oneConfig.toHitTestFramebufferPixels(logical, scale));
            }
        }
        for (InteractionState state : InteractionState.values()) {
            requireColor("state background " + state, elementa.stateBackground(state), oneConfig.stateColor(state));
        }
    }

    public static List<String> canonicalComponentStateMatrix() {
        String[] components = {"button", "toggle", "setting-row", "modal"};
        List<String> matrix = new ArrayList<>();
        for (String component : components) {
            for (InteractionState state : InteractionState.values()) {
                matrix.add(component + ":" + state.name().toLowerCase());
            }
        }
        return Collections.unmodifiableList(matrix);
    }

    private static void requireChannel(String channel, int expected, int elementa, int oneConfig, ColorRole role) {
        if (expected != elementa || expected != oneConfig) {
            throw new IllegalStateException(
                    role + " " + channel + " mismatch: expected " + expected
                            + ", Elementa " + elementa + ", OneConfig " + oneConfig);
        }
    }

    private static void requireColor(String label, Color elementa, OneColor oneConfig) {
        if (elementa.getRed() != oneConfig.getRed()
                || elementa.getGreen() != oneConfig.getGreen()
                || elementa.getBlue() != oneConfig.getBlue()
                || elementa.getAlpha() != oneConfig.getAlpha()) {
            throw new IllegalStateException(label + " differs between adapters");
        }
    }

    private static void requireFloat(String label, float expected, float actual) {
        if (Float.compare(expected, actual) != 0) {
            throw new IllegalStateException(label + " mismatch: expected " + expected + ", got " + actual);
        }
    }

    private static void requireInt(String label, int expected, int actual) {
        if (expected != actual) {
            throw new IllegalStateException(label + " mismatch: expected " + expected + ", got " + actual);
        }
    }

    private static void requireObject(String label, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(label + " mismatch: expected " + expected + ", got " + actual);
        }
    }
}
