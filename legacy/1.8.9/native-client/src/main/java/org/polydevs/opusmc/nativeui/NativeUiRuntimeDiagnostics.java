package org.polydevs.opusmc.nativeui;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import gg.essential.universal.UResolution;
import net.minecraft.client.Minecraft;
import org.polydevs.opusmc.nativeui.design.ElementaThemeAdapter;
import org.polydevs.opusmc.nativeui.design.OneConfigThemeAdapter;
import org.polydevs.opusmc.nativeui.design.OpusDesign;
import org.polydevs.opusmc.nativeui.design.OpusDesignParity;
import org.lwjgl.opengl.Display;

public final class NativeUiRuntimeDiagnostics {
    private static final Object LOCK = new Object();
    private static final long DISPLAY_STATE_SAMPLE_NANOS = 100_000_000L;
    private static final long DISPLAY_STATE_SETTLE_NANOS = 300_000_000L;
    private static final Map<String, String> VALUES = new LinkedHashMap<>();
    private static final List<String> LIFECYCLE = new ArrayList<>();
    private static final List<String> OPEN_ORDER = new ArrayList<>();
    private static final List<String> SCREEN_EVENTS = new ArrayList<>();
    private static final List<String> DISPLAY_STATES = new ArrayList<>();
    private static final List<String> GL_ERRORS = new ArrayList<>();
    private static File reportFile;
    private static String lastObservedDisplayState;
    private static String lastRecordedDisplayState;
    private static String lastRecordedDisplayScreen;
    private static int lastRecordedGuiScaleSetting = Integer.MIN_VALUE;
    private static boolean lastRecordedFullscreen;
    private static long lastDisplayStateSampleNanos;
    private static long lastDisplayStateChangeNanos;
    private static boolean displayStateDirty;

    private NativeUiRuntimeDiagnostics() {
    }

    public static void initialize(File gameDirectory) {
        synchronized (LOCK) {
            reportFile = new File(gameDirectory, "native-ui-runtime-report.json");
            VALUES.put("schemaVersion", "2");
            VALUES.put("nativeUiVersion", OpusNativeUiMod.VERSION);
            VALUES.put("runSession", System.getProperty("opus.native.ui.session", "missing"));
            VALUES.put("minecraft", "1.8.9");
            VALUES.put("forge", "11.15.1.2318");
            VALUES.put("elementa", "762");
            VALUES.put("universalCraft", "516");
            VALUES.put("oneConfig", "0.2.2-alpha228-full");
            OpusDesign design = OpusDesign.defaultDesign();
            OpusDesignParity.verify(
                    design,
                    new OneConfigThemeAdapter(design),
                    new ElementaThemeAdapter(design));
            VALUES.put("opusDesignVersion", OpusDesign.VERSION);
            VALUES.put("opusDesignParity", "pass");
            VALUES.put(
                    "optifineExpected",
                    Boolean.toString(Boolean.getBoolean("opus.native.ui.requireOptifine")));
            VALUES.put("javaVersion", System.getProperty("java.version", "unknown"));
            VALUES.put("osName", System.getProperty("os.name", "unknown"));
            VALUES.put("osArch", System.getProperty("os.arch", "unknown"));
            recordCodeSource("oneConfigCodeSource", cc.polyfrost.oneconfig.gui.OneConfigGui.class);
            recordCodeSource("elementaCodeSource", gg.essential.elementa.WindowScreen.class);
            recordCodeSource("universalCraftCodeSource", gg.essential.universal.UScreen.class);
            detectOptifine();
            flush();
        }
    }

    public static void recordLifecycle(String value) {
        synchronized (LOCK) {
            LIFECYCLE.add(value);
            flush();
        }
    }

    public static void recordScreenOpen(String screen) {
        synchronized (LOCK) {
            OPEN_ORDER.add(screen);
            addUnique(SCREEN_EVENTS, "open:" + screen);
            VALUES.put("lastScreen", screen);
            VALUES.put("openCount", Integer.toString(OPEN_ORDER.size()));
            flush();
        }
    }

    public static void recordScreenClose(String screen) {
        synchronized (LOCK) {
            boolean changed = addUnique(SCREEN_EVENTS, "close:" + screen);
            changed |= putIfChanged("lastClosedScreen", screen);
            if (changed) flush();
        }
    }

    public static void recordScreenDraw(String screen, String phase, int glError) {
        synchronized (LOCK) {
            boolean changed = addUnique(SCREEN_EVENTS, "draw-" + phase + ":" + screen);
            changed |= putIfChanged(screen + "Gl" + upperCamelCase(phase), Integer.toString(glError));
            if (glError != 0) {
                String entry = screen + ":" + phase + ":" + glError;
                changed |= addUnique(GL_ERRORS, entry);
            }
            changed |= putIfChanged("glErrorCount", Integer.toString(GL_ERRORS.size()));
            if (changed) flush();
        }
    }

    public static void recordDisplayState(String screen) {
        synchronized (LOCK) {
            int guiScaleSetting = Minecraft.getMinecraft().gameSettings.guiScale;
            boolean fullscreen = Display.isFullscreen();
            String state = screen
                    + "|window=" + Display.getWidth() + "x" + Display.getHeight()
                    + "|framebuffer=" + UResolution.getViewportWidth() + "x" + UResolution.getViewportHeight()
                    + "|gui=" + UResolution.getScaledWidth() + "x" + UResolution.getScaledHeight()
                    + "|scale=" + String.format(Locale.ROOT, "%.3f", UResolution.getScaleFactor())
                    + "|guiScaleSetting=" + guiScaleSetting
                    + "|fullscreen=" + fullscreen;
            long now = System.nanoTime();
            if (!state.equals(lastObservedDisplayState)) {
                lastObservedDisplayState = state;
                lastDisplayStateChangeNanos = now;
            }

            boolean significantTransition = !screen.equals(lastRecordedDisplayScreen)
                    || guiScaleSetting != lastRecordedGuiScaleSetting
                    || fullscreen != lastRecordedFullscreen;
            boolean sampleDue = now - lastDisplayStateSampleNanos >= DISPLAY_STATE_SAMPLE_NANOS;
            if (!state.equals(lastRecordedDisplayState)
                    && (significantTransition || sampleDue)
                    && addUnique(DISPLAY_STATES, state)) {
                lastRecordedDisplayState = state;
                lastRecordedDisplayScreen = screen;
                lastRecordedGuiScaleSetting = guiScaleSetting;
                lastRecordedFullscreen = fullscreen;
                lastDisplayStateSampleNanos = now;
                VALUES.put("lastDisplayState", state);
                VALUES.put("displayStateCount", Integer.toString(DISPLAY_STATES.size()));
                displayStateDirty = true;
            }

            if (displayStateDirty
                    && now - lastDisplayStateChangeNanos >= DISPLAY_STATE_SETTLE_NANOS) {
                flush();
            }
        }
    }

    /**
     * Updates evidence that changes in a render hot path without forcing a
     * synchronous report rewrite. The next settled display state or ordinary
     * lifecycle record persists the latest value.
     */
    public static void recordDeferred(String key, String value) {
        synchronized (LOCK) {
            putIfChanged(key, value);
        }
    }

    public static void record(String key, String value) {
        synchronized (LOCK) {
            if (putIfChanged(key, value)) flush();
        }
    }

    private static void recordCodeSource(String key, Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        URL location = source == null ? null : source.getLocation();
        VALUES.put(key, location == null ? "unknown" : location.toExternalForm());
    }

    private static void detectOptifine() {
        for (String className : new String[]{"Config", "net.minecraft.src.Config", "net.optifine.Config"}) {
            try {
                Class<?> config = Class.forName(className);
                Object version = config.getMethod("getVersion").invoke(null);
                VALUES.put("optifinePresent", "true");
                VALUES.put("optifineVersion", version == null ? "unknown" : version.toString());
                VALUES.put("optifineConfigClass", className);
                return;
            } catch (Throwable ignored) {
                // OptiFine has used different Config packages across legacy releases.
            }
        }
        VALUES.put("optifinePresent", "false");
        VALUES.put("optifineVersion", "absent");
        VALUES.put("optifineConfigClass", "absent");
    }

    private static boolean addUnique(List<String> values, String value) {
        if (!values.contains(value)) {
            values.add(value);
            return true;
        }
        return false;
    }

    private static boolean putIfChanged(String key, String value) {
        String previous = VALUES.put(key, value);
        return previous == null || !previous.equals(value);
    }

    private static String upperCamelCase(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder result = new StringBuilder(value.length());
        boolean capitalizeNext = true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '-' || character == '_') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(character));
                capitalizeNext = false;
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static void flush() {
        if (reportFile == null) return;
        displayStateDirty = false;
        File parent = reportFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(reportFile), StandardCharsets.UTF_8))) {
            writer.write("{\n");
            writer.write("  \"updatedAt\": \"");
            writer.write(escape(Instant.now().toString()));
            writer.write("\",\n");
            int index = 0;
            for (Map.Entry<String, String> entry : VALUES.entrySet()) {
                writer.write("  \"");
                writer.write(escape(entry.getKey()));
                writer.write("\": \"");
                writer.write(escape(entry.getValue()));
                writer.write("\",\n");
                index++;
            }
            writeArray(writer, "lifecycle", LIFECYCLE, true);
            writeArray(writer, "openOrder", OPEN_ORDER, true);
            writeArray(writer, "screenEvents", SCREEN_EVENTS, true);
            writeArray(writer, "displayStates", DISPLAY_STATES, true);
            writeArray(writer, "glErrors", GL_ERRORS, false);
            writer.write("}\n");
        } catch (IOException ignored) {
            // Diagnostics must keep the game usable even when storage is read-only.
        }
    }

    private static void writeArray(
            BufferedWriter writer,
            String name,
            List<String> values,
            boolean trailingComma) throws IOException {
        writer.write("  \"");
        writer.write(name);
        writer.write("\": [");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) writer.write(", ");
            writer.write("\"");
            writer.write(escape(values.get(index)));
            writer.write("\"");
        }
        writer.write(trailingComma ? "],\n" : "]\n");
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
