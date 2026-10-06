package org.polydevs.opusmc.client.interop;

import java.util.List;

/**
 * Bridge contract the client mod implements so the web UI can read and drive
 * real game data (worlds, servers) without touching Minecraft internals.
 *
 * <p>{@link OpusInteropServer} invokes this contract from request-worker
 * threads. Production providers must marshal every Minecraft/FML read and
 * write to the client thread; preview harness providers may remain in-memory.</p>
 */
public interface OpusBridgeProvider {

    /**
     * Requests a Java-owned UI transition. Implementations must marshal the
     * action onto Minecraft's client thread before touching GuiScreen state.
     */
    boolean performUiAction(
            String action,
            String routeId,
            String moduleId,
            String settingsSection,
            long expectedRevision);

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

    /** Rows are {id,name,enabled,offsetX,offsetY,scale,anchor}. */
    List<String[]> hudModules();

    void moveHudModule(String moduleId, int x, int y);

    /**
     * Publishes the current logical CEF chrome boundary for HUD Editor input.
     * The native editor must fail closed until this region is acknowledged.
     */
    void updateHudEditorCanvas(long navigationRevision, int x, int y, int width, int height);

    /** Optional CEF controls that remain above a full-viewport native editor. */
    default void updateHudEditorCanvas(long navigationRevision, int x, int y, int width, int height,
            List<int[]> exclusions) {
        updateHudEditorCanvas(navigationRevision, x, y, width, height);
    }
}
