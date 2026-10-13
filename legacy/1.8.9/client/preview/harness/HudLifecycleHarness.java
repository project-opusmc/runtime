package org.polydevs.opusmc.client;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.storage.WorldInfo;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.input.Keyboard;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiRenderer;

/** Exercises the gameplay HUD path and Right Shift edge contract without GL or a live client. */
public final class HudLifecycleHarness {
    public static void main(String[] args) throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        Field singleton = Minecraft.class.getDeclaredField("theMinecraft");
        singleton.setAccessible(true);
        Object previousMinecraft = singleton.get(null);
        singleton.set(null, minecraft);

        Path settingsFile = Files.createTempDirectory("opus-hud-lifecycle-test-").resolve("settings.json");
        String previousSettings = System.getProperty("opus.utility.settings.file");
        System.setProperty("opus.utility.settings.file", settingsFile.toString());
        Files.write(settingsFile, ("{\"schemaVersion\":1,\"utilities\":{"
                + "\"fps\":{\"enabled\":true,\"anchor\":\"top-left\","
                + "\"offset\":\"20 · 30\",\"scale\":100,\"opacity\":100}}}")
                .getBytes(StandardCharsets.UTF_8));

        Field debug = Minecraft.class.getDeclaredField("debugFPS");
        debug.setAccessible(true);
        int previousFps = debug.getInt(null);
        debug.setInt(null, 173);
        try {
            ClientOverlayController controller = new ClientOverlayController(
                    LogManager.getLogger("HudLifecycleHarness"));
            UtilitySettingsStore store = (UtilitySettingsStore) field(
                    ClientOverlayController.class, controller, "settingsStore");
            store.reload();
            Drawing drawing = new Drawing();
            UiBounds viewport = new UiBounds(0, 0, 900, 600);
            minecraft.theWorld = (net.minecraft.client.multiplayer.WorldClient)
                    unsafe.allocateInstance(net.minecraft.client.multiplayer.WorldClient.class);
            minecraft.currentScreen = null;

            controller.renderGameplayHud(minecraft, drawing.renderer, viewport);
            require(drawing.text.contains("FPS 173"),
                    "Enabled FPS widget did not render through the normal gameplay path");
            require(drawing.beginFrames == 1 && drawing.endFrames == 1,
                    "Gameplay HUD renderer boundary was not balanced");

            drawing.clear();
            minecraft.theWorld = null;
            controller.renderGameplayHud(minecraft, drawing.renderer, viewport);
            require(drawing.text.isEmpty(), "Gameplay HUD rendered without a world");

            minecraft.theWorld = (net.minecraft.client.multiplayer.WorldClient)
                    unsafe.allocateInstance(net.minecraft.client.multiplayer.WorldClient.class);
            controller.updatePerformanceOverlay(controller.performanceOverlay().withEnabled(false));
            drawing.clear();
            controller.renderGameplayHud(minecraft, drawing.renderer, viewport);
            require(drawing.text.isEmpty(), "Disabled FPS widget remained in the gameplay HUD");

            KeyBinding.unPressAllKeys();
            int key = Keyboard.KEY_RSHIFT;
            KeyBinding.setKeyBindState(key, true);
            KeyBinding.onTick(key);
            require(controller.consumeOpenOptionsPress(), "First Right Shift press was not accepted");
            KeyBinding.onTick(key);
            require(!controller.consumeOpenOptionsPress(),
                    "Held Right Shift repeat toggled the product route a second time");
            KeyBinding.setKeyBindState(key, false);
            require(!controller.consumeOpenOptionsPress(), "Right Shift release was treated as a press");
            KeyBinding.setKeyBindState(key, true);
            KeyBinding.onTick(key);
            require(controller.consumeOpenOptionsPress(), "A distinct Right Shift press was ignored");
            KeyBinding.setKeyBindState(key, false);
            KeyBinding.unPressAllKeys();
            System.out.println("PASS: gameplay HUD survives editor lifecycle, world guard/disable, and one-press Right Shift debounce");
        } finally {
            debug.setInt(null, previousFps);
            KeyBinding.unPressAllKeys();
            singleton.set(null, previousMinecraft);
            if (previousSettings == null) System.clearProperty("opus.utility.settings.file");
            else System.setProperty("opus.utility.settings.file", previousSettings);
            Files.deleteIfExists(settingsFile);
            Files.deleteIfExists(settingsFile.getParent());
        }
    }

    private static Object field(Class<?> type, Object target, String name) throws Exception {
        Field value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value.get(target);
    }

    private static final class Drawing {
        final List<String> text = new ArrayList<String>();
        int beginFrames;
        int endFrames;
        final UiRenderer renderer = (UiRenderer) Proxy.newProxyInstance(
                UiRenderer.class.getClassLoader(), new Class<?>[] {UiRenderer.class},
                (proxy, method, args) -> {
                    if ("beginFrame".equals(method.getName())) beginFrames++;
                    if ("endFrame".equals(method.getName())) endFrames++;
                    if ("text".equals(method.getName())) text.add((String) args[0]);
                    if ("measureText".equals(method.getName())) return ((String) args[0]).length() * 6;
                    if ("lineHeight".equals(method.getName())) return 9;
                    return null;
                });

        void clear() {
            text.clear();
            beginFrames = 0;
            endFrames = 0;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
