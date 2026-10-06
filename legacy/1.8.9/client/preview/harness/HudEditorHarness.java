package org.polydevs.opusmc.client;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.polydevs.opusmc.client.hud.HudManager;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiNavigationState;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.ui.UiRoute;

/** Exercises real FPS/editor/persistence code with a drawing boundary, no GL or input devices. */
public final class HudEditorHarness {
    public static void main(String[] args) throws Exception {
        Path settingsFile = Files.createTempDirectory("opus-hud-editor-test-").resolve("settings.json");
        String previous = System.getProperty("opus.utility.settings.file");
        System.setProperty("opus.utility.settings.file", settingsFile.toString());
        Files.write(settingsFile, ("{\"schemaVersion\":1,\"ownerNote\":\"keep\",\"utilities\":{"
                + "\"fps\":{\"enabled\":true,\"anchor\":\"top-left\",\"offset\":\"20 · 30\",\"scale\":100,\"opacity\":100},"
                + "\"unknown-module\":{\"custom\":17}}}").getBytes(StandardCharsets.UTF_8));
        Field debug = Minecraft.class.getDeclaredField("debugFPS");
        debug.setAccessible(true);
        int oldFps = debug.getInt(null);
        debug.setInt(null, 173);
        try {
            UtilitySettingsStore store = UtilitySettingsStore.fromSystemProperty(LogManager.getLogger("HudEditorHarness"));
            store.reload();
            FpsModule fps = new FpsModule(store);
            HudManager hud = new HudManager(Arrays.asList(fps.widget()));
            Drawing drawing = new Drawing();
            UiBounds viewport = new UiBounds(0, 0, 900, 600);
            render(hud, drawing, viewport, -1, -1);
            require(drawing.text.contains("FPS: 173"), "Editor fabricated or cached an FPS value");
            require(!drawing.borders.isEmpty(), "Enabled HUD outline is invisible until hover");
            require(hud.editorOwnsPoint(30, 35), "Actual FPS widget cannot be selected");
            require(!hud.editorOwnsPoint(450, 300), "Empty game canvas steals central CEF input");
            require(hud.editorMouseDown(30, 35, 0).handled(), "FPS body did not capture drag");
            hud.editorMouseDrag(viewport, 60, 55, 0);
            require(store.performanceOverlay().offsetX() == 50 && store.performanceOverlay().offsetY() == 50,
                    "FPS drag used a different geometry/anchor from live HUD");
            require(read(settingsFile).contains("20 · 30"), "Pointer move performed synchronous disk saves");
            hud.cancelPointerCapture();
            require(!hud.hasPointerCapture() && store.performanceOverlay().offsetX() == 20,
                    "Route/focus cancellation retained an unsaved drag");
            render(hud, drawing, viewport, 30, 35);
            hud.editorMouseDown(30, 35, 0);
            hud.editorMouseDrag(viewport, 70, 55, 0);
            require(hud.editorMouseUp(0), "Mouse-up did not commit the HUD position");
            store.reload();
            require(store.performanceOverlay().offsetX() == 60 && store.performanceOverlay().offsetY() == 50,
                    "HUD position did not survive reload");
            require(read(settingsFile).contains("unknown-module") && read(settingsFile).contains("\"custom\": 17")
                    && read(settingsFile).contains("ownerNote"), "HUD edit destroyed other settings");

            render(hud, drawing, viewport, 65, 53);
            UiBounds outline = drawing.borders.get(0);
            HudManager.EditorAction gear = hud.editorMouseDown(outline.x + 6, outline.bottom() - 7, 0);
            require(gear != null && "fps".equals(gear.settingsModuleId()), "Gear opened the wrong module");
            require(!hud.hasPointerCapture(), "Settings gear left a native mouse capture");
            hud.editorMouseDown(outline.x - 1, outline.y - 1, 0);
            hud.editorMouseDrag(viewport, outline.x + 19, outline.y - 1, 0);
            require(store.performanceOverlay().scale() > 100, "Resize handle did not resize the real widget");
            require(hud.editorMouseUp(0), "Resized scale did not persist");
            store.reload();
            require(store.performanceOverlay().scale() > 100, "Scale was lost after settings reload");
            render(hud, drawing, viewport, 65, 53);
            outline = drawing.borders.get(0);
            hud.editorMouseDown(outline.right() - 6, outline.bottom() - 7, 0);
            store.reload();
            require(!store.performanceOverlay().enabled() && store.performanceOverlay().offsetX() == 60,
                    "X deleted the widget preferences instead of only disabling it");
            require(!hud.editorOwnsPoint(65, 53), "Disabled HUD retains an invisible input target");

            UiNavigationState navigation = new UiNavigationState(UiRoute.hudEditor(), UiNavigationState.EntryPoint.HOTKEY);
            navigation.navigate(UiRoute.moduleDetail("fps"));
            require(navigation.entryPoint() == UiNavigationState.EntryPoint.HUD_WIDGET
                            && UiRoute.hudEditor().equals(navigation.back()) && !navigation.canGoBack()
                            && navigation.isRootHotkeyFlow(), "Gear/Back lost editor root/ownership");
            System.out.println("PASS: live FPS, immediate outline, drag/cancel/commit/resize/hide, preference retention, gear history");
        } finally {
            debug.setInt(null, oldFps);
            if (previous == null) System.clearProperty("opus.utility.settings.file");
            else System.setProperty("opus.utility.settings.file", previous);
            Files.deleteIfExists(settingsFile);
            Files.deleteIfExists(settingsFile.getParent());
        }
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void render(HudManager hud, Drawing drawing, UiBounds viewport, int x, int y) {
        drawing.borders.clear();
        drawing.text.clear();
        hud.renderEditor(null, drawing.renderer, viewport, new UiInput(x, y, 0.0F));
    }

    private static final class Drawing {
        final List<String> text = new ArrayList<String>();
        final List<UiBounds> borders = new ArrayList<UiBounds>();
        final UiRenderer renderer = (UiRenderer) Proxy.newProxyInstance(UiRenderer.class.getClassLoader(),
                new Class<?>[] {UiRenderer.class}, (proxy, method, args) -> {
                    if ("text".equals(method.getName())) text.add((String) args[0]);
                    if ("border".equals(method.getName())) borders.add((UiBounds) args[0]);
                    if ("measureText".equals(method.getName())) return ((String) args[0]).length() * 6;
                    if ("lineHeight".equals(method.getName())) return 9;
                    return null;
                });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
