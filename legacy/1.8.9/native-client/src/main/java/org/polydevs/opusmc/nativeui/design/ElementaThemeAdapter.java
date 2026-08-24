package org.polydevs.opusmc.nativeui.design;

import java.awt.Color;

import org.polydevs.opusmc.nativeui.design.OpusDesign.ColorRole;
import org.polydevs.opusmc.nativeui.design.OpusDesign.ComponentSize;
import org.polydevs.opusmc.nativeui.design.OpusDesign.AccessibilityMetric;
import org.polydevs.opusmc.nativeui.design.OpusDesign.DensityPolicy;
import org.polydevs.opusmc.nativeui.design.OpusDesign.ElevationToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.FontFamily;
import org.polydevs.opusmc.nativeui.design.OpusDesign.FontWeight;
import org.polydevs.opusmc.nativeui.design.OpusDesign.IconMetric;
import org.polydevs.opusmc.nativeui.design.OpusDesign.InteractionState;
import org.polydevs.opusmc.nativeui.design.OpusDesign.MotionToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.RadiusToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.SpacingToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.TextStyle;

/** Elementa-side conversion of the renderer-neutral OpusDesign contract. */
public final class ElementaThemeAdapter {
    private final OpusDesign design;

    public ElementaThemeAdapter(OpusDesign design) {
        if (design == null) throw new IllegalArgumentException("design cannot be null");
        this.design = design;
    }

    public OpusDesign getDesign() {
        return design;
    }

    public Color color(ColorRole role) {
        OpusColor color = design.color(role);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
    }

    public Color stateBackground(InteractionState state) {
        return color(design.backgroundRole(state));
    }

    public Color stateForeground(InteractionState state) {
        return color(design.foregroundRole(state));
    }

    public float spacing(SpacingToken token) {
        return design.spacing(token);
    }

    public float radius(RadiusToken token) {
        return design.radius(token);
    }

    public float textScale(TextStyle style) {
        return design.textScale(style);
    }

    public FontFamily fontFamily(TextStyle style) {
        return design.fontFamily(style);
    }

    public FontWeight fontWeight(TextStyle style) {
        return design.fontWeight(style);
    }

    public float textSize(TextStyle style) {
        return design.textSize(style);
    }

    public float lineHeight(TextStyle style) {
        return design.lineHeight(style);
    }

    public float letterSpacing(TextStyle style) {
        return design.letterSpacing(style);
    }

    public float motionSeconds(MotionToken token) {
        return design.motionSeconds(token);
    }

    public float motionSeconds(MotionToken token, boolean reducedMotion) {
        return design.motionSeconds(token, reducedMotion);
    }

    public float componentSize(ComponentSize token) {
        return design.componentSize(token);
    }

    public float iconSize(IconMetric metric) {
        return design.iconSize(metric);
    }

    public float iconStrokeWidth(IconMetric metric) {
        return design.iconStrokeWidth(metric);
    }

    public float iconOpticalInset(IconMetric metric) {
        return design.iconOpticalInset(metric);
    }

    public float elevationOffsetX(ElevationToken token) {
        return design.elevationOffsetX(token);
    }

    public float elevationOffsetY(ElevationToken token) {
        return design.elevationOffsetY(token);
    }

    public float elevationBlurRadius(ElevationToken token) {
        return design.elevationBlurRadius(token);
    }

    public float elevationOpacity(ElevationToken token) {
        return design.elevationOpacity(token);
    }

    public float accessibilityMetric(AccessibilityMetric metric) {
        return design.accessibilityMetric(metric);
    }

    public DensityPolicy densityPolicy() {
        return design.densityPolicy();
    }

    public int toLayoutFramebufferPixels(float logicalPixels, float framebufferScale) {
        return design.toLayoutFramebufferPixels(logicalPixels, framebufferScale);
    }

    public int toHitTestFramebufferPixels(float logicalPixels, float framebufferScale) {
        return design.toHitTestFramebufferPixels(logicalPixels, framebufferScale);
    }
}
