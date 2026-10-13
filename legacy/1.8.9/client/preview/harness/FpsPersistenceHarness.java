package org.polydevs.opusmc.client;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;

/** Persistence, row-protocol, validation, and rollback contract for FPS appearance. */
public final class FpsPersistenceHarness {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("opus-fps-settings-test-");
        Path file = root.resolve("settings.json");
        String previous = System.getProperty("opus.utility.settings.file");
        System.setProperty("opus.utility.settings.file", file.toString());
        try {
            Files.write(file, ("{\"schemaVersion\":1,\"external\":\"keep\",\"utilities\":{"
                    + "\"fps\":{\"enabled\":true,\"anchor\":\"bottom-right\","
                    + "\"offset\":\"17 · 19\",\"scale\":225,\"opacity\":80,"
                    + "\"reverseOrder\":false,\"textShadow\":false,\"showBrackets\":true,"
                    + "\"bracketColor\":\"#AABBCCDD\",\"showBackground\":true,"
                    + "\"staticBackgroundWidth\":true,\"backgroundWidth\":999,"
                    + "\"backgroundHeight\":22,\"border\":true,\"borderThickness\":75,"
                    + "\"borderColor\":\"#9F112233\",\"backgroundColor\":\"#6F445566\","
                    + "\"textColor\":\"not-a-color\",\"fpsUnknown\":41},"
                    + "\"keystrokes\":{\"enabled\":true,\"scale\":130},"
                    + "\"future\":{\"custom\":9}}}").getBytes(StandardCharsets.UTF_8));
            UtilitySettingsStore store = UtilitySettingsStore.fromSystemProperty(
                    LogManager.getLogger("FpsPersistenceHarness"));
            store.reload();
            FpsModule module = new FpsModule(store);
            require("FPS".equals(module.displayName()), "FPS module display name did not match the product");

            Object loaded = appearance(store.performanceOverlay());
            require(!bool(loaded, "reverseOrder") && !bool(loaded, "textShadow")
                            && bool(loaded, "showBrackets") && bool(loaded, "showBackground")
                            && bool(loaded, "staticBackgroundWidth") && bool(loaded, "border"),
                    "Stored FPS appearance flags were not reloaded");
            require(integer(loaded, "backgroundWidth") == 300
                            && integer(loaded, "backgroundHeight") == 22
                            && integer(loaded, "borderThickness") == 75,
                    "Stored FPS dimensions were not reloaded and clamped");
            require("#aabbccdd".equals(string(loaded, "bracketColor"))
                            && "#9f112233".equals(string(loaded, "borderColor"))
                            && "#6f445566".equals(string(loaded, "backgroundColor"))
                            && "#ffffffff".equals(string(loaded, "textColor")),
                    "Stored FPS colors were not canonicalized or safely defaulted");

            @SuppressWarnings("unchecked")
            List<String[]> rows = (List<String[]>) method(FpsModule.class, "moduleSettings")
                    .invoke(module);
            Map<String, String[]> byKey = new LinkedHashMap<String, String[]>();
            for (String[] row : rows) {
                require(row.length == 8, "FPS setting row did not use the eight-column protocol");
                byKey.put(row[0], row);
            }
            String[] requiredKeys = {
                    "enabled", "scale", "opacity", "anchor", "offsetX", "offsetY",
                    "reverseOrder", "textShadow", "showBrackets", "bracketColor",
                    "showBackground", "staticBackgroundWidth", "backgroundWidth",
                    "backgroundHeight", "border", "borderThickness", "borderColor",
                    "backgroundColor", "textColor",
            };
            require(rows.size() == requiredKeys.length, "FPS settings row count changed: " + rows.size());
            for (String key : requiredKeys) {
                require(byKey.containsKey(key), "FPS settings omitted " + key);
            }
            require(row(byKey, "reverseOrder")[2].equals("boolean")
                            && row(byKey, "reverseOrder")[3].equals("0"),
                    "FPS booleans did not use boolean/1-or-0 wire values");
            require(row(byKey, "bracketColor")[2].equals("color")
                            && row(byKey, "bracketColor")[3].equals("#aabbccdd"),
                    "FPS color row type/value changed");
            require(row(byKey, "scale")[4].equals("50")
                            && row(byKey, "scale")[5].equals("400")
                            && row(byKey, "backgroundWidth")[4].equals("10")
                            && row(byKey, "backgroundWidth")[5].equals("300")
                            && row(byKey, "backgroundHeight")[4].equals("10")
                            && row(byKey, "backgroundHeight")[5].equals("100")
                            && row(byKey, "borderThickness")[4].equals("10")
                            && row(byKey, "borderThickness")[5].equals("500"),
                    "FPS numeric row ranges changed");

            Method apply = method(FpsModule.class, "applySetting", String.class, String.class);
            UtilitySettingsStore staleStore = UtilitySettingsStore.fromSystemProperty(
                    LogManager.getLogger("FpsPersistenceHarness-stale"));
            staleStore.reload();
            FpsModule staleModule = new FpsModule(staleStore);
            require((Boolean) apply.invoke(module, "textColor", "#ABCDEF12"),
                    "Valid uppercase FPS color did not persist");
            require(store.updateKeystrokes(store.keystrokes().withScale(175)),
                    "First settings owner could not persist another module");
            require((Boolean) apply.invoke(staleModule, "backgroundWidth", "88"),
                    "Stale FPS settings owner could not persist its changed field");
            store.reload();
            Object saved = appearance(store.performanceOverlay());
            require(integer(saved, "backgroundWidth") == 88
                            && "#abcdef12".equals(string(saved, "textColor"))
                            && store.keystrokes().scale() == 175,
                    "A stale settings owner reverted newer FPS or other-module fields");
            String confirmedJson = read(file);
            require(confirmedJson.contains("\"external\": \"keep\"")
                            && confirmedJson.contains("\"fpsUnknown\": 41")
                            && confirmedJson.contains("\"future\"")
                            && confirmedJson.contains("\"custom\": 9")
                            && store.keystrokes().enabled() && store.keystrokes().scale() == 175,
                    "Saving FPS appearance erased unknown fields or another module");

            String[] invalidKeys = {"backgroundWidth", "backgroundHeight", "borderThickness",
                    "textColor", "reverseOrder", "anchor", "unknown"};
            String[] invalidValues = {"301", "9", "501", "#123456", "yes", "middle", "1"};
            for (int index = 0; index < invalidKeys.length; index++) {
                require(!(Boolean) apply.invoke(module, invalidKeys[index], invalidValues[index]),
                        "Invalid FPS setting was accepted: " + invalidKeys[index]);
                require(confirmedJson.equals(read(file)),
                        "Rejected FPS setting still rewrote the preferences document");
            }
            require(integer(appearance(store.performanceOverlay()), "backgroundWidth") == 88
                            && "#abcdef12".equals(string(appearance(store.performanceOverlay()), "textColor")),
                    "Rejected FPS setting mutated live state");

            Object previewAppearance = call(saved, "withBackgroundWidth",
                    new Class<?>[] {int.class}, 144);
            PerformanceOverlaySettings preview = (PerformanceOverlaySettings) call(
                    store.performanceOverlay(), "withAppearance",
                    new Class<?>[] {Class.forName("org.polydevs.opusmc.client.FpsAppearance")},
                    previewAppearance);
            PerformanceOverlaySettings resetSnapshot = module.settings();
            UtilitySettingsStore resetPeerStore = UtilitySettingsStore.fromSystemProperty(
                    LogManager.getLogger("FpsPersistenceHarness-reset-peer"));
            resetPeerStore.reload();
            FpsModule resetPeer = new FpsModule(resetPeerStore);
            require((Boolean) apply.invoke(resetPeer, "showBrackets", "0")
                            && (Boolean) apply.invoke(resetPeer, "opacity", "65")
                            && (Boolean) apply.invoke(resetPeer, "anchor", "top-left")
                            && (Boolean) apply.invoke(resetPeer, "offsetX", "97"),
                    "Concurrent settings owner could not prepare the stale reset case");
            byte[] beforeRejectedReset = Files.readAllBytes(file);
            require(!(Boolean) apply.invoke(module, "resetAppearance", "0")
                            && java.util.Arrays.equals(beforeRejectedReset, Files.readAllBytes(file)),
                    "Invalid reset command changed FPS settings");
            require((Boolean) apply.invoke(module, "resetAppearance", "1"), "FPS atomic reset failed");
            store.reload();
            PerformanceOverlaySettings reset = module.settings();
            require(reset.scale() == 100 && reset.appearance().backgroundWidth() == 56
                            && reset.appearance().backgroundHeight() == 18
                            && reset.appearance().borderThickness() == 50
                            && reset.appearance().reverseOrder() && reset.appearance().textShadow()
                            && reset.appearance().showBrackets() && !reset.appearance().showBackground()
                            && reset.appearance().staticBackgroundWidth() && !reset.appearance().border()
                            && reset.appearance().textColor().equals("#ffffffff"),
                    "Atomic FPS reset was not persisted as the complete default appearance");
            require(reset.enabled() == resetSnapshot.enabled() && reset.opacity() == 65
                            && reset.anchor() == PerformanceOverlaySettings.Anchor.TOP_LEFT
                            && reset.offsetX() == 97 && reset.offsetY() == resetSnapshot.offsetY(),
                    "FPS reset reverted newer placement, enable or opacity");
            require(module.update(resetSnapshot), "Could not restore the isolated FPS test snapshot");
            store.previewPerformanceOverlay(preview);
            store.cancelPerformanceOverlayPreview();
            require(integer(appearance(store.performanceOverlay()), "backgroundWidth") == 88,
                    "Cancelling FPS preview changed the persisted appearance");

            Files.delete(file);
            Files.createDirectory(file);
            require(!(Boolean) apply.invoke(module, "backgroundWidth", "90"),
                    "Failed FPS disk write was treated as confirmed");
            require(integer(appearance(store.performanceOverlay()), "backgroundWidth") == 88
                            && store.lastSaveError() != null,
                    "Failed FPS save retained an unpersisted appearance");
            Files.delete(file);
            Files.write(file, confirmedJson.getBytes(StandardCharsets.UTF_8));
            store.reload();
            require(integer(appearance(store.performanceOverlay()), "backgroundWidth") == 88,
                    "Reload after failed FPS save did not recover the last confirmed appearance");
            System.out.println("FPS PERSISTENCE: ALL PASSED");
        } finally {
            Files.deleteIfExists(root.resolve(".settings.json.opus-part"));
            Files.deleteIfExists(file);
            Files.deleteIfExists(root);
            if (previous == null) {
                System.clearProperty("opus.utility.settings.file");
            } else {
                System.setProperty("opus.utility.settings.file", previous);
            }
        }
    }

    private static Object appearance(PerformanceOverlaySettings settings) throws Exception {
        return call(settings, "appearance", new Class<?>[0]);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method result = type.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }

    private static Object call(Object target, String name, Class<?>[] parameters, Object... arguments)
            throws Exception {
        return target.getClass().getMethod(name, parameters).invoke(target, arguments);
    }

    private static boolean bool(Object target, String name) throws Exception {
        return (Boolean) call(target, name, new Class<?>[0]);
    }

    private static int integer(Object target, String name) throws Exception {
        return (Integer) call(target, name, new Class<?>[0]);
    }

    private static String string(Object target, String name) throws Exception {
        return (String) call(target, name, new Class<?>[0]);
    }

    private static String[] row(Map<String, String[]> rows, String key) {
        return rows.get(key);
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
