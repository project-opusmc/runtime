package org.polydevs.opusmc.client;

/** Owns the world blur only while Opus's pause route is visible. */
final class PauseWorldBlur {
    interface ShaderHost {
        Object currentShader();
        Object loadPauseShader();
        void stopShader();
    }

    private final ShaderHost host;
    private Object ownedShader;
    private boolean attempted;

    PauseWorldBlur(ShaderHost host) {
        this.host = host;
    }

    void update(boolean pauseVisible) {
        if (!pauseVisible) {
            close();
            return;
        }
        if (attempted) return;
        attempted = true;
        // Respect shaders selected by Minecraft, a resource pack or another mod.
        if (host.currentShader() != null) return;
        ownedShader = host.loadPauseShader();
    }

    void close() {
        try {
            if (ownedShader != null && host.currentShader() == ownedShader) {
                host.stopShader();
            }
        } finally {
            ownedShader = null;
            attempted = false;
        }
    }
}
