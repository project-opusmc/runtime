package org.polydevs.opusmc.client.ui;

/**
 * The complete in-game Opus route state. All entry points resolve one of these
 * values instead of creating unrelated Minecraft GuiScreen subclasses.
 */
public final class UiRoute {
    public enum Kind {
        MAIN_MENU,
        PAUSE_MENU,
        SINGLEPLAYER,
        MULTIPLAYER,
        GAME_OPTIONS,
        CONTROLS,
        LANGUAGE,
        HUD_EDITOR,
        MOD_HUB,
        MODULE_DETAIL
    }

    private final Kind kind;
    private final String moduleId;

    private UiRoute(Kind kind, String moduleId) {
        this.kind = kind;
        this.moduleId = moduleId;
    }

    public static UiRoute mainMenu() {
        return new UiRoute(Kind.MAIN_MENU, null);
    }

    public static UiRoute pauseMenu() {
        return new UiRoute(Kind.PAUSE_MENU, null);
    }

    public static UiRoute singleplayer() {
        return new UiRoute(Kind.SINGLEPLAYER, null);
    }

    public static UiRoute multiplayer() {
        return new UiRoute(Kind.MULTIPLAYER, null);
    }

    public static UiRoute gameOptions() {
        return new UiRoute(Kind.GAME_OPTIONS, null);
    }

    public static UiRoute controls() {
        return new UiRoute(Kind.CONTROLS, null);
    }

    public static UiRoute language() {
        return new UiRoute(Kind.LANGUAGE, null);
    }

    public static UiRoute hudEditor() {
        return new UiRoute(Kind.HUD_EDITOR, null);
    }

    public static UiRoute modHub() {
        return new UiRoute(Kind.MOD_HUB, null);
    }

    public static UiRoute moduleDetail(String moduleId) {
        if (moduleId == null || moduleId.trim().isEmpty()) {
            throw new IllegalArgumentException("moduleId is required for module detail");
        }
        return new UiRoute(Kind.MODULE_DETAIL, moduleId);
    }

    public Kind kind() {
        return kind;
    }

    public String moduleId() {
        return moduleId;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof UiRoute)) {
            return false;
        }
        UiRoute route = (UiRoute) other;
        return kind == route.kind
                && (moduleId == null ? route.moduleId == null : moduleId.equals(route.moduleId));
    }

    @Override
    public int hashCode() {
        return 31 * kind.hashCode() + (moduleId == null ? 0 : moduleId.hashCode());
    }
}
