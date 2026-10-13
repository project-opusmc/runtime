package org.polydevs.opusmc.client;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded ownership of already-rendered route surfaces between GuiScreens. */
final class RetainedUiSurfaceCache<T> {
    interface Adapter<T> {
        long bytes(T surface);
        void dispose(T surface);
    }

    static final class Key {
        final String route;
        final int width;
        final int height;
        final int scaleBits;
        Key(String route, int width, int height, float scale) {
            this.route = route;
            this.width = width;
            this.height = height;
            this.scaleBits = Float.floatToIntBits(scale);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return route.equals(key.route) && width == key.width && height == key.height && scaleBits == key.scaleBits;
        }
        @Override public int hashCode() {
            return (((route.hashCode() * 31 + width) * 31 + height) * 31 + scaleBits);
        }
    }

    private final Adapter<T> adapter;
    private final int limit;
    private final long budget;
    private final LinkedHashMap<Key, T> surfaces = new LinkedHashMap<Key, T>(8, 0.75F, true);
    private WeakReference<Object> world = new WeakReference<Object>(null);
    private WeakReference<Object> session = new WeakReference<Object>(null);
    private boolean contextSet;
    private boolean nullWorld;
    private boolean nullSession;
    private long epoch;
    private long bytes;

    RetainedUiSurfaceCache(int limit, long budget, Adapter<T> adapter) {
        if (limit < 1 || budget < 1 || adapter == null) throw new IllegalArgumentException("Invalid surface cache budget");
        this.limit = limit; this.budget = budget; this.adapter = adapter;
    }
    synchronized long context(Object nextWorld, Object nextSession) {
        if (!contextSet || nullWorld != (nextWorld == null) || nullSession != (nextSession == null)
                || world.get() != nextWorld || session.get() != nextSession) {
            clear(); world = new WeakReference<Object>(nextWorld); session = new WeakReference<Object>(nextSession); contextSet = true;
            nullWorld = nextWorld == null; nullSession = nextSession == null;
        }
        return epoch;
    }
    synchronized void retain(Key key, long sourceEpoch, T surface) {
        long size = adapter.bytes(surface);
        if (sourceEpoch != epoch || size < 1 || size > budget) { adapter.dispose(surface); return; }
        // Transfer, never alias the same GL owner under two route keys.
        Iterator<Map.Entry<Key, T>> duplicate = surfaces.entrySet().iterator();
        while (duplicate.hasNext()) {
            Map.Entry<Key, T> entry = duplicate.next();
            if (entry.getValue() == surface || entry.getKey().equals(key)) {
                duplicate.remove(); bytes -= adapter.bytes(entry.getValue());
                if (entry.getValue() != surface) adapter.dispose(entry.getValue());
            }
        }
        surfaces.put(key, surface); bytes += size;
        while (surfaces.size() > limit || bytes > budget) {
            Iterator<Map.Entry<Key, T>> entries = surfaces.entrySet().iterator();
            T oldest = entries.next().getValue(); entries.remove(); bytes -= adapter.bytes(oldest); adapter.dispose(oldest);
        }
    }
    synchronized T take(Key key, long expectedEpoch) {
        if (expectedEpoch != epoch) return null;
        T surface = surfaces.remove(key); if (surface != null) bytes -= adapter.bytes(surface); return surface;
    }
    synchronized void clear() {
        for (T surface : surfaces.values()) adapter.dispose(surface);
        surfaces.clear(); bytes = 0L; epoch++;
    }
}
