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
import org.polydevs.opusmc.client.hud.HudRenderContext;
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
            require(drawing.text.contains("FPS 173"), "Editor fabricated or cached an FPS value");
            require(!drawing.borders.isEmpty(), "Enabled HUD outline is invisible until hover");
            require(hud.editorOwnsPoint(30, 35), "Actual FPS widget cannot be selected");
            require(!hud.editorOwnsPoint(450, 300), "Empty game canvas steals central CEF input");
            java.lang.reflect.Method inspectorCommand = HudManager.class.getMethod("editorCommand", String.class, String.class, int.class, int.class);
            require(Boolean.TRUE.equals(inspectorCommand.invoke(hud, "select", "fps", 0, 0)), "Inspector cannot select the real widget");
            require(Boolean.TRUE.equals(inspectorCommand.invoke(hud, "position", "fps", 110, 95)), "Inspector cannot commit logical pixel position");
            store.reload();
            UiBounds inspectorFrame = fps.widget().bounds(new HudRenderContext(null,drawing.renderer,viewport));
            require(inspectorFrame.x==110 && inspectorFrame.y==95, "Inspector fields use different units from native frame");
            require(Boolean.TRUE.equals(inspectorCommand.invoke(hud, "scale", "fps", 175, 0)), "Inspector cannot scale the native widget");
            store.reload();
            require(fps.settings().scale()==175, "Inspector scale was not persisted");
            require(Boolean.TRUE.equals(inspectorCommand.invoke(hud, "align-right", "fps", 0, 0)), "Inspector alignment failed");
            inspectorFrame = fps.widget().bounds(new HudRenderContext(null,drawing.renderer,viewport));
            require(inspectorFrame.right()==viewport.right(), "Inspector alignment did not use real rendered bounds");
            require(hud.undo() && hud.undo() && hud.undo(), "Inspector edits are missing from the shared undo stack");
            store.reload();
            require(fps.settings().scale()==100 && fps.settings().offsetX()==20 && fps.settings().offsetY()==30,
                    "Inspector undo lost original native layout");
            String inspectorSnapshot = (String) HudManager.class.getMethod("editorStateJson").invoke(hud);
            require(inspectorSnapshot.contains("\"id\":\"fps\"") && inspectorSnapshot.contains("\"canvasWidth\":900"),
                    "Inspector snapshot omits native selection or viewport");
            System.out.println("PASS native inspector commits position, proportional scale, alignment and shared undo without changing units");
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

            hud.setEditing(false);
            render(hud, drawing, viewport, -1, -1);
            require(!hud.editorOwnsPoint(65, 53), "Game view captures native editing input");
            hud.setEditing(true);
            require(hud.undo(), "Committed drag cannot be undone");
            store.reload();
            require(store.performanceOverlay().offsetX() == 20 && store.performanceOverlay().offsetY() == 30,
                    "Undo does not restore the preceding persisted frame");
            render(hud, drawing, viewport, 30, 35);
            hud.editorMouseDown(30, 35, 0);
            hud.editorMouseDrag(viewport, 70, 55, 0);
            hud.editorMouseUp(0);

            render(hud, drawing, viewport, 65, 53);
            UiBounds outline = drawing.borders.get(0);
            require(drawing.settingsControl != null, "Settings action is missing outside the frame");
            HudManager.EditorAction gear = hud.editorMouseDown(drawing.settingsControl.x + 7, drawing.settingsControl.y + 7, 0);
            require(gear != null && "fps".equals(gear.settingsModuleId()), "Gear opened the wrong module");
            require(!hud.hasPointerCapture(), "Settings gear left a native mouse capture");
            hud.editorMouseDown(outline.x - 1, outline.y - 1, 0);
            hud.editorMouseDrag(viewport, outline.x - 21, outline.y - 21, 0);
            require(store.performanceOverlay().scale() > 100, "Dragging the corner outward did not enlarge the real widget");
            require(hud.editorMouseUp(0), "Resized scale did not persist");
            store.reload();
            require(store.performanceOverlay().scale() > 100, "Scale was lost after settings reload");
            render(hud, drawing, viewport, 65, 53);
            outline = drawing.borders.get(0);
            int grownScale = store.performanceOverlay().scale();
            hud.editorMouseDown(outline.x - 1, outline.y - 1, 0);
            hud.editorMouseDrag(viewport, outline.x + 15, outline.y + 15, 0);
            require(store.performanceOverlay().scale() < grownScale,
                    "Dragging the corner inward did not shrink the allocated frame");
            require(hud.editorMouseUp(0), "Inward resize did not persist");
            store.reload();
            require(store.performanceOverlay().scale() < grownScale,
                    "Inward resize scale was lost after settings reload");
            render(hud, drawing, viewport, 65, 53);
            outline = drawing.borders.get(0);
            hud.editorMouseDown(outline.x - 1, outline.y - 1, 0);
            hud.editorMouseDrag(viewport, outline.x - 19, outline.y - 19, 0);
            hud.cancelPointerCapture();
            require(store.performanceOverlay().scale() < grownScale,
                    "Cancel did not restore the last committed frame scale");
            verifyMovePersistsAtEveryAnchor(hud, fps, store, drawing);
            verifyMixedAxisResize(hud, fps, drawing);
            verifyResizeAtScaleLimit(hud, fps, store, drawing);
            verifyResizeStaysInViewport(hud, fps, store, drawing);
            render(hud, drawing, viewport, 65, 53);
            hud.editorMouseDown(65, 53, 0);
            hud.editorMouseUp(0);
            render(hud, drawing, viewport, 65, 53);
            outline = drawing.borders.get(0);
            int savedOffsetX = store.performanceOverlay().offsetX();
            require(drawing.closeControl != null, "Hide action is missing outside the frame");
            hud.editorMouseDown(drawing.closeControl.x + 7, drawing.closeControl.y + 7, 0);
            store.reload();
            require(!store.performanceOverlay().enabled() && store.performanceOverlay().offsetX() == savedOffsetX,
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

    private static void verifyMovePersistsAtEveryAnchor(HudManager hud, FpsModule fps,
            UtilitySettingsStore store, Drawing drawing) {
        PerformanceOverlaySettings previous = fps.settings();
        UiBounds viewport = new UiBounds(0, 0, 900, 600);
        try {
            for (PerformanceOverlaySettings.Anchor anchor : PerformanceOverlaySettings.Anchor.values()) {
                fps.update(previous.withAnchor(anchor).withScale(100).withOffset(40, 50));
                UiBounds frame = fps.widget().bounds(new HudRenderContext(null, drawing.renderer, viewport));
                int x = frame.x + 25, y = frame.y + 4;
                render(hud, drawing, viewport, x, y);
                require(hud.editorMouseDown(x, y, 0).handled(), "Anchor drag did not capture");
                hud.editorMouseDrag(viewport, x + 10, y + 15, 0);
                hud.editorMouseDrag(viewport, x + 30, y + 25, 0);
                require(hud.editorMouseUp(0), "Anchor drag did not persist on release");
                store.reload();
                boolean right = anchor == PerformanceOverlaySettings.Anchor.TOP_RIGHT
                        || anchor == PerformanceOverlaySettings.Anchor.BOTTOM_RIGHT;
                boolean bottom = anchor == PerformanceOverlaySettings.Anchor.BOTTOM_LEFT
                        || anchor == PerformanceOverlaySettings.Anchor.BOTTOM_RIGHT;
                require(fps.settings().offsetX() == (right ? 10 : 70)
                                && fps.settings().offsetY() == (bottom ? 25 : 75),
                        "Multiple drag events saved the wrong offsets at " + anchor);
                UiBounds moved = fps.widget().bounds(new HudRenderContext(null, drawing.renderer, viewport));
                require(moved.x == frame.x + 30 && moved.y == frame.y + 25,
                        "Reload changed the rendered drag position at " + anchor);
                hud.cancelPointerCapture();
            }
        } finally {
            hud.cancelPointerCapture();
            fps.update(previous);
        }
    }

    private static void verifyResizeStaysInViewport(HudManager hud, FpsModule fps,
            UtilitySettingsStore store, Drawing drawing) {
        PerformanceOverlaySettings previous = fps.settings();
        UiBounds viewport = new UiBounds(0, 0, 427, 240);
        try {
            for (PerformanceOverlaySettings.Anchor anchor : PerformanceOverlaySettings.Anchor.values()) {
                fps.update(previous.withAnchor(anchor).withScale(100).withOffset(370, 228));
                UiBounds frame = fps.widget().bounds(new HudRenderContext(null, drawing.renderer, viewport));
                render(hud, drawing, viewport, frame.x + 2, frame.y + 2);
                UiBounds handle = drawing.handles.get(0);
                hud.editorMouseDown(handle.x + 4, handle.y + 4, 0);
                hud.editorMouseDrag(viewport, handle.x - 96, handle.y - 96, 0);
                require(fps.settings().scale() > 100, "Edge resize did not enlarge the widget");
                UiBounds resized = fps.widget().bounds(new HudRenderContext(null, drawing.renderer, viewport));
                require(resized.x >= 0 && resized.y >= 0 && resized.right() <= viewport.width
                                && resized.bottom() <= viewport.height,
                        "HUD resize pushed the widget outside the game viewport at " + anchor);
                hud.cancelPointerCapture();
                require(fps.settings().offsetX() == 370 && fps.settings().offsetY() == 228
                                && fps.settings().scale() == 100,
                        "Cancel did not restore both the edge offset and scale");
                render(hud, drawing, viewport, frame.x + 2, frame.y + 2);
                handle = drawing.handles.get(0);
                hud.editorMouseDown(handle.x + 4, handle.y + 4, 0);
                hud.editorMouseDrag(viewport, handle.x - 16, handle.y - 16, 0);
                PerformanceOverlaySettings preview = fps.settings();
                require(hud.editorMouseUp(0), "Edge resize did not commit on release");
                store.reload();
                require(fps.settings().scale() == preview.scale()
                                && fps.settings().offsetX() == preview.offsetX()
                                && fps.settings().offsetY() == preview.offsetY(),
                        "Reload lost the clamped resize geometry at " + anchor);
                hud.cancelPointerCapture();
            }
        } finally {
            hud.cancelPointerCapture();
            fps.update(previous);
        }
    }

    private static void verifyMixedAxisResize(HudManager hud, FpsModule fps, Drawing drawing) {
        PerformanceOverlaySettings previous = fps.settings();
        UiBounds viewport = new UiBounds(0, 0, 900, 600);
        try {
            for (int[] delta : new int[][] {{80, -1}, {-1, 80}, {-20, 1}, {1, -20}}) {
                fps.update(previous.withAnchor(PerformanceOverlaySettings.Anchor.TOP_LEFT)
                        .withScale(100).withOffset(100, 100));
                render(hud, drawing, viewport, 110, 104);
                UiBounds outline = drawing.borders.get(0);
                int x = outline.x - 1, y = outline.y - 1;
                hud.editorMouseDown(x, y, 0);
                hud.editorMouseDrag(viewport, x + delta[0], y + delta[1], 0);
                require(delta[0] == 80 || delta[1] == 80
                                ? fps.settings().scale() < 100 : fps.settings().scale() > 100,
                        "Minor opposite-axis jitter reversed the corner resize direction");
                hud.cancelPointerCapture();
            }
        } finally {
            hud.cancelPointerCapture();
            fps.update(previous);
        }
    }

    private static void verifyResizeAtScaleLimit(HudManager hud, FpsModule fps,
            UtilitySettingsStore store, Drawing drawing) {
        PerformanceOverlaySettings previous = fps.settings();
        UiBounds viewport = new UiBounds(0, 0, 427, 240);
        try {
            for (PerformanceOverlaySettings.Anchor anchor : PerformanceOverlaySettings.Anchor.values()) {
                fps.update(previous.withAnchor(anchor).withScale(400).withOffset(370, 228));
                UiBounds frame = fps.widget().bounds(new HudRenderContext(null, drawing.renderer, viewport));
                render(hud, drawing, viewport, Math.max(0, Math.min(viewport.width - 1, frame.x + 10)),
                        Math.max(0, Math.min(viewport.height - 1, frame.y + 4)));
                require(!drawing.handles.isEmpty(), "Retained layout has no reachable corner handle");
                UiBounds handle = drawing.handles.get(0);
                hud.editorMouseDown(handle.x + 4, handle.y + 4, 0);
                hud.editorMouseDrag(viewport, handle.x - 16, handle.y - 16, 0);
                UiBounds resized = fps.widget().bounds(new HudRenderContext(null, drawing.renderer, viewport));
                require(fps.settings().scale() == 400 && resized.x >= 0 && resized.y >= 0
                                && resized.right() <= viewport.width && resized.bottom() <= viewport.height,
                        "Unchanged maximum scale skipped the viewport offset repair at " + anchor);
                require(hud.editorMouseUp(0), "Maximum-scale offset repair did not persist");
                store.reload();
                UiBounds saved = fps.widget().bounds(new HudRenderContext(null, drawing.renderer, viewport));
                require(saved.x == resized.x && saved.y == resized.y,
                        "Maximum-scale offset repair was lost on reload");
                hud.cancelPointerCapture();
            }
        } finally {
            hud.cancelPointerCapture();
            fps.update(previous);
        }
    }

    private static void render(HudManager hud, Drawing drawing, UiBounds viewport, int x, int y) {
        drawing.borders.clear();
        drawing.text.clear();
        drawing.settingsControl = null;
        drawing.closeControl = null;
        drawing.handles.clear();
        hud.renderEditor(null, drawing.renderer, viewport, new UiInput(x, y, 0.0F));
    }

    private static final class Drawing {
        final List<String> text = new ArrayList<String>();
        final List<UiBounds> borders = new ArrayList<UiBounds>();
        final List<UiBounds> handles = new ArrayList<UiBounds>();
        UiBounds settingsControl;
        UiBounds closeControl;
        final UiRenderer renderer = (UiRenderer) Proxy.newProxyInstance(UiRenderer.class.getClassLoader(),
                new Class<?>[] {UiRenderer.class}, (proxy, method, args) -> {
                    if ("text".equals(method.getName())) text.add((String) args[0]);
                    if ("border".equals(method.getName())) borders.add((UiBounds) args[0]);
                    if ("roundedRect".equals(method.getName())) {
                        UiBounds bounds = (UiBounds) args[0];
                        if (bounds.width == 14 && ((Integer) args[2]) == 0xE513141A) settingsControl = bounds;
                        if (bounds.width == 8 && ((Integer) args[2]) == 0xE513141A) handles.add(bounds);
                        if (((Integer) args[2]) == 0xD9261A1D) closeControl = bounds;
                    }
                    if ("measureText".equals(method.getName())) return ((String) args[0]).length() * 6;
                    if ("lineHeight".equals(method.getName())) return 9;
                    return null;
                });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
