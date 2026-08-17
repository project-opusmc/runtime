package org.polydevs.opusmc.client.interop;

import java.util.List;

/**
 * Bridge contract the client mod implements so the web UI can read and drive
 * real game data (worlds, servers) without touching Minecraft internals.
 */
public interface OpusBridgeProvider {

    /** Rows are {fileName, displayName}. */
    List<String[]> listWorlds();

    void loadWorld(String fileName, String displayName);

    /** Rows are {name, address}. */
    List<String[]> listServers();

    void connectServer(String address);

    void addServer(String name, String address);
}
