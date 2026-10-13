package org.polydevs.opusmc.client.interop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Separate UI preferences: never creates, migrates or resets HUD/launcher settings. */
final class ClientPreferencesStore {
    private static JsonObject defaults() {
        JsonObject data = new JsonObject();
        data.addProperty("uiScale", 1); data.addProperty("reduceMotion", false);
        data.addProperty("backgroundBlur", 4); data.addProperty("compactMode", false); data.addProperty("accent", "mono");
        return data;
    }
    static JsonObject read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return defaults();
        if (Files.size(path) > 16384) throw new IOException("UI preferences are too large");
        try { return validate(new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject()); }
        catch (RuntimeException invalid) { throw new IOException("UI preferences are invalid", invalid); }
    }
    static JsonObject validate(JsonObject source) {
        JsonObject data = defaults();
        for (String key : new String[] {"reduceMotion", "compactMode"}) {
            if (!source.has(key) || !source.get(key).isJsonPrimitive() || !source.get(key).getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Invalid UI preference");
            data.addProperty(key, source.get(key).getAsBoolean());
        }
        for (String key : new String[] {"uiScale", "backgroundBlur"}) {
            double value = source.get(key).getAsDouble();
            double min = "uiScale".equals(key) ? .5 : 0, max = "uiScale".equals(key) ? 2 : 12;
            if (Double.isNaN(value) || Double.isInfinite(value) || value < min || value > max) throw new IllegalArgumentException("Invalid UI preference");
            data.addProperty(key, value);
        }
        String accent = source.get("accent").getAsString();
        if (!"mono".equals(accent) && !"violet".equals(accent) && !"blue".equals(accent)) throw new IllegalArgumentException("Invalid accent");
        data.addProperty("accent", accent);
        return data;
    }
    static void write(Path path, JsonObject source) throws IOException {
        JsonObject data = validate(source);
        Files.createDirectories(path.getParent());
        Path pending = Files.createTempFile(path.getParent(), ".opus-ui-preferences-", ".tmp");
        try {
            Files.write(pending, data.toString().getBytes(StandardCharsets.UTF_8));
            try { Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException fallback) { Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(pending); }
    }
}
