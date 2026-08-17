package org.polydevs.opusmc.client.interop;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Logger;

/**
 * Virtual screen + acknowledgement (LiquidBounce ScreenAcknowledgement model).
 * The client emits screen changes over WebSocket; the frontend confirms via
 * REST. If no confirmation arrives in time the browser is reloaded so both
 * sides can never silently desync.
 */
public final class OpusVirtualScreenManager {
    private static final long ACK_TIMEOUT_MS = 1500L;

    private final Logger log;
    private final OpusInteropServer server;
    private final ScheduledExecutorService scheduler = Executors
            .newSingleThreadScheduledExecutor(daemonThreads("opus-vscreen-ack"));
    private volatile String current = "";
    private volatile boolean confirmed = true;
    private volatile ScheduledFuture<?> pendingTimeout;
    private volatile Runnable reloadAction = new Runnable() {
        @Override
        public void run() {
            // No-op until the controller wires the external/CEF reload path.
        }
    };

    public OpusVirtualScreenManager(Logger log, OpusInteropServer server) {
        this.log = log;
        this.server = server;
    }

    public void setReloadAction(Runnable reloadAction) {
        this.reloadAction = reloadAction == null
                ? this.reloadAction
                : reloadAction;
    }

    public String current() {
        return current;
    }

    public void open(String name) {
        String safe = name == null ? "" : name;
        if (Objects.equals(current, safe) && confirmed) {
            return;
        }
        current = safe;
        confirmed = false;
        cancelTimeout();
        broadcast(safe, "open");
        scheduleTimeout(safe);
    }

    public void close() {
        current = "";
        confirmed = true;
        cancelTimeout();
        broadcast("", "close");
    }

    public void confirm(String name) {
        String safe = name == null ? "" : name;
        if (!Objects.equals(current, safe)) {
            return;
        }
        confirmed = true;
        cancelTimeout();
    }

    private void broadcast(String screenName, String action) {
        String message = "{\"name\":\"virtualScreen\",\"event\":{"
                + "\"screenName\":\"" + OpusJson.escape(screenName) + "\","
                + "\"action\":\"" + action + "\"}}";
        server.broadcast(message);
        log.info("Opus virtual screen {} -> {} ({})", action, screenName,
                screenName.isEmpty() ? "in game" : "ui");
    }

    private void scheduleTimeout(final String expected) {
        try {
            pendingTimeout = scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    if (!confirmed && Objects.equals(current, expected)) {
                        log.warn(
                                "Opus UI did not acknowledge screen '{}'; reloading",
                                expected);
                        reloadAction.run();
                    }
                }
            }, ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            log.warn("Opus virtual screen ack timer failed", failure);
        }
    }

    private void cancelTimeout() {
        ScheduledFuture<?> pending = pendingTimeout;
        pendingTimeout = null;
        if (pending != null) {
            pending.cancel(false);
        }
    }

    private static ThreadFactory daemonThreads(final String prefix) {
        return new ThreadFactory() {
            private int sequence;

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable,
                        prefix + "-" + (++sequence));
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
