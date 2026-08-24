package org.polydevs.opusmc.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import net.minecraft.client.Minecraft;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiRoute;

/**
 * A local, opt-in driver for the real Forge client UI. It deliberately sends
 * semantic route/input commands to the production UI and captures the same
 * framebuffer that a player sees. It is not a second renderer or mock.
 */
final class UiPreviewSession {
    static final String CONTROL_FILE_PROPERTY = "opus.ui.preview.control.file";
    private static final String FIXTURE_WORLD_ID = "opus-ui-preview";
    private static final String FIXTURE_WORLD_NAME = "Opus UI Preview";
    private static final int ROUTE_SETTLE_TICKS = 6;
    private static final int POST_INPUT_SETTLE_TICKS = 6;
    private static final int PRODUCT_READY_TIMEOUT_TICKS = 20 * 30;

    private enum State {
        IDLE,
        PENDING_ROUTE,
        WAITING_FOR_WORLD,
        WAITING_FOR_PRODUCT,
        WAITING_FOR_ROUTE,
        WAITING_FOR_POST_INPUT,
        WAITING_FOR_CAPTURE,
        STOPPING
    }

    private final Logger log;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path controlPath;
    private final Path statusPath;
    private long appliedRevision = -1L;
    private UiPreviewSpec active;
    private State state = State.IDLE;
    private int remainingTicks;
    private int nativeHudRegionTimeoutTicks;
    private int postInputSettleTicks;
    private boolean worldLaunchRequested;
    private String renderedRoute;
    private String surfaceState = "closed";
    private int viewportWidth;
    private int viewportHeight;

    private UiPreviewSession(Logger log, Path controlPath) {
        this.log = log;
        this.controlPath = controlPath;
        this.statusPath = controlPath == null
                ? null
                : controlPath.resolveSibling(controlPath.getFileName().toString() + ".status.json");
    }

    static UiPreviewSession fromSystemProperties(Logger log) {
        String rawPath = System.getProperty(CONTROL_FILE_PROPERTY);
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return new UiPreviewSession(log, null);
        }
        try {
            Path controlPath = Paths.get(rawPath);
            log.info("Opus UI Preview control enabled: {}", controlPath);
            return new UiPreviewSession(log, controlPath);
        } catch (RuntimeException exception) {
            log.warn("Opus UI preview control path is invalid; Preview Mode is disabled.", exception);
            return new UiPreviewSession(log, null);
        }
    }

    /** Preview-only controls are never active unless the launch property exists. */
    boolean isEnabled() {
        return controlPath != null;
    }

    void tick(ClientOverlayController controller) {
        if (controlPath == null) {
            return;
        }
        pollControl();
        if (active == null) {
            return;
        }
        updateSurfaceDiagnostics(controller);

        if (state == State.WAITING_FOR_WORLD) {
            if (Minecraft.getMinecraft().theWorld != null) {
                state = State.PENDING_ROUTE;
            }
            return;
        }
        if (state == State.PENDING_ROUTE) {
            applyRoute(controller);
            return;
        }
        if (state == State.WAITING_FOR_PRODUCT) {
            waitForProductFrame(controller);
            return;
        }
        if (state == State.WAITING_FOR_ROUTE) {
            if (controller.previewProductSurfaceFailed()) {
                failAndStop("CEF product surface failed after becoming ready");
                return;
            }
            if (!controller.previewProductSurfaceReady()) {
                state = State.WAITING_FOR_PRODUCT;
                remainingTicks = PRODUCT_READY_TIMEOUT_TICKS;
                return;
            }
            if (active.route.kind() == UiRoute.Kind.HUD_EDITOR
                    && !controller.previewNativeHudCanvasReady()) {
                if (--nativeHudRegionTimeoutTicks <= 0) {
                    failAndStop("timed out waiting for the native HUD editor input region");
                }
                return;
            }
            if (--remainingTicks <= 0) {
                applyInput(controller);
            }
            return;
        }
        if (state == State.WAITING_FOR_POST_INPUT) {
            waitAfterInput(controller);
            return;
        }
        if (state == State.WAITING_FOR_CAPTURE && --remainingTicks <= 0) {
            capture(controller);
            return;
        }
        if (state == State.STOPPING && --remainingTicks <= 0) {
            // A preview is a one-shot review tool. Shutting the real client
            // down here guarantees its Gradle parent can release the lock.
            Minecraft.getMinecraft().shutdown();
        }
    }

    private void pollControl() {
        if (!Files.isRegularFile(controlPath)) {
            return;
        }
        try {
            // The preview command is a tiny local JSON file. Parsing it on
            // each tick avoids missing a new revision when an editor writes
            // identical byte lengths within the filesystem timestamp window.
            UiPreviewSpec next = UiPreviewSpec.load(controlPath);
            if (next.revision <= appliedRevision) {
                return;
            }
            active = next;
            appliedRevision = next.revision;
            log.info("Opus UI Preview accepted revision {} for {}", next.revision, next.route.kind());
            worldLaunchRequested = false;
            surfaceState = "closed";
            viewportWidth = 0;
            viewportHeight = 0;
            writeStatus("accepted", "revision accepted", null);
            beginActiveSpec();
        } catch (Exception exception) {
            log.warn("Opus UI Preview ignored an invalid control file.", exception);
            writeStatus("error", "invalid control file: " + exception.getMessage(), null);
        }
    }

    private void beginActiveSpec() {
        if ((active.fixture == UiPreviewSpec.Fixture.WORLD
                    || active.fixture == UiPreviewSpec.Fixture.PAUSE_MENU
                    || active.fixture == UiPreviewSpec.Fixture.RIGHT_SHIFT)
                && Minecraft.getMinecraft().theWorld == null) {
            launchFixtureWorld();
            return;
        }
        if (active.fixture == UiPreviewSpec.Fixture.PAUSE_MENU) {
            Minecraft.getMinecraft().displayGuiScreen(new OpusPreviewPauseMenu());
        }
        state = State.PENDING_ROUTE;
    }

    private void launchFixtureWorld() {
        if (worldLaunchRequested) {
            return;
        }
        worldLaunchRequested = true;
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.displayGuiScreen(null);
            minecraft.launchIntegratedServer(
                    FIXTURE_WORLD_ID,
                    FIXTURE_WORLD_NAME,
                    new WorldSettings(
                            0L,
                            WorldSettings.GameType.CREATIVE,
                            true,
                            false,
                            WorldType.DEFAULT));
            state = State.WAITING_FOR_WORLD;
            writeStatus("waiting", "loading deterministic preview world", null);
        } catch (Throwable failure) {
            state = State.IDLE;
            log.warn("Opus UI Preview could not start its fixture world.", failure);
            writeStatus("error", "could not start preview world: " + failure.getClass().getSimpleName(), null);
        }
    }

    private void applyRoute(ClientOverlayController controller) {
        if (active == null) {
            return;
        }
        if (active.fixture == UiPreviewSpec.Fixture.PAUSE_MENU) {
            if (!(Minecraft.getMinecraft().currentScreen instanceof OpusPreviewPauseMenu)) {
                // The world is available one tick before GuiScreen has run
                // its first lifecycle frame. Open the genuine pause menu and
                // retry the route dispatch on the following client tick.
                Minecraft.getMinecraft().displayGuiScreen(new OpusPreviewPauseMenu());
                return;
            }
            if (!controller.previewOpenPauseMenuCatalog()) {
                writeStatus("error", "pause-menu canonical Mods route was unavailable", null);
                state = State.STOPPING;
                remainingTicks = 2;
                return;
            }
        } else if (active.fixture == UiPreviewSpec.Fixture.RIGHT_SHIFT) {
            if (!controller.previewOpenRightShiftQuickHub()) {
                writeStatus("error", "Right Shift route did not open", null);
                state = State.STOPPING;
                remainingTicks = 2;
                return;
            }
        } else {
            controller.openPreviewRoute(active.route);
        }
        renderedRoute = controller.previewRouteName();
        if (active.pointer == null) {
            controller.clearPreviewPointer();
        } else {
            controller.setPreviewPointer(active.pointer.x, active.pointer.y);
        }
        state = State.WAITING_FOR_PRODUCT;
        remainingTicks = PRODUCT_READY_TIMEOUT_TICKS;
        writeStatus("waiting", "waiting for CEF product frame", null);
    }

    private void waitForProductFrame(ClientOverlayController controller) {
        renderedRoute = controller.previewRouteName();
        if (controller.previewProductSurfaceFailed()) {
            failAndStop("CEF product surface entered failed state");
            return;
        }
        if (controller.previewProductSurfaceReady()) {
            state = State.WAITING_FOR_ROUTE;
            remainingTicks = ROUTE_SETTLE_TICKS;
            nativeHudRegionTimeoutTicks = PRODUCT_READY_TIMEOUT_TICKS;
            writeStatus("rendering", "CEF product frame ready", null);
            return;
        }
        if (--remainingTicks <= 0) {
            failAndStop("timed out waiting for CEF product frame");
        }
    }

    private void applyInput(ClientOverlayController controller) {
        if (active == null) {
            return;
        }
        boolean handled = true;
        if (active.input.kind == UiPreviewSpec.InputKind.CLICK) {
            if (active.input.hasPrimeClick) {
                handled = controller.previewClick(active.input.primeX, active.input.primeY, active.input.button);
            }
            if (handled) {
                handled = controller.previewClick(active.input.x, active.input.y, active.input.button);
            }
        } else if (active.input.kind == UiPreviewSpec.InputKind.DRAG) {
            handled = controller.previewDrag(
                    active.input.x,
                    active.input.y,
                    active.input.toX,
                    active.input.toY,
                    active.input.button);
        } else if (active.input.kind == UiPreviewSpec.InputKind.KEY) {
            handled = controller.previewKey(active.input.key);
        }
        if (!handled && active.input.kind != UiPreviewSpec.InputKind.NONE) {
            failAndStop("input could not be dispatched to the active product surface");
            return;
        }
        // A real click may replace the Opus screen with a vanilla destination.
        // Record the result after dispatch rather than the source route.
        renderedRoute = controller.previewRouteName();
        if (active.input.kind == UiPreviewSpec.InputKind.NONE) {
            completeOrWaitForCapture();
            return;
        }
        state = State.WAITING_FOR_POST_INPUT;
        remainingTicks = PRODUCT_READY_TIMEOUT_TICKS;
        postInputSettleTicks = POST_INPUT_SETTLE_TICKS;
        writeStatus("input", "production input dispatched", null);
    }

    private void waitAfterInput(ClientOverlayController controller) {
        renderedRoute = controller.previewRouteName();
        if (postInputSettleTicks > 0) {
            postInputSettleTicks--;
            return;
        }
        if (controller.previewProductSurfaceFailed()) {
            failAndStop("CEF product surface failed after input");
            return;
        }
        String currentSurfaceState = controller.previewProductSurfaceState();
        if ("closed".equals(currentSurfaceState)
                || controller.previewProductSurfaceReady()) {
            completeOrWaitForCapture();
            return;
        }
        if (--remainingTicks <= 0) {
            failAndStop("timed out waiting for the post-input product frame");
        }
    }

    private void completeOrWaitForCapture() {
        if (active == null || active.captureFile == null) {
            writeStatus("ready", "route and input settled", null);
            state = State.IDLE;
            return;
        }
        state = State.WAITING_FOR_CAPTURE;
        remainingTicks = active.settleFrames;
    }

    private void capture(ClientOverlayController controller) {
        if (active == null || active.captureFile == null) {
            state = State.IDLE;
            return;
        }
        updateSurfaceDiagnostics(controller);
        if (!canCaptureSurfaceState(surfaceState)) {
            failAndStop("capture refused because the CEF product frame is not ready");
            return;
        }
        try {
            controller.capturePreview(active.captureFile);
            writeStatus("captured", "framebuffer PNG written", active.captureFile);
            state = State.STOPPING;
            remainingTicks = 2;
        } catch (RuntimeException exception) {
            log.warn("Opus UI Preview could not capture the game framebuffer.", exception);
            writeStatus("error", "capture failed: " + exception.getClass().getSimpleName(), null);
        } finally {
            if (state != State.STOPPING) {
                state = State.IDLE;
            }
        }
    }

    static boolean canCaptureSurfaceState(String state) {
        // A ready compositor can be captured normally. A closed compositor is
        // also valid after an input such as Escape closes the product surface
        // and the preview intentionally captures the resulting game frame.
        return "ready".equals(state) || "closed".equals(state);
    }

    private void updateSurfaceDiagnostics(ClientOverlayController controller) {
        surfaceState = controller.previewProductSurfaceState();
        UiBounds viewport = controller.previewProductViewport();
        viewportWidth = viewport.width;
        viewportHeight = viewport.height;
    }

    private void failAndStop(String message) {
        writeStatus("error", message, null);
        state = State.STOPPING;
        remainingTicks = 2;
    }

    private void writeStatus(String stateName, String message, String captureFile) {
        if (statusPath == null) {
            return;
        }
        try {
            JsonObject status = new JsonObject();
            status.addProperty("schemaVersion", UiPreviewSpec.SCHEMA_VERSION);
            status.addProperty("state", stateName);
            status.addProperty("message", message);
            status.addProperty("revision", appliedRevision);
            status.addProperty("productCompositor", "cef");
            status.addProperty("surfaceState", surfaceState);
            JsonObject viewport = new JsonObject();
            viewport.addProperty("width", viewportWidth);
            viewport.addProperty("height", viewportHeight);
            status.add("viewport", viewport);
            if (renderedRoute != null) {
                status.addProperty("route", renderedRoute);
            } else if (active != null) {
                status.addProperty("route", active.route.kind().name().toLowerCase(java.util.Locale.ROOT));
            }
            if (captureFile != null) {
                status.addProperty("capture", "screenshots/" + captureFile);
            }
            Path parent = statusPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = statusPath.resolveSibling("." + statusPath.getFileName() + ".part");
            Files.write(temporary, (gson.toJson(status) + "\n").getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, statusPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, statusPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            log.warn("Opus UI Preview could not write its local status file.", exception);
        }
    }
}
