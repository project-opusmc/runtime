package dev.rbw.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Boundary between injected Minecraft bytecode and Opus-owned UI code.
 *
 * <p>This class intentionally exposes only {@link Object}-typed methods.
 * Core is loaded by the bootstrap parent class loader, whereas Minecraft's
 * obfuscated classes are loaded by the child transformer loader. Reflection
 * keeps that boundary explicit and prevents game classes from leaking into
 * the launcher/core class path.</p>
 */
public final class ClientUiHooks {
    public static final int PAUSE_MENU_BUTTON_ID = 27000;

    private static final String SCREEN_FACTORY =
            "dev.rbw.patches.RbwClientOptionsScreenFactory";
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();

    private ClientUiHooks() {
    }

    /** Handles one raw keyboard event from Minecraft's existing input loop. */
    public static void onKeyboardEvent(int keyCode, boolean pressed, Object minecraft) {
        if (keyCode != 54 || !pressed || minecraft == null) {
            return;
        }
        try {
            if (readField(minecraft, "m") != null) {
                return;
            }
            openClientOptions(minecraft, null);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reportFailure("open Client Options from Right Shift", failure);
        }
    }

    /** Opens Client Options when the injected pause-menu button is clicked. */
    public static void onPauseMenuButton(Object pauseMenu, Object button) {
        if (pauseMenu == null || button == null) {
            return;
        }
        try {
            if (readIntField(button, "k") != PAUSE_MENU_BUTTON_ID) {
                return;
            }
            Object minecraft = readField(pauseMenu, "j");
            if (minecraft != null) {
                openClientOptions(minecraft, pauseMenu);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reportFailure("open Client Options from the pause menu", failure);
        }
    }

    /** Draws only enabled, functional client widgets after Minecraft's own HUD. */
    public static void renderHud(Object guiIngame) {
        if (guiIngame == null) {
            return;
        }
        try {
            ClientConfigUi.renderHud(guiIngame);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reportFailure("render Opus HUD", failure);
        }
    }

    /** Initializes one custom Utilities workspace after Minecraft opens it. */
    public static void openConfigScreen(Object screen) {
        if (screen == null) {
            return;
        }
        try {
            ClientConfigUi.opened(screen);
        } catch (RuntimeException failure) {
            reportFailure("initialize Client Options", failure);
        }
    }

    /** Releases the per-screen workspace state when Minecraft closes it. */
    public static void closeConfigScreen(Object screen) {
        if (screen == null) {
            return;
        }
        try {
            ClientConfigUi.closed(screen);
        } catch (RuntimeException failure) {
            reportFailure("close Client Options", failure);
        }
    }

    /** Renders the full Utilities workspace over the live game view. */
    public static void renderConfigScreen(
            Object screen,
            int mouseX,
            int mouseY,
            float partialTicks) {
        if (screen == null) {
            return;
        }
        try {
            ClientConfigUi.render(screen, mouseX, mouseY, partialTicks);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reportFailure("render Client Options", failure);
        }
    }

    public static void configMouseClicked(
            Object screen,
            int mouseX,
            int mouseY,
            int button) {
        try {
            ClientConfigUi.mouseClicked(screen, mouseX, mouseY, button);
        } catch (RuntimeException failure) {
            reportFailure("handle a Client Options click", failure);
        }
    }

    public static void configMouseDragged(
            Object screen,
            int mouseX,
            int mouseY,
            int button,
            long elapsed) {
        try {
            ClientConfigUi.mouseDragged(screen, mouseX, mouseY, button, elapsed);
        } catch (RuntimeException failure) {
            reportFailure("handle a Client Options drag", failure);
        }
    }

    public static void configMouseReleased(
            Object screen,
            int mouseX,
            int mouseY,
            int button) {
        try {
            ClientConfigUi.mouseReleased(screen, mouseX, mouseY, button);
        } catch (RuntimeException failure) {
            reportFailure("handle a Client Options release", failure);
        }
    }

    public static void configKeyTyped(Object screen, char typed, int keyCode) {
        try {
            ClientConfigUi.keyTyped(screen, typed, keyCode);
        } catch (RuntimeException failure) {
            reportFailure("handle Client Options keyboard input", failure);
        }
    }

    private static void openClientOptions(Object minecraft, Object parentScreen)
            throws ReflectiveOperationException {
        ClassLoader gameLoader = minecraft.getClass().getClassLoader();
        if (gameLoader == null) {
            throw new IllegalStateException("Minecraft was loaded without a game class loader");
        }

        Class<?> guiScreen = Class.forName("axu", false, gameLoader);
        Class<?> factory = Class.forName(SCREEN_FACTORY);
        Method create = factory.getMethod("create", ClassLoader.class, Object.class);
        Object screen = create.invoke(null, gameLoader, parentScreen);
        Method displayGuiScreen = minecraft.getClass().getMethod("a", guiScreen);
        displayGuiScreen.invoke(minecraft, screen);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Object readField(Object subject, String name) throws ReflectiveOperationException {
        return findField(subject.getClass(), name).get(subject);
    }

    private static int readIntField(Object subject, String name) throws ReflectiveOperationException {
        return findField(subject.getClass(), name).getInt(subject);
    }

    private static void reportFailure(String action, Throwable failure) {
        if (FAILURE_REPORTED.compareAndSet(false, true)) {
            System.err.println(
                    "[OPUS/UI] could not " + action + ": " + failure.getClass().getSimpleName());
        }
    }

}
