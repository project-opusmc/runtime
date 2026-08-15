package dev.rbw.forge;

import dev.rbw.core.ClientTelemetry;
import dev.rbw.core.LifecycleStage;
import dev.rbw.core.RbwCore;
import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.launchwrapper.LaunchClassLoader;

/**
 * Restores the Opus diagnostics lifecycle after Forge takes ownership of the
 * game class loader.
 *
 * <p>This class lives in the FML coremod rather than the process bootstrap,
 * so the bootstrap JVM class path never needs Opus core classes. It records
 * only lifecycle names, transformer counts, and a class-loader entry count;
 * it never reads identity, server, mod-path, or game argument data.</p>
 */
public final class ForgeTelemetryLifecycle {
    private static final Object LIFECYCLE_LOCK = new Object();
    private static final AtomicBoolean COREMOD_LOADED = new AtomicBoolean();
    private static final AtomicBoolean RUNNING_MARKED = new AtomicBoolean();
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean TERMINATED = new AtomicBoolean();
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean UNCAUGHT_EXCEPTION_HANDLER_INSTALLED = new AtomicBoolean();

    private ForgeTelemetryLifecycle() {
    }

    static void coremodLoaded(Map<String, Object> data) {
        if (!COREMOD_LOADED.compareAndSet(false, true)) {
            return;
        }

        installShutdownHook();
        installUncaughtExceptionHandler();
        ClientTelemetry.startFromSystemProperties();
        ClientTelemetry.bootstrapLoaded(
                classLoaderEntryCount(data), RbwForgeClassTransformer.transformerCount());
        synchronized (LIFECYCLE_LOCK) {
            RbwCore.transition(LifecycleStage.CREATED, LifecycleStage.BOOTSTRAP);
            RbwCore.transition(LifecycleStage.BOOTSTRAP, LifecycleStage.GAME_STARTING);
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
            if (RbwCore.stage() == LifecycleStage.GAME_STARTING) {
                RbwCore.transition(LifecycleStage.GAME_STARTING, LifecycleStage.RUNNING);
            }
            RUNNING_MARKED.set(true);
        }
    }

    static void reportFailure(Throwable failure) {
        if (failure != null && FAILURE_REPORTED.compareAndSet(false, true)) {
            ClientTelemetry.failure(failure);
        }
    }

    static void markTerminated() {
        if (!TERMINATED.compareAndSet(false, true)) {
            return;
        }
        synchronized (LIFECYCLE_LOCK) {
            LifecycleStage current = RbwCore.stage();
            if (current != LifecycleStage.SHUTDOWN) {
                RbwCore.transition(current, LifecycleStage.SHUTDOWN);
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
                // ClientTelemetry's own shutdown hook writes the durable
                // session_end record. This marker is a best-effort lifecycle
                // equivalent and must never delay game termination.
                markTerminated();
            }
        }, "rbw-forge-lifecycle"));
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
                    // Diagnostics must never hide the original crash or its handler.
                }
                if (previous != null) {
                    previous.uncaughtException(thread, failure);
                } else if (!(failure instanceof ThreadDeath)) {
                    failure.printStackTrace();
                }
            }
        });
    }

    static int classLoaderEntryCount(Map<String, Object> data) {
        int launchClassLoaderEntries = launchClassLoaderEntryCount(data);
        return launchClassLoaderEntries > 0
                ? launchClassLoaderEntries
                : systemClasspathEntryCount();
    }

    private static int launchClassLoaderEntryCount(Map<String, Object> data) {
        try {
            if (data == null) {
                return 0;
            }
            Object classLoader = data.get("classLoader");
            if (classLoader instanceof LaunchClassLoader) {
                return ((LaunchClassLoader) classLoader).getSources().size();
            }
        } catch (RuntimeException ignored) {
            // A diagnostics counter must not interfere with Forge startup.
        }
        return 0;
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
