package dev.rbw.client.ui;

/**
 * The complete in-game Opus route state. All entry points resolve one of these
 * values instead of creating unrelated Minecraft GuiScreen subclasses.
 */
public final class UiRoute {
    public enum Kind {
        MAIN_MENU,
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
