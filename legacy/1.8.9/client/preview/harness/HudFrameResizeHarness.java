package org.polydevs.opusmc.client;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import org.apache.logging.log4j.LogManager;
import org.polydevs.opusmc.client.hud.HudManager;
import org.polydevs.opusmc.client.hud.HudRenderContext;
import org.polydevs.opusmc.client.hud.HudWidget;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.ui.UiRoute;

/** Real widgets, pointer routing and saved layouts; drawing/input devices are isolated. */
public final class HudFrameResizeHarness {
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("opus-frame-resize-");
        Path file = folder.resolve("settings.json");
        String oldSettings = System.getProperty("opus.utility.settings.file");
        System.setProperty("opus.utility.settings.file", file.toString());
        Field debug = Minecraft.class.getDeclaredField("debugFPS");
        debug.setAccessible(true);
        int oldFps = debug.getInt(null);
        debug.setInt(null, 173);
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Minecraft minecraft = (Minecraft) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(Minecraft.class);
        minecraft.gameSettings = new GameSettings();
        Field singleton = Minecraft.class.getDeclaredField("theMinecraft");
        singleton.setAccessible(true);
        Object oldMinecraft = singleton.get(null);
        singleton.set(null, minecraft);
        try {
            UtilitySettingsStore store = UtilitySettingsStore.fromSystemProperty(LogManager.getLogger("HudFrameResize"));
            FpsModule fps = new FpsModule(store);
            KeystrokesModule keys = new KeystrokesModule(new KeystrokesModule.SettingsAccess() {
                public KeystrokesSettings settings() { return store.keystrokes(); }
                public boolean update(KeystrokesSettings next) { return store.updateKeystrokes(next); }
                public void preview(KeystrokesSettings next) { store.previewKeystrokes(next); }
                public boolean persist() { return store.persistKeystrokes(); }
                public void cancelPreview() { store.cancelKeystrokesPreview(); }
            }, new KeystrokesInputReader(new KeystrokesInputReader.Devices() {
                public boolean windowActive() { return false; }
                public boolean keyboardCreated() { throw new AssertionError("Inactive window queried input"); }
                public boolean mouseCreated() { throw new AssertionError("Inactive window queried input"); }
                public boolean keyboardDown(int key) { throw new AssertionError("Physical input queried"); }
                public boolean mouseDown(int button) { throw new AssertionError("Physical input queried"); }
            }));
            HudManager hud = new HudManager(Arrays.asList(fps.widget(), keys.widget()));
            Drawing drawing = new Drawing();
            UiBounds viewport = new UiBounds(0, 0, 900, 600);
            fps.update(fps.settings().withEnabled(true).withScale(100).withOffset(100, 120));
            render(hud, minecraft, drawing, viewport);
            UiBounds outline = drawing.outlines.get(0);
            require(same(outline, new UiBounds(97, 117, 61, 16)),
                    "FPS selection frame has asymmetric empty space: " + describe(outline));
            for (HudWidget widget : Arrays.asList(fps.widget(), keys.widget())) {
                for (PerformanceOverlaySettings.Anchor anchor : PerformanceOverlaySettings.Anchor.values()) {
                    for (int[] corner : new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                        hud.cancelPointerCapture();
                        fps.update(fps.settings().withEnabled(widget == fps.widget()).withScale(100).withAnchor(anchor).withOffset(100, 120));
                        keys.update(keys.settings().withEnabled(widget == keys.widget()).withScale(100).withAnchor(anchor).withOffset(100, 120));
                        render(hud, minecraft, drawing, viewport);
                        HudRenderContext context = new HudRenderContext(minecraft, drawing.renderer, viewport);
                        UiBounds start = widget.bounds(context);
                        UiBounds frame = drawing.outlines.get(0);
                        require(same(frame, start.inset(-3)), "Frame does not surround actual content symmetrically");
                        int x = corner[0] < 0 ? frame.x : frame.right();
                        int y = corner[1] < 0 ? frame.y : frame.bottom();
                        HudManager.EditorAction down = hud.editorMouseDown(x, y, 0);
                        require(down != null && hud.hasPointerCapture(), "Corner failed to capture " + widget.moduleId());
                        byte[] persisted = Files.readAllBytes(file);
                        hud.editorMouseDrag(viewport, x + corner[0] * start.width / 4, y + corner[1] * start.height / 4, 0);
                        // No intervening render: pointer events must use current geometry.
                        hud.editorMouseDrag(viewport, x + corner[0] * start.width, y + corner[1] * start.height, 0);
                        UiBounds grown = widget.bounds(context);
                        require(widget.scale() == 200, "Corner did not scale content uniformly to 200%: " + widget.moduleId());
                        require(grown.width == Math.round(start.width * 2.0F) && grown.height == Math.round(start.height * 2.0F),
                                "Frame and content use different resize ratios");
                        require((corner[0] < 0 ? grown.right() == start.right() : grown.x == start.x)
                                        && (corner[1] < 0 ? grown.bottom() == start.bottom() : grown.y == start.y),
                                "Resize moved the opposite corner: " + widget.moduleId() + " " + anchor
                                        + " " + Arrays.toString(corner) + " " + describe(start) + " -> " + describe(grown));
                        require(Arrays.equals(persisted, Files.readAllBytes(file)), "Drag saved synchronously before release");
                        render(hud, minecraft, drawing, viewport);
                        require(same(drawing.outlines.get(0), grown.inset(-3)), "Outline lagged behind the resized contents");
                        require(Math.abs(drawing.contentScale - 2.0F) < 0.0001F, "Content did not grow with its frame");
                        require(hud.editorMouseUp(0), "Resize was not saved on release");
                        store.reload();
                        require(same(widget.bounds(context), grown), "Saved resize changed after reload");
                        hud.cancelPointerCapture();
                        render(hud, minecraft, drawing, viewport);
                        frame = drawing.outlines.get(0);
                        x = corner[0] < 0 ? frame.x : frame.right();
                        y = corner[1] < 0 ? frame.y : frame.bottom();
                        hud.editorMouseDown(x, y, 0);
                        hud.editorMouseDrag(viewport, x - corner[0] * start.width / 4, y - corner[1] * start.height / 4, 0);
                        require(widget.scale() < 200, "Inward corner movement did not shrink content");
                        hud.cancelPointerCapture();
                        require(widget.scale() == 200 && same(widget.bounds(context), grown), "Cancel lost position or scale");
                    }
                }
            }
            for (HudWidget widget : Arrays.asList(fps.widget(), keys.widget())) {
                hud.cancelPointerCapture();
                fps.update(fps.settings().withEnabled(widget == fps.widget()).withScale(150)
                        .withAnchor(PerformanceOverlaySettings.Anchor.TOP_LEFT).withOffset(100, 120));
                keys.update(keys.settings().withEnabled(widget == keys.widget()).withScale(150)
                        .withAnchor(PerformanceOverlaySettings.Anchor.TOP_LEFT).withOffset(100, 120));
                render(hud, minecraft, drawing, viewport);
                UiBounds start = widget.bounds(new HudRenderContext(minecraft, drawing.renderer, viewport));
                UiBounds frame = drawing.outlines.get(0);
                hud.editorMouseDown(frame.right(), frame.bottom(), 0);
                hud.editorMouseDrag(viewport, frame.right() + start.width, frame.bottom() + start.height, 0);
                require(widget.scale() == 300, "Saved 150% widget cannot grow beyond the old cap: " + widget.moduleId());
                require(hud.editorMouseUp(0), "Scale above 150% failed to persist");
                store.reload();
                require(widget.scale() == 300, "Reload clipped a larger saved HUD");
            }
            ClientOverlayController controller = new ClientOverlayController(LogManager.getLogger("HudFrameSettingRange"));
            for (String module : Arrays.asList("fps", "keystrokes", "armor-status")) {
                boolean offered = false;
                for (String[] row : controller.moduleSettings(module)) {
                    if (row[0].equals("scale")) {
                        offered = Integer.parseInt(row[4]) <= 300 && Integer.parseInt(row[5]) >= 300;
                    }
                }
                require(offered, "Settings UI cannot offer the larger saved scale: " + module);
            }
            controller.setModuleSetting("keystrokes", "scale", "225");
            store.reload();
            require(keys.settings().scale() == 225, "Settings edit clipped the larger HUD scale");
            verifyCaptureSurvivesVisibility(hud, fps, keys, store, minecraft, drawing, viewport);
            verifyEdgeHandles(hud, fps, keys, minecraft, drawing, viewport);
            verifyActionsRequireSelection(hud, fps, keys, minecraft, drawing, viewport);
            verifyActionsAvoidChrome(hud, fps, keys, controller, minecraft, drawing, viewport);
            verifySmallFrameActionsAvoidHandles(hud, fps, keys, minecraft, drawing, viewport);
            verifyOversizedRetainedFrame(hud, fps, keys, minecraft, drawing);
            exportSamples(hud, fps, keys, minecraft, drawing, viewport);
            System.out.println("PASS: balanced frame; four-corner proportional resize keeps opposite corner; real FPS/Keystrokes content, repeated input, save/reload/cancel at every anchor");
        } finally {
            singleton.set(null, oldMinecraft);
            debug.setInt(null, oldFps);
            if (oldSettings == null) System.clearProperty("opus.utility.settings.file");
            else System.setProperty("opus.utility.settings.file", oldSettings);
            Files.deleteIfExists(file);
            Files.deleteIfExists(folder);
        }
    }

    private static void exportSamples(HudManager hud, FpsModule fps, KeystrokesModule keys,
            Minecraft minecraft, Drawing drawing, UiBounds viewport) throws Exception {
        String destination = System.getenv("OPUS_HUD_FRAME_PREVIEW_DIR");
        if (destination == null) return;
        Path folder = java.nio.file.Paths.get(destination);
        Files.createDirectories(folder);
        hud.updateEditorCanvas(viewport, java.util.Collections.<UiBounds>emptyList());
        for (HudWidget widget : Arrays.asList(fps.widget(), keys.widget())) {
            hud.cancelPointerCapture();
            fps.update(fps.settings().withEnabled(widget == fps.widget()).withScale(100)
                    .withAnchor(PerformanceOverlaySettings.Anchor.TOP_LEFT).withOffset(120, 140));
            keys.update(keys.settings().withEnabled(widget == keys.widget()).withScale(100)
                    .withAnchor(PerformanceOverlaySettings.Anchor.TOP_LEFT).withOffset(120, 140)
                    .withShowJump(true).withShowMouseButtons(true));
            render(hud, minecraft, drawing, viewport);
            UiBounds start = widget.bounds(new HudRenderContext(minecraft, drawing.renderer, viewport));
            hud.editorMouseDown(start.x + start.width / 2, start.y + start.height / 2, 0);
            hud.editorMouseUp(0);
            render(hud, minecraft, drawing, viewport);
            Files.write(folder.resolve(widget.moduleId() + "-100.json"),
                    new com.google.gson.Gson().toJson(drawing.commands).getBytes(StandardCharsets.UTF_8));
            UiBounds frame = drawing.outlines.get(0);
            hud.editorMouseDown(frame.right(), frame.bottom(), 0);
            hud.editorMouseDrag(viewport, frame.right() + start.width, frame.bottom() + start.height, 0);
            render(hud, minecraft, drawing, viewport);
            Files.write(folder.resolve(widget.moduleId() + "-200.json"),
                    new com.google.gson.Gson().toJson(drawing.commands).getBytes(StandardCharsets.UTF_8));
            hud.cancelPointerCapture();
        }
    }

    private static void verifyCaptureSurvivesVisibility(HudManager hud, FpsModule fps, KeystrokesModule keys,
            UtilitySettingsStore store, Minecraft minecraft, Drawing drawing, UiBounds viewport) {
        for (boolean commit : new boolean[] {false, true}) {
            hud.cancelPointerCapture();
            keys.setEnabled(false);
            fps.update(fps.settings().withEnabled(true).withScale(100)
                    .withAnchor(PerformanceOverlaySettings.Anchor.TOP_LEFT).withOffset(100, 120));
            render(hud, minecraft, drawing, viewport);
            hud.editorMouseDown(124, 124, 0);
            hud.editorMouseDrag(viewport, 144, 139, 0);
            fps.preview(fps.settings().withEnabled(false));
            if (commit) {
                require(hud.editorMouseUp(0), "Invisible captured widget skipped its commit");
                store.reload();
                require(!fps.settings().enabled() && fps.settings().offsetX() == 120,
                        "Visibility change lost the completed layout");
            } else {
                hud.cancelPointerCapture();
                require(fps.settings().enabled() && fps.settings().offsetX() == 100,
                        "Invisible captured widget skipped layout rollback");
                require(!hud.hasPointerCapture() && !hud.editorMouseUp(0), "Canceled gesture still owns release");
            }
        }
        hud.cancelPointerCapture();
        fps.update(fps.settings().withEnabled(true).withScale(100)
                .withAnchor(PerformanceOverlaySettings.Anchor.TOP_LEFT).withOffset(100, 120));
        render(hud, minecraft, drawing, viewport);
        hud.editorMouseDown(124, 124, 0);
        hud.editorMouseDrag(viewport, 144, 139, 0);
        fps.preview(fps.settings().withEnabled(false));
        hud.editorMouseDrag(viewport, 164, 149, 0);
        require(!hud.hasPointerCapture() && !hud.editorMouseUp(0),
                "Widget disappearing during pointer movement leaves a stale captured gesture");
        require(fps.settings().enabled() && fps.settings().offsetX() == 100 && fps.settings().offsetY() == 120,
                "Canceled invisible drag did not restore its original layout");
    }

    private static void verifyEdgeHandles(HudManager hud, FpsModule fps, KeystrokesModule keys,
            Minecraft minecraft, Drawing drawing, UiBounds viewport) {
        keys.setEnabled(false);
        for (PerformanceOverlaySettings.Anchor anchor : PerformanceOverlaySettings.Anchor.values()) {
            hud.cancelPointerCapture();
            fps.update(fps.settings().withEnabled(true).withScale(100).withAnchor(anchor).withOffset(0, 0));
            render(hud, minecraft, drawing, viewport);
            UiBounds body = fps.widget().bounds(new HudRenderContext(minecraft, drawing.renderer, viewport));
            hud.editorMouseDown(body.x + body.width / 2, body.y + body.height / 2, 0);
            hud.editorMouseUp(0);
            render(hud, minecraft, drawing, viewport);
            require(drawing.handles.size() == 4, "Selected frame does not expose four corner handles");
            for (UiBounds handle : drawing.handles) {
                require(handle.x >= 0 && handle.y >= 0 && handle.right() <= viewport.width
                                && handle.bottom() <= viewport.height,
                        "A visible corner handle is clipped at " + anchor + ": " + describe(handle));
            }
        }
    }

    private static void verifyActionsRequireSelection(HudManager hud, FpsModule fps, KeystrokesModule keys,
            Minecraft minecraft, Drawing drawing, UiBounds viewport) {
        hud.cancelPointerCapture();
        fps.update(fps.settings().withEnabled(true).withScale(100)
                .withAnchor(PerformanceOverlaySettings.Anchor.TOP_LEFT).withOffset(100, 120));
        keys.update(keys.settings().withEnabled(true).withScale(100)
                .withAnchor(PerformanceOverlaySettings.Anchor.TOP_LEFT).withOffset(108, 76)
                .withShowJump(false).withShowMouseButtons(false));
        drawing.reset();
        hud.renderEditor(minecraft, drawing.renderer, viewport, new UiInput(124, 124, 0));
        require(drawing.settingsControl == null, "Hover exposes a toolbar whose owner can vanish across its gap");
        hud.editorMouseDown(124, 124, 0);
        hud.editorMouseUp(0);
        render(hud, minecraft, drawing, viewport);
        UiBounds control = drawing.settingsControl;
        require(control != null, "Selected widget has no Settings action");
        for (int y = 124; y >= control.y + 7; y--) {
            drawing.reset();
            hud.renderEditor(minecraft, drawing.renderer, viewport, new UiInput(control.x + 7, y, 0));
            require(drawing.settingsControl != null && same(drawing.settingsControl, control),
                    "Selected Settings toolbar vanishes while crossing the pointer gap");
        }
        HudManager.EditorAction action = hud.editorMouseDown(control.x + 7, control.y + 7, 0);
        require(action != null && "fps".equals(action.settingsModuleId()) && !hud.hasPointerCapture(),
                "Another widget beneath the toolbar stole the selected Settings click");
    }

    private static void verifyOversizedRetainedFrame(HudManager hud, FpsModule fps, KeystrokesModule keys,
            Minecraft minecraft, Drawing drawing) {
        hud.cancelPointerCapture();
        fps.setEnabled(false);
        keys.update(keys.settings().withEnabled(true).withScale(400)
                .withAnchor(PerformanceOverlaySettings.Anchor.TOP_LEFT).withOffset(12, 12)
                .withShowJump(true).withShowMouseButtons(true));
        UiBounds viewport = new UiBounds(0, 0, 427, 240);
        hud.updateEditorCanvas(viewport, java.util.Collections.<UiBounds>emptyList());
        drawing.reset();
        hud.renderEditor(minecraft, drawing.renderer, viewport, new UiInput(30, 30, 0));
        UiBounds handle = drawing.handles.get(0);
        hud.editorMouseDown(handle.x + 4, handle.y + 4, 0);
        hud.editorMouseDrag(viewport, handle.x - 16, handle.y - 16, 0);
        UiBounds resized = keys.widget().bounds(new HudRenderContext(minecraft, drawing.renderer, viewport));
        require(resized.x >= 0 && resized.y >= 0 && resized.right() <= 427 && resized.bottom() <= 240,
                "Retained large Keystrokes cannot be resized into the smaller viewport: " + describe(resized));
        hud.cancelPointerCapture();
        require(keys.settings().scale() == 400 && keys.settings().offsetX() == 12 && keys.settings().offsetY() == 12,
                "Cancel did not restore the oversized saved layout");
    }

    private static void verifySmallFrameActionsAvoidHandles(HudManager hud, FpsModule fps, KeystrokesModule keys,
            Minecraft minecraft, Drawing drawing, UiBounds viewport) {
        hud.cancelPointerCapture();
        hud.updateEditorCanvas(viewport, java.util.Collections.<UiBounds>emptyList());
        keys.setEnabled(false);
        fps.update(fps.settings().withEnabled(true).withScale(50)
                .withAnchor(PerformanceOverlaySettings.Anchor.TOP_LEFT).withOffset(100, 20));
        render(hud, minecraft, drawing, viewport);
        UiBounds body = fps.widget().bounds(new HudRenderContext(minecraft, drawing.renderer, viewport));
        hud.editorMouseDown(body.x + body.width / 2, body.y + body.height / 2, 0);
        hud.editorMouseUp(0);
        render(hud, minecraft, drawing, viewport);
        require(drawing.settingsControl != null && drawing.closeControl != null,
                "Minimum-scale FPS lost its selected actions");
        for (UiBounds action : Arrays.asList(drawing.settingsControl, drawing.closeControl)) {
            for (UiBounds handle : drawing.handles) {
                UiBounds intersection = action.intersect(handle.inset(-2));
                require(intersection.width == 0 || intersection.height == 0,
                        "Selected action steals a minimum-scale edge handle: " + describe(action));
            }
        }
    }

    private static void verifyActionsAvoidChrome(HudManager hud, FpsModule fps, KeystrokesModule keys,
            ClientOverlayController controller, Minecraft minecraft, Drawing drawing, UiBounds viewport) throws Exception {
        hud.cancelPointerCapture();
        keys.setEnabled(false);
        fps.update(fps.settings().withEnabled(true).withScale(100)
                .withAnchor(PerformanceOverlaySettings.Anchor.TOP_LEFT).withOffset(100, 120));
        Field manager = ClientOverlayController.class.getDeclaredField("hudManager");
        manager.setAccessible(true);
        manager.set(controller, hud);
        OpusClientScreen screen = new OpusClientScreen(controller, UiRoute.hudEditor());
        Field screenViewport = OpusClientScreen.class.getDeclaredField("productViewport");
        screenViewport.setAccessible(true);
        screenViewport.set(screen, viewport);
        UiBounds hole = new UiBounds(90, 85, 80, 30);
        screen.acceptHudEditorCanvas(controller.currentUiNavigationRevision(), viewport, Arrays.asList(hole));
        render(hud, minecraft, drawing, viewport);
        hud.editorMouseDown(124, 124, 0);
        hud.editorMouseUp(0);
        render(hud, minecraft, drawing, viewport);
        require(drawing.settingsControl != null && drawing.closeControl != null,
                "Accessible widget lost its actions beside CEF chrome");
        for (UiBounds action : Arrays.asList(drawing.settingsControl, drawing.closeControl)) {
            UiBounds overlap = action.intersect(hole);
            require(overlap.width == 0 || overlap.height == 0,
                    "External HUD action lies under CEF chrome: " + describe(action));
        }
        UiBounds control = drawing.settingsControl;
        HudManager.EditorAction action = hud.editorMouseDown(control.x + 7, control.y + 7, 0);
        require(action != null && "fps".equals(action.settingsModuleId()), "Relocated Settings action is not clickable");
        screen.acceptHudEditorCanvas(controller.currentUiNavigationRevision() - 1, viewport,
                Arrays.asList(new UiBounds(0, 0, 900, 600)));
        render(hud, minecraft, drawing, viewport);
        require(drawing.settingsControl != null && same(drawing.settingsControl, control),
                "Stale canvas acknowledgement replaced current control placement");
    }

    private static void render(HudManager hud, Minecraft minecraft, Drawing drawing, UiBounds viewport) {
        drawing.reset();
        hud.renderEditor(minecraft, drawing.renderer, viewport, new UiInput(-1, -1, 0));
    }

    private static final class Drawing {
        final List<UiBounds> outlines = new ArrayList<UiBounds>();
        final List<UiBounds> handles = new ArrayList<UiBounds>();
        final List<Map<String, Object>> commands = new ArrayList<Map<String, Object>>();
        final List<Float> transforms = new ArrayList<Float>();
        float transform = 1;
        UiBounds settingsControl;
        UiBounds closeControl;
        float contentScale;
        void reset() {
            outlines.clear();
            handles.clear();
            settingsControl = null;
            closeControl = null;
            contentScale = 0;
            commands.clear();
            transforms.clear();
            transform = 1;
        }
        final UiRenderer renderer = (UiRenderer) Proxy.newProxyInstance(UiRenderer.class.getClassLoader(),
                new Class<?>[] {UiRenderer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("border") && ((Integer) args[2]) == 0x889FA4AE) outlines.add((UiBounds) args[0]);
                    String name = method.getName();
                    if (name.equals("pushTransform")) transforms.add(transform);
                    if (name.equals("popTransform")) transform = transforms.remove(transforms.size() - 1);
                    if (name.equals("scale")) { contentScale = (Float) args[0]; transform *= contentScale; }
                    if (method.getName().equals("roundedRect") && ((Integer) args[2]) == 0xE513141A
                            && ((UiBounds) args[0]).width == 8) handles.add((UiBounds) args[0]);
                    if (method.getName().equals("roundedRect")) {
                        UiBounds bounds = (UiBounds) args[0];
                        if (bounds.width == 14 && ((Integer) args[2]) == 0xE513141A) settingsControl = bounds;
                        if (((Integer) args[2]) == 0xD9261A1D) closeControl = bounds;
                    }
                    if (method.getName().equals("measureText")) return ((String) args[0]).length() * 6;
                    if (method.getName().equals("lineHeight")) return 9;
                    if (name.equals("border") || name.equals("roundedRect") || name.equals("text")) {
                        Map<String, Object> command = new LinkedHashMap<String, Object>();
                        command.put("type", name);
                        if (name.equals("text")) {
                            command.put("text", args[0]);
                            command.put("x", ((Integer) args[1]) * transform);
                            command.put("y", ((Integer) args[2]) * transform);
                            command.put("scale", transform);
                            command.put("argb", args[3]);
                        } else {
                            UiBounds bounds = (UiBounds) args[0];
                            command.put("x", bounds.x * transform);
                            command.put("y", bounds.y * transform);
                            command.put("width", bounds.width * transform);
                            command.put("height", bounds.height * transform);
                            command.put("argb", args[2]);
                        }
                        commands.add(command);
                    }
                    return null;
                });
    }

    private static boolean same(UiBounds a, UiBounds b) {
        return a.x == b.x && a.y == b.y && a.width == b.width && a.height == b.height;
    }

    private static String describe(UiBounds bounds) {
        return bounds.x + "," + bounds.y + " " + bounds.width + "x" + bounds.height;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
