package org.polydevs.opusmc.client;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;

/** New Keystrokes preferences must share atomic storage without resetting old HUD choices. */
public final class HudSettingsHarness {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("opus-hud-settings-test-");
        Path file = root.resolve("settings.json");
        String before = System.getProperty("opus.utility.settings.file");
        System.setProperty("opus.utility.settings.file", file.toString());
        try {
            Files.write(file, ("{\"schemaVersion\":1,\"external\":\"keep\",\"utilities\":{"
                    + "\"fps\":{\"enabled\":true,\"scale\":125},\"armor-status\":{\"enabled\":true},"
                    + "\"future\":{\"custom\":9}}}").getBytes(StandardCharsets.UTF_8));
            UtilitySettingsStore store = UtilitySettingsStore.fromSystemProperty(LogManager.getLogger("HudSettingsHarness"));
            store.reload();
            // Reflection lets RED exercise the prior compiled production store,
            // not fail because the test itself cannot link to a new method.
            Method get = UtilitySettingsStore.class.getDeclaredMethod("keystrokes");
            KeystrokesSettings initial = (KeystrokesSettings) get.invoke(store);
            require(!initial.enabled() && initial.offsetY() == 50, "New widget silently enabled or overlaps default FPS");
            Method update = UtilitySettingsStore.class.getDeclaredMethod("updateKeystrokes", KeystrokesSettings.class);
            Method preview = UtilitySettingsStore.class.getDeclaredMethod("previewKeystrokes", KeystrokesSettings.class);
            Method cancel = UtilitySettingsStore.class.getDeclaredMethod("cancelKeystrokesPreview");
            KeystrokesSettings next = initial.withEnabled(true).withOffset(34, 78).withScale(130)
                    .withShowMouseButtons(false).withShowJump(false);
            require((Boolean) update.invoke(store, next), "Keystrokes changes did not save");
            store.reload();
            KeystrokesSettings saved = (KeystrokesSettings) get.invoke(store);
            require(saved.enabled() && saved.offsetX() == 34 && saved.offsetY() == 78 && saved.scale() == 130
                    && !saved.showMouseButtons() && !saved.showJump(), "Keystrokes options lost on reload");
            require(store.performanceOverlay().enabled() && store.performanceOverlay().scale() == 125
                    && store.armorStatus().enabled(), "New module reset existing HUD preferences");
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            require(json.contains("external") && json.contains("future") && json.contains("\"custom\": 9"),
                    "Saving new module erased unknown settings");
            preview.invoke(store, saved.withOffset(300, 200));
            cancel.invoke(store);
            require(((KeystrokesSettings) get.invoke(store)).offsetX() == 34, "Cancelled preview changed persisted placement");
            Files.delete(file);
            Files.createDirectory(file); // Deterministic write failure, including when running as file owner.
            require(!(Boolean) update.invoke(store, saved.withScale(140)) && store.lastSaveError() != null,
                    "Failed disk write was treated as saved");
            require(((KeystrokesSettings) get.invoke(store)).scale() == 130, "Failed save retained an unpersisted HUD state");
            require(!store.updatePerformanceOverlay(store.performanceOverlay().withScale(140))
                            && store.performanceOverlay().scale() == 125,
                    "Failed FPS save left a fake confirmed value");
            require(!store.updateArmorStatus(store.armorStatus().withScale(140)) && store.armorStatus().scale() == 100,
                    "Failed Armor save left a fake confirmed value");
            Files.delete(file);
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
            require(store.updatePerformanceOverlay(store.performanceOverlay().withOpacity(90)), "Save did not recover after disk failure");
            store.reload();
            require(((KeystrokesSettings) get.invoke(store)).scale() == 130 && store.armorStatus().scale() == 100,
                    "A later HUD save leaked another module's rejected values into the document");
            System.out.println("PASS: Keystrokes opt-in/persistence/cancel/failure rollback and old/unknown preference retention");
        } finally {
            Files.deleteIfExists(root.resolve(".settings.json.opus-part"));
            Files.deleteIfExists(file);
            Files.deleteIfExists(root);
            if (before == null) System.clearProperty("opus.utility.settings.file");
            else System.setProperty("opus.utility.settings.file", before);
        }
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
