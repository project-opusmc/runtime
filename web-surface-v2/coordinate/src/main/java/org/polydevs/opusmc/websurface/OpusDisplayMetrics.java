package org.polydevs.opusmc.websurface;

public final class OpusDisplayMetrics {
    private final long generation;
    private final int logicalWidth;
    private final int logicalHeight;
    private final int framebufferWidth;
    private final int framebufferHeight;
    private final double scaleX;
    private final double scaleY;

    public OpusDisplayMetrics(long generation, int logicalWidth, int logicalHeight,
            int framebufferWidth, int framebufferHeight) {
        if (generation <= 0L) throw new IllegalArgumentException("generation must be positive");
        if (logicalWidth <= 0 || logicalHeight <= 0) {
            throw new IllegalArgumentException("logical size must be positive");
        }
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            throw new IllegalArgumentException("framebuffer size must be positive");
        }
        this.generation = generation;
        this.logicalWidth = logicalWidth;
        this.logicalHeight = logicalHeight;
        this.framebufferWidth = framebufferWidth;
        this.framebufferHeight = framebufferHeight;
        this.scaleX = framebufferWidth / (double) logicalWidth;
        this.scaleY = framebufferHeight / (double) logicalHeight;
    }

    public long generation() { return generation; }
    public int logicalWidth() { return logicalWidth; }
    public int logicalHeight() { return logicalHeight; }
    public int framebufferWidth() { return framebufferWidth; }
    public int framebufferHeight() { return framebufferHeight; }
    public double scaleX() { return scaleX; }
    public double scaleY() { return scaleY; }

    public int framebufferToLogicalX(int framebufferX) {
        return clamp((int) Math.floor(clamp(framebufferX, 0, framebufferWidth - 1)
                * logicalWidth / (double) framebufferWidth), 0, logicalWidth - 1);
    }

    public int framebufferBottomToLogicalY(int framebufferY) {
        int clamped = clamp(framebufferY, 0, framebufferHeight - 1);
        int topOriginY = framebufferHeight - 1 - clamped;
        return clamp((int) Math.floor(topOriginY * logicalHeight
                / (double) framebufferHeight), 0, logicalHeight - 1);
    }

    public int framebufferTopToLogicalY(int framebufferY) {
        return clamp((int) Math.floor(clamp(framebufferY, 0, framebufferHeight - 1)
                * logicalHeight / (double) framebufferHeight), 0, logicalHeight - 1);
    }

    public int logicalToFramebufferX(int logicalX) {
        int clamped = clamp(logicalX, 0, logicalWidth - 1);
        return clamp((int) Math.ceil(clamped * framebufferWidth
                / (double) logicalWidth), 0, framebufferWidth - 1);
    }

    public int logicalToFramebufferTopY(int logicalY) {
        int clamped = clamp(logicalY, 0, logicalHeight - 1);
        return clamp((int) Math.ceil(clamped * framebufferHeight
                / (double) logicalHeight), 0, framebufferHeight - 1);
    }

    public int logicalToFramebufferBottomY(int logicalY) {
        return framebufferHeight - 1 - logicalToFramebufferTopY(logicalY);
    }

    public boolean sameGeometry(OpusDisplayMetrics other) {
        return other != null
                && logicalWidth == other.logicalWidth
                && logicalHeight == other.logicalHeight
                && framebufferWidth == other.framebufferWidth
                && framebufferHeight == other.framebufferHeight;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
