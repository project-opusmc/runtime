package dev.rbw.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rbw.client.ui.UiRoute;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Parsed, revisioned command sent to a local real-game UI preview session. */
final class UiPreviewSpec {
    static final int SCHEMA_VERSION = 1;

    enum Fixture {
        CURRENT,
        WORLD,
        PAUSE_MENU,
        RIGHT_SHIFT
    }

    enum InputKind {
        NONE,
        CLICK,
        DRAG
    }

    static final class Pointer {
        final int x;
        final int y;

        Pointer(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    static final class Input {
        static final Input NONE = new Input(InputKind.NONE, 0, 0, 0, 0, 0, false, 0, 0);

        final InputKind kind;
        final int x;
        final int y;
        final int toX;
        final int toY;
        final int button;
        final boolean hasPrimeClick;
        final int primeX;
        final int primeY;

        Input(
                InputKind kind,
                int x,
                int y,
                int toX,
                int toY,
                int button,
                boolean hasPrimeClick,
                int primeX,
                int primeY) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.toX = toX;
            this.toY = toY;
            this.button = button;
            this.hasPrimeClick = hasPrimeClick;
            this.primeX = primeX;
            this.primeY = primeY;
        }
    }

    final long revision;
    final Fixture fixture;
    final UiRoute route;
    final Pointer pointer;
    final Input input;
    final String captureFile;
    final int settleFrames;

    private UiPreviewSpec(
            long revision,
            Fixture fixture,
            UiRoute route,
            Pointer pointer,
            Input input,
            String captureFile,
            int settleFrames) {
        this.revision = revision;
        this.fixture = fixture;
        this.route = route;
        this.pointer = pointer;
        this.input = input;
        this.captureFile = captureFile;
        this.settleFrames = settleFrames;
    }

    static UiPreviewSpec load(Path path) throws Exception {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("root must be a JSON object");
            }
            return fromJson(parsed.getAsJsonObject());
        }
    }

    static UiPreviewSpec fromJson(JsonObject root) {
        requireSchema(root);
        long revision = requiredLong(root, "revision", 0L, Long.MAX_VALUE);
        Fixture fixture = fixture(string(root, "fixture", "current"));
        UiRoute route = route(string(root, "route", "mods"));
        Pointer pointer = pointer(object(root, "pointer"));
        Input input = input(object(root, "input"));
        JsonObject capture = object(root, "capture");
        String captureFile = capture == null ? null : safePngName(string(capture, "file", null));
        int settleFrames = capture == null ? 0 : integer(capture, "afterFrames", 8, 1, 120);
        return new UiPreviewSpec(revision, fixture, route, pointer, input, captureFile, settleFrames);
    }

    private static void requireSchema(JsonObject root) {
        int schema = integer(root, "schemaVersion", -1, -1, Integer.MAX_VALUE);
        if (schema != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
        }
    }

    private static Fixture fixture(String raw) {
        if ("current".equalsIgnoreCase(raw)) {
            return Fixture.CURRENT;
        }
        if ("world".equalsIgnoreCase(raw)) {
            return Fixture.WORLD;
        }
        if ("pause-menu".equalsIgnoreCase(raw) || "pause_menu".equalsIgnoreCase(raw)) {
            return Fixture.PAUSE_MENU;
        }
        if ("right-shift".equalsIgnoreCase(raw) || "right_shift".equalsIgnoreCase(raw)) {
            return Fixture.RIGHT_SHIFT;
        }
        throw new IllegalArgumentException("fixture must be current, world, pause-menu or right-shift");
    }

    private static UiRoute route(String raw) {
        if ("main".equalsIgnoreCase(raw)) {
            return UiRoute.mainMenu();
        }
        if ("hud".equalsIgnoreCase(raw)) {
            return UiRoute.hudEditor();
        }
        if ("mods".equalsIgnoreCase(raw)) {
            return UiRoute.modHub();
        }
        if ("detail".equalsIgnoreCase(raw) || "performance".equalsIgnoreCase(raw)) {
            return UiRoute.moduleDetail(FpsModule.ID);
        }
        if ("armor".equalsIgnoreCase(raw) || "armor-status".equalsIgnoreCase(raw)) {
            return UiRoute.moduleDetail(ArmorStatusModule.ID);
        }
        throw new IllegalArgumentException("route must be main, hud, mods, detail or armor");
    }

    private static Pointer pointer(JsonObject object) {
        if (object == null) {
            return null;
        }
        return new Pointer(
                integer(object, "x", 0, -10000, 10000),
                integer(object, "y", 0, -10000, 10000));
    }

    private static Input input(JsonObject object) {
        if (object == null) {
            return Input.NONE;
        }
        String type = string(object, "type", "");
        int button = integer(object, "button", 0, 0, 2);
        JsonObject prime = object(object, "prime");
        boolean hasPrime = prime != null;
        int primeX = hasPrime ? integer(prime, "x", 0, -10000, 10000) : 0;
        int primeY = hasPrime ? integer(prime, "y", 0, -10000, 10000) : 0;
        if ("click".equalsIgnoreCase(type)) {
            return new Input(
                    InputKind.CLICK,
                    integer(object, "x", 0, -10000, 10000),
                    integer(object, "y", 0, -10000, 10000),
                    0,
                    0,
                    button,
                    hasPrime,
                    primeX,
                    primeY);
        }
        if ("drag".equalsIgnoreCase(type)) {
            return new Input(
                    InputKind.DRAG,
                    integer(object, "x", 0, -10000, 10000),
                    integer(object, "y", 0, -10000, 10000),
                    integer(object, "toX", 0, -10000, 10000),
                    integer(object, "toY", 0, -10000, 10000),
                    button,
                    hasPrime,
                    primeX,
                    primeY);
        }
        throw new IllegalArgumentException("input.type must be click or drag");
    }

    private static String safePngName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.contains("/") || trimmed.contains("\\") || !trimmed.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            throw new IllegalArgumentException("capture.file must be a simple .png file name");
        }
        return trimmed;
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int integer(JsonObject object, String name, int fallback, int minimum, int maximum) {
        try {
            JsonElement value = object.get(name);
            if (value == null || !value.isJsonPrimitive()) {
                return fallback;
            }
            return Math.max(minimum, Math.min(maximum, value.getAsInt()));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long requiredLong(JsonObject object, String name, long minimum, long maximum) {
        try {
            JsonElement value = object.get(name);
            if (value == null || !value.isJsonPrimitive()) {
                throw new IllegalArgumentException(name + " is required");
            }
            long parsed = value.getAsLong();
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(name + " is outside its supported range");
            }
            return parsed;
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) exception;
            }
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }
}
