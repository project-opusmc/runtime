package net.minecraftforge.fml.client.registry;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.settings.KeyBinding;

public final class ClientRegistry {
    private ClientRegistry() {
    }

    public static void registerKeyBinding(KeyBinding key) {
        registerKeyBinding((Object) key);
    }

    public static void registerKeyBinding(Object key) {
        if (key == null) {
            return;
        }
        try {
            Class<?> aveClass = null;
            try {
                aveClass = Class.forName("ave");
            } catch (ClassNotFoundException e) {
                aveClass = Class.forName("net.minecraft.client.Minecraft");
            }

            Object mc = null;
            try {
                mc = aveClass.getMethod("A").invoke(null);
            } catch (Exception ignored) {
                mc = aveClass.getMethod("func_71410_x").invoke(null);
            }
            if (mc == null) return;

            Field settingsField = null;
            for (String fName : new String[]{"t", "field_71474_y", "gameSettings"}) {
                try {
                    settingsField = aveClass.getDeclaredField(fName);
                    settingsField.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (settingsField == null) return;
            Object settings = settingsField.get(mc);
            if (settings == null) return;

            Field kbField = null;
            for (String fName : new String[]{"ax", "field_74324_K", "keyBindings"}) {
                try {
                    kbField = settings.getClass().getDeclaredField(fName);
                    kbField.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (kbField == null) return;

            Object existingArray = kbField.get(settings);
            if (existingArray == null) return;

            int len = Array.getLength(existingArray);
            for (int i = 0; i < len; i++) {
                if (Array.get(existingArray, i) == key) {
                    return;
                }
            }

            Object newArray = Array.newInstance(existingArray.getClass().getComponentType(), len + 1);
            System.arraycopy(existingArray, 0, newArray, 0, len);
            Array.set(newArray, len, key);
            kbField.set(settings, newArray);
            System.out.println("[OPUS/STANDALONE] Registered keybinding successfully: " + key);
        } catch (Throwable t) {
            System.err.println("[OPUS/STANDALONE] Failed to register keybinding: " + t);
            t.printStackTrace();
        }
    }
}
