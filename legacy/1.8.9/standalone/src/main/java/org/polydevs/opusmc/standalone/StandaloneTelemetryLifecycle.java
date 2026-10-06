package org.polydevs.opusmc.standalone;

import org.polydevs.opusmc.core.ClientTelemetry;
import org.polydevs.opusmc.core.LifecycleStage;
import org.polydevs.opusmc.core.OpusCore;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.launchwrapper.LaunchClassLoader;

/**
 * Manages the Opus telemetry and diagnostics lifecycle for the standalone loader.
 */
public final class StandaloneTelemetryLifecycle {
    private static final Object LIFECYCLE_LOCK = new Object();
    private static final AtomicBoolean LOADER_STARTED = new AtomicBoolean();
    private static final AtomicBoolean RUNNING_MARKED = new AtomicBoolean();
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean TERMINATED = new AtomicBoolean();
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean UNCAUGHT_EXCEPTION_HANDLER_INSTALLED = new AtomicBoolean();

    private StandaloneTelemetryLifecycle() {
    }

    public static void loaderStarted(LaunchClassLoader classLoader) {
        if (!LOADER_STARTED.compareAndSet(false, true)) {
            return;
        }

        installShutdownHook();
        installUncaughtExceptionHandler();
        ClientTelemetry.startFromSystemProperties();
        int entries = classLoader != null ? classLoader.getSources().size() : systemClasspathEntryCount();
        ClientTelemetry.bootstrapLoaded(entries, OpusStandaloneClassTransformer.transformerCount());
        synchronized (LIFECYCLE_LOCK) {
            OpusCore.transition(LifecycleStage.CREATED, LifecycleStage.BOOTSTRAP);
            OpusCore.transition(LifecycleStage.BOOTSTRAP, LifecycleStage.GAME_STARTING);
        }
    }

    /** Called from the injected first-frame hook after Minecraft has entered its loop. */
    public static void markRunning() {
        if (RUNNING_MARKED.get()) {
            return;
        }
        synchronized (LIFECYCLE_LOCK) {
            if (RUNNING_MARKED.get()) {
                return;
            }
            if (OpusCore.stage() == LifecycleStage.GAME_STARTING) {
                OpusCore.transition(LifecycleStage.GAME_STARTING, LifecycleStage.RUNNING);
            }
            RUNNING_MARKED.set(true);
        }
    }

    public static void reportFailure(Throwable failure) {
        if (failure != null && FAILURE_REPORTED.compareAndSet(false, true)) {
            ClientTelemetry.failure(failure);
        }
    }

    public static void markTerminated() {
        if (!TERMINATED.compareAndSet(false, true)) {
            return;
        }
        synchronized (LIFECYCLE_LOCK) {
            LifecycleStage current = OpusCore.stage();
            if (current != LifecycleStage.SHUTDOWN) {
                OpusCore.transition(current, LifecycleStage.SHUTDOWN);
            }
        }
    }

    private static void installShutdownHook() {
        if (!SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                markTerminated();
            }
        }, "opus-standalone-lifecycle"));
    }

    private static void installUncaughtExceptionHandler() {
        if (!UNCAUGHT_EXCEPTION_HANDLER_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable failure) {
                try {
                    if (!(failure instanceof ThreadDeath)) {
                        reportFailure(failure);
                    }
                } catch (Throwable ignored) {
                }
                if (previous != null) {
                    previous.uncaughtException(thread, failure);
                } else if (!(failure instanceof ThreadDeath)) {
                    failure.printStackTrace();
                }
            }
        });
    }

    private static int systemClasspathEntryCount() {
        try {
            String classpath = System.getProperty("java.class.path");
            if (classpath == null || classpath.isEmpty()) {
                return 0;
            }
            int entries = 1;
            for (int index = 0; index < classpath.length(); index++) {
                if (classpath.charAt(index) == File.pathSeparatorChar) {
                    entries++;
                }
            }
            return entries;
        } catch (SecurityException ignored) {
            return 0;
        }
    }
}
