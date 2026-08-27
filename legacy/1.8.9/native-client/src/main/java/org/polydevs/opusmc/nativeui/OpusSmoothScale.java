package org.polydevs.opusmc.nativeui;

/**
 * Frame-rate-independent scalar interpolation used by live-resize text.
 *
 * <p>This class deliberately has no Elementa or Minecraft dependency so the
 * resize behavior can be checked without starting the game.</p>
 */
final class OpusSmoothScale {
    static final float RESPONSE = 12f;
    static final float MAX_DELTA_SECONDS = 0.1f;
    static final float EPSILON = 0.0005f;

    private float current;
    private float target;
    private boolean initialized;

    boolean setTarget(float nextTarget) {
        target = nextTarget;
        if (!initialized) {
            current = nextTarget;
            initialized = true;
            return true;
        }
        return false;
    }

    float update(float deltaSeconds) {
        if (!initialized || deltaSeconds <= 0f) {
            return current;
        }

        float boundedDelta = Math.max(0f, Math.min(deltaSeconds, MAX_DELTA_SECONDS));
        float difference = target - current;
        if (Math.abs(difference) <= EPSILON) {
            if (current != target) {
                current = target;
            }
            return current;
        }

        float interpolation = (float) (1d - Math.exp(-RESPONSE * boundedDelta));
        float next = current + difference * interpolation;
        if (Math.abs(target - next) <= EPSILON) {
            next = target;
        }
        current = next;
        return current;
    }

    float getCurrent() {
        return current;
    }

    float getTarget() {
        return target;
    }
}
