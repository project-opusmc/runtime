package org.polydevs.opusmc.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.logging.log4j.Logger;

/**
 * Development-only theme hot reload. It remains inert until an explicit local
 * file path is supplied, so a packaged player session cannot be influenced by
 * files outside its signed mod JAR.
 */
final class UiThemeStore {
    static final String THEME_FILE_PROPERTY = "opus.ui.preview.theme.file";
    private static final int SCHEMA_VERSION = 1;

    private final Logger log;
    private final Path path;
    private long lastModified = Long.MIN_VALUE;
    private long lastSize = Long.MIN_VALUE;

    private UiThemeStore(Logger log, Path path) {
        this.log = log;
        this.path = path;
    }

    static UiThemeStore fromSystemProperties(Logger log) {
        String rawPath = System.getProperty(THEME_FILE_PROPERTY);
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return new UiThemeStore(log, null);
        }
        try {
            return new UiThemeStore(log, Paths.get(rawPath));
        } catch (RuntimeException exception) {
            log.warn("Opus UI preview theme path is invalid; using shipped visual tokens.", exception);
            return new UiThemeStore(log, null);
        }
    }

    void poll() {
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            long modified = Files.getLastModifiedTime(path).toMillis();
            long size = Files.size(path);
            if (modified == lastModified && size == lastSize) {
                return;
            }
            // Mark this version as observed before parsing. A half-written file
            // never retries every game tick; saving a complete new file changes
            // its timestamp/size and is picked up immediately.
            lastModified = modified;
            lastSize = size;
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement parsed = new JsonParser().parse(reader);
                if (!parsed.isJsonObject()) {
                    throw new IllegalArgumentException("root must be a JSON object");
                }
                JsonObject root = parsed.getAsJsonObject();
                JsonElement schema = root.get("schemaVersion");
                if (schema == null || !schema.isJsonPrimitive() || schema.getAsInt() != SCHEMA_VERSION) {
                    throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
                }
                UiTheme.install(UiTheme.fromPreviewJson(root));
                log.info("Opus UI preview theme reloaded from {}", path);
            }
        } catch (Exception exception) {
            log.warn("Opus UI preview theme was not applied; retaining the last valid visual tokens.", exception);
        }
    }
}
