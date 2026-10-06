package org.polydevs.opusmc.client;

import java.util.Locale;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/** Reads assigned Minecraft bindings without manufacturing input state. */
final class KeystrokesInputReader {
    interface Devices {
        boolean windowActive();

        boolean keyboardCreated();

        boolean mouseCreated();

        boolean keyboardDown(int keyCode);

        boolean mouseDown(int button);
    }

    static final class KeyState {
        private final String label;
        private final boolean pressed;

        private KeyState(String label, boolean pressed) {
            this.label = label;
            this.pressed = pressed;
        }

        String label() {
            return label;
        }

        boolean pressed() {
            return pressed;
        }
    }

    static final class Snapshot {
        private static final KeyState MISSING = new KeyState("", false);
        private static final Snapshot UNAVAILABLE = new Snapshot(
                false, MISSING, MISSING, MISSING, MISSING, MISSING, MISSING, MISSING);

        private final boolean available;
        private final KeyState forward;
        private final KeyState left;
        private final KeyState back;
        private final KeyState right;
        private final KeyState attack;
        private final KeyState use;
        private final KeyState jump;

        private Snapshot(
                boolean available,
                KeyState forward,
                KeyState left,
                KeyState back,
                KeyState right,
                KeyState attack,
                KeyState use,
                KeyState jump) {
            this.available = available;
            this.forward = forward;
            this.left = left;
            this.back = back;
            this.right = right;
            this.attack = attack;
            this.use = use;
            this.jump = jump;
        }

        boolean available() {
            return available;
        }

        KeyState forward() {
            return forward;
        }

        KeyState left() {
            return left;
        }

        KeyState back() {
            return back;
        }

        KeyState right() {
            return right;
        }

        KeyState attack() {
            return attack;
        }

        KeyState use() {
            return use;
        }

        KeyState jump() {
            return jump;
        }
    }

    private final Devices devices;

    KeystrokesInputReader() {
        this(new LwjglDevices());
    }

    KeystrokesInputReader(Devices devices) {
        if (devices == null) {
            throw new IllegalArgumentException("devices");
        }
        this.devices = devices;
    }

    Snapshot read(GameSettings settings) {
        if (!hasRequiredBindings(settings)) {
            return Snapshot.UNAVAILABLE;
        }

        boolean active = safeWindowActive();
        boolean keyboardCreated = active && safeKeyboardCreated();
        boolean mouseCreated = active && safeMouseCreated();
        return new Snapshot(
                true,
                read(settings.keyBindForward, active, keyboardCreated, mouseCreated),
                read(settings.keyBindLeft, active, keyboardCreated, mouseCreated),
                read(settings.keyBindBack, active, keyboardCreated, mouseCreated),
                read(settings.keyBindRight, active, keyboardCreated, mouseCreated),
                read(settings.keyBindAttack, active, keyboardCreated, mouseCreated),
                read(settings.keyBindUseItem, active, keyboardCreated, mouseCreated),
                read(settings.keyBindJump, active, keyboardCreated, mouseCreated));
    }

    private KeyState read(
            KeyBinding binding,
            boolean active,
            boolean keyboardCreated,
            boolean mouseCreated) {
        int keyCode = binding.getKeyCode();
        return new KeyState(
                labelFor(keyCode),
                isPressed(keyCode, active, keyboardCreated, mouseCreated));
    }

    private boolean isPressed(
            int keyCode,
            boolean active,
            boolean keyboardCreated,
            boolean mouseCreated) {
        if (!active || keyCode == Keyboard.KEY_NONE) {
            return false;
        }
        if (keyCode < 0) {
            int button = keyCode + 100;
            if (button < 0 || !mouseCreated) {
                return false;
            }
            try {
                return devices.mouseDown(button);
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        if (!keyboardCreated) {
            return false;
        }
        try {
            return devices.keyboardDown(keyCode);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean safeWindowActive() {
        try {
            return devices.windowActive();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean safeKeyboardCreated() {
        try {
            return devices.keyboardCreated();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean safeMouseCreated() {
        try {
            return devices.mouseCreated();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean hasRequiredBindings(GameSettings settings) {
        return settings != null
                && settings.keyBindForward != null
                && settings.keyBindLeft != null
                && settings.keyBindBack != null
                && settings.keyBindRight != null
                && settings.keyBindAttack != null
                && settings.keyBindUseItem != null
                && settings.keyBindJump != null;
    }

    private static String labelFor(int keyCode) {
        if (keyCode < 0) {
            return "M" + (keyCode + 101);
        }
        if (keyCode == Keyboard.KEY_NONE) {
            return "NONE";
        }
        if (keyCode < Keyboard.KEYBOARD_SIZE) {
            String name = Keyboard.getKeyName(keyCode);
            return name == null || name.isEmpty() ? "KEY " + keyCode : name.toUpperCase(Locale.ROOT);
        }
        return String.valueOf((char) (keyCode - Keyboard.KEYBOARD_SIZE)).toUpperCase(Locale.ROOT);
    }

    private static final class LwjglDevices implements Devices {
        @Override
        public boolean windowActive() {
            return Display.isCreated() && Display.isActive();
        }

        @Override
        public boolean keyboardCreated() {
            return Keyboard.isCreated();
        }

        @Override
        public boolean mouseCreated() {
            return Mouse.isCreated();
        }

        @Override
        public boolean keyboardDown(int keyCode) {
            return Keyboard.isKeyDown(keyCode);
        }

        @Override
        public boolean mouseDown(int button) {
            return Mouse.isButtonDown(button);
        }
    }
}
