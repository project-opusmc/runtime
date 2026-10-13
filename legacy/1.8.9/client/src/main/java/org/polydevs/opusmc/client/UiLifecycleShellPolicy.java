package org.polydevs.opusmc.client;

/** Keeps a normal warm-open from flashing loading text for one frame. */
final class UiLifecycleShellPolicy {
    private static final long FRAME_TIMEOUT_MILLIS = 8000L;

    private UiLifecycleShellPolicy() {}

    static boolean showText(long elapsedMillis, boolean failed) {
        return failed;
    }

    static boolean frameTimedOut(long elapsedMillis) {
        return elapsedMillis >= FRAME_TIMEOUT_MILLIS;
    }

    static boolean failedWithoutView(boolean prewarming, boolean prewarmFailed) {
        return !prewarming && prewarmFailed;
    }

    /** Keep the last texture on screen during a route/resize wait, never for a dead lease. */
    static boolean drawTexture(
            boolean currentFrameReady,
            boolean waitingForCurrentFrame,
            boolean retainedTextureAvailable,
            boolean liveVisibleLease) {
        return currentFrameReady
                || (waitingForCurrentFrame && retainedTextureAvailable && liveVisibleLease);
    }

    /** A transport generation is safe only after the matching React route committed. */
    static boolean acceptCommittedFrame(
            long expectedRevision,
            long requestedRevision,
            long currentRevision,
            boolean confirmed) {
        return expectedRevision > 0L && expectedRevision == requestedRevision
                && expectedRevision == currentRevision && confirmed;
    }
}
