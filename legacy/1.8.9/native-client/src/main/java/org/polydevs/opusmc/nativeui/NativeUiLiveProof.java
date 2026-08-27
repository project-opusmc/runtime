package org.polydevs.opusmc.nativeui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import cc.polyfrost.oneconfig.gui.OneConfigGui;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

/**
 * File-gated exact-runtime acceptance sequence for the native UI.
 *
 * <p>Create {@code native-ui-live-proof.request} in the instance game
 * directory before launch. The probe captures the Elementa shell, routes
 * through the production Right Shift and Escape handlers, performs repeated
 * native grow/shrink cycles, and leaves the shell open. It is inert in every
 * ordinary game run.</p>
 */
public final class NativeUiLiveProof {
    private static final String REQUEST_FILE = "native-ui-live-proof.request";
    private static final String RESULT_FILE = "native-ui-live-proof.result";
    private static final int SCREEN_SETTLE_FRAMES = 45;
    private static final int OPUSCONFIG_SETTLE_FRAMES = 90;
    private static final int RESIZE_SETTLE_FRAMES = 45;
    private static final int RESIZE_TIMEOUT_FRAMES = 240;
    private static final int[][] RESIZE_SEQUENCE = {
            {640, 420},
            {1000, 640},
            {720, 450},
            {1100, 700},
            {760, 500},
            {854, 480},
    };

    private enum Step {
        WAIT_FOR_ELEMENTA,
        WAIT_FOR_OPUSCONFIG,
        WAIT_FOR_ESCAPE_RESTORE,
        WAIT_FOR_RESIZE,
        COMPLETE,
        FAILED
    }

    private final OpusNativeUiMod mod;
    private final File gameDirectory;
    private final File requestFile;
    private final File resultFile;
    private final String screenshotPrefix;
    private Step step = Step.WAIT_FOR_ELEMENTA;
    private int stableFrames;
    private int resizeIndex;
    private int resizeWaitFrames;
    private int resizeCorrectionAttempts;
    private int resizeBaselineWidth;
    private int resizeBaselineHeight;
    private int resizeLastWidth;
    private int resizeLastHeight;
    private boolean resizeObservedChange;

    private NativeUiLiveProof(OpusNativeUiMod mod, File gameDirectory) {
        this.mod = mod;
        this.gameDirectory = gameDirectory;
        this.requestFile = new File(gameDirectory, REQUEST_FILE);
        this.resultFile = new File(gameDirectory, RESULT_FILE);
        this.screenshotPrefix = "opus-native-ui-live-proof-"
                + System.getProperty("opus.native.ui.session", "unknown");
    }

    static void installIfRequested(OpusNativeUiMod mod, File gameDirectory) {
        File request = new File(gameDirectory, REQUEST_FILE);
        if (!request.isFile()) {
            return;
        }
        NativeUiLiveProof proof = new NativeUiLiveProof(mod, gameDirectory);
        MinecraftForge.EVENT_BUS.register(proof);
        NativeUiRuntimeDiagnostics.record("liveProof", "running");
        NativeUiRuntimeDiagnostics.record("liveProofRequest", request.getAbsolutePath());
        proof.writeResult("running\n");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDrawPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (step == Step.COMPLETE || step == Step.FAILED) {
            return;
        }

        try {
            switch (step) {
                case WAIT_FOR_ELEMENTA:
                    waitForInitialElementa(event);
                    break;
                case WAIT_FOR_OPUSCONFIG:
                    waitForOpusConfig(event);
                    break;
                case WAIT_FOR_ESCAPE_RESTORE:
                    waitForEscapeRestore(event);
                    break;
                case WAIT_FOR_RESIZE:
                    waitForResize(event);
                    break;
                default:
                    break;
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void waitForInitialElementa(GuiScreenEvent.DrawScreenEvent.Post event) throws Exception {
        if (!(event.gui instanceof OpusElementaShellScreen)) {
            stableFrames = 0;
            return;
        }
        if (++stableFrames < SCREEN_SETTLE_FRAMES) {
            return;
        }

        capture("01-elementa-initial.png");
        NativeUiRuntimeDiagnostics.record("liveProofInitialScreen", "elementa");
        require(mod.handleLiveProofKey(OpusNativeUiMod.OPEN_KEY),
                "Right Shift handler rejected the live proof route");
        NativeUiRuntimeDiagnostics.record("liveProofRightShift", "opened-opusconfig");
        step = Step.WAIT_FOR_OPUSCONFIG;
        stableFrames = 0;
    }

    private void waitForOpusConfig(GuiScreenEvent.DrawScreenEvent.Post event) throws Exception {
        if (!(event.gui instanceof OneConfigGui)) {
            stableFrames = 0;
            return;
        }
        if (++stableFrames < OPUSCONFIG_SETTLE_FRAMES) {
            return;
        }

        capture("02-opusconfig.png");
        NativeUiRuntimeDiagnostics.record("liveProofOpusConfig", "drawn");
        require(mod.handleLiveProofKey(Keyboard.KEY_ESCAPE),
                "Escape handler rejected the OpusConfig restore route");
        NativeUiRuntimeDiagnostics.record("liveProofEscape", "requested-restore");
        step = Step.WAIT_FOR_ESCAPE_RESTORE;
        stableFrames = 0;
    }

    private void waitForEscapeRestore(GuiScreenEvent.DrawScreenEvent.Post event) throws Exception {
        if (!(event.gui instanceof OpusElementaShellScreen)) {
            stableFrames = 0;
            return;
        }
        if (++stableFrames < SCREEN_SETTLE_FRAMES) {
            return;
        }

        capture("03-elementa-restored.png");
        NativeUiRuntimeDiagnostics.record("liveProofEscapeRestore", "elementa");
        step = Step.WAIT_FOR_RESIZE;
        stableFrames = 0;
        requestResize(RESIZE_SEQUENCE[resizeIndex]);
    }

    private void waitForResize(GuiScreenEvent.DrawScreenEvent.Post event) throws Exception {
        if (!(event.gui instanceof OpusElementaShellScreen)) {
            stableFrames = 0;
            return;
        }

        int[] target = RESIZE_SEQUENCE[resizeIndex];
        int currentWidth = Display.getWidth();
        int currentHeight = Display.getHeight();
        resizeWaitFrames++;

        if (!resizeObservedChange
                && (currentWidth != resizeBaselineWidth || currentHeight != resizeBaselineHeight)) {
            resizeObservedChange = true;
            resizeLastWidth = currentWidth;
            resizeLastHeight = currentHeight;
            stableFrames = 0;
        } else if (resizeObservedChange
                && (currentWidth != resizeLastWidth || currentHeight != resizeLastHeight)) {
            resizeLastWidth = currentWidth;
            resizeLastHeight = currentHeight;
            stableFrames = 0;
        } else if (resizeObservedChange) {
            stableFrames++;
        }

        if (resizeWaitFrames > RESIZE_TIMEOUT_FRAMES) {
            throw new IllegalStateException(
                    "resize did not settle: target=" + target[0] + "x" + target[1]
                            + " baseline=" + resizeBaselineWidth + "x" + resizeBaselineHeight
                            + " observed=" + currentWidth + "x" + currentHeight);
        }
        if (!resizeObservedChange || stableFrames < RESIZE_SETTLE_FRAMES) {
            return;
        }

        if (currentWidth != target[0] || currentHeight != target[1]) {
            if (resizeCorrectionAttempts++ >= 2) {
                throw new IllegalStateException(
                        "resize content mismatch: target=" + target[0] + "x" + target[1]
                                + " observed=" + currentWidth + "x" + currentHeight);
            }
            correctResize(target, currentWidth, currentHeight);
            return;
        }

        capture(String.format(
                "04-resize-%02d-%dx%d.png",
                resizeIndex + 1,
                target[0],
                target[1]));
        NativeUiRuntimeDiagnostics.record(
                "liveProofResize" + (resizeIndex + 1),
                target[0] + "x" + target[1] + "|elementa|gl=" + org.lwjgl.opengl.GL11.glGetError());

        resizeIndex++;
        stableFrames = 0;
        if (resizeIndex < RESIZE_SEQUENCE.length) {
            requestResize(RESIZE_SEQUENCE[resizeIndex]);
            return;
        }

        complete();
    }

    private void capture(String suffix) throws IOException {
        Minecraft minecraft = Minecraft.getMinecraft();
        String fileName = screenshotPrefix + "-" + suffix;
        ScreenShotHelper.saveScreenshot(
                gameDirectory,
                fileName,
                minecraft.displayWidth,
                minecraft.displayHeight,
                minecraft.getFramebuffer());
        NativeUiRuntimeDiagnostics.record("liveProofScreenshot", fileName);
    }

    private void requestResize(int[] target) throws Exception {
        resizeBaselineWidth = Display.getWidth();
        resizeBaselineHeight = Display.getHeight();
        resizeLastWidth = resizeBaselineWidth;
        resizeLastHeight = resizeBaselineHeight;
        resizeObservedChange = false;
        resizeWaitFrames = 0;
        resizeCorrectionAttempts = 0;
        stableFrames = 0;
        invokeResize("opusResizeWindowForVerification", target[0], target[1]);
        NativeUiRuntimeDiagnostics.record(
                "liveProofResizeRequested",
                target[0] + "x" + target[1]
                        + "|baseline=" + resizeBaselineWidth + "x" + resizeBaselineHeight);
    }

    private void correctResize(int[] target, int observedWidth, int observedHeight) throws Exception {
        resizeBaselineWidth = observedWidth;
        resizeBaselineHeight = observedHeight;
        resizeLastWidth = observedWidth;
        resizeLastHeight = observedHeight;
        resizeObservedChange = false;
        resizeWaitFrames = 0;
        stableFrames = 0;
        invokeResizeContent(
                target[0], target[1], observedWidth, observedHeight);
        NativeUiRuntimeDiagnostics.record(
                "liveProofResizeCorrection",
                target[0] + "x" + target[1]
                        + "|observed=" + observedWidth + "x" + observedHeight);
    }

    private static void invokeResize(String methodName, int width, int height) throws Exception {
        Field implementationField = Display.class.getDeclaredField("display_impl");
        implementationField.setAccessible(true);
        Object implementation = implementationField.get(null);
        Method resize = implementation.getClass().getDeclaredMethod(
                methodName,
                Integer.TYPE,
                Integer.TYPE);
        resize.setAccessible(true);
        try {
            resize.invoke(implementation, width, height);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw failure;
        }
    }

    private static void invokeResizeContent(
            int contentWidth,
            int contentHeight,
            int observedWidth,
            int observedHeight) throws Exception {
        Field implementationField = Display.class.getDeclaredField("display_impl");
        implementationField.setAccessible(true);
        Object implementation = implementationField.get(null);
        Method resize = implementation.getClass().getDeclaredMethod(
                "opusResizeWindowForVerificationContent",
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE);
        resize.setAccessible(true);
        try {
            resize.invoke(
                    implementation,
                    contentWidth,
                    contentHeight,
                    observedWidth,
                    observedHeight);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw failure;
        }
    }

    private void complete() {
        step = Step.COMPLETE;
        requestFile.delete();
        writeResult("passed\n");
        NativeUiRuntimeDiagnostics.record("liveProofResizeCount", Integer.toString(resizeIndex));
        NativeUiRuntimeDiagnostics.record("liveProof", "passed");
        MinecraftForge.EVENT_BUS.unregister(this);
    }

    private void fail(Throwable failure) {
        step = Step.FAILED;
        String message = failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
        writeResult("failed\n" + message + "\n");
        NativeUiRuntimeDiagnostics.record("liveProof", "failed");
        NativeUiRuntimeDiagnostics.record("liveProofFailure", message);
        MinecraftForge.EVENT_BUS.unregister(this);
    }

    private void writeResult(String value) {
        try (FileOutputStream output = new FileOutputStream(resultFile)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The primary runtime report still records the result.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
