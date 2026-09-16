package org.polydevs.opusmc.websurface;

public final class OpusDisplayMetricsTracker {
    private long generation;
    private OpusDisplayMetrics current;

    public synchronized OpusDisplayMetrics update(int logicalWidth, int logicalHeight,
            int framebufferWidth, int framebufferHeight) {
        OpusDisplayMetrics candidate = new OpusDisplayMetrics(
                generation == 0L ? 1L : generation,
                logicalWidth, logicalHeight, framebufferWidth, framebufferHeight);
        if (current == null || !current.sameGeometry(candidate)) {
            generation++;
            current = new OpusDisplayMetrics(generation,
                    logicalWidth, logicalHeight, framebufferWidth, framebufferHeight);
        }
        return current;
    }

    public synchronized OpusDisplayMetrics current() {
        return current;
    }
}
