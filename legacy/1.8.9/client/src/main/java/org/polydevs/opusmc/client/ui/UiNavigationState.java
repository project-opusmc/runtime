package org.polydevs.opusmc.client.ui;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Java-owned navigation state for one Opus product-screen flow.
 *
 * <p>The web UI may request a transition, but it never mutates this state
 * directly. Keeping the route stack here makes Back, Escape and route
 * parameters deterministic even when the CEF document is reloaded.</p>
 */
public final class UiNavigationState {
    public enum EntryPoint {
        LAUNCH,
        TITLE,
        PAUSE,
        HOTKEY,
        MODS_CATALOG,
        HUD_WIDGET
    }

    private static final class HistoryEntry {
        private final UiRoute route;
        private final EntryPoint entryPoint;

        private HistoryEntry(UiRoute route, EntryPoint entryPoint) {
            this.route = route;
            this.entryPoint = entryPoint;
        }
    }

    private final Deque<HistoryEntry> history = new ArrayDeque<HistoryEntry>();
    /** Entry point that owns the whole screen flow, independent of detail origin. */
    private final EntryPoint rootEntryPoint;
    private EntryPoint entryPoint;
    private UiRoute current;
    private long revision = 1L;

    public UiNavigationState(UiRoute initial, EntryPoint entryPoint) {
        this(initial, entryPoint, null);
    }

    public UiNavigationState(UiRoute initial, EntryPoint entryPoint, UiRoute returnTo) {
        if (initial == null) {
            throw new IllegalArgumentException("initial route is required");
        }
        this.current = initial;
        this.entryPoint = entryPoint == null ? EntryPoint.LAUNCH : entryPoint;
        this.rootEntryPoint = this.entryPoint;
        if (returnTo != null && !returnTo.equals(initial)) {
            history.push(new HistoryEntry(returnTo, this.entryPoint));
        }
    }

    public UiRoute current() {
        return current;
    }

    public EntryPoint entryPoint() {
        return entryPoint;
    }

    /** True when the flow was opened by the in-game Right Shift hotkey. */
    public boolean isRootHotkeyFlow() {
        return rootEntryPoint == EntryPoint.HOTKEY;
    }

    public long revision() {
        return revision;
    }

    public boolean canGoBack() {
        return !history.isEmpty();
    }

    public UiRoute returnTo() {
        HistoryEntry entry = history.peek();
        return entry == null ? null : entry.route;
    }

    public boolean navigate(UiRoute next) {
        if (next == null || next.equals(current)) {
            return false;
        }
        history.push(new HistoryEntry(current, entryPoint));
        entryPoint = entryPointForTransition(current, next, entryPoint);
        current = next;
        revision++;
        return true;
    }

    public UiRoute back() {
        if (history.isEmpty()) {
            return null;
        }
        HistoryEntry previous = history.pop();
        current = previous.route;
        entryPoint = previous.entryPoint;
        revision++;
        return current;
    }

    private static EntryPoint entryPointForTransition(
            UiRoute previous,
            UiRoute next,
            EntryPoint currentEntryPoint) {
        if (next.kind() == UiRoute.Kind.MODULE_DETAIL) {
            if (previous.kind() == UiRoute.Kind.MOD_HUB) {
                return EntryPoint.MODS_CATALOG;
            }
            if (previous.kind() == UiRoute.Kind.HUD_EDITOR) {
                return EntryPoint.HUD_WIDGET;
            }
        }
        return currentEntryPoint;
    }
}
