package org.polydevs.opusmc.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.polydevs.opusmc.client.hud.HudRenderContext;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiRenderer;

/** Exact native FPS draw-call and shared editor-bounds contract without OpenGL. */
public final class FpsRenderingHarness {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("opus-fps-render-test-");
        Path file = root.resolve("settings.json");
        String previousPath = System.getProperty("opus.utility.settings.file");
        System.setProperty("opus.utility.settings.file", file.toString());
        Field debug = Minecraft.class.getDeclaredField("debugFPS");
        debug.setAccessible(true);
        int previousFps = debug.getInt(null);
        debug.setInt(null, 54321);
        try {
            Files.write(file, ("{\"schemaVersion\":1,\"utilities\":{\"fps\":{"
                    + "\"enabled\":true,\"anchor\":\"top-left\",\"offset\":\"20 · 30\","
                    + "\"scale\":100,\"opacity\":50,\"reverseOrder\":false,"
                    + "\"textShadow\":false,\"showBrackets\":true,"
                    + "\"bracketColor\":\"#40FF0000\",\"showBackground\":true,"
                    + "\"staticBackgroundWidth\":true,\"backgroundWidth\":100,"
                    + "\"backgroundHeight\":18,\"border\":true,\"borderThickness\":50,"
                    + "\"borderColor\":\"#9F00FF00\",\"backgroundColor\":\"#6F000000\","
                    + "\"textColor\":\"#80FFFFFF\"}}}").getBytes(StandardCharsets.UTF_8));
            UtilitySettingsStore store = UtilitySettingsStore.fromSystemProperty(
                    LogManager.getLogger("FpsRenderingHarness"));
            store.reload();
            FpsModule module = new FpsModule(store);
            FakeRenderer renderer = new FakeRenderer();
            UiBounds viewport = new UiBounds(0, 0, 300, 200);
            HudRenderContext context = new HudRenderContext(null, renderer, viewport);

            UiBounds bounds = module.widget().bounds(context);
            require(same(bounds, new UiBounds(20, 30, 100, 18)),
                    "Static FPS background did not define the editor bounds: " + describe(bounds));
            module.widget().renderNormal(context);
            require(renderer.pushes == 1 && renderer.pops == 1
                            && renderer.scaleX == 1.0F && renderer.scaleY == 1.0F,
                    "FPS render did not isolate its uniform scale transform");
            require(renderer.fills.size() == 1
                            && same(renderer.fills.get(0).bounds, bounds)
                            && renderer.fills.get(0).argb == 0x37000000,
                    "FPS background geometry/color did not multiply stored alpha by opacity");
            require(renderer.lines.size() == 4, "FPS border was not drawn as four float lines");
            require(line(renderer.lines.get(0), 20.25F, 30.25F, 119.75F, 30.25F, 0.5F, 0x4F00FF00)
                            && line(renderer.lines.get(1), 20.25F, 47.75F, 119.75F, 47.75F, 0.5F, 0x4F00FF00)
                            && line(renderer.lines.get(2), 20.25F, 30.25F, 20.25F, 47.75F, 0.5F, 0x4F00FF00)
                            && line(renderer.lines.get(3), 119.75F, 30.25F, 119.75F, 47.75F, 0.5F, 0x4F00FF00),
                    "FPS 0.50 px border escaped or diverged from the background frame");
            require(renderer.texts.size() == 3
                            && text(renderer.texts.get(0), "[", 40, 34, 0x20FF0000, false)
                            && text(renderer.texts.get(1), "54321 FPS", 44, 34, 0x40FFFFFF, false)
                            && text(renderer.texts.get(2), "]", 96, 34, 0x20FF0000, false),
                    "FPS order/bracket colors/centering/shadow did not match the appearance");

            Method apply = method(FpsModule.class, "applySetting", String.class, String.class);
            require((Boolean) apply.invoke(module, "showBackground", "0"),
                    "FPS background toggle did not persist");
            renderer.reset();
            bounds = module.widget().bounds(context);
            require(same(bounds, new UiBounds(20, 30, 60, 9)),
                    "Hidden FPS background retained invisible frame geometry: " + describe(bounds));
            module.widget().renderNormal(context);
            require(renderer.fills.isEmpty() && renderer.lines.isEmpty() && renderer.texts.size() == 3,
                    "Hidden FPS background or border still drew");

            require((Boolean) apply.invoke(module, "textShadow", "1"),
                    "FPS text-shadow toggle did not persist");
            renderer.reset();
            bounds = module.widget().bounds(context);
            require(same(bounds, new UiBounds(20, 30, 61, 10)),
                    "FPS bounds clipped the real one-pixel text shadow: " + describe(bounds));
            module.widget().renderNormal(context);
            require(renderer.texts.size() == 3
                            && renderer.texts.get(0).shadow != null && renderer.texts.get(0).shadow
                            && renderer.texts.get(1).shadow != null && renderer.texts.get(1).shadow
                            && renderer.texts.get(2).shadow != null && renderer.texts.get(2).shadow,
                    "FPS text shadow did not reach every native FontRenderer draw call");
            require((Boolean) apply.invoke(module, "textShadow", "0"),
                    "FPS text-shadow reset did not persist");

            require((Boolean) apply.invoke(module, "showBackground", "1")
                            && (Boolean) apply.invoke(module, "staticBackgroundWidth", "0"),
                    "FPS dynamic-background settings did not persist");
            bounds = module.widget().bounds(context);
            require(same(bounds, new UiBounds(20, 30, 68, 18)),
                    "Dynamic FPS background did not use text plus padding: " + describe(bounds));
            require((Boolean) apply.invoke(module, "showBrackets", "0")
                            && (Boolean) apply.invoke(module, "reverseOrder", "1"),
                    "FPS text-format settings did not persist");
            renderer.reset();
            bounds = module.widget().bounds(context);
            require(same(bounds, new UiBounds(20, 30, 60, 18)),
                    "Bracket-free dynamic FPS bounds did not match actual content");
            module.widget().renderNormal(context);
            require(renderer.texts.size() == 1
                            && text(renderer.texts.get(0), "FPS 54321", 24, 34, 0x40FFFFFF, false),
                    "Bracket-free reverse FPS text did not render as one centered text run");

            require((Boolean) apply.invoke(module, "showBrackets", "1")
                            && (Boolean) apply.invoke(module, "reverseOrder", "0")
                            && (Boolean) apply.invoke(module, "staticBackgroundWidth", "1")
                            && (Boolean) apply.invoke(module, "backgroundWidth", "56"),
                    "FPS fixed minimum frame settings did not persist");
            bounds = module.widget().bounds(context);
            require(bounds.width == 68,
                    "Fixed FPS frame clipped live digits instead of growing to contain text");

            require((Boolean) apply.invoke(module, "backgroundWidth", "100")
                            && (Boolean) apply.invoke(module, "scale", "200")
                            && (Boolean) apply.invoke(module, "anchor", "bottom-right")
                            && (Boolean) apply.invoke(module, "offsetX", "12")
                            && (Boolean) apply.invoke(module, "offsetY", "12"),
                    "FPS scaled anchored frame settings did not persist");
            renderer.reset();
            bounds = module.widget().bounds(context);
            require(same(bounds, new UiBounds(88, 152, 200, 36)),
                    "Scaled bottom-right FPS bounds changed: " + describe(bounds));
            module.widget().renderNormal(context);
            require(renderer.fills.size() == 1
                            && same(renderer.fills.get(0).bounds, new UiBounds(44, 76, 100, 18))
                            && renderer.scaleX == 2.0F && renderer.scaleY == 2.0F,
                    "Scaled FPS draw frame and editor bounds used different geometry");
            // Non-divisible offsets must remain exact after the GL transform.
            for (int scale : new int[] {119, 154, 225, 399}) {
                for (PerformanceOverlaySettings.Anchor anchor : PerformanceOverlaySettings.Anchor.values()) {
                    module.update(module.settings().withScale(scale).withAnchor(anchor).withOffset(17, 19));
                    renderer.reset();
                    bounds = module.widget().bounds(context);
                    module.widget().renderNormal(context);
                    UiBounds fill = renderer.fills.get(0).bounds;
                    float drawnX = (fill.x + renderer.translateX) * renderer.scaleX;
                    float drawnY = (fill.y + renderer.translateY) * renderer.scaleY;
                    require(Math.abs(drawnX - bounds.x) < 0.0001F && Math.abs(drawnY - bounds.y) < 0.0001F,
                            "Scaled FPS drawing drifted from the editor's exact saved position");
                    require(Math.round(fill.width * renderer.scaleX) == bounds.width
                                    && Math.round(fill.height * renderer.scaleY) == bounds.height,
                            "Scaled FPS frame does not round to the shared editor extent");
                }
            }
            System.out.println("FPS RENDERING: ALL PASSED");
        } finally {
            debug.setInt(null, previousFps);
            Files.deleteIfExists(root.resolve(".settings.json.opus-part"));
            Files.deleteIfExists(file);
            Files.deleteIfExists(root);
            if (previousPath == null) {
                System.clearProperty("opus.utility.settings.file");
            } else {
                System.setProperty("opus.utility.settings.file", previousPath);
            }
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method result = type.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }

    private static boolean same(UiBounds left, UiBounds right) {
        return left.x == right.x && left.y == right.y
                && left.width == right.width && left.height == right.height;
    }

    private static String describe(UiBounds bounds) {
        return bounds.x + "," + bounds.y + " " + bounds.width + "x" + bounds.height;
    }

    private static boolean text(TextCall actual, String value, int x, int y, int argb, boolean shadow) {
        return value.equals(actual.value) && actual.x == x && actual.y == y
                && actual.argb == argb && actual.shadow != null && actual.shadow == shadow;
    }

    private static boolean line(LineCall actual, float x0, float y0, float x1, float y1,
            float thickness, int argb) {
        return actual.x0 == x0 && actual.y0 == y0 && actual.x1 == x1 && actual.y1 == y1
                && actual.thickness == thickness && actual.argb == argb;
    }

    private static final class RectCall {
        final UiBounds bounds;
        final int argb;

        RectCall(UiBounds bounds, int argb) {
            this.bounds = bounds;
            this.argb = argb;
        }
    }

    private static final class LineCall {
        final float x0;
        final float y0;
        final float x1;
        final float y1;
        final float thickness;
        final int argb;

        LineCall(float x0, float y0, float x1, float y1, float thickness, int argb) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.thickness = thickness;
            this.argb = argb;
        }
    }

    private static final class TextCall {
        final String value;
        final int x;
        final int y;
        final int argb;
        final Boolean shadow;

        TextCall(String value, int x, int y, int argb, Boolean shadow) {
            this.value = value;
            this.x = x;
            this.y = y;
            this.argb = argb;
            this.shadow = shadow;
        }
    }

    private static final class FakeRenderer implements UiRenderer {
        final List<RectCall> fills = new ArrayList<RectCall>();
        final List<LineCall> lines = new ArrayList<LineCall>();
        final List<TextCall> texts = new ArrayList<TextCall>();
        int pushes;
        int pops;
        float scaleX;
        float scaleY;
        float translateX;
        float translateY;

        @Override public void beginFrame(UiBounds viewport) { }
        @Override public void endFrame() { }
        @Override public void fill(UiBounds bounds, int argb) { fills.add(new RectCall(bounds, argb)); }
        @Override public void roundedRect(UiBounds bounds, int radius, int argb) { }
        @Override public void border(UiBounds bounds, int thickness, int argb) { }
        @Override public void text(String value, int x, int y, int argb) {
            texts.add(new TextCall(value, x, y, argb, null));
        }
        public void text(String value, int x, int y, int argb, boolean shadow) {
            texts.add(new TextCall(value, x, y, argb, shadow));
        }
        @Override public int measureText(String value) {
            int width = 0;
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                width += character == ' ' || character == '[' || character == ']' ? 4 : 6;
            }
            return width;
        }
        @Override public int lineHeight() { return 9; }
        @Override public void line(float x0, float y0, float x1, float y1, float thickness, int argb) {
            lines.add(new LineCall(x0, y0, x1, y1, thickness, argb));
        }
        @Override public void ring(float centerX, float centerY, float radius, float thickness, int argb) { }
        @Override public void pushTransform() { pushes++; }
        @Override public void popTransform() { pops++; }
        @Override public void scale(float x, float y) { scaleX = x; scaleY = y; }
        @Override public void translate(float x, float y) { translateX = x; translateY = y; }

        void reset() {
            fills.clear();
            lines.clear();
            texts.clear();
            pushes = 0;
            pops = 0;
            scaleX = 0.0F;
            scaleY = 0.0F;
            translateX = 0.0F;
            translateY = 0.0F;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
