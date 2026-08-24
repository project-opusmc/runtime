package org.polydevs.opusmc.client.ui;

/**
 * The complete in-game Opus route state. All entry points resolve one of these
 * values instead of creating unrelated Minecraft GuiScreen subclasses.
 */
public final class UiRoute {
    public enum SettingsSection {
        INTERFACE("interface"),
        GAME("game");

        private final String wireValue;

        SettingsSection(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static SettingsSection fromWireValue(String value) {
            for (SettingsSection section : values()) {
                if (section.wireValue.equals(value)) {
                    return section;
                }
            }
            return null;
        }
    }

    public enum Kind {
        MAIN_MENU,
        PAUSE_MENU,
        SINGLEPLAYER,
        MULTIPLAYER,
        GAME_OPTIONS,
        ACCOUNTS,
        QUICK_HUB,
        HUD_EDITOR,
        MOD_HUB,
        MODULE_DETAIL
    }

    private final Kind kind;
    private final String moduleId;
    private final SettingsSection settingsSection;

    private UiRoute(Kind kind, String moduleId, SettingsSection settingsSection) {
        this.kind = kind;
        this.moduleId = moduleId;
        this.settingsSection = settingsSection;
    }

    public static UiRoute mainMenu() {
        return new UiRoute(Kind.MAIN_MENU, null, null);
    }

    public static UiRoute pauseMenu() {
        return new UiRoute(Kind.PAUSE_MENU, null, null);
    }

    public static UiRoute singleplayer() {
        return new UiRoute(Kind.SINGLEPLAYER, null, null);
    }

    public static UiRoute multiplayer() {
        return new UiRoute(Kind.MULTIPLAYER, null, null);
    }

    public static UiRoute gameOptions() {
        return gameOptions(SettingsSection.GAME);
    }

    public static UiRoute gameOptions(SettingsSection settingsSection) {
        return new UiRoute(
                Kind.GAME_OPTIONS,
                null,
                settingsSection == null ? SettingsSection.GAME : settingsSection);
    }

    public static UiRoute accounts() {
        return new UiRoute(Kind.ACCOUNTS, null, null);
    }

    public static UiRoute quickHub() {
        return new UiRoute(Kind.QUICK_HUB, null, null);
    }

    public static UiRoute hudEditor() {
        return new UiRoute(Kind.HUD_EDITOR, null, null);
    }

    public static UiRoute modHub() {
        return new UiRoute(Kind.MOD_HUB, null, null);
    }

    public static UiRoute moduleDetail(String moduleId) {
        if (moduleId == null || moduleId.trim().isEmpty()) {
            throw new IllegalArgumentException("moduleId is required for module detail");
        }
        return new UiRoute(Kind.MODULE_DETAIL, moduleId, null);
    }

    public Kind kind() {
        return kind;
    }

    public String moduleId() {
        return moduleId;
    }

    public SettingsSection settingsSection() {
        return settingsSection;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof UiRoute)) {
            return false;
        }
        UiRoute route = (UiRoute) other;
        return kind == route.kind
                && (moduleId == null ? route.moduleId == null : moduleId.equals(route.moduleId))
                && settingsSection == route.settingsSection;
    }

    @Override
    public int hashCode() {
        int result = 31 * kind.hashCode() + (moduleId == null ? 0 : moduleId.hashCode());
        return 31 * result + (settingsSection == null ? 0 : settingsSection.hashCode());
    }
}
