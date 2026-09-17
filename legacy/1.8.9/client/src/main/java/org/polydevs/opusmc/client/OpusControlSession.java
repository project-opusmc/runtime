package org.polydevs.opusmc.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.ui.UiBounds;

/**
 * Local semantic control plane for development and automation.
 *
 * <p>It deliberately exposes no mouse, keyboard, drag, or focus operation.
 * Commands are revisioned JSON files consumed on Minecraft's client thread,
 * so an external agent can inspect and drive product routes without taking
 * ownership of the user's desktop input.</p>
 */
final class OpusControlSession {
    static final int SCHEMA_VERSION = 1;
    static final String CONTROL_FILE_PROPERTY = "opus.control.file";
    private static final int PRODUCT_READY_TIMEOUT_TICKS = 20 * 15;

    private final Logger log;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path controlPath;
    private final Path statusPath;
    private long appliedRevision = -1L;
    private String pendingAction;
    private String pendingRoute;
    private long pendingGeneration = -1L;
    private int remainingTicks;

    private OpusControlSession(Logger log, Path controlPath) {
        this.log = log;
        this.controlPath = controlPath;
        this.statusPath = controlPath == null ? null : statusPath(controlPath);
    }

    static OpusControlSession fromSystemProperties(Logger log) {
        String rawPath = System.getProperty(CONTROL_FILE_PROPERTY);
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return new OpusControlSession(log, null);
        }
        try {
            Path path = Paths.get(rawPath).toAbsolutePath().normalize();
            log.info("Opus semantic control plane enabled: {}", path);
            return new OpusControlSession(log, path);
        } catch (RuntimeException exception) {
            log.warn("Opus control path is invalid; semantic control is disabled.", exception);
            return new OpusControlSession(log, null);
        }
    }

    boolean isEnabled() {
        return controlPath != null;
    }

    void tick(ClientOverlayController controller) {
        if (controlPath == null) {
            return;
        }
        poll(controller);
        settlePending(controller);
    }

    private void poll(ClientOverlayController controller) {
        if (!Files.isRegularFile(controlPath)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(controlPath, StandardCharsets.UTF_8)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("root must be a JSON object");
            }
            JsonObject command = parsed.getAsJsonObject();
            int schema = integer(command, "schemaVersion", -1);
            if (schema != SCHEMA_VERSION) {
                throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
            }
            long revision = longValue(command, "revision", -1L);
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
            if (revision <= appliedRevision) {
                return;
            }
            String action = string(command, "action", null);
            if (action == null) {
                throw new IllegalArgumentException("action is required");
            }

            appliedRevision = revision;
            pendingAction = null;
            pendingRoute = null;
            pendingGeneration = -1L;
            remainingTicks = 0;

            if ("status".equals(action) || "metrics".equals(action)) {
                writeStatus(controller, "ready", action, "snapshot ready");
                return;
            }
            if ("reload-ui".equals(action)) {
                long reloadGeneration = controller.controlReloadUi();
                if (reloadGeneration <= 0L) {
                    writeStatus(controller, "error", action, "CEF product surface is not open");
                    return;
                }
                pendingAction = action;
                pendingGeneration = reloadGeneration;
                remainingTicks = PRODUCT_READY_TIMEOUT_TICKS;
                writeStatus(controller, "waiting", action, "waiting for reloaded CEF frame");
                return;
            }
            if ("route".equals(action)) {
                JsonObject route = object(command, "route");
                if (route == null) {
                    throw new IllegalArgumentException("route action requires route object");
                }
                String routeId = string(route, "id", null);
                JsonObject params = object(route, "params");
                String moduleId = params == null ? null : string(params, "moduleId", null);
                String settingsSection = params == null ? null : string(params, "settingsSection", null);
                if (!controller.controlOpenRoute(routeId, moduleId, settingsSection)) {
                    writeStatus(controller, "error", action, "route is invalid in the current game context");
                    return;
                }
                pendingAction = action;
                pendingRoute = routeId;
                remainingTicks = PRODUCT_READY_TIMEOUT_TICKS;
                writeStatus(controller, "waiting", action, "waiting for requested route frame");
                return;
            }
            throw new IllegalArgumentException("unsupported action: " + action);
        } catch (Exception exception) {
            log.warn("Opus semantic control ignored an invalid command.", exception);
            writeStatus(controller, "error", "invalid", exception.getMessage());
        }
    }

    private void settlePending(ClientOverlayController controller) {
        if (pendingAction == null) {
            return;
        }
        if (controller.controlProductSurfaceFailed()) {
            writeStatus(controller, "error", pendingAction, "CEF product surface failed");
            pendingAction = null;
            pendingRoute = null;
            pendingGeneration = -1L;
            return;
        }
        boolean routeSettled = pendingRoute == null || pendingRoute.equals(controller.controlRouteName());
        boolean generationSettled = pendingGeneration <= 0L
                || controller.controlGenerationPublished(pendingGeneration);
        if (routeSettled && generationSettled && controller.controlProductSurfaceReady()) {
            writeStatus(controller, "ready", pendingAction, "command settled");
            pendingAction = null;
            pendingRoute = null;
            pendingGeneration = -1L;
            return;
        }
        if (--remainingTicks <= 0) {
            writeStatus(controller, "error", pendingAction, "timed out waiting for product surface");
            pendingAction = null;
            pendingRoute = null;
            pendingGeneration = -1L;
        }
    }

    private void writeStatus(
            ClientOverlayController controller,
            String state,
            String action,
            String message) {
        if (statusPath == null) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            UiBounds viewport = controller.controlProductViewport();
            JsonObject status = new JsonObject();
            status.addProperty("schemaVersion", SCHEMA_VERSION);
            status.addProperty("revision", appliedRevision);
            status.addProperty("state", state);
            status.addProperty("action", action);
            status.addProperty("message", message == null ? "" : message);
            status.addProperty("route", controller.controlRouteName());
            status.addProperty("surfaceState", controller.controlProductSurfaceState());
            status.addProperty("cefReady", controller.controlProductSurfaceReady());
            status.addProperty("musicVolume", minecraft.gameSettings == null
                    ? -1.0F
                    : minecraft.gameSettings.getSoundLevel(SoundCategory.MUSIC));

            JsonObject logical = new JsonObject();
            logical.addProperty("width", viewport.width);
            logical.addProperty("height", viewport.height);
            status.add("logicalViewport", logical);

            JsonObject framebuffer = new JsonObject();
            framebuffer.addProperty("width", Math.max(1, minecraft.displayWidth));
            framebuffer.addProperty("height", Math.max(1, minecraft.displayHeight));
            status.add("framebuffer", framebuffer);
            status.addProperty("deviceScale", controller.controlDeviceScale());

            Path parent = statusPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = statusPath.resolveSibling("." + statusPath.getFileName() + ".part");
            Files.write(temporary, (gson.toJson(status) + "\n").getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, statusPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, statusPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            log.warn("Opus semantic control could not write its status file.", exception);
        }
    }

    private static Path statusPath(Path controlPath) {
        String fileName = controlPath.getFileName().toString();
        String statusName = fileName.endsWith(".json")
                ? fileName.substring(0, fileName.length() - 5) + ".status.json"
                : fileName + ".status.json";
        return controlPath.resolveSibling(statusName);
    }

    private static JsonObject object(JsonObject root, String name) {
        JsonElement value = root.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject root, String name, String fallback) {
        JsonElement value = root.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static int integer(JsonObject root, String name, int fallback) {
        JsonElement value = root.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }

    private static long longValue(JsonObject root, String name, long fallback) {
        JsonElement value = root.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsLong() : fallback;
    }
}
