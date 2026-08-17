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

    /** Rows are {key,label,type,value,min,max,step}. */
    List<String[]> gameOptions();

    void adjustGameOption(String key, int delta);

    void setGameOptionFloat(String key, float value);

    void leaveWorld();

    /** Rows are {id, name, enabled}. */
    List<String[]> modules();

    void setModuleEnabled(String moduleId, boolean enabled);

    /**
     * Rows are {key,label,type,value,min,max,step,options}; options is a
     * comma-separated enum storage list or empty.
     */
    List<String[]> moduleSettings(String moduleId);

    void setModuleSetting(String moduleId, String key, String value);
}
