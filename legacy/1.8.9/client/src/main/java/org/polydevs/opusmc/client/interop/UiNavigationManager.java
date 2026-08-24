package org.polydevs.opusmc.client.interop;

import java.util.Objects;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.ui.UiNavigationState;
import org.polydevs.opusmc.client.ui.UiRoute;

/**
 * Canonical navigation transport and acknowledgement owner.
 *
 * <p>The wire contract is exclusively {@code ui-state}, {@code ui-actions},
 * and {@code uiNavigationChanged}; no legacy event or endpoint is emitted as a
 * second routing authority.</p>
 */
public final class UiNavigationManager {
    // The embedded web view has to spawn a helper process, boot WebKit, load
    // the SPA and open a WebSocket before it can acknowledge a route. 1.5s was
    // too tight and caused a disruptive reload flash on first paint, so allow a
    // generous window; a real desync still recovers, just a little later.
    private static final long ACK_TIMEOUT_MS = 8000L;

    private final Logger log;
    private final OpusInteropServer server;
    private final ScheduledExecutorService scheduler = Executors
            .newSingleThreadScheduledExecutor(daemonThreads("opus-ui-ack"));
    private volatile String current = "none";
    private volatile String moduleId = "";
    private volatile String settingsSection = "";
    private volatile String entryPoint = "launch";
    private volatile String worldContext = "none";
    private volatile String presentation = "opaque";
    private volatile String pausePolicy = "not_applicable";
    private volatile String returnTo = "";
    private volatile String returnModuleId = "";
    private volatile String returnSettingsSection = "";
    private volatile long revision;
    private volatile boolean canGoBack;
    private volatile boolean canCloseToGame;
    private volatile boolean confirmed = true;
    private volatile ScheduledFuture<?> pendingTimeout;
    private volatile Runnable reloadAction = new Runnable() {
        @Override
        public void run() {
            // No-op until the controller wires the external/CEF reload path.
        }
    };

    public UiNavigationManager(Logger log, OpusInteropServer server) {
        this.log = log;
        this.server = server;
    }

    public void setReloadAction(Runnable reloadAction) {
        this.reloadAction = reloadAction == null
                ? this.reloadAction
                : reloadAction;
    }

    public String stateJson() {
        return navigationJson();
    }

    /** Read-only lifecycle diagnostic used by the real CEF integration harness. */
    public boolean isConfirmed() {
        return confirmed;
    }

    /** Monotonic revision shared with the CEF bridge and input ownership. */
    public long revision() {
        return revision;
    }

    public void open(String name) {
        commit(name, null, revision + 1L, "launch", "none", "opaque",
                "not_applicable", false, false);
    }

    /** Commits the complete Java-owned navigation state to the bridge. */
    public void commit(
            UiNavigationState nextNavigation,
            String nextWorldContext,
            String nextPresentation,
            String nextPausePolicy,
            boolean nextCanCloseToGame) {
        if (nextNavigation == null || nextNavigation.current() == null) {
            return;
        }
        UiRoute route = nextNavigation.current();
        UiRoute previous = nextNavigation.returnTo();
        commitValues(
                routeId(route),
                route.moduleId(),
                route.settingsSection() == null ? null : route.settingsSection().wireValue(),
                nextNavigation.revision(),
                entryPointName(nextNavigation.entryPoint()),
                nextWorldContext,
                nextPresentation,
                nextPausePolicy,
                nextNavigation.canGoBack(),
                nextCanCloseToGame,
                previous == null ? null : routeId(previous),
                previous == null ? null : previous.moduleId(),
                previous == null || previous.settingsSection() == null
                        ? null
                        : previous.settingsSection().wireValue());
    }

    public void commit(
            String name,
            String nextModuleId,
            long nextRevision,
            String nextEntryPoint,
            String nextWorldContext,
            String nextPresentation,
            String nextPausePolicy,
            boolean nextCanGoBack,
            boolean nextCanCloseToGame) {
        commitValues(
                name,
                nextModuleId,
                null,
                nextRevision,
                nextEntryPoint,
                nextWorldContext,
                nextPresentation,
                nextPausePolicy,
                nextCanGoBack,
                nextCanCloseToGame,
                null,
                null,
                null);
    }

    private void commitValues(
            String name,
            String nextModuleId,
            String nextSettingsSection,
            long nextRevision,
            String nextEntryPoint,
            String nextWorldContext,
            String nextPresentation,
            String nextPausePolicy,
            boolean nextCanGoBack,
            boolean nextCanCloseToGame,
            String nextReturnTo,
            String nextReturnModuleId,
            String nextReturnSettingsSection) {
        String safe = name == null ? "" : name;
        String safeModuleId = nextModuleId == null ? "" : nextModuleId;
        String safeSettingsSection = nextSettingsSection == null ? "" : nextSettingsSection;
        String safeReturnTo = nextReturnTo == null ? "" : nextReturnTo;
        String safeReturnModuleId = nextReturnModuleId == null ? "" : nextReturnModuleId;
        String safeReturnSettingsSection = nextReturnSettingsSection == null
                ? ""
                : nextReturnSettingsSection;
        boolean sameRoute = Objects.equals(current, safe)
                && Objects.equals(moduleId, safeModuleId)
                && Objects.equals(settingsSection, safeSettingsSection);
        long safeRevision = sameRoute
                ? Math.max(revision, nextRevision)
                : Math.max(revision + 1L, nextRevision);
        if (sameRoute
                && revision == safeRevision
                && confirmed
                && Objects.equals(entryPoint, safeValue(nextEntryPoint, "launch"))
                && Objects.equals(worldContext, safeValue(nextWorldContext, "none"))
                && Objects.equals(presentation, safeValue(nextPresentation, "opaque"))
                && Objects.equals(pausePolicy, safeValue(nextPausePolicy, "not_applicable"))
                && canGoBack == nextCanGoBack
                && canCloseToGame == nextCanCloseToGame
                && Objects.equals(returnTo, safeReturnTo)
                && Objects.equals(returnModuleId, safeReturnModuleId)
                && Objects.equals(returnSettingsSection, safeReturnSettingsSection)) {
            return;
        }
        current = safe;
        moduleId = safeModuleId;
        settingsSection = safeSettingsSection;
        revision = safeRevision;
        entryPoint = safeValue(nextEntryPoint, "launch");
        worldContext = safeValue(nextWorldContext, "none");
        presentation = safeValue(nextPresentation, "opaque");
        pausePolicy = safeValue(nextPausePolicy, "not_applicable");
        canGoBack = nextCanGoBack;
        canCloseToGame = nextCanCloseToGame;
        returnTo = safeReturnTo;
        returnModuleId = safeReturnModuleId;
        returnSettingsSection = safeReturnSettingsSection;
        confirmed = false;
        cancelTimeout();
        broadcast("open");
        scheduleTimeout(revision);
    }

    public void close() {
        current = "none";
        moduleId = "";
        settingsSection = "";
        returnTo = "";
        returnModuleId = "";
        returnSettingsSection = "";
        revision++;
        entryPoint = "launch";
        worldContext = "none";
        presentation = "opaque";
        pausePolicy = "not_applicable";
        canGoBack = false;
        canCloseToGame = false;
        confirmed = true;
        cancelTimeout();
        broadcast("close");
    }

    public void confirm(long acknowledgedRevision) {
        if (acknowledgedRevision != revision) {
            return;
        }
        confirmed = true;
        cancelTimeout();
    }

    /** Returns false when a browser action was built from an older state. */
    public boolean isCurrentRevision(long expectedRevision) {
        return expectedRevision == revision;
    }

    public void shutdown() {
        cancelTimeout();
        scheduler.shutdownNow();
    }

    private void broadcast(String action) {
        String navigation = "{\"name\":\"uiNavigationChanged\",\"event\":"
                + navigationJson() + "}";
        server.broadcast(navigation);
        log.info("Opus UI navigation r{} {} -> {}{}", revision, action, current,
                moduleId.isEmpty() ? "" : " (" + moduleId + ")");
    }

    private String navigationJson() {
        StringBuilder out = new StringBuilder("{");
        out.append("\"revision\":").append(revision).append(',');
        out.append("\"current\":");
        appendRoute(out, current, moduleId, settingsSection);
        out.append(',');
        out.append("\"entryPoint\":\"").append(OpusJson.escape(entryPoint)).append("\",");
        out.append("\"returnTo\":");
        if (returnTo.isEmpty()) {
            out.append("null");
        } else {
            appendRoute(out, returnTo, returnModuleId, returnSettingsSection);
        }
        out.append(',');
        out.append("\"worldContext\":\"").append(OpusJson.escape(worldContext)).append("\",");
        out.append("\"presentation\":\"").append(OpusJson.escape(presentation)).append("\",");
        out.append("\"pausePolicy\":\"").append(OpusJson.escape(pausePolicy)).append("\",");
        out.append("\"canGoBack\":").append(canGoBack).append(',');
        out.append("\"canCloseToGame\":").append(canCloseToGame);
        out.append('}');
        return out.toString();
    }

    private static void appendRoute(
            StringBuilder out,
            String id,
            String moduleId,
            String settingsSection) {
        out.append("{\"id\":\"").append(OpusJson.escape(id)).append('"');
        boolean hasModule = moduleId != null && !moduleId.isEmpty();
        boolean hasSettings = settingsSection != null && !settingsSection.isEmpty();
        if (hasModule || hasSettings) {
            out.append(",\"params\":{");
            boolean needsComma = false;
            if (hasModule) {
                out.append("\"moduleId\":\"")
                        .append(OpusJson.escape(moduleId)).append('"');
                needsComma = true;
            }
            if (hasSettings) {
                if (needsComma) {
                    out.append(',');
                }
                out.append("\"settingsSection\":\"")
                        .append(OpusJson.escape(settingsSection)).append('"');
            }
            out.append('}');
        }
        out.append('}');
    }

    private static String routeId(UiRoute route) {
        switch (route.kind()) {
            case MAIN_MENU:
                return "title";
            case SINGLEPLAYER:
                return "singleplayer";
            case MULTIPLAYER:
                return "multiplayer";
            case GAME_OPTIONS:
                return "settings";
            case ACCOUNTS:
                return "accounts";
            case PAUSE_MENU:
                return "game_menu";
            case QUICK_HUB:
                return "quick_hub";
            case MOD_HUB:
                return "mods_catalog";
            case MODULE_DETAIL:
                return "module_detail";
            case HUD_EDITOR:
                return "hud_editor";
            default:
                return "none";
        }
    }

    private static String entryPointName(UiNavigationState.EntryPoint entryPoint) {
        return entryPoint == null
                ? "launch"
                : entryPoint.name().toLowerCase(Locale.ROOT);
    }

    private void scheduleTimeout(final long expectedRevision) {
        try {
            pendingTimeout = scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    if (!confirmed && revision == expectedRevision) {
                        log.warn(
                                "Opus UI did not acknowledge navigation revision {}; reloading",
                                expectedRevision);
                        reloadAction.run();
                    }
                }
            }, ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            log.warn("Opus navigation ack timer failed", failure);
        }
    }

    private static String safeValue(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
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
