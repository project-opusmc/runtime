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
    private static final String KEYSTROKES_ID = "keystrokes";
    private static final Pattern OFFSET_PATTERN =
            Pattern.compile("\\s*(-?\\d+)\\s*(?:·|,|x|×)\\s*(-?\\d+)\\s*");

    private final Logger log;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path settingsPath;

    private JsonObject document = new JsonObject();
    private PerformanceOverlaySettings performanceOverlay = PerformanceOverlaySettings.defaults();
    private ArmorStatusSettings armorStatus = ArmorStatusSettings.defaults();
    private KeystrokesSettings keystrokes = KeystrokesSettings.defaults();
    private PerformanceOverlaySettings persistedPerformanceOverlay = performanceOverlay;
    private ArmorStatusSettings persistedArmorStatus = armorStatus;
    private KeystrokesSettings persistedKeystrokes = keystrokes;
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
        keystrokes = KeystrokesSettings.defaults();
        persistedPerformanceOverlay = performanceOverlay;
        persistedArmorStatus = armorStatus;
        persistedKeystrokes = keystrokes;
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
            JsonObject keys = utilities == null ? null : childObject(utilities, KEYSTROKES_ID, false);
            if (keys != null) keystrokes = readKeystrokes(keys);
            persistedPerformanceOverlay = performanceOverlay;
            persistedArmorStatus = armorStatus;
            persistedKeystrokes = keystrokes;
        } catch (Exception exception) {
            // A damaged preferences file is never allowed to take down the
            // game or manufacture a plausible-looking HUD. It fails closed.
            document = new JsonObject();
            performanceOverlay = PerformanceOverlaySettings.defaults();
            armorStatus = ArmorStatusSettings.defaults();
            keystrokes = KeystrokesSettings.defaults();
            persistedPerformanceOverlay = performanceOverlay;
            persistedArmorStatus = armorStatus;
            persistedKeystrokes = keystrokes;
            log.warn("Unable to read Opus utility settings; the Performance Overlay remains disabled.", exception);
        }
    }

    synchronized PerformanceOverlaySettings performanceOverlay() {
        return performanceOverlay;
    }

    synchronized ArmorStatusSettings armorStatus() {
        return armorStatus;
    }

    synchronized KeystrokesSettings keystrokes() { return keystrokes; }

    synchronized boolean updateKeystrokes(KeystrokesSettings next) {
        previewKeystrokes(next);
        return persistKeystrokes();
    }

    synchronized void previewKeystrokes(KeystrokesSettings next) {
        keystrokes = next;
        lastSaveError = null;
    }

    synchronized void cancelKeystrokesPreview() { keystrokes = persistedKeystrokes; }

    synchronized boolean persistKeystrokes() {
        if (settingsPath == null) {
            lastSaveError = "The launcher did not provide a settings file.";
            cancelKeystrokesPreview();
            return false;
        }
        JsonObject previousDocument = copyDocument();
        try {
            JsonObject nextDocument = latestDocumentForWrite();
            boolean writeAll = readInt(nextDocument, "schemaVersion", 0) != SETTINGS_SCHEMA_VERSION;
            nextDocument.addProperty("schemaVersion", SETTINGS_SCHEMA_VERSION);
            JsonObject utilities = childObject(nextDocument, UTILITIES_PROPERTY, true);
            JsonObject keys = childObject(utilities, KEYSTROKES_ID, false);
            if (keys == null) {
                keys = childObject(utilities, KEYSTROKES_ID, true);
                writeAll = true;
            }
            mergeKeystrokes(keys, writeAll);
            writeAtomically(gson.toJson(nextDocument));
            adoptDocument(nextDocument);
            return true;
        } catch (Exception exception) {
            document = previousDocument;
            lastSaveError = "Could not save the Keystrokes setting.";
            cancelKeystrokesPreview();
            log.warn(lastSaveError, exception);
            return false;
        }
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

    synchronized void cancelPerformanceOverlayPreview() {
        performanceOverlay = persistedPerformanceOverlay;
    }

    synchronized void cancelArmorStatusPreview() {
        armorStatus = persistedArmorStatus;
    }

    synchronized boolean persistPerformanceOverlay() {
        return persistPerformanceOverlay(false);
    }

    synchronized boolean resetPerformanceOverlayAppearance() {
        PerformanceOverlaySettings current = performanceOverlay;
        previewPerformanceOverlay(current.withScale(100).withAppearance(FpsAppearance.defaults()));
        return persistPerformanceOverlay(true);
    }

    private boolean persistPerformanceOverlay(boolean resetAppearance) {
        if (settingsPath == null) {
            lastSaveError = "The launcher did not provide a settings file.";
            cancelPerformanceOverlayPreview();
            return false;
        }

        JsonObject previousDocument = copyDocument();
        try {
            JsonObject nextDocument = latestDocumentForWrite();
            boolean writeAll = readInt(nextDocument, "schemaVersion", 0) != SETTINGS_SCHEMA_VERSION;
            nextDocument.addProperty("schemaVersion", SETTINGS_SCHEMA_VERSION);
            JsonObject utilities = childObject(nextDocument, UTILITIES_PROPERTY, true);
            JsonObject fps = childObject(utilities, FPS_ID, false);
            if (fps == null) {
                fps = childObject(utilities, FPS_ID, true);
                writeAll = true;
            }
            mergePerformanceOverlay(fps, writeAll, resetAppearance);
            writeAtomically(gson.toJson(nextDocument));
            adoptDocument(nextDocument);
            return true;
        } catch (Exception exception) {
            document = previousDocument;
            cancelPerformanceOverlayPreview();
            lastSaveError = "Could not save the Performance Overlay setting.";
            log.warn(lastSaveError, exception);
            return false;
        }
    }

    synchronized boolean persistArmorStatus() {
        if (settingsPath == null) {
            lastSaveError = "The launcher did not provide a settings file.";
            cancelArmorStatusPreview();
            return false;
        }
        JsonObject previousDocument = copyDocument();
        try {
            JsonObject nextDocument = latestDocumentForWrite();
            boolean writeAll = readInt(nextDocument, "schemaVersion", 0) != SETTINGS_SCHEMA_VERSION;
            nextDocument.addProperty("schemaVersion", SETTINGS_SCHEMA_VERSION);
            JsonObject utilities = childObject(nextDocument, UTILITIES_PROPERTY, true);
            JsonObject armor = childObject(utilities, ARMOR_STATUS_ID, false);
            if (armor == null) {
                armor = childObject(utilities, ARMOR_STATUS_ID, true);
                writeAll = true;
            }
            mergeArmorStatus(armor, writeAll);
            writeAtomically(gson.toJson(nextDocument));
            adoptDocument(nextDocument);
            return true;
        } catch (Exception exception) {
            document = previousDocument;
            cancelArmorStatusPreview();
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
        FpsAppearance appearanceDefaults = defaults.appearance();
        FpsAppearance appearance = new FpsAppearance(
                readBoolean(object, "reverseOrder", appearanceDefaults.reverseOrder()),
                readBoolean(object, "textShadow", appearanceDefaults.textShadow()),
                readBoolean(object, "showBrackets", appearanceDefaults.showBrackets()),
                readColor(object, "bracketColor", appearanceDefaults.bracketColor()),
                readBoolean(object, "showBackground", appearanceDefaults.showBackground()),
                readBoolean(object, "staticBackgroundWidth", appearanceDefaults.staticBackgroundWidth()),
                readInt(object, "backgroundWidth", appearanceDefaults.backgroundWidth()),
                readInt(object, "backgroundHeight", appearanceDefaults.backgroundHeight()),
                readBoolean(object, "border", appearanceDefaults.border()),
                readInt(object, "borderThickness", appearanceDefaults.borderThickness()),
                readColor(object, "borderColor", appearanceDefaults.borderColor()),
                readColor(object, "backgroundColor", appearanceDefaults.backgroundColor()),
                readColor(object, "textColor", appearanceDefaults.textColor()));
        return new PerformanceOverlaySettings(
                enabled, anchor, offset[0], offset[1], scale, opacity, appearance);
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

    private KeystrokesSettings readKeystrokes(JsonObject object) {
        KeystrokesSettings defaults = KeystrokesSettings.defaults();
        int[] offset = readOffset(readString(object, "offset", "12 · 50"), defaults.offsetX(), defaults.offsetY());
        return new KeystrokesSettings(readBoolean(object, "enabled", defaults.enabled()),
                PerformanceOverlaySettings.Anchor.fromStorage(readString(object, "anchor", defaults.anchor().storageValue())),
                offset[0], offset[1], readInt(object, "scale", defaults.scale()), readInt(object, "opacity", defaults.opacity()),
                readBoolean(object, "showMouseButtons", defaults.showMouseButtons()), readBoolean(object, "showJump", defaults.showJump()));
    }

    private void mergePerformanceOverlay(JsonObject fps, boolean writeAll, boolean resetAppearance) {
        PerformanceOverlaySettings current = performanceOverlay;
        PerformanceOverlaySettings baseline = persistedPerformanceOverlay;
        PerformanceOverlaySettings latest = readPerformanceOverlay(fps);
        if (writeAll || current.enabled() != baseline.enabled()) {
            fps.addProperty("enabled", current.enabled());
        }
        if (writeAll || current.anchor() != baseline.anchor()) {
            fps.addProperty("anchor", current.anchor().storageValue());
        }
        boolean offsetXChanged = current.offsetX() != baseline.offsetX();
        boolean offsetYChanged = current.offsetY() != baseline.offsetY();
        if (writeAll || offsetXChanged || offsetYChanged) {
            int offsetX = writeAll || offsetXChanged ? current.offsetX() : latest.offsetX();
            int offsetY = writeAll || offsetYChanged ? current.offsetY() : latest.offsetY();
            fps.addProperty("offset", offsetX + " · " + offsetY);
        }
        if (writeAll || resetAppearance || current.scale() != baseline.scale()) {
            fps.addProperty("scale", current.scale());
        }
        if (writeAll || current.opacity() != baseline.opacity()) {
            fps.addProperty("opacity", current.opacity());
        }

        FpsAppearance appearance = current.appearance();
        FpsAppearance persisted = baseline.appearance();
        if (writeAll || resetAppearance || appearance.reverseOrder() != persisted.reverseOrder()) {
            fps.addProperty("reverseOrder", appearance.reverseOrder());
        }
        if (writeAll || resetAppearance || appearance.textShadow() != persisted.textShadow()) {
            fps.addProperty("textShadow", appearance.textShadow());
        }
        if (writeAll || resetAppearance || appearance.showBrackets() != persisted.showBrackets()) {
            fps.addProperty("showBrackets", appearance.showBrackets());
        }
        if (writeAll || resetAppearance || !appearance.bracketColor().equals(persisted.bracketColor())) {
            fps.addProperty("bracketColor", appearance.bracketColor());
        }
        if (writeAll || resetAppearance || appearance.showBackground() != persisted.showBackground()) {
            fps.addProperty("showBackground", appearance.showBackground());
        }
        if (writeAll || resetAppearance || appearance.staticBackgroundWidth() != persisted.staticBackgroundWidth()) {
            fps.addProperty("staticBackgroundWidth", appearance.staticBackgroundWidth());
        }
        if (writeAll || resetAppearance || appearance.backgroundWidth() != persisted.backgroundWidth()) {
            fps.addProperty("backgroundWidth", appearance.backgroundWidth());
        }
        if (writeAll || resetAppearance || appearance.backgroundHeight() != persisted.backgroundHeight()) {
            fps.addProperty("backgroundHeight", appearance.backgroundHeight());
        }
        if (writeAll || resetAppearance || appearance.border() != persisted.border()) {
            fps.addProperty("border", appearance.border());
        }
        if (writeAll || resetAppearance || appearance.borderThickness() != persisted.borderThickness()) {
            fps.addProperty("borderThickness", appearance.borderThickness());
        }
        if (writeAll || resetAppearance || !appearance.borderColor().equals(persisted.borderColor())) {
            fps.addProperty("borderColor", appearance.borderColor());
        }
        if (writeAll || resetAppearance || !appearance.backgroundColor().equals(persisted.backgroundColor())) {
            fps.addProperty("backgroundColor", appearance.backgroundColor());
        }
        if (writeAll || resetAppearance || !appearance.textColor().equals(persisted.textColor())) {
            fps.addProperty("textColor", appearance.textColor());
        }
    }

    private void mergeArmorStatus(JsonObject armor, boolean writeAll) {
        ArmorStatusSettings current = armorStatus;
        ArmorStatusSettings baseline = persistedArmorStatus;
        ArmorStatusSettings latest = readArmorStatus(armor);
        if (writeAll || current.enabled() != baseline.enabled()) {
            armor.addProperty("enabled", current.enabled());
        }
        if (writeAll || current.anchor() != baseline.anchor()) {
            armor.addProperty("anchor", current.anchor().storageValue());
        }
        boolean offsetXChanged = current.offsetX() != baseline.offsetX();
        boolean offsetYChanged = current.offsetY() != baseline.offsetY();
        if (writeAll || offsetXChanged || offsetYChanged) {
            int offsetX = writeAll || offsetXChanged ? current.offsetX() : latest.offsetX();
            int offsetY = writeAll || offsetYChanged ? current.offsetY() : latest.offsetY();
            armor.addProperty("offset", offsetX + " · " + offsetY);
        }
        if (writeAll || current.scale() != baseline.scale()) {
            armor.addProperty("scale", current.scale());
        }
        if (writeAll || current.opacity() != baseline.opacity()) {
            armor.addProperty("opacity", current.opacity());
        }
        if (writeAll || current.showDurability() != baseline.showDurability()) {
            armor.addProperty("showDurability", current.showDurability());
        }
    }

    private void mergeKeystrokes(JsonObject keys, boolean writeAll) {
        KeystrokesSettings current = keystrokes;
        KeystrokesSettings baseline = persistedKeystrokes;
        KeystrokesSettings latest = readKeystrokes(keys);
        if (writeAll || current.enabled() != baseline.enabled()) {
            keys.addProperty("enabled", current.enabled());
        }
        if (writeAll || current.anchor() != baseline.anchor()) {
            keys.addProperty("anchor", current.anchor().storageValue());
        }
        boolean offsetXChanged = current.offsetX() != baseline.offsetX();
        boolean offsetYChanged = current.offsetY() != baseline.offsetY();
        if (writeAll || offsetXChanged || offsetYChanged) {
            int offsetX = writeAll || offsetXChanged ? current.offsetX() : latest.offsetX();
            int offsetY = writeAll || offsetYChanged ? current.offsetY() : latest.offsetY();
            keys.addProperty("offset", offsetX + " · " + offsetY);
        }
        if (writeAll || current.scale() != baseline.scale()) {
            keys.addProperty("scale", current.scale());
        }
        if (writeAll || current.opacity() != baseline.opacity()) {
            keys.addProperty("opacity", current.opacity());
        }
        if (writeAll || current.showMouseButtons() != baseline.showMouseButtons()) {
            keys.addProperty("showMouseButtons", current.showMouseButtons());
        }
        if (writeAll || current.showJump() != baseline.showJump()) {
            keys.addProperty("showJump", current.showJump());
        }
    }

    private JsonObject latestDocumentForWrite() throws IOException {
        if (!Files.isRegularFile(settingsPath)) {
            return copyDocument();
        }
        try (Reader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (!parsed.isJsonObject()) {
                throw new IOException("utility settings root is not a JSON object");
            }
            JsonObject latest = parsed.getAsJsonObject();
            int schemaVersion = readInt(latest, "schemaVersion", 0);
            if (schemaVersion > SETTINGS_SCHEMA_VERSION) {
                throw new IOException("utility settings schema is newer than this client");
            }
            return latest;
        } catch (RuntimeException exception) {
            throw new IOException("utility settings file is invalid", exception);
        }
    }

    private void adoptDocument(JsonObject nextDocument) {
        document = nextDocument;
        JsonObject utilities = childObject(document, UTILITIES_PROPERTY, false);
        JsonObject fps = utilities == null ? null : childObject(utilities, FPS_ID, false);
        JsonObject armor = utilities == null ? null : childObject(utilities, ARMOR_STATUS_ID, false);
        JsonObject keys = utilities == null ? null : childObject(utilities, KEYSTROKES_ID, false);
        performanceOverlay = fps == null ? PerformanceOverlaySettings.defaults() : readPerformanceOverlay(fps);
        armorStatus = armor == null ? ArmorStatusSettings.defaults() : readArmorStatus(armor);
        keystrokes = keys == null ? KeystrokesSettings.defaults() : readKeystrokes(keys);
        persistedPerformanceOverlay = performanceOverlay;
        persistedArmorStatus = armorStatus;
        persistedKeystrokes = keystrokes;
        lastSaveError = null;
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

    private JsonObject copyDocument() {
        return new JsonParser().parse(gson.toJson(document)).getAsJsonObject();
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

    private static String readColor(JsonObject object, String name, String fallback) {
        try {
            return FpsAppearance.canonicalColor(readString(object, name, fallback));
        } catch (IllegalArgumentException ignored) {
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
