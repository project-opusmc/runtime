package org.polydevs.opusmc.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.polydevs.opusmc.core.LifecycleStage;
import org.polydevs.opusmc.core.OpusCore;
import java.io.File;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.Test;

final class ForgeTelemetryLifecycleTest {
    @Test
    void fallsBackToSystemClasspathWhenLaunchClassLoaderHasNoSources() throws Exception {
        String originalClasspath = System.getProperty("java.class.path");
        LaunchClassLoader classLoader = new LaunchClassLoader(new URL[0]);
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("classLoader", classLoader);
        try {
            System.setProperty(
                    "java.class.path",
                    "first" + File.pathSeparator + "second" + File.pathSeparator + "third");
            assertEquals(3, ForgeTelemetryLifecycle.classLoaderEntryCount(data));
        } finally {
            classLoader.close();
            if (originalClasspath == null) {
                System.clearProperty("java.class.path");
            } else {
                System.setProperty("java.class.path", originalClasspath);
            }
        }
    }

    @Test
    void followsTheLifecycleAndForwardsUnhandledFailures() throws InterruptedException {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        AtomicReference<Thread> forwardedThread = new AtomicReference<Thread>();
        AtomicReference<Throwable> forwardedFailure = new AtomicReference<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable failure) {
                forwardedThread.set(thread);
                forwardedFailure.set(failure);
            }
        });

        try {
            ForgeTelemetryLifecycle.coremodLoaded(Collections.<String, Object>emptyMap());
            assertEquals(LifecycleStage.GAME_STARTING, OpusCore.stage());

            final RuntimeException failure = new IllegalStateException("expected test failure");
            Thread crashingThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    throw failure;
                }
            }, "opus-forge-test-crash");
            crashingThread.start();
            crashingThread.join();
            assertSame(crashingThread, forwardedThread.get());
            assertSame(failure, forwardedFailure.get());

            ForgeTelemetryLifecycle.markRunning();
            ForgeTelemetryLifecycle.markRunning();
            assertEquals(LifecycleStage.RUNNING, OpusCore.stage());

            ForgeTelemetryLifecycle.markTerminated();
            assertEquals(LifecycleStage.SHUTDOWN, OpusCore.stage());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
    }
}
