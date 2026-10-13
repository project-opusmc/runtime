package org.polydevs.opusmc.client;

public final class RetainedUiSurfaceHarness {
    private static final class Surface {
        final long bytes;
        int disposed;
        Surface(long bytes) { this.bytes = bytes; }
    }
    private static RetainedUiSurfaceCache<Surface> cache(int count, long bytes) {
        return new RetainedUiSurfaceCache<Surface>(count, bytes, new RetainedUiSurfaceCache.Adapter<Surface>() {
            public long bytes(Surface surface) { return surface.bytes; }
            public void dispose(Surface surface) { surface.disposed++; }
        });
    }
    private static RetainedUiSurfaceCache.Key key(String route) {
        return new RetainedUiSurfaceCache.Key(route, 854, 480, 2.0F);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        RetainedUiSurfaceCache<Surface> cache = cache(4, 100);
        Object world = new Object(), session = new Object();
        long epoch = cache.context(world, session);
        Surface pause = new Surface(30);
        cache.retain(key("pause"), epoch, pause);
        require(cache.take(key("title"), epoch) == null, "opaque Home frame leaked into Pause");
        require(cache.take(new RetainedUiSurfaceCache.Key("pause", 854, 480, 1.0F), epoch) == null, "wrong DPI frame reused");
        require(cache.take(key("pause"), epoch) == pause && pause.disposed == 0, "warm reopen discarded its committed surface");
        require(cache.take(key("pause"), epoch) == null, "one surface was handed to two screens");
        cache.retain(key("pause"), epoch, pause);
        long next = cache.context(new Object(), session);
        require(pause.disposed == 1 && cache.take(key("pause"), next) == null, "previous world was retained");
        Surface stale = new Surface(30);
        cache.retain(key("pause"), epoch, stale);
        require(stale.disposed == 1, "late close repopulated a stale world cache");
        Surface current = new Surface(30);
        cache.retain(key("pause"), next, current);
        long account = cache.context(null, new Object());
        require(current.disposed == 1 && cache.take(key("pause"), account) == null, "previous account frame was retained");

        cache = cache(4, 100); epoch = cache.context(null, session);
        Surface a = new Surface(40), b = new Surface(40), c = new Surface(40);
        cache.retain(key("a"), epoch, a); cache.retain(key("b"), epoch, b); cache.retain(key("c"), epoch, c);
        require(a.disposed == 1 && b.disposed == 0 && c.disposed == 0, "byte budget did not evict oldest surface");
        require(cache.take(key("b"), epoch) == b, "budget evicted the wrong surface");
        cache.clear();
        require(c.disposed == 1 && b.disposed == 0, "clearing cache disposed an active borrowed surface");
        Surface oversized = new Surface(101);
        epoch = cache.context(null, session); cache.retain(key("large"), epoch, oversized);
        require(oversized.disposed == 1 && cache.take(key("large"), epoch) == null, "oversized frame escaped budget");
        cache = cache(1, 100); epoch = cache.context(null, session);
        Surface alias = new Surface(10), other = new Surface(10);
        cache.retain(key("a"), epoch, alias); cache.retain(key("a"), epoch, alias);
        require(alias.disposed == 0, "same resource was disposed during replacement");
        cache.retain(key("other"), epoch, other);
        require(alias.disposed == 1, "entry limit leaked a previous GPU surface");
        cache.clear(); require(other.disposed == 1, "retry/shutdown leaked a GPU surface");
        cache = cache(4, 100); epoch = cache.context(world, session);
        Surface unloaded = new Surface(20); cache.retain(key("pause"), epoch, unloaded);
        try {
            java.lang.reflect.Field reference = RetainedUiSurfaceCache.class.getDeclaredField("world");
            reference.setAccessible(true);
            ((java.lang.ref.WeakReference<?>) reference.get(cache)).clear();
        } catch (Exception failure) { throw new AssertionError(failure); }
        long unloadedEpoch = cache.context(null, session);
        require(unloadedEpoch != epoch && unloaded.disposed == 1,
                "collected previous world became indistinguishable from the title context");
        System.out.println("PASS: warm surface transfer, route/DPI/world/account isolation, stale-close rejection and bounded GPU ownership");
    }
}
