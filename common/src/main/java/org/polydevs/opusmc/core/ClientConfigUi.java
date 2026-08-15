package org.polydevs.opusmc.core;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import javax.imageio.ImageIO;

/**
 * Functional in-game utility catalog and HUD renderer.
 *
 * <p>The bootstrap parent cannot link against Minecraft or LWJGL classes. All
 * game calls therefore stay behind cached reflection bindings, while layout,
 * persistence, filtering and input remain normal Java code.</p>
 */
public final class ClientConfigUi {
    private static final int ALL = 0;
    private static final int PERFORMANCE = 1;
    private static final int HUD = 2;
    private static final int INPUT = 3;

    private static final int TEXT = 0xFFF2F4F1;
    private static final int MUTED = 0xFFA2AAA5;
    private static final int SUBTLE = 0xFF747D78;
    private static final int HAIRLINE = 0x2AFFFFFF;
    private static final int WINDOW = 0xD90B0E11;
    private static final int HEADER = 0xE9080A0D;
    private static final int SIDEBAR = 0xA8101416;
    private static final int CARD = 0x5C2C3032;
    private static final int CARD_HOVER = 0x73363A3C;
    private static final int CONTROL = 0x3BFFFFFF;
    private static final int WHITE = 0xFFF3F5F2;
    private static final int OFF = 0xFF555D59;
    private static final int TRACK = 0xFF555B58;
    private static final int[] CATEGORY_WIDTHS = {44, 82, 48, 54};
    private static final String[] CATEGORY_LABELS = {"ALL", "PERFORMANCE", "HUD", "INPUT"};
    private static final String[] ANCHORS = {
            "top-left", "top-right", "bottom-left", "bottom-right"
    };
    private static final String[] ANCHOR_LABELS = {
            "Top left", "Top right", "Bottom left", "Bottom right"
    };
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private static final UtilityDefinition[] UTILITIES = {
            new UtilityDefinition(
                    "fps", "FPS", "Live frame rate", "FPS", PERFORMANCE),
            new UtilityDefinition(
                    "cps", "CPS", "Clicks in the last second", "CPS", PERFORMANCE),
            new UtilityDefinition(
                    "memory", "Memory", "Current Java heap usage", "MEM", PERFORMANCE),
            new UtilityDefinition(
                    "coordinates", "Coordinates", "Live player position", "XYZ", HUD),
            new UtilityDefinition(
                    "clock", "Clock", "Local system time", "TIME", HUD),
            new UtilityDefinition(
                    "keystrokes", "Keystrokes", "WASD input state", "WASD", INPUT)
    };

    private static final Map<Object, ScreenState> STATES =
            Collections.synchronizedMap(new WeakHashMap<Object, ScreenState>());
    private static final Object SETTINGS_LOCK = new Object();
    private static final Object CLICK_LOCK = new Object();
    private static final long[] CLICK_TIMES = new long[32];
    private static final Preference[] PREFERENCES = defaultPreferences();

    private static volatile int clickCursor;
    private static volatile boolean settingsLoaded;
    private static volatile boolean saveFailed;
    private static volatile ScreenBindings screenBindings;
    private static volatile HudBindings hudBindings;
    private static volatile BlurBindings blurBindings;
    private static volatile BrandTexture brandTexture;
    private static volatile boolean brandTextureFailed;

    private ClientConfigUi() {
    }

    public static void opened(Object screen) {
        ensureSettingsLoaded();
        ScreenState state = new ScreenState();
        STATES.put(screen, state);
        tryApplyBlur(screen, state);
    }

    public static void closed(Object screen) {
        ScreenState state = STATES.remove(screen);
        if (state != null && state.blurApplied) {
            tryRemoveBlur(screen);
        }
    }

    public static void render(Object screen, int mouseX, int mouseY, float partialTicks)
            throws ReflectiveOperationException {
        ensureSettingsLoaded();
        ScreenBindings draw = bindingsFor(screen);
        ScreenState state = state(screen);
        Layout layout = Layout.forScreen(draw.width.getInt(screen), draw.height.getInt(screen));
        Object font = draw.font.get(screen);

        draw.rect(screen, 0, 0, layout.screenW, layout.screenH, 0x3D000000);
        draw.rounded(
                screen,
                layout.windowX,
                layout.windowY,
                layout.windowW,
                layout.windowH,
                5,
                WINDOW);
        drawHeader(draw, screen, font, layout, state);
        drawSidebar(draw, screen, font, layout);
        if (state.detail >= 0) {
            drawDetail(draw, screen, font, layout, state, mouseX, mouseY);
        } else {
            drawToolbar(draw, screen, font, layout, state);
            drawGrid(draw, screen, font, layout, state, mouseX, mouseY);
        }
    }

    public static void mouseClicked(Object screen, int mouseX, int mouseY, int button) {
        if (button != 0) {
            return;
        }
        ensureSettingsLoaded();
        ScreenState state = state(screen);
        Layout layout = layout(screen);

        if (layout.contains(layout.closeX, layout.closeY, 24, 24, mouseX, mouseY)) {
            requestClose(screen);
            return;
        }

        if (state.detail >= 0) {
            handleDetailClick(state, layout, mouseX, mouseY);
            return;
        }

        if (layout.contains(layout.searchX, layout.searchY, layout.searchW, 22, mouseX, mouseY)) {
            state.searchFocused = true;
            return;
        }
        state.searchFocused = false;

        for (int category = 0; category < CATEGORY_LABELS.length; category++) {
            if (layout.contains(
                    layout.categoryX(category),
                    layout.toolbarY + 8,
                    CATEGORY_WIDTHS[category],
                    20,
                    mouseX,
                    mouseY)) {
                state.category = category;
                return;
            }
        }

        int[] visible = visibleUtilities(state);
        for (int position = 0; position < visible.length; position++) {
            int index = visible[position];
            CardBounds card = layout.card(position, visible.length);
            if (!card.contains(mouseX, mouseY)) {
                continue;
            }
            state.selected = index;
            if (mouseY >= card.statusY) {
                toggle(index);
            } else {
                state.detail = index;
            }
            return;
        }
    }

    public static void mouseDragged(Object screen, int mouseX, int mouseY, int button, long elapsed) {
        if (button != 0) {
            return;
        }
        ScreenState state = state(screen);
        if (state.detail >= 0 && state.dragging != 0) {
            updateSlider(state.detail, state.dragging, mouseX, layout(screen));
        }
    }

    public static void mouseReleased(Object screen, int mouseX, int mouseY, int button) {
        ScreenState state = state(screen);
        if (state.dragging != 0) {
            state.dragging = 0;
            saveSettings();
        }
    }

    public static void keyTyped(Object screen, char typed, int keyCode) {
        ScreenState state = state(screen);
        if (!state.searchFocused || state.detail >= 0) {
            return;
        }
        if (keyCode == 14 && !state.query.isEmpty()) {
            state.query = state.query.substring(0, state.query.length() - 1);
            return;
        }
        if (keyCode == 28) {
            state.searchFocused = false;
            return;
        }
        if (typed >= 32 && typed != 127 && state.query.length() < 32) {
            state.query += typed;
        }
    }

    /** Records a real left-click for the CPS widget. */
    public static void recordClick() {
        synchronized (CLICK_LOCK) {
            CLICK_TIMES[clickCursor++ % CLICK_TIMES.length] = System.nanoTime();
        }
    }

    /** Draws enabled widgets using live values from the current game instance. */
    public static void renderHud(Object guiIngame) throws ReflectiveOperationException {
        ensureSettingsLoaded();
        HudBindings draw = hudBindings;
        if (draw == null || draw.guiIngameClass != guiIngame.getClass()) {
            draw = HudBindings.create(guiIngame.getClass());
            hudBindings = draw;
        }
        Object minecraft = draw.minecraft.get(guiIngame);
        Object font = draw.font.get(minecraft);
        Object scaled = draw.scaledResolution.newInstance(minecraft);
        int screenW = ((Integer) draw.scaledWidth.invoke(scaled)).intValue();
        int screenH = ((Integer) draw.scaledHeight.invoke(scaled)).intValue();

        for (int index = 0; index < UTILITIES.length; index++) {
            Preference preference = copyPreference(index);
            if (!preference.enabled) {
                continue;
            }
            if (index == 5) {
                drawKeystrokes(draw, font, preference, screenW, screenH);
                continue;
            }
            String value = hudValue(index, draw, minecraft);
            if (value != null) {
                drawTextWidget(draw, font, value, preference, screenW, screenH);
            }
        }
    }

    private static void drawHeader(
            ScreenBindings draw,
            Object screen,
            Object font,
            Layout layout,
            ScreenState state) throws ReflectiveOperationException {
        draw.rounded(
                screen,
                layout.windowX,
                layout.windowY,
                layout.windowW,
                layout.headerH,
                5,
                HEADER);
        draw.rect(
                screen,
                layout.windowX,
                layout.windowY + layout.headerH - 1,
                layout.windowX + layout.windowW,
                layout.windowY + layout.headerH,
                HAIRLINE);

        if (!drawBrand(draw, layout.windowX + 9, layout.windowY, 152, layout.headerH)) {
            draw.scaledText(font, "OPUS CLIENT", layout.windowX + 14, layout.windowY + 17, 1.25f, TEXT);
        }

        int tabX = layout.contentX + 17;
        draw.rounded(screen, tabX, layout.windowY + 11, 58, 27, 3, 0x26FFFFFF);
        draw.centeredText(font, "MODS", tabX + 29, layout.windowY + 20, TEXT);
        draw.text(font, saveFailed ? "SAVE FAILED" : "SAVED", layout.closeX - 72,
                layout.windowY + 20, saveFailed ? 0xFFE1AAA4 : SUBTLE);

        boolean closeHover = layout.contains(
                layout.closeX, layout.closeY, 24, 24, state.mouseX, state.mouseY);
        draw.rounded(
                screen,
                layout.closeX,
                layout.closeY,
                24,
                24,
                4,
                closeHover ? 0x30FFFFFF : 0x1EFFFFFF);
        draw.text(font, "x", layout.closeX + 9, layout.closeY + 8, TEXT);
    }

    private static void drawSidebar(
            ScreenBindings draw,
            Object screen,
            Object font,
            Layout layout) throws ReflectiveOperationException {
        draw.rect(
                screen,
                layout.windowX,
                layout.contentY,
                layout.contentX,
                layout.windowY + layout.windowH,
                SIDEBAR);
        draw.rect(
                screen,
                layout.contentX - 1,
                layout.contentY,
                layout.contentX,
                layout.windowY + layout.windowH,
                HAIRLINE);

        int x = layout.windowX + 12;
        int width = layout.sidebarW - 24;
        draw.text(font, "PROFILE", x, layout.contentY + 14, SUBTLE);
        draw.rounded(screen, x, layout.contentY + 29, width, 29, 3, 0x24FFFFFF);
        draw.text(font, "Opus", x + 9, layout.contentY + 40, TEXT);
        draw.text(font, "HUD SET", x, layout.contentY + 77, SUBTLE);
        draw.rounded(screen, x, layout.contentY + 92, width, 29, 3, 0x15FFFFFF);
        draw.text(font, "Default", x + 9, layout.contentY + 103, MUTED);

        int footerY = layout.windowY + layout.windowH - 38;
        draw.rect(screen, x, footerY, x + width, footerY + 1, HAIRLINE);
        draw.text(font, "AUTOSAVE", x, footerY + 14, SUBTLE);
        draw.rightText(font, saveFailed ? "ERROR" : "ON", x + width, footerY + 14,
                saveFailed ? 0xFFE1AAA4 : TEXT);
    }

    private static void drawToolbar(
            ScreenBindings draw,
            Object screen,
            Object font,
            Layout layout,
            ScreenState state) throws ReflectiveOperationException {
        draw.rect(
                screen,
                layout.contentX,
                layout.toolbarY + layout.toolbarH - 1,
                layout.windowX + layout.windowW,
                layout.toolbarY + layout.toolbarH,
                HAIRLINE);

        for (int category = 0; category < CATEGORY_LABELS.length; category++) {
            int x = layout.categoryX(category);
            int width = CATEGORY_WIDTHS[category];
            boolean active = state.category == category;
            draw.rounded(
                    screen,
                    x,
                    layout.toolbarY + 8,
                    width,
                    20,
                    3,
                    active ? WHITE : 0x22FFFFFF);
            draw.centeredText(
                    font,
                    CATEGORY_LABELS[category],
                    x + width / 2,
                    layout.toolbarY + 14,
                    active ? 0xFF161A19 : MUTED);
        }

        if (layout.searchW > 0) {
            draw.rounded(
                    screen,
                    layout.searchX,
                    layout.searchY,
                    layout.searchW,
                    22,
                    3,
                    state.searchFocused ? 0x32FFFFFF : 0x1CFFFFFF);
            String value = state.query.isEmpty() ? "Search utilities" : state.query;
            draw.text(font, value, layout.searchX + 9, layout.searchY + 7,
                    state.query.isEmpty() ? SUBTLE : TEXT);
        }
    }

    private static void drawGrid(
            ScreenBindings draw,
            Object screen,
            Object font,
            Layout layout,
            ScreenState state,
            int mouseX,
            int mouseY) throws ReflectiveOperationException {
        state.mouseX = mouseX;
        state.mouseY = mouseY;
        int[] visible = visibleUtilities(state);
        if (visible.length == 0) {
            draw.centeredText(
                    font,
                    "No utilities match this search",
                    layout.gridX + layout.gridW / 2,
                    layout.gridY + 40,
                    MUTED);
            return;
        }

        for (int position = 0; position < visible.length; position++) {
            int index = visible[position];
            UtilityDefinition utility = UTILITIES[index];
            Preference preference = copyPreference(index);
            CardBounds card = layout.card(position, visible.length);
            boolean hovered = card.contains(mouseX, mouseY);

            draw.rounded(
                    screen,
                    card.x,
                    card.y,
                    card.width,
                    card.height,
                    5,
                    hovered ? CARD_HOVER : CARD);
            draw.outline(
                    screen,
                    card.x,
                    card.y,
                    card.width,
                    card.height,
                    hovered ? 0x32FFFFFF : 0x1FFFFFFF);

            draw.centeredScaledText(
                    font,
                    utility.icon,
                    card.x + card.width / 2,
                    card.y + Math.max(16, card.height / 7),
                    card.height >= 125 ? 1.5f : 1.15f,
                    TEXT);
            draw.centeredText(
                    font,
                    fit(draw, font, utility.name, card.width - 20),
                    card.x + card.width / 2,
                    card.y + card.height / 2 - 2,
                    TEXT);
            draw.centeredText(
                    font,
                    fit(draw, font, utility.description, card.width - 18),
                    card.x + card.width / 2,
                    card.y + card.height / 2 + 12,
                    MUTED);

            draw.rect(
                    screen,
                    card.x,
                    card.optionsY,
                    card.x + card.width,
                    card.optionsY + 1,
                    HAIRLINE);
            draw.centeredText(
                    font,
                    "OPTIONS",
                    card.x + card.width / 2,
                    card.optionsY + 7,
                    TEXT);

            int statusColor = preference.enabled ? WHITE : 0x2AFFFFFF;
            int statusText = preference.enabled ? 0xFF151918 : TEXT;
            draw.rounded(
                    screen,
                    card.x + 1,
                    card.statusY,
                    card.width - 2,
                    card.statusH,
                    4,
                    statusColor);
            draw.centeredText(
                    font,
                    preference.enabled ? "ENABLED" : "DISABLED",
                    card.x + card.width / 2,
                    card.statusY + Math.max(6, card.statusH / 2 - 4),
                    statusText);
        }
    }

    private static void drawDetail(
            ScreenBindings draw,
            Object screen,
            Object font,
            Layout layout,
            ScreenState state,
            int mouseX,
            int mouseY) throws ReflectiveOperationException {
        state.mouseX = mouseX;
        state.mouseY = mouseY;
        UtilityDefinition utility = UTILITIES[state.detail];
        Preference preference = copyPreference(state.detail);
        int x = layout.detailX;
        int right = layout.detailX + layout.detailW;

        draw.text(font, "<  ALL UTILITIES", x, layout.detailY, MUTED);
        draw.scaledText(font, utility.name, x, layout.detailY + 27, 1.45f, TEXT);
        draw.text(font, utility.description, x, layout.detailY + 49, MUTED);
        drawToggle(draw, screen, right - 34, layout.detailY + 25, preference.enabled);

        int divider = layout.detailY + 69;
        draw.rect(screen, x, divider, right, divider + 1, HAIRLINE);
        draw.text(font, "PLACEMENT", x, divider + 15, SUBTLE);

        draw.text(font, "Anchor", x, divider + 37, MUTED);
        draw.rounded(screen, right - 104, divider + 28, 104, 22, 3, CONTROL);
        draw.centeredText(font, ANCHOR_LABELS[preference.anchor], right - 52, divider + 35, TEXT);

        draw.text(font, "Offset X", x, divider + 64, MUTED);
        drawStepper(draw, screen, font, right - 104, divider + 55, preference.offsetX);
        draw.text(font, "Offset Y", x, divider + 91, MUTED);
        drawStepper(draw, screen, font, right - 104, divider + 82, preference.offsetY);

        int appearance = divider + 119;
        draw.rect(screen, x, appearance, right, appearance + 1, HAIRLINE);
        draw.text(font, "APPEARANCE", x, appearance + 15, SUBTLE);
        draw.text(font, "Scale", x, appearance + 38, MUTED);
        draw.rightText(font, preference.scale + "%", right, appearance + 38, TEXT);
        drawSlider(
                draw,
                screen,
                layout.scaleX,
                layout.scaleY,
                layout.sliderW,
                (preference.scale - 50) / 100.0f);
        draw.text(font, "Opacity", x, appearance + 73, MUTED);
        draw.rightText(font, preference.opacity + "%", right, appearance + 73, TEXT);
        drawSlider(
                draw,
                screen,
                layout.opacityX,
                layout.opacityY,
                layout.sliderW,
                (preference.opacity - 25) / 75.0f);

        draw.text(font, "RESET UTILITY", x, layout.resetY + 5, MUTED);
        draw.rect(screen, x, layout.resetY + 15,
                x + draw.width(font, "RESET UTILITY"), layout.resetY + 16, HAIRLINE);

        int footer = layout.windowY + layout.windowH - 32;
        draw.rect(screen, x, footer, right, footer + 1, HAIRLINE);
        draw.text(font, "Changes apply live in game", x, footer + 12, MUTED);
        draw.rightText(font, saveFailed ? "SAVE FAILED" : "AUTOSAVED", right, footer + 12,
                saveFailed ? 0xFFE1AAA4 : TEXT);
    }

    private static void drawStepper(
            ScreenBindings draw,
            Object screen,
            Object font,
            int x,
            int y,
            int value) throws ReflectiveOperationException {
        draw.rounded(screen, x, y, 104, 22, 3, CONTROL);
        draw.centeredText(font, "-", x + 13, y + 7, TEXT);
        draw.centeredText(font, Integer.toString(value), x + 52, y + 7, TEXT);
        draw.centeredText(font, "+", x + 91, y + 7, TEXT);
    }

    private static void drawToggle(
            ScreenBindings draw,
            Object screen,
            int x,
            int y,
            boolean enabled) throws ReflectiveOperationException {
        draw.rounded(screen, x, y, 34, 17, 8, enabled ? WHITE : OFF);
        draw.rounded(
                screen,
                enabled ? x + 20 : x + 3,
                y + 3,
                11,
                11,
                5,
                enabled ? 0xFF171B1A : 0xFFD8DDDA);
    }

    private static void drawSlider(
            ScreenBindings draw,
            Object screen,
            int x,
            int y,
            int width,
            float fraction) throws ReflectiveOperationException {
        int filled = Math.max(0, Math.min(width, Math.round(width * fraction)));
        draw.rect(screen, x, y, x + width, y + 2, TRACK);
        draw.rect(screen, x, y, x + filled, y + 2, WHITE);
        draw.rounded(screen, x + filled - 3, y - 3, 7, 8, 3, WHITE);
    }

    private static void handleDetailClick(ScreenState state, Layout layout, int mouseX, int mouseY) {
        if (layout.contains(layout.detailX, layout.detailY - 5, 110, 18, mouseX, mouseY)) {
            state.detail = -1;
            return;
        }
        int selected = state.detail;
        int right = layout.detailX + layout.detailW;
        int divider = layout.detailY + 69;
        if (layout.contains(right - 40, layout.detailY + 20, 40, 26, mouseX, mouseY)) {
            toggle(selected);
            return;
        }
        if (layout.contains(right - 104, divider + 28, 104, 22, mouseX, mouseY)) {
            synchronized (SETTINGS_LOCK) {
                PREFERENCES[selected].anchor = (PREFERENCES[selected].anchor + 1) % ANCHORS.length;
            }
            saveSettings();
            return;
        }
        if (handleOffsetClick(selected, right - 104, divider + 55, mouseX, mouseY, true)
                || handleOffsetClick(selected, right - 104, divider + 82, mouseX, mouseY, false)) {
            saveSettings();
            return;
        }
        if (layout.contains(layout.scaleX, layout.scaleY - 6, layout.sliderW, 15, mouseX, mouseY)) {
            state.dragging = 1;
            updateSlider(selected, 1, mouseX, layout);
            return;
        }
        if (layout.contains(
                layout.opacityX, layout.opacityY - 6, layout.sliderW, 15, mouseX, mouseY)) {
            state.dragging = 2;
            updateSlider(selected, 2, mouseX, layout);
            return;
        }
        if (layout.contains(layout.detailX, layout.resetY, 95, 20, mouseX, mouseY)) {
            reset(selected);
        }
    }

    private static boolean handleOffsetClick(
            int selected,
            int x,
            int y,
            int mouseX,
            int mouseY,
            boolean horizontal) {
        if (mouseY < y || mouseY >= y + 22) {
            return false;
        }
        int delta;
        if (mouseX >= x && mouseX < x + 27) {
            delta = -1;
        } else if (mouseX >= x + 77 && mouseX < x + 104) {
            delta = 1;
        } else {
            return false;
        }
        synchronized (SETTINGS_LOCK) {
            Preference preference = PREFERENCES[selected];
            if (horizontal) {
                preference.offsetX = clamp(preference.offsetX + delta, 0, 999);
            } else {
                preference.offsetY = clamp(preference.offsetY + delta, 0, 999);
            }
        }
        return true;
    }

    private static void toggle(int index) {
        synchronized (SETTINGS_LOCK) {
            PREFERENCES[index].enabled = !PREFERENCES[index].enabled;
        }
        saveSettings();
    }

    private static void updateSlider(int utility, int slider, int mouseX, Layout layout) {
        float fraction;
        synchronized (SETTINGS_LOCK) {
            if (slider == 1) {
                fraction = clampFraction(mouseX, layout.scaleX, layout.sliderW);
                PREFERENCES[utility].scale = 50 + Math.round(fraction * 100.0f);
            } else {
                fraction = clampFraction(mouseX, layout.opacityX, layout.sliderW);
                PREFERENCES[utility].opacity = 25 + Math.round(fraction * 75.0f);
            }
        }
    }

    private static float clampFraction(int mouseX, int start, int width) {
        return Math.max(0.0f, Math.min(1.0f, (mouseX - start) / (float) width));
    }

    private static int[] visibleUtilities(ScreenState state) {
        int count = 0;
        for (int index = 0; index < UTILITIES.length; index++) {
            if (isVisible(index, state)) {
                count++;
            }
        }
        int[] visible = new int[count];
        int position = 0;
        for (int index = 0; index < UTILITIES.length; index++) {
            if (isVisible(index, state)) {
                visible[position++] = index;
            }
        }
        return visible;
    }

    private static boolean isVisible(int index, ScreenState state) {
        UtilityDefinition utility = UTILITIES[index];
        if (state.category != ALL && utility.category != state.category) {
            return false;
        }
        String query = state.query.trim().toLowerCase(Locale.ROOT);
        return query.isEmpty()
                || (utility.name + " " + utility.description)
                        .toLowerCase(Locale.ROOT)
                        .contains(query);
    }

    private static String fit(ScreenBindings draw, Object font, String value, int maxWidth)
            throws ReflectiveOperationException {
        if (draw.width(font, value) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        String result = value;
        while (!result.isEmpty() && draw.width(font, result + suffix) > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + suffix;
    }

    private static ScreenState state(Object screen) {
        ScreenState state = STATES.get(screen);
        if (state == null) {
            state = new ScreenState();
            STATES.put(screen, state);
        }
        return state;
    }

    private static Layout layout(Object screen) {
        try {
            ScreenBindings draw = bindingsFor(screen);
            return Layout.forScreen(draw.width.getInt(screen), draw.height.getInt(screen));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Opus config layout is unavailable", failure);
        }
    }

    private static ScreenBindings bindingsFor(Object screen) throws ReflectiveOperationException {
        ScreenBindings draw = screenBindings;
        if (draw == null || draw.screenClass != screen.getClass()) {
            draw = ScreenBindings.create(screen.getClass());
            screenBindings = draw;
        }
        return draw;
    }

    private static void requestClose(Object screen) {
        try {
            screen.getClass().getMethod("opusClose").invoke(screen);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Opus config screen cannot close", failure);
        }
    }

    private static void tryApplyBlur(Object screen, ScreenState state) {
        try {
            BlurBindings blur = blurBindings;
            if (blur == null || blur.screenClass != screen.getClass()) {
                blur = BlurBindings.create(screen.getClass());
                blurBindings = blur;
            }
            Object minecraft = blur.minecraft.get(screen);
            Object renderer = blur.renderer.get(minecraft);
            boolean active = ((Boolean) blur.shaderActive.invoke(renderer)).booleanValue();
            if (!active) {
                Object resource = blur.resource.newInstance("shaders/post/blur.json");
                blur.loadShader.invoke(renderer, resource);
                state.blurApplied = true;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            state.blurApplied = false;
        }
    }

    private static void tryRemoveBlur(Object screen) {
        try {
            BlurBindings blur = blurBindings;
            if (blur == null || blur.screenClass != screen.getClass()) {
                return;
            }
            Object minecraft = blur.minecraft.get(screen);
            blur.stopShader.invoke(blur.renderer.get(minecraft));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The screen is already closing; a blur cleanup failure must not trap it.
        }
    }

    private static boolean drawBrand(ScreenBindings draw, int x, int y, int width, int height) {
        if (brandTextureFailed) {
            return false;
        }
        try {
            BrandTexture texture = brandTexture;
            if (texture == null) {
                texture = BrandTexture.load(draw.gl);
                brandTexture = texture;
            }
            texture.draw(draw.gl, x, y, width, height);
            return true;
        } catch (IOException | ReflectiveOperationException | RuntimeException failure) {
            brandTextureFailed = true;
            return false;
        }
    }

    private static void drawTextWidget(
            HudBindings draw,
            Object font,
            String value,
            Preference preference,
            int screenW,
            int screenH) throws ReflectiveOperationException {
        float scale = preference.scale / 100.0f;
        int baseWidth = draw.width(font, value);
        int width = Math.round(baseWidth * scale);
        int height = Math.round(9 * scale);
        int[] position = anchored(preference, screenW, screenH, width, height);
        int color = alphaColor(preference.opacity, 0xF4F6F3);

        draw.gl.push();
        draw.gl.scale(scale, scale, 1.0f);
        draw.text(
                font,
                value,
                Math.round(position[0] / scale),
                Math.round(position[1] / scale),
                color);
        draw.gl.pop();
    }

    private static void drawKeystrokes(
            HudBindings draw,
            Object font,
            Preference preference,
            int screenW,
            int screenH) throws ReflectiveOperationException {
        float scale = preference.scale / 100.0f;
        int width = Math.round(35 * scale);
        int height = Math.round(23 * scale);
        int[] position = anchored(preference, screenW, screenH, width, height);
        float x = position[0] / scale;
        float y = position[1] / scale;
        int background = alphaColor(Math.max(25, preference.opacity * 55 / 100), 0x111514);
        int active = alphaColor(preference.opacity, 0xF3F5F2);
        int inactive = alphaColor(preference.opacity, 0x4E5652);

        draw.gl.push();
        draw.gl.scale(scale, scale, 1.0f);
        drawKey(draw, font, x + 12, y, "W", draw.keyDown(17), background, active, inactive);
        drawKey(draw, font, x, y + 12, "A", draw.keyDown(30), background, active, inactive);
        drawKey(draw, font, x + 12, y + 12, "S", draw.keyDown(31), background, active, inactive);
        drawKey(draw, font, x + 24, y + 12, "D", draw.keyDown(32), background, active, inactive);
        draw.gl.pop();
    }

    private static void drawKey(
            HudBindings draw,
            Object font,
            float x,
            float y,
            String label,
            boolean pressed,
            int background,
            int active,
            int inactive) throws ReflectiveOperationException {
        draw.gl.rect(x, y, 11, 11, pressed ? active : background);
        draw.text(font, label, Math.round(x + 3), Math.round(y + 2), pressed ? 0xFF151918 : inactive);
    }

    private static String hudValue(int index, HudBindings draw, Object minecraft)
            throws ReflectiveOperationException {
        switch (index) {
            case 0:
                return draw.fps.invoke(null) + " FPS";
            case 1:
                return recentClicks() + " CPS";
            case 2:
                Runtime runtime = Runtime.getRuntime();
                long used = runtime.totalMemory() - runtime.freeMemory();
                return used / (1024L * 1024L) + " MB";
            case 3:
                Object player = draw.player.get(minecraft);
                if (player == null) {
                    return null;
                }
                return "XYZ "
                        + floor(draw.positionX.getDouble(player)) + " / "
                        + floor(draw.positionY.getDouble(player)) + " / "
                        + floor(draw.positionZ.getDouble(player));
            case 4:
                return LocalTime.now().format(CLOCK_FORMAT);
            default:
                return null;
        }
    }

    private static int recentClicks() {
        long threshold = System.nanoTime() - 1_000_000_000L;
        int clicks = 0;
        synchronized (CLICK_LOCK) {
            for (long time : CLICK_TIMES) {
                if (time >= threshold) {
                    clicks++;
                }
            }
        }
        return clicks;
    }

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static int[] anchored(
            Preference preference,
            int screenW,
            int screenH,
            int width,
            int height) {
        int x = preference.offsetX;
        int y = preference.offsetY;
        if (preference.anchor == 1 || preference.anchor == 3) {
            x = screenW - preference.offsetX - width;
        }
        if (preference.anchor == 2 || preference.anchor == 3) {
            y = screenH - preference.offsetY - height;
        }
        return new int[] {Math.max(0, x), Math.max(0, y)};
    }

    private static int alphaColor(int opacity, int rgb) {
        return clamp(opacity * 255 / 100, 4, 255) << 24 | rgb & 0x00FFFFFF;
    }

    private static Preference copyPreference(int index) {
        synchronized (SETTINGS_LOCK) {
            return PREFERENCES[index].copy();
        }
    }

    private static void reset(int index) {
        Preference[] defaults = defaultPreferences();
        synchronized (SETTINGS_LOCK) {
            PREFERENCES[index] = defaults[index];
        }
        saveSettings();
    }

    private static Preference[] defaultPreferences() {
        return new Preference[] {
                new Preference(true, 0, 12, 12, 100, 100),
                new Preference(false, 0, 12, 26, 100, 100),
                new Preference(false, 1, 12, 12, 100, 100),
                new Preference(false, 2, 12, 28, 100, 100),
                new Preference(false, 1, 12, 26, 100, 100),
                new Preference(false, 3, 12, 28, 100, 100)
        };
    }

    private static void ensureSettingsLoaded() {
        if (settingsLoaded) {
            return;
        }
        synchronized (SETTINGS_LOCK) {
            if (settingsLoaded) {
                return;
            }
            Path path = settingsPath();
            if (Files.isRegularFile(path)) {
                try {
                    String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    for (int index = 0; index < UTILITIES.length; index++) {
                        String id = UTILITIES[index].id;
                        String object = objectFor(json, id);
                        if (object == null && index == 0) {
                            object = objectFor(json, "performance-overlay");
                        }
                        if (object != null) {
                            readPreference(object, PREFERENCES[index]);
                        }
                    }
                } catch (IOException | RuntimeException failure) {
                    saveFailed = true;
                    System.err.println("[OPUS/UI] could not read utility settings: "
                            + failure.getClass().getSimpleName());
                }
            }
            settingsLoaded = true;
        }
    }

    private static void readPreference(String object, Preference target) {
        target.enabled = booleanFor(object, "enabled", target.enabled);
        String anchor = stringFor(object, "anchor", ANCHORS[target.anchor]);
        for (int index = 0; index < ANCHORS.length; index++) {
            if (ANCHORS[index].equals(anchor)) {
                target.anchor = index;
                break;
            }
        }
        String offset = stringFor(object, "offset", target.offsetX + " · " + target.offsetY);
        int[] values = parseOffset(offset, target.offsetX, target.offsetY);
        target.offsetX = values[0];
        target.offsetY = values[1];
        target.scale = clamp(integerFor(object, "scale", target.scale), 50, 150);
        target.opacity = clamp(integerFor(object, "opacity", target.opacity), 25, 100);
    }

    private static String objectFor(String json, String id) {
        int key = json.indexOf('"' + id + '"');
        if (key < 0) {
            return null;
        }
        int start = json.indexOf('{', key);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean string = false;
        boolean escaped = false;
        for (int index = start; index < json.length(); index++) {
            char value = json.charAt(index);
            if (string) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    string = false;
                }
                continue;
            }
            if (value == '"') {
                string = true;
            } else if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return json.substring(start, index + 1);
            }
        }
        return null;
    }

    private static boolean booleanFor(String object, String key, boolean fallback) {
        int value = valueStart(object, key);
        if (value >= 0 && object.startsWith("true", value)) {
            return true;
        }
        if (value >= 0 && object.startsWith("false", value)) {
            return false;
        }
        return fallback;
    }

    private static int integerFor(String object, String key, int fallback) {
        int value = valueStart(object, key);
        if (value < 0) {
            return fallback;
        }
        int end = value;
        while (end < object.length()
                && (object.charAt(end) == '-' || Character.isDigit(object.charAt(end)))) {
            end++;
        }
        try {
            return Integer.parseInt(object.substring(value, end));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String stringFor(String object, String key, String fallback) {
        int value = valueStart(object, key);
        if (value < 0 || value >= object.length() || object.charAt(value) != '"') {
            return fallback;
        }
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int index = value + 1; index < object.length(); index++) {
            char current = object.charAt(index);
            if (escaped) {
                result.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                return result.toString();
            } else {
                result.append(current);
            }
        }
        return fallback;
    }

    private static int valueStart(String object, String key) {
        int found = object.indexOf('"' + key + '"');
        if (found < 0) {
            return -1;
        }
        int colon = object.indexOf(':', found);
        if (colon < 0) {
            return -1;
        }
        int value = colon + 1;
        while (value < object.length() && Character.isWhitespace(object.charAt(value))) {
            value++;
        }
        return value;
    }

    private static int[] parseOffset(String value, int fallbackX, int fallbackY) {
        String normalized = value.replace('·', ',').replace('/', ',');
        String[] parts = normalized.split(",");
        if (parts.length != 2) {
            return new int[] {fallbackX, fallbackY};
        }
        try {
            return new int[] {
                    clamp(Integer.parseInt(parts[0].trim()), 0, 999),
                    clamp(Integer.parseInt(parts[1].trim()), 0, 999)
            };
        } catch (NumberFormatException ignored) {
            return new int[] {fallbackX, fallbackY};
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Path settingsPath() {
        String configured = System.getProperty("opus.utility.settings.file");
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".opus-client", "utility-settings-v1.json")
                .toAbsolutePath()
                .normalize();
    }

    private static void saveSettings() {
        ensureSettingsLoaded();
        synchronized (SETTINGS_LOCK) {
            Path path = settingsPath();
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path temporary = path.resolveSibling(path.getFileName().toString() + ".part");
                Files.write(
                        temporary,
                        settingsJson().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                try {
                    Files.move(
                            temporary,
                            path,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
                saveFailed = false;
            } catch (IOException | RuntimeException failure) {
                saveFailed = true;
                System.err.println("[OPUS/UI] could not save utility settings: "
                        + failure.getClass().getSimpleName());
            }
        }
    }

    private static String settingsJson() {
        StringBuilder json = new StringBuilder(1024);
        json.append("{\n  \"utilities\": {\n");
        for (int index = 0; index < UTILITIES.length; index++) {
            Preference preference = PREFERENCES[index];
            json.append("    \"").append(UTILITIES[index].id).append("\": {\n")
                    .append("      \"enabled\": ").append(preference.enabled).append(",\n")
                    .append("      \"anchor\": \"").append(ANCHORS[preference.anchor]).append("\",\n")
                    .append("      \"offset\": \"")
                    .append(preference.offsetX).append(" · ").append(preference.offsetY).append("\",\n")
                    .append("      \"scale\": ").append(preference.scale).append(",\n")
                    .append("      \"opacity\": ").append(preference.opacity).append("\n")
                    .append("    }");
            json.append(index + 1 == UTILITIES.length ? '\n' : ",\n");
        }
        json.append("  }\n}\n");
        return json.toString();
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static final class UtilityDefinition {
        private final String id;
        private final String name;
        private final String description;
        private final String icon;
        private final int category;

        private UtilityDefinition(
                String id,
                String name,
                String description,
                String icon,
                int category) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.icon = icon;
            this.category = category;
        }
    }

    private static final class Preference {
        private boolean enabled;
        private int anchor;
        private int offsetX;
        private int offsetY;
        private int scale;
        private int opacity;

        private Preference(
                boolean enabled,
                int anchor,
                int offsetX,
                int offsetY,
                int scale,
                int opacity) {
            this.enabled = enabled;
            this.anchor = anchor;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.scale = scale;
            this.opacity = opacity;
        }

        private Preference copy() {
            return new Preference(enabled, anchor, offsetX, offsetY, scale, opacity);
        }
    }

    private static final class ScreenState {
        private int category;
        private int selected;
        private int detail = -1;
        private int dragging;
        private int mouseX;
        private int mouseY;
        private boolean searchFocused;
        private boolean blurApplied;
        private String query = "";
    }

    private static final class CardBounds {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final int optionsY;
        private final int statusY;
        private final int statusH;

        private CardBounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            statusH = Math.max(19, Math.min(25, height / 5));
            statusY = y + height - statusH;
            optionsY = statusY - Math.max(19, Math.min(25, height / 5));
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private static final class Layout {
        private final int screenW;
        private final int screenH;
        private final int windowX;
        private final int windowY;
        private final int windowW;
        private final int windowH;
        private final int headerH;
        private final int sidebarW;
        private final int contentX;
        private final int contentY;
        private final int contentW;
        private final int toolbarY;
        private final int toolbarH;
        private final int searchX;
        private final int searchY;
        private final int searchW;
        private final int gridX;
        private final int gridY;
        private final int gridW;
        private final int gridH;
        private final int closeX;
        private final int closeY;
        private final int detailX;
        private final int detailY;
        private final int detailW;
        private final int scaleX;
        private final int scaleY;
        private final int opacityX;
        private final int opacityY;
        private final int sliderW;
        private final int resetY;

        private Layout(int width, int height) {
            screenW = width;
            screenH = height;
            windowW = Math.min(820, width - 24);
            windowH = Math.min(500, height - 24);
            windowX = (width - windowW) / 2;
            windowY = (height - windowH) / 2;
            headerH = 48;
            sidebarW = Math.max(132, Math.min(178, windowW / 4));
            contentX = windowX + sidebarW;
            contentY = windowY + headerH;
            contentW = windowW - sidebarW;
            toolbarY = contentY;
            toolbarH = 37;
            closeX = windowX + windowW - 34;
            closeY = windowY + 12;

            int categoryEnd = categoryX(CATEGORY_LABELS.length - 1)
                    + CATEGORY_WIDTHS[CATEGORY_LABELS.length - 1];
            int availableSearch = windowX + windowW - 10 - categoryEnd - 10;
            searchW = availableSearch >= 90 ? Math.min(146, availableSearch) : 0;
            searchX = windowX + windowW - 10 - searchW;
            searchY = toolbarY + 7;
            gridX = contentX + 9;
            gridY = toolbarY + toolbarH + 8;
            gridW = contentW - 18;
            gridH = windowY + windowH - gridY - 9;

            detailX = contentX + 24;
            detailY = contentY + 18;
            detailW = contentW - 48;
            sliderW = Math.max(130, detailW - 8);
            scaleX = detailX + 4;
            scaleY = detailY + 231;
            opacityX = scaleX;
            opacityY = detailY + 266;
            resetY = detailY + 286;
        }

        private static Layout forScreen(int width, int height) {
            return new Layout(width, height);
        }

        private int categoryX(int category) {
            int x = contentX + 9;
            for (int index = 0; index < category; index++) {
                x += CATEGORY_WIDTHS[index] + 5;
            }
            return x;
        }

        private CardBounds card(int position, int visibleCount) {
            int columns = gridW >= 500 ? 3 : 2;
            int rows = Math.max(1, (visibleCount + columns - 1) / columns);
            int gap = 7;
            int width = (gridW - gap * (columns - 1)) / columns;
            int height = (gridH - gap * (rows - 1)) / rows;
            int column = position % columns;
            int row = position / columns;
            return new CardBounds(
                    gridX + column * (width + gap),
                    gridY + row * (height + gap),
                    width,
                    height);
        }

        private boolean contains(int x, int y, int width, int height, int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private static final class ScreenBindings {
        private final Class<?> screenClass;
        private final Field width;
        private final Field height;
        private final Field font;
        private final Method drawGradient;
        private final Method drawString;
        private final Method stringWidth;
        private final GlBindings gl;

        private ScreenBindings(
                Class<?> screenClass,
                Field width,
                Field height,
                Field font,
                Method drawGradient,
                Method drawString,
                Method stringWidth,
                GlBindings gl) {
            this.screenClass = screenClass;
            this.width = width;
            this.height = height;
            this.font = font;
            this.drawGradient = drawGradient;
            this.drawString = drawString;
            this.stringWidth = stringWidth;
            this.gl = gl;
        }

        private static ScreenBindings create(Class<?> screenClass) throws ReflectiveOperationException {
            Field width = findField(screenClass, "l");
            Field height = findField(screenClass, "m");
            Field font = findField(screenClass, "q");
            Method drawGradient = findMethod(
                    screenClass,
                    "a",
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE);
            Method drawString = font.getType().getMethod(
                    "a", String.class, Integer.TYPE, Integer.TYPE, Integer.TYPE);
            Method stringWidth = font.getType().getMethod("a", String.class);
            GlBindings gl = GlBindings.create(screenClass.getClassLoader());
            return new ScreenBindings(
                    screenClass, width, height, font, drawGradient, drawString, stringWidth, gl);
        }

        private void rect(Object screen, int left, int top, int right, int bottom, int color)
                throws ReflectiveOperationException {
            drawGradient.invoke(screen, left, top, right, bottom, color, color);
        }

        private void rounded(Object screen, int x, int y, int width, int height, int radius, int color)
                throws ReflectiveOperationException {
            if (radius <= 0 || width <= radius * 2 || height <= radius * 2) {
                rect(screen, x, y, x + width, y + height, color);
                return;
            }
            rect(screen, x + radius, y, x + width - radius, y + height, color);
            rect(screen, x, y + radius, x + width, y + height - radius, color);
            for (int inset = 1; inset < radius; inset++) {
                rect(
                        screen,
                        x + radius - inset,
                        y + inset,
                        x + width - radius + inset,
                        y + height - inset,
                        color);
            }
        }

        private void outline(Object screen, int x, int y, int width, int height, int color)
                throws ReflectiveOperationException {
            rect(screen, x + 3, y, x + width - 3, y + 1, color);
            rect(screen, x + 3, y + height - 1, x + width - 3, y + height, color);
            rect(screen, x, y + 3, x + 1, y + height - 3, color);
            rect(screen, x + width - 1, y + 3, x + width, y + height - 3, color);
        }

        private void text(Object font, String value, int x, int y, int color)
                throws ReflectiveOperationException {
            drawString.invoke(font, value, x, y, color);
        }

        private void centeredText(Object font, String value, int centerX, int y, int color)
                throws ReflectiveOperationException {
            text(font, value, centerX - width(font, value) / 2, y, color);
        }

        private void rightText(Object font, String value, int right, int y, int color)
                throws ReflectiveOperationException {
            text(font, value, right - width(font, value), y, color);
        }

        private int width(Object font, String value) throws ReflectiveOperationException {
            return ((Integer) stringWidth.invoke(font, value)).intValue();
        }

        private void scaledText(
                Object font,
                String value,
                int x,
                int y,
                float scale,
                int color) throws ReflectiveOperationException {
            gl.push();
            gl.scale(scale, scale, 1.0f);
            text(font, value, Math.round(x / scale), Math.round(y / scale), color);
            gl.pop();
        }

        private void centeredScaledText(
                Object font,
                String value,
                int centerX,
                int y,
                float scale,
                int color) throws ReflectiveOperationException {
            int scaledWidth = Math.round(width(font, value) * scale);
            scaledText(font, value, centerX - scaledWidth / 2, y, scale, color);
        }
    }

    private static final class HudBindings {
        private final Class<?> guiIngameClass;
        private final Field minecraft;
        private final Field font;
        private final Field player;
        private final Field positionX;
        private final Field positionY;
        private final Field positionZ;
        private final Constructor<?> scaledResolution;
        private final Method scaledWidth;
        private final Method scaledHeight;
        private final Method fps;
        private final Method drawString;
        private final Method stringWidth;
        private final Method keyDown;
        private final GlBindings gl;

        private HudBindings(
                Class<?> guiIngameClass,
                Field minecraft,
                Field font,
                Field player,
                Field positionX,
                Field positionY,
                Field positionZ,
                Constructor<?> scaledResolution,
                Method scaledWidth,
                Method scaledHeight,
                Method fps,
                Method drawString,
                Method stringWidth,
                Method keyDown,
                GlBindings gl) {
            this.guiIngameClass = guiIngameClass;
            this.minecraft = minecraft;
            this.font = font;
            this.player = player;
            this.positionX = positionX;
            this.positionY = positionY;
            this.positionZ = positionZ;
            this.scaledResolution = scaledResolution;
            this.scaledWidth = scaledWidth;
            this.scaledHeight = scaledHeight;
            this.fps = fps;
            this.drawString = drawString;
            this.stringWidth = stringWidth;
            this.keyDown = keyDown;
            this.gl = gl;
        }

        private static HudBindings create(Class<?> guiIngameClass) throws ReflectiveOperationException {
            ClassLoader loader = guiIngameClass.getClassLoader();
            Field minecraft = findField(guiIngameClass, "j");
            Class<?> minecraftClass = minecraft.getType();
            Field font = findField(minecraftClass, "k");
            Field player = findField(minecraftClass, "h");
            Field positionX = findField(player.getType(), "p");
            Field positionY = findField(player.getType(), "q");
            Field positionZ = findField(player.getType(), "r");
            Class<?> scaledClass = Class.forName("avr", false, loader);
            Constructor<?> scaledResolution = scaledClass.getConstructor(minecraftClass);
            Method scaledWidth = scaledClass.getMethod("a");
            Method scaledHeight = scaledClass.getMethod("b");
            Method fps = minecraftClass.getMethod("C");
            Method drawString = font.getType().getMethod(
                    "a", String.class, Integer.TYPE, Integer.TYPE, Integer.TYPE);
            Method stringWidth = font.getType().getMethod("a", String.class);
            Class<?> keyboard = Class.forName("org.lwjgl.input.Keyboard", false, loader);
            Method keyDown = keyboard.getMethod("isKeyDown", Integer.TYPE);
            return new HudBindings(
                    guiIngameClass,
                    minecraft,
                    font,
                    player,
                    positionX,
                    positionY,
                    positionZ,
                    scaledResolution,
                    scaledWidth,
                    scaledHeight,
                    fps,
                    drawString,
                    stringWidth,
                    keyDown,
                    GlBindings.create(loader));
        }

        private void text(Object font, String value, int x, int y, int color)
                throws ReflectiveOperationException {
            drawString.invoke(font, value, x, y, color);
        }

        private int width(Object font, String value) throws ReflectiveOperationException {
            return ((Integer) stringWidth.invoke(font, value)).intValue();
        }

        private boolean keyDown(int key) throws ReflectiveOperationException {
            return ((Boolean) keyDown.invoke(null, key)).booleanValue();
        }
    }

    private static final class BlurBindings {
        private final Class<?> screenClass;
        private final Field minecraft;
        private final Field renderer;
        private final Constructor<?> resource;
        private final Method shaderActive;
        private final Method loadShader;
        private final Method stopShader;

        private BlurBindings(
                Class<?> screenClass,
                Field minecraft,
                Field renderer,
                Constructor<?> resource,
                Method shaderActive,
                Method loadShader,
                Method stopShader) {
            this.screenClass = screenClass;
            this.minecraft = minecraft;
            this.renderer = renderer;
            this.resource = resource;
            this.shaderActive = shaderActive;
            this.loadShader = loadShader;
            this.stopShader = stopShader;
        }

        private static BlurBindings create(Class<?> screenClass) throws ReflectiveOperationException {
            ClassLoader loader = screenClass.getClassLoader();
            Field minecraft = findField(screenClass, "j");
            Field renderer = findField(minecraft.getType(), "o");
            Class<?> resourceClass = Class.forName("jy", false, loader);
            Constructor<?> resource = resourceClass.getConstructor(String.class);
            Method shaderActive = findMethod(renderer.getType(), "a");
            Method loadShader = findMethod(renderer.getType(), "a", resourceClass);
            Method stopShader = findMethod(renderer.getType(), "b");
            return new BlurBindings(
                    screenClass, minecraft, renderer, resource, shaderActive, loadShader, stopShader);
        }
    }

    private static final class BrandTexture {
        private final int textureId;

        private BrandTexture(int textureId) {
            this.textureId = textureId;
        }

        private static BrandTexture load(GlBindings gl)
                throws IOException, ReflectiveOperationException {
            String configured = System.getProperty("opus.brand.wordmark.file");
            if (configured == null || configured.trim().isEmpty()) {
                throw new IOException("brand wordmark path is unavailable");
            }
            BufferedImage image = ImageIO.read(Paths.get(configured).toFile());
            if (image == null) {
                throw new IOException("brand wordmark is not a readable image");
            }
            ByteBuffer pixels = ByteBuffer
                    .allocateDirect(image.getWidth() * image.getHeight() * 4)
                    .order(ByteOrder.nativeOrder());
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    pixels.put((byte) (argb >> 16 & 0xff));
                    pixels.put((byte) (argb >> 8 & 0xff));
                    pixels.put((byte) (argb & 0xff));
                    pixels.put((byte) (argb >> 24 & 0xff));
                }
            }
            pixels.flip();
            return new BrandTexture(gl.uploadTexture(image.getWidth(), image.getHeight(), pixels));
        }

        private void draw(GlBindings gl, int x, int y, int width, int height)
                throws ReflectiveOperationException {
            gl.texture(textureId, x, y, width, height);
        }
    }

    private static final class GlBindings {
        private final Method pushMatrix;
        private final Method popMatrix;
        private final Method scale;
        private final Method enable;
        private final Method disable;
        private final Method blendFunc;
        private final Method color;
        private final Method begin;
        private final Method end;
        private final Method vertex;
        private final Method texCoord;
        private final Method generateTexture;
        private final Method bindTexture;
        private final Method textureParameter;
        private final Method textureImage;

        private GlBindings(
                Method pushMatrix,
                Method popMatrix,
                Method scale,
                Method enable,
                Method disable,
                Method blendFunc,
                Method color,
                Method begin,
                Method end,
                Method vertex,
                Method texCoord,
                Method generateTexture,
                Method bindTexture,
                Method textureParameter,
                Method textureImage) {
            this.pushMatrix = pushMatrix;
            this.popMatrix = popMatrix;
            this.scale = scale;
            this.enable = enable;
            this.disable = disable;
            this.blendFunc = blendFunc;
            this.color = color;
            this.begin = begin;
            this.end = end;
            this.vertex = vertex;
            this.texCoord = texCoord;
            this.generateTexture = generateTexture;
            this.bindTexture = bindTexture;
            this.textureParameter = textureParameter;
            this.textureImage = textureImage;
        }

        private static GlBindings create(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> gl = Class.forName("org.lwjgl.opengl.GL11", false, loader);
            return new GlBindings(
                    gl.getMethod("glPushMatrix"),
                    gl.getMethod("glPopMatrix"),
                    gl.getMethod("glScalef", Float.TYPE, Float.TYPE, Float.TYPE),
                    gl.getMethod("glEnable", Integer.TYPE),
                    gl.getMethod("glDisable", Integer.TYPE),
                    gl.getMethod("glBlendFunc", Integer.TYPE, Integer.TYPE),
                    gl.getMethod("glColor4f", Float.TYPE, Float.TYPE, Float.TYPE, Float.TYPE),
                    gl.getMethod("glBegin", Integer.TYPE),
                    gl.getMethod("glEnd"),
                    gl.getMethod("glVertex2f", Float.TYPE, Float.TYPE),
                    gl.getMethod("glTexCoord2f", Float.TYPE, Float.TYPE),
                    gl.getMethod("glGenTextures"),
                    gl.getMethod("glBindTexture", Integer.TYPE, Integer.TYPE),
                    gl.getMethod("glTexParameteri", Integer.TYPE, Integer.TYPE, Integer.TYPE),
                    gl.getMethod(
                            "glTexImage2D",
                            Integer.TYPE,
                            Integer.TYPE,
                            Integer.TYPE,
                            Integer.TYPE,
                            Integer.TYPE,
                            Integer.TYPE,
                            Integer.TYPE,
                            Integer.TYPE,
                            ByteBuffer.class));
        }

        private void push() throws ReflectiveOperationException {
            pushMatrix.invoke(null);
        }

        private void pop() throws ReflectiveOperationException {
            popMatrix.invoke(null);
        }

        private void scale(float x, float y, float z) throws ReflectiveOperationException {
            scale.invoke(null, x, y, z);
        }

        private void rect(float x, float y, float width, float height, int argb)
                throws ReflectiveOperationException {
            disable.invoke(null, 3553);
            enable.invoke(null, 3042);
            blendFunc.invoke(null, 770, 771);
            setColor(argb);
            begin.invoke(null, 7);
            vertex.invoke(null, x, y + height);
            vertex.invoke(null, x + width, y + height);
            vertex.invoke(null, x + width, y);
            vertex.invoke(null, x, y);
            end.invoke(null);
            color.invoke(null, 1.0f, 1.0f, 1.0f, 1.0f);
            enable.invoke(null, 3553);
        }

        private int uploadTexture(int width, int height, ByteBuffer pixels)
                throws ReflectiveOperationException {
            int texture = ((Integer) generateTexture.invoke(null)).intValue();
            bindTexture.invoke(null, 3553, texture);
            textureParameter.invoke(null, 3553, 10241, 9729);
            textureParameter.invoke(null, 3553, 10240, 9729);
            textureParameter.invoke(null, 3553, 10242, 33071);
            textureParameter.invoke(null, 3553, 10243, 33071);
            textureImage.invoke(null, 3553, 0, 6408, width, height, 0, 6408, 5121, pixels);
            bindTexture.invoke(null, 3553, 0);
            return texture;
        }

        private void texture(int texture, int x, int y, int width, int height)
                throws ReflectiveOperationException {
            enable.invoke(null, 3553);
            enable.invoke(null, 3042);
            blendFunc.invoke(null, 770, 771);
            bindTexture.invoke(null, 3553, texture);
            color.invoke(null, 1.0f, 1.0f, 1.0f, 1.0f);
            begin.invoke(null, 7);
            texCoord.invoke(null, 0.0f, 0.0f);
            vertex.invoke(null, (float) x, (float) y);
            texCoord.invoke(null, 0.0f, 1.0f);
            vertex.invoke(null, (float) x, (float) (y + height));
            texCoord.invoke(null, 1.0f, 1.0f);
            vertex.invoke(null, (float) (x + width), (float) (y + height));
            texCoord.invoke(null, 1.0f, 0.0f);
            vertex.invoke(null, (float) (x + width), (float) y);
            end.invoke(null);
            bindTexture.invoke(null, 3553, 0);
        }

        private void setColor(int argb) throws ReflectiveOperationException {
            float alpha = (argb >> 24 & 0xff) / 255.0f;
            float red = (argb >> 16 & 0xff) / 255.0f;
            float green = (argb >> 8 & 0xff) / 255.0f;
            float blue = (argb & 0xff) / 255.0f;
            color.invoke(null, red, green, blue, alpha);
        }
    }
}
