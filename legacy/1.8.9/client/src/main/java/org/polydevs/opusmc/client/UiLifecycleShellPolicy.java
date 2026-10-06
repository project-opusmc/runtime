package org.polydevs.opusmc.client;

/** Keeps a normal warm-open from flashing loading text for one frame. */
final class UiLifecycleShellPolicy {
    private static final long TEXT_GRACE_MILLIS = 180L;

    private UiLifecycleShellPolicy() {}

    static boolean showText(long elapsedMillis, boolean failed) {
        return failed || elapsedMillis >= TEXT_GRACE_MILLIS;
    }

    static boolean failedWithoutView(boolean prewarming, boolean prewarmFailed) {
        return !prewarming && prewarmFailed;
    }

    /** Missing the first CEF frame is a transition, not a native-UI failure. */
    static boolean drawNativeSystem(
            boolean systemScreen, boolean failed, boolean currentFrameReady, long elapsedMillis) {
        return systemScreen && (failed || (!currentFrameReady && elapsedMillis >= 1000L));
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
