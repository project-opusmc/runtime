package org.polydevs.opusmc.nativeui.design;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.polydevs.opusmc.nativeui.design.OpusDesign.AccessibilityMetric;
import org.polydevs.opusmc.nativeui.design.OpusDesign.ColorRole;
import org.polydevs.opusmc.nativeui.design.OpusDesign.ComponentSize;
import org.polydevs.opusmc.nativeui.design.OpusDesign.ElevationToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.IconMetric;
import org.polydevs.opusmc.nativeui.design.OpusDesign.MotionToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.RadiusToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.SpacingToken;
import org.polydevs.opusmc.nativeui.design.OpusDesign.TextStyle;

/** Plain Java 8 contract test; it needs no additional network-fetched test library. */
public final class OpusDesignContractTest {
    private OpusDesignContractTest() {
    }

    public static void main(String[] args) throws Exception {
        OpusDesign design = OpusDesign.defaultDesign();
        OneConfigThemeAdapter oneConfig = new OneConfigThemeAdapter(design);
        ElementaThemeAdapter elementa = new ElementaThemeAdapter(design);
        OpusDesignParity.verify(design, oneConfig, elementa);

        require("#0C0E14F5".equals(design.color(ColorRole.BACKDROP).toHexRgba()), "backdrop token changed");
        require("#775FFF".equals(design.color(ColorRole.ACCENT).toHexRgba().substring(0, 7)), "accent token changed");
        require(design.spacing(SpacingToken.PANEL_INSET) == 18f, "panel inset token changed");
        require(design.radius(RadiusToken.MODAL) == 8f, "modal radius token changed");
        require(design.textScale(TextStyle.TITLE) == 1.4f, "title scale token changed");
        require(design.textSize(TextStyle.BODY) == 13f, "body text size token changed");
        require(design.lineHeight(TextStyle.BODY) == 1.45f, "body line height token changed");
        require(design.fontWeight(TextStyle.CONTROL).getNumericWeight() == 500,
                "control font weight token changed");
        require(design.motionSeconds(MotionToken.HOVER) == 0.25f, "hover motion token changed");
        require(design.motionSeconds(MotionToken.HOVER, true) == 0.01f,
                "reduced-motion token changed");
        require(design.componentSize(ComponentSize.BUTTON_HEIGHT) == 34f, "button height token changed");
        require(design.componentSize(ComponentSize.MIN_HIT_TARGET) == 44f,
                "minimum hit target token changed");
        require(design.iconSize(IconMetric.MEDIUM) == 20f, "icon size token changed");
        require(design.elevationBlurRadius(ElevationToken.MODAL) == 24f,
                "modal elevation token changed");
        require(design.accessibilityMetric(AccessibilityMetric.MINIMUM_HIT_TARGET) == 44f,
                "accessibility hit target token changed");
        require(design.toLayoutFramebufferPixels(12.5f, 1.5f) == 19,
                "layout framebuffer rounding changed");
        require(design.toLayoutFramebufferPixels(12.5f, 1.5f)
                        == design.toHitTestFramebufferPixels(12.5f, 1.5f),
                "layout and hit-test rounding diverged");

        List<String> matrix = OpusDesignParity.canonicalComponentStateMatrix();
        require(matrix.size() == 4 * OpusDesign.InteractionState.values().length, "state matrix is incomplete");
        require(matrix.contains("button:normal"), "normal button state is missing");
        require(matrix.contains("modal:error"), "error modal state is missing");

        String reportPath = System.getProperty("opus.design.report");
        if (reportPath != null && !reportPath.trim().isEmpty()) {
            writeReport(new File(reportPath), design, matrix);
        }
        System.out.println("verified OpusDesign contract: " + matrix.size() + " canonical component states");
    }

    private static void writeReport(File destination, OpusDesign design, List<String> matrix) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create design report directory: " + parent);
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(destination), StandardCharsets.UTF_8))) {
            writer.write("{\n");
            writer.write("  \"schemaVersion\": 2,\n");
            writer.write("  \"designVersion\": " + quote(OpusDesign.VERSION) + ",\n");
            writer.write("  \"rendererNeutral\": true,\n");
            writer.write("  \"densityPolicy\": " + quote(design.densityPolicy().name()) + ",\n");
            writer.write("  \"canonicalSignature\": " + quote(design.canonicalSignature()) + ",\n");
            writer.write("  \"colors\": {");
            for (int index = 0; index < ColorRole.values().length; index++) {
                ColorRole role = ColorRole.values()[index];
                if (index > 0) writer.write(",");
                writer.write("\n    " + quote(role.name()) + ": " + quote(design.color(role).toHexRgba()));
            }
            writer.write("\n  },\n");
            writeNumericEnum(writer, "spacing", SpacingToken.values(), design);
            writer.write(",\n");
            writeNumericEnum(writer, "radii", RadiusToken.values(), design);
            writer.write(",\n");
            writeNumericEnum(writer, "motionSeconds", MotionToken.values(), design);
            writer.write(",\n");
            writer.write("  \"reducedMotionSeconds\": {");
            for (int index = 0; index < MotionToken.values().length; index++) {
                MotionToken token = MotionToken.values()[index];
                if (index > 0) writer.write(",");
                writer.write("\n    " + quote(token.name()) + ": " + design.motionSeconds(token, true));
            }
            writer.write("\n  },\n");
            writeNumericEnum(writer, "componentSizes", ComponentSize.values(), design);
            writer.write(",\n  \"typography\": {");
            for (int index = 0; index < TextStyle.values().length; index++) {
                TextStyle style = TextStyle.values()[index];
                if (index > 0) writer.write(",");
                writer.write("\n    " + quote(style.name()) + ": {");
                writer.write("\"family\": " + quote(design.fontFamily(style).name()));
                writer.write(", \"familyName\": " + quote(design.fontFamily(style).getCssLikeName()));
                writer.write(", \"weight\": " + design.fontWeight(style).getNumericWeight());
                writer.write(", \"size\": " + design.textSize(style));
                writer.write(", \"lineHeight\": " + design.lineHeight(style));
                writer.write(", \"letterSpacing\": " + design.letterSpacing(style));
                writer.write(", \"scale\": " + design.textScale(style) + "}");
            }
            writer.write("\n  },\n  \"icons\": {");
            for (int index = 0; index < IconMetric.values().length; index++) {
                IconMetric metric = IconMetric.values()[index];
                if (index > 0) writer.write(",");
                writer.write("\n    " + quote(metric.name()) + ": {\"size\": "
                        + design.iconSize(metric)
                        + ", \"strokeWidth\": " + design.iconStrokeWidth(metric)
                        + ", \"opticalInset\": " + design.iconOpticalInset(metric) + "}");
            }
            writer.write("\n  },\n  \"elevation\": {");
            for (int index = 0; index < ElevationToken.values().length; index++) {
                ElevationToken token = ElevationToken.values()[index];
                if (index > 0) writer.write(",");
                writer.write("\n    " + quote(token.name()) + ": {\"offsetX\": "
                        + design.elevationOffsetX(token)
                        + ", \"offsetY\": " + design.elevationOffsetY(token)
                        + ", \"blurRadius\": " + design.elevationBlurRadius(token)
                        + ", \"opacity\": " + design.elevationOpacity(token) + "}");
            }
            writer.write("\n  },\n  \"accessibility\": {");
            for (int index = 0; index < AccessibilityMetric.values().length; index++) {
                AccessibilityMetric metric = AccessibilityMetric.values()[index];
                if (index > 0) writer.write(",");
                writer.write("\n    " + quote(metric.name()) + ": " + design.accessibilityMetric(metric));
            }
            writer.write("\n  },\n  \"framebufferRounding\": [");
            float[] logicalSamples = {0f, 0.5f, 1f, 12.5f, 44f};
            float[] scaleSamples = {1f, 1.25f, 1.5f, 2f};
            boolean firstSample = true;
            for (float logical : logicalSamples) {
                for (float scale : scaleSamples) {
                    if (!firstSample) writer.write(",");
                    firstSample = false;
                    writer.write("\n    {\"logical\": " + logical
                            + ", \"scale\": " + scale
                            + ", \"layout\": " + design.toLayoutFramebufferPixels(logical, scale)
                            + ", \"hitTest\": " + design.toHitTestFramebufferPixels(logical, scale) + "}");
                }
            }
            writer.write("\n  ],\n  \"adapters\": {\n");
            writer.write("    \"oneConfig\": \"OneConfigThemeAdapter\",\n");
            writer.write("    \"elementa\": \"ElementaThemeAdapter\"\n");
            writer.write("  },\n  \"canonicalComponentStates\": [");
            for (int index = 0; index < matrix.size(); index++) {
                if (index > 0) writer.write(", ");
                writer.write(quote(matrix.get(index)));
            }
            writer.write("],\n  \"parity\": \"pass\"\n}\n");
        }
    }

    private static void writeNumericEnum(
            BufferedWriter writer,
            String name,
            Enum<?>[] values,
            OpusDesign design) throws IOException {
        writer.write("  \"" + name + "\": {");
        for (int index = 0; index < values.length; index++) {
            Enum<?> value = values[index];
            if (index > 0) writer.write(",");
            float number;
            if (value instanceof SpacingToken) number = design.spacing((SpacingToken) value);
            else if (value instanceof RadiusToken) number = design.radius((RadiusToken) value);
            else if (value instanceof MotionToken) number = design.motionSeconds((MotionToken) value);
            else if (value instanceof ComponentSize) number = design.componentSize((ComponentSize) value);
            else throw new IllegalArgumentException("Unsupported numeric design enum: " + value);
            writer.write("\n    " + quote(value.name()) + ": " + number);
        }
        writer.write("\n  }");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
