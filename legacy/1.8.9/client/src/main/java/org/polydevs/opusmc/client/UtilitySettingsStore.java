package org.polydevs.opusmc.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes the same versioned utility-settings JSON passed by the
 * launcher. It preserves entries for utilities that have not been implemented
 * in the game yet; this client only reads and updates real shipped utilities.
 */
final class UtilitySettingsStore {
    private static final String SETTINGS_PROPERTY = "opus.utility.settings.file";
    private static final int SETTINGS_SCHEMA_VERSION = 1;
    private static final String UTILITIES_PROPERTY = "utilities";
    private static final String FPS_ID = "fps";
    private static final String ARMOR_STATUS_ID = "armor-status";
    private static final Pattern OFFSET_PATTERN =
            Pattern.compile("\\s*(-?\\d+)\\s*(?:·|,|x|×)\\s*(-?\\d+)\\s*");

    private final Logger log;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path settingsPath;

    private JsonObject document = new JsonObject();
    private PerformanceOverlaySettings performanceOverlay = PerformanceOverlaySettings.defaults();
    private ArmorStatusSettings armorStatus = ArmorStatusSettings.defaults();
    private String lastSaveError;

    private UtilitySettingsStore(Logger log, Path settingsPath) {
        this.log = log;
        this.settingsPath = settingsPath;
    }

    static UtilitySettingsStore fromSystemProperty(Logger log) {
        String rawPath = System.getProperty(SETTINGS_PROPERTY);
        if (rawPath == null || rawPath.trim().isEmpty()) {
            log.warn("Opus utility settings path was not supplied; the Performance Overlay remains disabled and changes cannot persist.");
            return new UtilitySettingsStore(log, null);
        }

        try {
            return new UtilitySettingsStore(log, Paths.get(rawPath));
        } catch (RuntimeException exception) {
            log.warn("Opus utility settings path is invalid; the Performance Overlay remains disabled.", exception);
            return new UtilitySettingsStore(log, null);
        }
    }

    synchronized void reload() {
        document = new JsonObject();
        performanceOverlay = PerformanceOverlaySettings.defaults();
        armorStatus = ArmorStatusSettings.defaults();
        lastSaveError = null;

        if (settingsPath == null || !Files.isRegularFile(settingsPath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (!parsed.isJsonObject()) {
                throw new IOException("utility settings root is not a JSON object");
            }

            document = parsed.getAsJsonObject();
            if (readInt(document, "schemaVersion", 0) != SETTINGS_SCHEMA_VERSION) {
                // A file written before a real in-game renderer existed must
                // not silently turn into a visible HUD element. The launcher
                // migrates it too, but this client-side guard makes the
                // failure mode safe even when the game starts first.
                performanceOverlay = PerformanceOverlaySettings.defaults();
                return;
            }
            JsonObject utilities = childObject(document, UTILITIES_PROPERTY, false);
            JsonObject fps = utilities == null ? null : childObject(utilities, FPS_ID, false);
            if (fps != null) {
                performanceOverlay = readPerformanceOverlay(fps);
            }
            JsonObject armor = utilities == null ? null : childObject(utilities, ARMOR_STATUS_ID, false);
            if (armor != null) {
                armorStatus = readArmorStatus(armor);
            }
        } catch (Exception exception) {
            // A damaged preferences file is never allowed to take down the
            // game or manufacture a plausible-looking HUD. It fails closed.
            document = new JsonObject();
            performanceOverlay = PerformanceOverlaySettings.defaults();
            log.warn("Unable to read Opus utility settings; the Performance Overlay remains disabled.", exception);
        }
    }

    synchronized PerformanceOverlaySettings performanceOverlay() {
        return performanceOverlay;
    }

    synchronized ArmorStatusSettings armorStatus() {
        return armorStatus;
    }

    synchronized String lastSaveError() {
        return lastSaveError;
    }

    synchronized boolean updatePerformanceOverlay(PerformanceOverlaySettings next) {
        previewPerformanceOverlay(next);
        return persistPerformanceOverlay();
    }

    synchronized boolean updateArmorStatus(ArmorStatusSettings next) {
        previewArmorStatus(next);
        return persistArmorStatus();
    }

    synchronized void previewPerformanceOverlay(PerformanceOverlaySettings next) {
        performanceOverlay = next;
        lastSaveError = null;
    }

    synchronized void previewArmorStatus(ArmorStatusSettings next) {
        armorStatus = next;
        lastSaveError = null;
    }

    synchronized boolean persistPerformanceOverlay() {
        if (settingsPath == null) {
            lastSaveError = "The launcher did not provide a settings file.";
            return false;
        }

        try {
            document.addProperty("schemaVersion", SETTINGS_SCHEMA_VERSION);
            JsonObject utilities = childObject(document, UTILITIES_PROPERTY, true);
            JsonObject fps = childObject(utilities, FPS_ID, true);
            fps.addProperty("enabled", performanceOverlay.enabled());
            fps.addProperty("anchor", performanceOverlay.anchor().storageValue());
            fps.addProperty("offset", performanceOverlay.offsetX() + " · " + performanceOverlay.offsetY());
            fps.addProperty("scale", performanceOverlay.scale());
            fps.addProperty("opacity", performanceOverlay.opacity());
            writeAtomically(gson.toJson(document));
            return true;
        } catch (Exception exception) {
            lastSaveError = "Could not save the Performance Overlay setting.";
            log.warn(lastSaveError, exception);
            return false;
        }
    }

    synchronized boolean persistArmorStatus() {
        if (settingsPath == null) {
            lastSaveError = "The launcher did not provide a settings file.";
            return false;
        }
        try {
            document.addProperty("schemaVersion", SETTINGS_SCHEMA_VERSION);
            JsonObject utilities = childObject(document, UTILITIES_PROPERTY, true);
            JsonObject armor = childObject(utilities, ARMOR_STATUS_ID, true);
            armor.addProperty("enabled", armorStatus.enabled());
            armor.addProperty("anchor", armorStatus.anchor().storageValue());
            armor.addProperty("offset", armorStatus.offsetX() + " · " + armorStatus.offsetY());
            armor.addProperty("scale", armorStatus.scale());
            armor.addProperty("opacity", armorStatus.opacity());
            armor.addProperty("showDurability", armorStatus.showDurability());
            writeAtomically(gson.toJson(document));
            return true;
        } catch (Exception exception) {
            lastSaveError = "Could not save the Armor Status setting.";
            log.warn(lastSaveError, exception);
            return false;
        }
    }

    private PerformanceOverlaySettings readPerformanceOverlay(JsonObject object) {
        PerformanceOverlaySettings defaults = PerformanceOverlaySettings.defaults();
        boolean enabled = readBoolean(object, "enabled", defaults.enabled());
        PerformanceOverlaySettings.Anchor anchor = PerformanceOverlaySettings.Anchor.fromStorage(
                readString(object, "anchor", defaults.anchor().storageValue()));
        int[] offset = readOffset(
                readString(object, "offset", defaults.offsetX() + " · " + defaults.offsetY()),
                defaults.offsetX(),
                defaults.offsetY());
        int scale = readInt(object, "scale", defaults.scale());
        int opacity = readInt(object, "opacity", defaults.opacity());
        return new PerformanceOverlaySettings(enabled, anchor, offset[0], offset[1], scale, opacity);
    }

    private ArmorStatusSettings readArmorStatus(JsonObject object) {
        ArmorStatusSettings defaults = ArmorStatusSettings.defaults();
        boolean enabled = readBoolean(object, "enabled", defaults.enabled());
        PerformanceOverlaySettings.Anchor anchor = PerformanceOverlaySettings.Anchor.fromStorage(
                readString(object, "anchor", defaults.anchor().storageValue()));
        int[] offset = readOffset(
                readString(object, "offset", defaults.offsetX() + " · " + defaults.offsetY()),
                defaults.offsetX(), defaults.offsetY());
        return new ArmorStatusSettings(
                enabled,
                anchor,
                offset[0],
                offset[1],
                readInt(object, "scale", defaults.scale()),
                readInt(object, "opacity", defaults.opacity()),
                readBoolean(object, "showDurability", defaults.showDurability()));
    }

    private static JsonObject childObject(JsonObject parent, String name, boolean create) {
        JsonElement candidate = parent.get(name);
        if (candidate != null && candidate.isJsonObject()) {
            return candidate.getAsJsonObject();
        }
        if (!create) {
            return null;
        }
        JsonObject child = new JsonObject();
        parent.add(name, child);
        return child;
    }

    private static boolean readBoolean(JsonObject object, String name, boolean fallback) {
        try {
            JsonElement value = object.get(name);
            return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String readString(JsonObject object, String name, String fallback) {
        try {
            JsonElement value = object.get(name);
            return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int readInt(JsonObject object, String name, int fallback) {
        try {
            JsonElement value = object.get(name);
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int[] readOffset(String value, int fallbackX, int fallbackY) {
        Matcher matcher = OFFSET_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return new int[] {fallbackX, fallbackY};
        }
        try {
            return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
        } catch (NumberFormatException ignored) {
            return new int[] {fallbackX, fallbackY};
        }
    }

    private void writeAtomically(String json) throws IOException {
        Path parent = settingsPath.getParent();
        if (parent == null) {
            throw new IOException("utility settings path has no parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = parent.resolve("." + settingsPath.getFileName() + ".opus-part");
        Files.write(temporary, (json + "\n").getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary, settingsPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, settingsPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
