package org.polydevs.opusmc.client.interop;

/** Immutable presentation of one Minecraft-owned system screen. */
public final class ConnectionUiState {
    public final String phase;
    public final String title;
    public final String detail;
    public final String serverName;
    public final String serverAddress;
    public final boolean canCancel;

    public ConnectionUiState(String phase, String title, String detail,
            String serverName, String serverAddress, boolean canCancel) {
        if (!"connecting".equals(phase) && !"loading".equals(phase) && !"disconnected".equals(phase)) {
            throw new IllegalArgumentException("Unknown connection phase");
        }
        this.phase = phase;
        this.title = safe(title);
        this.detail = safe(detail);
        this.serverName = safe(serverName);
        this.serverAddress = safe(serverAddress);
        this.canCancel = canCancel;
    }

    String json() {
        return "{\"phase\":\"" + OpusJson.escape(phase)
                + "\",\"title\":\"" + OpusJson.escape(title)
                + "\",\"detail\":\"" + OpusJson.escape(detail)
                + "\",\"serverName\":\"" + OpusJson.escape(serverName)
                + "\",\"serverAddress\":\"" + OpusJson.escape(serverAddress)
                + "\",\"canCancel\":" + canCancel + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConnectionUiState)) return false;
        ConnectionUiState that = (ConnectionUiState) o;
        return canCancel == that.canCancel
                && java.util.Objects.equals(phase, that.phase)
                && java.util.Objects.equals(title, that.title)
                && java.util.Objects.equals(detail, that.detail)
                && java.util.Objects.equals(serverName, that.serverName)
                && java.util.Objects.equals(serverAddress, that.serverAddress);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(phase, title, detail, serverName, serverAddress, canCancel);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
