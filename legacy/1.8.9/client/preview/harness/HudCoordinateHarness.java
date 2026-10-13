package org.polydevs.opusmc.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.LanguageManager;
import net.minecraft.client.resources.data.IMetadataSerializer;
import net.minecraft.client.settings.GameSettings;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.Display;
import org.polydevs.opusmc.client.hud.HudRenderContext;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.ui.UiRoute;
import org.polydevs.opusmc.client.ui.render.MinecraftUiScale;

/** Real viewport producers and widgets; only the display/GL output is isolated. */
public final class HudCoordinateHarness {
    public static void main(String[] args) throws Exception {
        Field unsafeField = field(sun.misc.Unsafe.class, "theUnsafe");
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        minecraft.gameSettings = new GameSettings();
        field(Minecraft.class, "mcLanguageManager").set(minecraft,
                new LanguageManager(new IMetadataSerializer(), "en_US"));
        minecraft.theWorld = (net.minecraft.client.multiplayer.WorldClient)
                unsafe.allocateInstance(net.minecraft.client.multiplayer.WorldClient.class);
        Field singleton = field(Minecraft.class, "theMinecraft");
        Object oldMinecraft = singleton.get(null);
        singleton.set(null, minecraft);
        Path directory = Files.createTempDirectory("opus-hud-coordinates-");
        Path settings = directory.resolve("settings.json");
        String oldSettings = System.getProperty("opus.utility.settings.file");
        System.setProperty("opus.utility.settings.file", settings.toString());
        Files.write(settings, ("{\"schemaVersion\":1,\"utilities\":{"
                + "\"fps\":{\"enabled\":true,\"anchor\":\"top-left\",\"offset\":\"152 · 145\",\"scale\":119,\"opacity\":100},"
                + "\"keystrokes\":{\"enabled\":true,\"anchor\":\"top-left\",\"offset\":\"1259 · 285\",\"scale\":150,\"opacity\":100,"
                + "\"showJump\":true,\"showMouseButtons\":true}}}").getBytes(StandardCharsets.UTF_8));
        try {
            ClientOverlayController controller = new ClientOverlayController(LogManager.getLogger("HudCoordinates"));
            ((UtilitySettingsStore) field(ClientOverlayController.class, "settingsStore").get(controller)).reload();
            OpusClientScreen screen = new OpusClientScreen(controller, UiRoute.hudEditor());
            Method refresh = OpusClientScreen.class.getDeclaredMethod("refreshViewport", int.class, int.class, float.class, boolean.class);
            refresh.setAccessible(true);
            FpsModule fps = (FpsModule) field(ClientOverlayController.class, "fpsModule").get(controller);
            KeystrokesModule keys = (KeystrokesModule) field(ClientOverlayController.class, "keystrokesModule").get(controller);
            for (int deviceScale : new int[] {1, 2}) {
                try (DisplayMetrics display = new DisplayMetrics(unsafe, deviceScale)) {
                    minecraft.displayWidth = 1920 * deviceScale;
                    minecraft.displayHeight = 1080 * deviceScale;
                    for (int guiScale : new int[] {3, 1, 2, 4, 0}) {
                        minecraft.gameSettings.guiScale = guiScale;
                        refresh.invoke(screen, minecraft.displayWidth, minecraft.displayHeight, (float) deviceScale, true);
                        UiBounds editor = (UiBounds) field(OpusClientScreen.class, "productViewport").get(screen);
                        UiBounds gameplay = MinecraftUiScale.viewport(minecraft);
                        require(gameplay.width == 1920 && gameplay.height == 1080,
                                "Gameplay viewport differs from the 1920x1080 editor at GUI scale " + guiScale
                                        + ": " + gameplay.width + "x" + gameplay.height);
                        require(editor.width == gameplay.width && editor.height == gameplay.height,
                                "Editor and gameplay use different coordinate units");
                        for (PerformanceOverlaySettings.Anchor anchor : PerformanceOverlaySettings.Anchor.values()) {
                            fps.update(fps.settings().withAnchor(anchor));
                            keys.update(keys.settings().withAnchor(anchor));
                            Drawing normal = new Drawing();
                            controller.renderGameplayHud(minecraft, normal.renderer, gameplay);
                            Drawing editing = new Drawing();
                            editing.renderer.beginFrame(editor);
                            controller.renderHudEditor(editing.renderer, editor, new UiInput(-1, -1, 0));
                            editing.renderer.endFrame();
                            require(normal.text.equals(editing.text), "HUD text position/size differs at " + anchor);
                            require(normal.cells.equals(editing.cells), "Keystrokes geometry differs at " + anchor);
                            require(normal.text.stream().anyMatch(value -> value.startsWith("W:")),
                                    "Enabled Keystrokes disappeared from gameplay");
                            HudRenderContext context = new HudRenderContext(minecraft, normal.renderer, gameplay);
                            UiBounds keyBounds = keys.widget().bounds(context);
                            require(keyBounds.x >= 0 && keyBounds.y >= 0 && keyBounds.right() <= 1920
                                            && keyBounds.bottom() <= 1080,
                                    "Saved Keystrokes position is outside gameplay at " + anchor);
                            require(normal.frames == 1 && normal.ends == 1, "Gameplay frame boundary is unbalanced");
                            require(Math.abs(MinecraftUiScale.matrixScaleX(minecraft, gameplay)
                                            - MinecraftUiScale.matrixScaleX(minecraft, editor)) < 0.00001F,
                                    "Editor and gameplay apply different GL scaling");
                        }
                    }
                }
            }
            System.out.println("PASS: saved FPS/Keystrokes match editor/gameplay at four anchors, GUI scales 1/2/3/4/Auto and device scales 1/2");
        } finally {
            singleton.set(null, oldMinecraft);
            if (oldSettings == null) System.clearProperty("opus.utility.settings.file");
            else System.setProperty("opus.utility.settings.file", oldSettings);
            Files.deleteIfExists(settings);
            Files.deleteIfExists(directory);
        }
    }

    private static final class Drawing {
        final List<String> text = new ArrayList<String>();
        final List<String> cells = new ArrayList<String>();
        int frames, ends;
        final UiRenderer renderer = (UiRenderer) Proxy.newProxyInstance(UiRenderer.class.getClassLoader(),
                new Class<?>[] {UiRenderer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("beginFrame")) frames++;
                    if (method.getName().equals("endFrame")) ends++;
                    if (method.getName().equals("text")) text.add(args[0] + ":" + args[1] + ":" + args[2]);
                    if (method.getName().equals("roundedRect")) {
                        UiBounds bounds = (UiBounds) args[0];
                        cells.add(bounds.x + ":" + bounds.y + ":" + bounds.width + ":" + bounds.height);
                    }
                    if (method.getName().equals("measureText")) return ((String) args[0]).length() * 6;
                    if (method.getName().equals("lineHeight")) return 9;
                    return null;
                });
    }

    private static final class DisplayMetrics implements AutoCloseable {
        final sun.misc.Unsafe unsafe;
        final Field implementation = field(Display.class, "display_impl");
        final Field created = field(Display.class, "window_created");
        final Object oldImplementation, oldCreated;

        DisplayMetrics(sun.misc.Unsafe unsafe, float scale) throws Exception {
            this.unsafe = unsafe;
            oldImplementation = implementation.get(null);
            oldCreated = created.get(null);
            Class<?> type = implementation.getType();
            Object isolated = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getPixelScaleFactor")) return scale;
                        if (method.getName().equals("isActive")) return false;
                        throw new AssertionError("Unexpected native display call: " + method.getName());
                    });
            unsafe.putObject(unsafe.staticFieldBase(implementation), unsafe.staticFieldOffset(implementation), isolated);
            created.set(null, true);
        }

        @Override public void close() throws Exception {
            created.set(null, oldCreated);
            unsafe.putObject(unsafe.staticFieldBase(implementation), unsafe.staticFieldOffset(implementation), oldImplementation);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
