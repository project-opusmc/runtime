package org.polydevs.opusmc.client;

/** Identifies the one Minecraft screen currently allowed to reveal the web surface. */
final class EmbeddedSurfaceLeaseState {
    private long nextId;
    private long currentId;

    long attach() {
        if (nextId == Long.MAX_VALUE) {
            throw new IllegalStateException("embedded surface lease IDs exhausted");
        }
        currentId = ++nextId;
        return currentId;
    }

    boolean detach(long id) {
        if (!isCurrent(id)) {
            return false;
        }
        currentId = 0L;
        return true;
    }

    boolean isCurrent(long id) {
        return id > 0L && id == currentId;
    }

    void clear() {
        currentId = 0L;
    }
}
