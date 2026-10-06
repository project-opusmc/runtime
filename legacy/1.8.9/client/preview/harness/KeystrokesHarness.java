package org.polydevs.opusmc.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import org.lwjgl.input.Keyboard;
import org.polydevs.opusmc.client.hud.HudRenderContext;
import org.polydevs.opusmc.client.hud.HudWidget;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiRenderer;

/** Headless contract harness for the native Keystrokes HUD. */
public final class KeystrokesHarness {
    public static void main(String[] args) throws Exception {
        settingsAreOptInImmutableAndClamped();
        remappedBindingsDriveRealUpAndDownState();
        inactiveAndUnavailableDevicesFailClosed();
        layoutFitsLabelsAndOptionalRows();
        moduleRendersRealStateInSharedGeometry();
        editorChangesPreviewClampCommitAndCancel();
        System.out.println("KEYSTROKES: ALL PASSED");
    }

    private static void settingsAreOptInImmutableAndClamped() {
        KeystrokesSettings defaults = KeystrokesSettings.defaults();
        require(!defaults.enabled(), "Keystrokes must remain opt-in");
        require(defaults.anchor() == PerformanceOverlaySettings.Anchor.TOP_LEFT,
                "Keystrokes default anchor changed");
        require(defaults.offsetX() == 12 && defaults.offsetY() == 50,
                "Keystrokes default offset no longer avoids FPS");
        require(defaults.scale() == 100 && defaults.opacity() == 100,
                "Keystrokes defaults changed scale or opacity");
        require(defaults.showMouseButtons() && defaults.showJump(),
                "Keystrokes default rows changed");

        KeystrokesSettings clamped = new KeystrokesSettings(
                true, null, -4, -9, 999, -1, false, false);
        require(clamped.anchor() == PerformanceOverlaySettings.Anchor.TOP_LEFT,
                "Null anchor did not fail safe to top-left");
        require(clamped.offsetX() == 0 && clamped.offsetY() == 0,
                "Negative offsets escaped clamping");
        require(clamped.scale() == 150 && clamped.opacity() == 25,
                "Scale or opacity escaped its persisted range");

        KeystrokesSettings changed = defaults
                .withEnabled(true)
                .withAnchor(PerformanceOverlaySettings.Anchor.BOTTOM_RIGHT)
                .withOffset(7, 8)
                .withScale(50)
                .withOpacity(25)
                .withShowMouseButtons(false)
                .withShowJump(false);
        require(!defaults.enabled() && defaults.offsetX() == 12 && defaults.showJump(),
                "A with-method mutated the original settings");
        require(changed.enabled()
                        && changed.anchor() == PerformanceOverlaySettings.Anchor.BOTTOM_RIGHT
                        && changed.offsetX() == 7 && changed.offsetY() == 8
                        && changed.scale() == 50 && changed.opacity() == 25
                        && !changed.showMouseButtons() && !changed.showJump(),
                "A with-method lost an unrelated setting");
        pass("settings stay opt-in, immutable, and clamped");
    }

    private static void remappedBindingsDriveRealUpAndDownState() {
        GameSettings bindings = new GameSettings();
        bindings.keyBindForward.setKeyCode(Keyboard.KEY_Q);
        bindings.keyBindAttack.setKeyCode(Keyboard.KEY_R);
        bindings.keyBindUseItem.setKeyCode(-98);
        bindings.keyBindJump.setKeyCode(-97);

        FakeDevices devices = new FakeDevices();
        devices.keyboardDown.add(Keyboard.KEY_Q);
        devices.keyboardDown.add(Keyboard.KEY_R);
        devices.mouseDown.add(2);
        devices.mouseDown.add(3);
        KeystrokesInputReader reader = new KeystrokesInputReader(devices);
        KeystrokesInputReader.Snapshot down = reader.read(bindings);

        require(down.available(), "Complete Minecraft bindings were reported unavailable");
        require(devices.activeQueries == 1
                        && devices.keyboardCreatedQueries == 1
                        && devices.mouseCreatedQueries == 1,
                "One snapshot repeated environment probes per binding cell");
        require("Q".equals(down.forward().label()) && down.forward().pressed(),
                "Remapped forward key did not use its assigned keyboard code");
        require("R".equals(down.attack().label()) && down.attack().pressed(),
                "Keyboard-remapped attack did not use its assigned code");
        require("M3".equals(down.use().label()) && down.use().pressed(),
                "Mouse-remapped use did not use its assigned button");
        require("M4".equals(down.jump().label()) && down.jump().pressed(),
                "Mouse-remapped jump did not use its assigned button");
        require("A".equals(down.left().label()) && !down.left().pressed(),
                "An unpressed movement binding was fabricated as down");

        devices.keyboardDown.clear();
        devices.mouseDown.clear();
        KeystrokesInputReader.Snapshot up = reader.read(bindings);
        require(!up.forward().pressed() && !up.attack().pressed()
                        && !up.use().pressed() && !up.jump().pressed(),
                "Released bindings remained pressed");
        pass("assigned keyboard and mouse bindings drive real up/down state");
    }

    private static void inactiveAndUnavailableDevicesFailClosed() {
        GameSettings bindings = new GameSettings();
        FakeDevices devices = new FakeDevices();
        devices.keyboardDown.add(bindings.keyBindForward.getKeyCode());
        devices.mouseDown.add(bindings.keyBindAttack.getKeyCode() + 100);
        KeystrokesInputReader reader = new KeystrokesInputReader(devices);

        devices.active = false;
        KeystrokesInputReader.Snapshot inactive = reader.read(bindings);
        require(!inactive.forward().pressed() && !inactive.attack().pressed(),
                "An inactive game window leaked pressed state");
        require(devices.activeQueries == 1
                        && devices.keyboardCreatedQueries == 0
                        && devices.mouseCreatedQueries == 0
                        && devices.keyboardQueries == 0 && devices.mouseQueries == 0,
                "Inactive input performed device probes after the focus check");

        devices.active = true;
        devices.keyboardCreated = false;
        devices.mouseCreated = false;
        KeystrokesInputReader.Snapshot unavailable = reader.read(bindings);
        require(!unavailable.forward().pressed() && !unavailable.attack().pressed(),
                "An unavailable device leaked pressed state");
        require(devices.activeQueries == 2
                        && devices.keyboardCreatedQueries == 1
                        && devices.mouseCreatedQueries == 1
                        && devices.keyboardQueries == 0 && devices.mouseQueries == 0,
                "Uncreated input repeated probes or queried physical state");

        require(!reader.read(null).available(),
                "Missing Minecraft bindings invented a usable snapshot");
        pass("inactive, uncreated, and missing input fail closed without device queries");
    }

    private static void layoutFitsLabelsAndOptionalRows() {
        GameSettings bindings = new GameSettings();
        FakeDevices devices = new FakeDevices();
        KeystrokesInputReader reader = new KeystrokesInputReader(devices);
        FakeRenderer renderer = new FakeRenderer();

        KeystrokesLayout full = KeystrokesLayout.create(renderer, reader.read(bindings), true, true);
        require(full.width() == 70 && full.height() == 78,
                "Default layout geometry changed: " + full.width() + "x" + full.height());
        require(full.cells().size() == 7, "Default layout lost a real binding cell");
        require(cell(full.cells(), "W").bounds().x == 24
                        && cell(full.cells(), "W").bounds().y == 0,
                "Forward cell is not centered above the movement row");
        require(cell(full.cells(), "A").bounds().x == 0
                        && cell(full.cells(), "S").bounds().x == 24
                        && cell(full.cells(), "D").bounds().x == 48,
                "Movement row geometry changed");
        require(cell(full.cells(), "M1").bounds().y == 40
                        && cell(full.cells(), "M2").bounds().y == 40
                        && cell(full.cells(), "SPACE").bounds().y == 60,
                "Mouse/jump rows do not follow movement geometry");

        KeystrokesLayout movementOnly = KeystrokesLayout.create(
                renderer, reader.read(bindings), false, false);
        require(movementOnly.width() == 70 && movementOnly.height() == 38
                        && movementOnly.cells().size() == 4,
                "Disabled optional rows retained fake geometry");

        bindings.keyBindForward.setKeyCode(Keyboard.KEY_LSHIFT);
        KeystrokesLayout longLabel = KeystrokesLayout.create(
                renderer, reader.read(bindings), false, false);
        KeystrokesLayout.Cell shift = cell(longLabel.cells(), "LSHIFT");
        require(shift.bounds().width >= renderer.measureText("LSHIFT") + 8
                        && longLabel.width() == 136,
                "A remapped long label is clipped or ignored by layout");

        KeystrokesLayout missing = KeystrokesLayout.create(renderer, reader.read(null), true, true);
        require(missing.width() == 0 && missing.height() == 0 && missing.cells().isEmpty(),
                "Unavailable bindings manufactured placeholder cells");
        pass("layout shares exact geometry, optional rows, and remapped label fit");
    }

    private static void moduleRendersRealStateInSharedGeometry() throws Exception {
        GameSettings bindings = new GameSettings();
        bindings.keyBindForward.setKeyCode(Keyboard.KEY_Q);
        FakeDevices devices = new FakeDevices();
        devices.keyboardDown.add(Keyboard.KEY_Q);
        devices.mouseDown.add(0);
        MemorySettings settings = new MemorySettings(KeystrokesSettings.defaults().withEnabled(true));
        KeystrokesModule module = new KeystrokesModule(settings, new KeystrokesInputReader(devices));
        FakeRenderer renderer = new FakeRenderer();
        HudRenderContext context = new HudRenderContext(
                minecraft(bindings), renderer, new UiBounds(0, 0, 200, 200));

        require("keystrokes".equals(module.id()) && "Keystrokes".equals(module.displayName()),
                "Module identity changed");
        require(module.isEnabled() && module.widget().isVisible(),
                "Enabled Keystrokes widget is not visible");
        require(module.settings() == settings.current, "Module settings did not use its state owner");

        UiBounds bounds = module.widget().bounds(context);
        require(bounds.x == 12 && bounds.y == 50 && bounds.width == 70 && bounds.height == 78,
                "Widget bounds diverged from layout: " + describe(bounds));
        module.widget().renderNormal(context);
        require(renderer.pushes == 1 && renderer.pops == 1
                        && renderer.scaleX == 1.0F && renderer.scaleY == 1.0F,
                "Widget did not isolate its scale transform");
        require(renderer.rounded.size() == 7 && renderer.borders == 7 && renderer.texts.size() == 7,
                "Widget did not render exactly one native cell per visible binding");
        require(renderer.rounded.get(0).argb == 0xFFD9DCE1
                        && renderer.rounded.get(1).argb == 0xFF1B1D21
                        && renderer.rounded.get(4).argb == 0xFFD9DCE1
                        && renderer.rounded.get(5).argb == 0xFF1B1D21,
                "Pressed and released cells are not inverse soft-grey states");
        require(text(renderer.texts, "Q").argb == 0xFF202226
                        && text(renderer.texts, "A").argb == 0xFFF2F3F5
                        && text(renderer.texts, "M1").argb == 0xFF202226
                        && text(renderer.texts, "M2").argb == 0xFFF2F3F5,
                "Pressed and released labels are not inverse monochrome states");
        require(renderer.fills == 0 && renderer.lines == 0 && renderer.rings == 0,
                "Keystrokes introduced a color bar or decorative effect");
        require(same(bounds, union(renderer.rounded)),
                "Rendered cells and editor hit bounds use different geometry");

        devices.mouseDown.clear();
        renderer.reset();
        module.widget().renderNormal(context);
        require(text(renderer.texts, "M1").argb == 0xFFF2F3F5,
                "Released physical attack button remained displayed as pressed");

        settings.preview(settings.current.withShowMouseButtons(false).withShowJump(false));
        renderer.reset();
        UiBounds movementBounds = module.widget().bounds(context);
        module.widget().renderNormal(context);
        require(movementBounds.width == 70 && movementBounds.height == 38
                        && renderer.rounded.size() == 4,
                "Hidden optional rows still rendered or occupied hit geometry");

        settings.preview(settings.current
                .withShowMouseButtons(true)
                .withShowJump(true)
                .withAnchor(PerformanceOverlaySettings.Anchor.BOTTOM_RIGHT)
                .withOffset(12, 12)
                .withScale(150));
        UiBounds anchored = module.widget().bounds(context);
        require(anchored.x == 83 && anchored.y == 71
                        && anchored.width == 105 && anchored.height == 117,
                "Bottom-right scaled bounds are incorrect: " + describe(anchored));

        context.minecraft().gameSettings = null;
        renderer.reset();
        module.widget().renderNormal(context);
        UiBounds unavailable = module.widget().bounds(context);
        require(renderer.rounded.isEmpty() && renderer.texts.isEmpty()
                        && unavailable.width == 0 && unavailable.height == 0,
                "Missing game bindings manufactured a plausible HUD");
        pass("module renders real remapped state with shared normal/editor geometry");
    }

    private static void editorChangesPreviewClampCommitAndCancel() throws Exception {
        MemorySettings settings = new MemorySettings(KeystrokesSettings.defaults().withEnabled(true));
        KeystrokesModule module = new KeystrokesModule(
                settings, new KeystrokesInputReader(new FakeDevices()));
        FakeRenderer renderer = new FakeRenderer();
        HudRenderContext context = new HudRenderContext(
                minecraft(new GameSettings()), renderer, new UiBounds(0, 0, 100, 100));
        HudWidget widget = module.widget();
        UiBounds bounds = widget.bounds(context);

        require(widget.moveBy(context.viewport(), bounds, 100, 100),
                "Drag preview was rejected");
        require(settings.current.offsetX() == 30 && settings.current.offsetY() == 22,
                "Drag preview did not clamp the complete widget into the viewport");
        require(settings.previewCount == 1 && settings.persistCount == 0,
                "Drag persisted before release");
        require(widget.commitEditorChange() && settings.persistCount == 1,
                "Mouse-up did not persist the drag preview");

        UiBounds movedBounds = widget.bounds(context);
        require(widget.moveBy(context.viewport(), movedBounds, -100, -100),
                "Second drag preview was rejected");
        require(settings.current.offsetX() == 0 && settings.current.offsetY() == 0,
                "Negative drag escaped offset clamping");
        widget.cancelEditorChange();
        require(settings.current.offsetX() == 30 && settings.current.offsetY() == 22
                        && settings.cancelCount == 1 && settings.persistCount == 1,
                "Cancel did not restore the last persisted layout");

        int previewsBeforeResize = settings.previewCount;
        require(!widget.resizeBy(widget.bounds(context), 0)
                        && settings.previewCount == previewsBeforeResize,
                "Zero-width resize manufactured a preview");
        require(widget.resizeBy(widget.bounds(context), 70),
                "Resize preview was rejected");
        require(settings.current.scale() == 150 && settings.persistCount == 1,
                "Resize failed to clamp or persisted before release");
        require(widget.commitEditorChange() && settings.persistCount == 2,
                "Mouse-up did not persist the resize preview");

        require(widget.disable() && !module.isEnabled() && !widget.isVisible(),
                "Disable did not update the module's real settings");
        require(settings.updateCount == 1,
                "Disable bypassed the normal persistent update path");
        pass("editor changes preview, clamp, commit on release, cancel, and disable cleanly");
    }

    private static Minecraft minecraft(GameSettings bindings) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        Method allocateInstance = unsafeType.getMethod("allocateInstance", Class.class);
        Minecraft minecraft = (Minecraft) allocateInstance.invoke(unsafe, Minecraft.class);
        minecraft.gameSettings = bindings;
        return minecraft;
    }

    private static UiBounds union(List<RectCall> rectangles) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (RectCall rectangle : rectangles) {
            left = Math.min(left, rectangle.bounds.x);
            top = Math.min(top, rectangle.bounds.y);
            right = Math.max(right, rectangle.bounds.right());
            bottom = Math.max(bottom, rectangle.bounds.bottom());
        }
        return new UiBounds(left, top, right - left, bottom - top);
    }

    private static TextCall text(List<TextCall> calls, String value) {
        for (TextCall call : calls) {
            if (value.equals(call.value)) {
                return call;
            }
        }
        throw new AssertionError("Missing rendered label: " + value);
    }

    private static boolean same(UiBounds first, UiBounds second) {
        return first.x == second.x && first.y == second.y
                && first.width == second.width && first.height == second.height;
    }

    private static String describe(UiBounds bounds) {
        return bounds.x + "," + bounds.y + " " + bounds.width + "x" + bounds.height;
    }

    private static KeystrokesLayout.Cell cell(List<KeystrokesLayout.Cell> cells, String label) {
        for (KeystrokesLayout.Cell cell : cells) {
            if (label.equals(cell.state().label())) {
                return cell;
            }
        }
        throw new AssertionError("Missing rendered binding cell: " + label);
    }

    private static final class FakeDevices implements KeystrokesInputReader.Devices {
        boolean active = true;
        boolean keyboardCreated = true;
        boolean mouseCreated = true;
        int activeQueries;
        int keyboardCreatedQueries;
        int mouseCreatedQueries;
        int keyboardQueries;
        int mouseQueries;
        final Set<Integer> keyboardDown = new HashSet<Integer>();
        final Set<Integer> mouseDown = new HashSet<Integer>();

        @Override
        public boolean windowActive() {
            activeQueries++;
            return active;
        }

        @Override
        public boolean keyboardCreated() {
            keyboardCreatedQueries++;
            return keyboardCreated;
        }

        @Override
        public boolean mouseCreated() {
            mouseCreatedQueries++;
            return mouseCreated;
        }

        @Override
        public boolean keyboardDown(int keyCode) {
            keyboardQueries++;
            return keyboardDown.contains(keyCode);
        }

        @Override
        public boolean mouseDown(int button) {
            mouseQueries++;
            return mouseDown.contains(button);
        }
    }

    private static final class MemorySettings implements KeystrokesModule.SettingsAccess {
        KeystrokesSettings current;
        KeystrokesSettings persisted;
        int updateCount;
        int previewCount;
        int persistCount;
        int cancelCount;

        MemorySettings(KeystrokesSettings initial) {
            current = initial;
            persisted = initial;
        }

        @Override public KeystrokesSettings settings() { return current; }

        @Override
        public boolean update(KeystrokesSettings next) {
            updateCount++;
            current = next;
            persisted = next;
            return true;
        }

        @Override
        public void preview(KeystrokesSettings next) {
            previewCount++;
            current = next;
        }

        @Override
        public boolean persist() {
            persistCount++;
            persisted = current;
            return true;
        }

        @Override
        public void cancelPreview() {
            cancelCount++;
            current = persisted;
        }
    }

    private static final class RectCall {
        final UiBounds bounds;
        final int argb;

        RectCall(UiBounds bounds, int argb) {
            this.bounds = bounds;
            this.argb = argb;
        }
    }

    private static final class TextCall {
        final String value;
        final int x;
        final int y;
        final int argb;

        TextCall(String value, int x, int y, int argb) {
            this.value = value;
            this.x = x;
            this.y = y;
            this.argb = argb;
        }
    }

    private static final class FakeRenderer implements UiRenderer {
        final List<RectCall> rounded = new ArrayList<RectCall>();
        final List<TextCall> texts = new ArrayList<TextCall>();
        int fills;
        int borders;
        int lines;
        int rings;
        int pushes;
        int pops;
        float scaleX;
        float scaleY;

        @Override public void beginFrame(UiBounds viewport) { }
        @Override public void endFrame() { }
        @Override public void fill(UiBounds bounds, int argb) { fills++; }
        @Override public void roundedRect(UiBounds bounds, int radius, int argb) {
            rounded.add(new RectCall(bounds, argb));
        }
        @Override public void border(UiBounds bounds, int thickness, int argb) { borders++; }
        @Override public void text(String value, int x, int y, int argb) {
            texts.add(new TextCall(value, x, y, argb));
        }
        @Override public int measureText(String value) { return value.length() * 6; }
        @Override public int lineHeight() { return 9; }
        @Override public void line(float x0, float y0, float x1, float y1, float thickness, int argb) { lines++; }
        @Override public void ring(float centerX, float centerY, float radius, float thickness, int argb) { rings++; }
        @Override public void pushTransform() { pushes++; }
        @Override public void popTransform() { pops++; }
        @Override public void scale(float x, float y) { scaleX = x; scaleY = y; }

        void reset() {
            rounded.clear();
            texts.clear();
            fills = 0;
            borders = 0;
            lines = 0;
            rings = 0;
            pushes = 0;
            pops = 0;
            scaleX = 0.0F;
            scaleY = 0.0F;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void pass(String message) {
        System.out.println("PASS: " + message);
    }
}
