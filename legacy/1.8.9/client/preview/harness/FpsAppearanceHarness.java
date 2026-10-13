package org.polydevs.opusmc.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Focused value-object contract for FPS appearance and legacy settings compatibility. */
public final class FpsAppearanceHarness {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("org.polydevs.opusmc.client.FpsAppearance");
        Object defaults = callStatic(type, "defaults");
        require(bool(defaults, "reverseOrder"), "FPS default order must put the label first");
        require(bool(defaults, "textShadow"), "FPS text shadow must default on");
        require(bool(defaults, "showBrackets"), "FPS brackets must default on");
        require("#ffffffff".equals(string(defaults, "bracketColor")),
                "FPS bracket color default changed");
        require(!bool(defaults, "showBackground"), "FPS background must default off");
        require(bool(defaults, "staticBackgroundWidth"), "FPS fixed-width background must default on");
        require(integer(defaults, "backgroundWidth") == 56
                        && integer(defaults, "backgroundHeight") == 18,
                "FPS background defaults changed");
        require(!bool(defaults, "border"), "FPS border must default off");
        require(integer(defaults, "borderThickness") == 50,
                "FPS border thickness must be stored in hundredths");
        require("#9f000000".equals(string(defaults, "borderColor"))
                        && "#6f000000".equals(string(defaults, "backgroundColor"))
                        && "#ffffffff".equals(string(defaults, "textColor")),
                "FPS default colors changed");

        Object custom = call(defaults, "withReverseOrder", new Class<?>[] {boolean.class}, false);
        custom = call(custom, "withTextShadow", new Class<?>[] {boolean.class}, false);
        custom = call(custom, "withShowBrackets", new Class<?>[] {boolean.class}, false);
        custom = call(custom, "withBracketColor", new Class<?>[] {String.class}, "#AABBCCDD");
        custom = call(custom, "withShowBackground", new Class<?>[] {boolean.class}, true);
        custom = call(custom, "withStaticBackgroundWidth", new Class<?>[] {boolean.class}, false);
        custom = call(custom, "withBackgroundWidth", new Class<?>[] {int.class}, 999);
        custom = call(custom, "withBackgroundHeight", new Class<?>[] {int.class}, 1);
        custom = call(custom, "withBorder", new Class<?>[] {boolean.class}, true);
        custom = call(custom, "withBorderThickness", new Class<?>[] {int.class}, 999);
        custom = call(custom, "withBorderColor", new Class<?>[] {String.class}, "#ABCDEF12");
        custom = call(custom, "withBackgroundColor", new Class<?>[] {String.class}, "#12345678");
        custom = call(custom, "withTextColor", new Class<?>[] {String.class}, "#FFEEDDCC");
        require(!bool(custom, "reverseOrder") && !bool(custom, "textShadow")
                        && !bool(custom, "showBrackets") && bool(custom, "showBackground")
                        && !bool(custom, "staticBackgroundWidth") && bool(custom, "border"),
                "An FPS appearance with-method lost an unrelated flag");
        require(integer(custom, "backgroundWidth") == 300
                        && integer(custom, "backgroundHeight") == 10
                        && integer(custom, "borderThickness") == 500,
                "FPS appearance numeric ranges were not clamped");
        require("#aabbccdd".equals(string(custom, "bracketColor"))
                        && "#abcdef12".equals(string(custom, "borderColor"))
                        && "#12345678".equals(string(custom, "backgroundColor"))
                        && "#ffeeddcc".equals(string(custom, "textColor")),
                "FPS colors were not canonicalized to lowercase #AARRGGBB");

        boolean rejected = false;
        try {
            call(custom, "withTextColor", new Class<?>[] {String.class}, "#123456");
        } catch (InvocationTargetException expected) {
            rejected = expected.getCause() instanceof IllegalArgumentException;
        }
        require(rejected, "A malformed FPS color was accepted");

        PerformanceOverlaySettings legacy = new PerformanceOverlaySettings(
                true, PerformanceOverlaySettings.Anchor.BOTTOM_RIGHT, 7, 9, 140, 75);
        Object legacyAppearance = call(legacy, "appearance", new Class<?>[0]);
        require(bool(legacyAppearance, "reverseOrder") && bool(legacyAppearance, "showBrackets"),
                "The legacy six-argument settings constructor lost compatible appearance defaults");
        PerformanceOverlaySettings styled = (PerformanceOverlaySettings) call(
                legacy, "withAppearance", new Class<?>[] {type}, custom);
        require(call(styled.withEnabled(false), "appearance", new Class<?>[0]) == custom
                        && call(styled.withAnchor(PerformanceOverlaySettings.Anchor.TOP_LEFT),
                                "appearance", new Class<?>[0]) == custom
                        && call(styled.withOffset(30, 40), "appearance", new Class<?>[0]) == custom
                        && call(styled.withScale(250), "appearance", new Class<?>[0]) == custom
                        && call(styled.withOpacity(90), "appearance", new Class<?>[0]) == custom,
                "A legacy PerformanceOverlaySettings with-method discarded FPS appearance");
        require(legacy.enabled() && legacy.scale() == 140 && legacy.opacity() == 75
                        && legacy.offsetX() == 7 && legacy.offsetY() == 9,
                "Applying FPS appearance mutated the original settings");
        System.out.println("FPS APPEARANCE: ALL PASSED");
    }

    private static Object callStatic(Class<?> type, String name) throws Exception {
        Method method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(null);
    }

    private static Object call(Object target, String name, Class<?>[] parameters, Object... arguments)
            throws Exception {
        Method method = target.getClass().getMethod(name, parameters);
        return method.invoke(target, arguments);
    }

    private static boolean bool(Object target, String name) throws Exception {
        return (Boolean) call(target, name, new Class<?>[0]);
    }

    private static int integer(Object target, String name) throws Exception {
        return (Integer) call(target, name, new Class<?>[0]);
    }

    private static String string(Object target, String name) throws Exception {
        return (String) call(target, name, new Class<?>[0]);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
