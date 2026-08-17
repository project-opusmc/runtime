package org.polydevs.opusmc.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiErrorScreen;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.GuiScreenWorking;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.GuiScreenEvent;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.ui.render.MinecraftUiRenderer;
import org.polydevs.opusmc.client.ui.render.MinecraftUiScale;

/**
 * Terminal compatibility shell for vanilla, Forge and OptiFine screens that
 * still own important runtime logic. Their draw pass is cancelled completely;
 * only an Opus pane/list surface is visible. The original screen remains the
 * active object underneath so network handshakes, callbacks, option controls
 * and mod integrations keep their exact behavior.
 */
final class OpusVanillaTerminalOverlay {
    private static final int BACKGROUND = 0xFF000000;
    private static final int SURFACE = 0xFF050505;
    private static final int BORDER_STRONG = 0xFF565656;
    private static final int BORDER_SOFT = 0xFF262626;
    private static final int ACCENT = 0xFF55A7FF;
    private static final int FOREGROUND = 0xFFF5F5F5;
    private static final int MUTED = 0xFFB5B5B5;
    private static final int QUIET = 0xFF707070;
    private static final int SELECT_BG = 0xFF10151D;
    private static final int OK = 0xFF64C98A;

    private static final class State {
        List<GuiButton> buttons = Collections.emptyList();
        List<UiBounds> visibleRows = Collections.emptyList();
        List<GuiButton> visibleButtons = Collections.emptyList();
        int focusedIndex;
        int scrollTop;
        int visibleCount = 1;
    }

    private final Logger log;
    private final Map<GuiScreen, State> states = new WeakHashMap<GuiScreen, State>();
    private MinecraftUiRenderer renderer;

    OpusVanillaTerminalOverlay(Logger log) {
        this.log = log;
    }

    void initialized(GuiScreen gui, List<GuiButton> buttons) {
        if (!shouldTerminalize(gui)) {
            return;
        }
        State state = state(gui);
        state.buttons = buttons == null ? Collections.<GuiButton>emptyList() : buttons;
        layout(gui, state);
    }

    void draw(GuiScreenEvent.DrawScreenEvent.Pre event) {
        if (!shouldTerminalize(event.gui)) {
            return;
        }
        event.setCanceled(true);
        State state = state(event.gui);
        layout(event.gui, state);
        syncFocusToMouse(event.gui, state, event.mouseX, event.mouseY);
        render(event.gui, state);
    }

    void keyboard(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!shouldTerminalize(event.gui) || !Keyboard.getEventKeyState()) {
            return;
        }
        State state = state(event.gui);
        layout(event.gui, state);
        if (state.visibleButtons.isEmpty()) {
            return;
        }

        int key = Keyboard.getEventKey();
        switch (key) {
            case Keyboard.KEY_UP:
            case Keyboard.KEY_W:
                moveFocus(state, -1);
                event.setCanceled(true);
                return;
            case Keyboard.KEY_DOWN:
            case Keyboard.KEY_S:
            case Keyboard.KEY_TAB:
                moveFocus(state, 1);
                event.setCanceled(true);
                return;
            case Keyboard.KEY_HOME:
                state.focusedIndex = 0;
                clampScroll(state);
                event.setCanceled(true);
                return;
            case Keyboard.KEY_END:
                state.focusedIndex = state.visibleButtons.size() - 1;
                clampScroll(state);
                event.setCanceled(true);
                return;
            case Keyboard.KEY_LEFT:
            case Keyboard.KEY_A:
                activate(event.gui, selected(state), 0.25F, true);
                event.setCanceled(true);
                return;
            case Keyboard.KEY_RIGHT:
            case Keyboard.KEY_D:
                activate(event.gui, selected(state), 0.75F, false);
                event.setCanceled(true);
                return;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
            case Keyboard.KEY_SPACE:
                activate(event.gui, selected(state), 0.5F, false);
                event.setCanceled(true);
                return;
            case Keyboard.KEY_ESCAPE:
                GuiButton back = backButton(state.visibleButtons);
                if (back != null) {
                    activate(event.gui, back, 0.5F, false);
                    event.setCanceled(true);
                }
                return;
            default:
                return;
        }
    }

    void mouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        if (!shouldTerminalize(event.gui)) {
            return;
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        State state = state(event.gui);
        layout(event.gui, state);
        moveFocus(state, wheel > 0 ? -1 : 1);
        event.setCanceled(true);
    }

    private void render(GuiScreen gui, State state) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (renderer == null) {
            renderer = new MinecraftUiRenderer(minecraft);
        }
        UiBounds viewport = MinecraftUiScale.viewport(minecraft);
        float scale = Math.max(0.75F, Math.min(2.0F, viewport.height / 320.0F));
        float bodyFont = Math.max(9.0F, 11.0F * scale);
        float titleFont = Math.max(11.0F, 14.0F * scale);
        int headerHeight = Math.round(38 * scale);
        int footerHeight = Math.round(26 * scale);
        int pad = Math.round(10 * scale);
        int gap = Math.max(1, Math.round(scale));

        UiBounds header = new UiBounds(0, 0, viewport.width, headerHeight);
        UiBounds footer = new UiBounds(0, viewport.height - footerHeight, viewport.width, footerHeight);
        int bodyTop = header.bottom() + gap;
        int bodyBottom = footer.y - gap;
        int bodyLeft = pad;
        int bodyRight = viewport.width - pad;
        int menuWidth = Math.round((bodyRight - bodyLeft) * 0.62F);
        UiBounds menu = new UiBounds(bodyLeft, bodyTop, menuWidth, bodyBottom - bodyTop);
        UiBounds status = new UiBounds(
                menu.right() + pad,
                bodyTop,
                bodyRight - menu.right() - pad,
                bodyBottom - bodyTop);

        renderer.beginFrame(viewport);
        try {
            renderer.fill(viewport, BACKGROUND);
            renderer.fill(header, SURFACE);
            renderer.fill(
                    new UiBounds(header.x, header.bottom() - Math.max(1, Math.round(scale)),
                            header.width, Math.max(1, Math.round(scale))),
                    BORDER_STRONG);
            float cell = renderer.monoCellWidth(titleFont);
            float titleX = Math.round(14 * scale);
            float titleY = (header.height - titleFont) / 2.0F;
            renderer.monoText("OPUS CLIENT", titleX, titleY, titleFont, FOREGROUND);
            float wordWidth = renderer.measureMonoText("OPUS CLIENT", titleFont);
            renderer.monoText("|", titleX + wordWidth + cell, titleY, titleFont, BORDER_STRONG);
            renderer.monoText("TUI", titleX + wordWidth + cell * 3, titleY, titleFont, ACCENT);

            String title = title(gui);
            float titleWidth = renderer.measureMonoText(title, bodyFont);
            renderer.monoText(
                    title,
                    header.right() - Math.round(14 * scale) - titleWidth,
                    (header.height - bodyFont) / 2.0F,
                    bodyFont,
                    MUTED);

            pane(menu, "COMMANDS", scale, bodyFont);
            pane(status, "SESSION", scale, bodyFont);
            renderButtons(state, menu, scale, bodyFont);
            renderStatus(gui, state, status, scale, bodyFont);

            renderer.fill(footer, SURFACE);
            renderer.fill(
                    new UiBounds(footer.x, footer.y, footer.width, Math.max(1, Math.round(scale))),
                    BORDER_STRONG);
            float footFont = Math.max(8.0F, 10.0F * scale);
            String hint = state.visibleButtons.isEmpty()
                    ? "PLEASE WAIT   OPUS runtime is processing"
                    : "ARROW navigate   ENTER select   < > adjust   MOUSE supported   ESC back";
            renderer.monoText(
                    hint,
                    footer.x + Math.round(14 * scale),
                    footer.y + (footer.height - footFont) / 2.0F,
                    footFont,
                    QUIET);
        } finally {
            renderer.endFrame();
        }
    }

    private void pane(UiBounds bounds, String label, float scale, float font) {
        renderer.fill(bounds, SURFACE);
        renderer.border(bounds, Math.max(1, Math.round(scale)), BORDER_STRONG);
        int headerHeight = Math.round(24 * scale);
        renderer.monoText(
                label,
                bounds.x + renderer.monoCellWidth(font) + Math.round(4 * scale),
                bounds.y + (headerHeight - font) / 2.0F,
                font,
                QUIET);
        renderer.fill(
                new UiBounds(bounds.x + 1, bounds.y + headerHeight, bounds.width - 2,
                        Math.max(1, Math.round(scale))),
                BORDER_SOFT);
    }

    private void renderButtons(State state, UiBounds pane, float scale, float font) {
        float cell = renderer.monoCellWidth(font);
        if (state.visibleButtons.isEmpty()) {
            String[] logLines = {
                "[ OK ] Forge runtime attached",
                "[ OK ] OptiFine compatibility active",
                "[ .. ] " + processingLine(),
            };
            float y = pane.y + Math.round(38 * scale);
            float line = renderer.monoLineHeight(font) + Math.round(5 * scale);
            for (int index = 0; index < logLines.length; index++) {
                renderer.monoText(
                        logLines[index],
                        pane.x + cell * 2,
                        y + index * line,
                        font,
                        index < 2 ? OK : ACCENT);
            }
            return;
        }
        for (int visible = 0; visible < state.visibleRows.size(); visible++) {
            int index = state.scrollTop + visible;
            if (index >= state.visibleButtons.size()) {
                break;
            }
            UiBounds row = state.visibleRows.get(visible);
            GuiButton button = state.visibleButtons.get(index);
            boolean selected = index == state.focusedIndex;
            if (selected) {
                renderer.fill(row, SELECT_BG);
                renderer.fill(
                        new UiBounds(row.x, row.y, Math.max(2, Math.round(2 * scale)), row.height),
                        ACCENT);
            }
            float y = row.y + (row.height - font) / 2.0F;
            renderer.monoText(selected ? "\u25B8" : " ", row.x + cell, y, font, ACCENT);
            String label = clean(button.displayString);
            int available = Math.max(4, (int) ((row.width - cell * 5) / Math.max(1.0F, cell)));
            label = ellipsize(label, available);
            renderer.monoText(
                    label,
                    row.x + cell * 3,
                    y,
                    font,
                    button.enabled ? (selected ? FOREGROUND : MUTED) : QUIET);
        }
        if (state.visibleButtons.size() > state.visibleCount) {
            String meta = (state.focusedIndex + 1) + "/" + state.visibleButtons.size();
            float width = renderer.measureMonoText(meta, font);
            renderer.monoText(
                    meta,
                    pane.right() - Math.round(8 * scale) - width,
                    pane.y + Math.round(8 * scale),
                    font,
                    QUIET);
        }
    }

    private void renderStatus(GuiScreen gui, State state, UiBounds pane, float scale, float font) {
        List<String> messages = textFields(gui);
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] {"Screen", title(gui)});
        rows.add(new String[] {"Logic", "ACTIVE"});
        rows.add(new String[] {"Renderer", "OPUS TUI"});
        rows.add(new String[] {"Controls", Integer.toString(state.visibleButtons.size())});
        for (int index = 0; index < messages.size() && index < 4; index++) {
            rows.add(new String[] {index == 0 ? "Message" : "", messages.get(index)});
        }

        float cell = renderer.monoCellWidth(font);
        float line = renderer.monoLineHeight(font) + Math.round(4 * scale);
        float y = pane.y + Math.round(34 * scale);
        int available = Math.max(4, (int) ((pane.width * 0.60F) / Math.max(1.0F, cell)));
        for (int index = 0; index < rows.size(); index++) {
            String key = rows.get(index)[0];
            String value = ellipsize(clean(rows.get(index)[1]), available);
            renderer.monoText(key, pane.x + cell + Math.round(4 * scale), y + index * line, font, QUIET);
            float valueWidth = renderer.measureMonoText(value, font);
            renderer.monoText(
                    value,
                    pane.right() - cell - Math.round(4 * scale) - valueWidth,
                    y + index * line,
                    font,
                    FOREGROUND);
        }
    }

    private void layout(GuiScreen gui, State state) {
        List<GuiButton> visible = new ArrayList<GuiButton>();
        for (GuiButton button : state.buttons) {
            if (button != null && button.visible) {
                visible.add(button);
            }
        }
        state.visibleButtons = visible;
        if (visible.isEmpty()) {
            state.focusedIndex = 0;
            state.scrollTop = 0;
            state.visibleRows = Collections.emptyList();
            return;
        }
        state.focusedIndex = Math.max(0, Math.min(state.focusedIndex, visible.size() - 1));

        Minecraft minecraft = Minecraft.getMinecraft();
        UiBounds viewport = MinecraftUiScale.viewport(minecraft);
        float scale = Math.max(0.75F, Math.min(2.0F, viewport.height / 320.0F));
        int headerHeight = Math.round(38 * scale);
        int footerHeight = Math.round(26 * scale);
        int pad = Math.round(10 * scale);
        int gap = Math.max(1, Math.round(scale));
        int bodyTop = headerHeight + gap;
        int bodyBottom = viewport.height - footerHeight - gap;
        int bodyLeft = pad;
        int bodyRight = viewport.width - pad;
        int menuWidth = Math.round((bodyRight - bodyLeft) * 0.62F);
        UiBounds menu = new UiBounds(bodyLeft, bodyTop, menuWidth, bodyBottom - bodyTop);
        int listTop = menu.y + Math.round(30 * scale);
        int rowHeight = Math.round(Math.max(18, 24 * scale));
        int listBottom = menu.bottom() - Math.round(8 * scale);
        state.visibleCount = Math.max(1, (listBottom - listTop) / (rowHeight + gap));
        clampScroll(state);

        List<UiBounds> rows = new ArrayList<UiBounds>(state.visibleCount);
        int inset = Math.round(8 * scale);
        for (int index = 0; index < state.visibleCount; index++) {
            rows.add(new UiBounds(
                    menu.x + inset,
                    listTop + index * (rowHeight + gap),
                    menu.width - inset * 2,
                    rowHeight));
        }
        state.visibleRows = rows;

        for (int index = 0; index < visible.size(); index++) {
            GuiButton button = visible.get(index);
            int visibleIndex = index - state.scrollTop;
            if (visibleIndex < 0 || visibleIndex >= rows.size()) {
                button.xPosition = -10000;
                button.yPosition = -10000;
                button.width = 1;
                button.height = 1;
                continue;
            }
            UiBounds row = rows.get(visibleIndex);
            button.xPosition = toGui(row.x, viewport.width, gui.width);
            button.yPosition = toGui(row.y, viewport.height, gui.height);
            button.width = Math.max(1, toGui(row.width, viewport.width, gui.width));
            button.height = Math.max(1, toGui(row.height, viewport.height, gui.height));
        }
    }

    private void syncFocusToMouse(GuiScreen gui, State state, int guiMouseX, int guiMouseY) {
        if (state.visibleRows.isEmpty()) {
            return;
        }
        UiBounds viewport = MinecraftUiScale.viewport(Minecraft.getMinecraft());
        int mouseX = toProduct(guiMouseX, gui.width, viewport.width);
        int mouseY = toProduct(guiMouseY, gui.height, viewport.height);
        for (int visible = 0; visible < state.visibleRows.size(); visible++) {
            if (state.visibleRows.get(visible).contains(mouseX, mouseY)) {
                int index = state.scrollTop + visible;
                if (index < state.visibleButtons.size()) {
                    state.focusedIndex = index;
                }
                return;
            }
        }
    }

    private void activate(GuiScreen gui, GuiButton button, float horizontal, boolean rightClick) {
        if (button == null || !button.enabled || !button.visible) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            int x = button.xPosition + Math.round(Math.max(1, button.width - 1) * horizontal);
            int y = button.yPosition + Math.max(1, button.height / 2);
            if (isSlider(button)) {
                button.mousePressed(minecraft, x, y);
                button.mouseReleased(x, y);
            }
            button.playPressSound(minecraft.getSoundHandler());
            Method method = actionMethod(gui.getClass(), rightClick);
            if (method != null) {
                method.invoke(gui, button);
            }
        } catch (Throwable failure) {
            log.warn("Opus TUI could not activate compatibility control {}", button.id, failure);
        }
    }

    private static Method actionMethod(Class<?> type, boolean rightClick) {
        if (rightClick) {
            Method right = namedAction(type, new String[] {"actionPerformedRightClick"});
            if (right != null) {
                return right;
            }
        }
        Method named = namedAction(
                type,
                new String[] {"actionPerformed", "func_146284_a", "a"});
        if (named != null) {
            return named;
        }
        for (Class<?> current = type; current != null && GuiScreen.class.isAssignableFrom(current);
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getReturnType() == Void.TYPE
                        && parameters.length == 1
                        && GuiButton.class.isAssignableFrom(parameters[0])) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private static Method namedAction(Class<?> type, String[] names) {
        for (Class<?> current = type; current != null && GuiScreen.class.isAssignableFrom(current);
                current = current.getSuperclass()) {
            for (String name : names) {
                try {
                    Method method = current.getDeclaredMethod(name, GuiButton.class);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {
                    // Runtime names vary between Forge dev, SRG and obfuscated
                    // launch lanes; the signature fallback below is deliberate.
                }
            }
        }
        return null;
    }

    private static boolean isSlider(GuiButton button) {
        return button.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("slider");
    }

    private static GuiButton selected(State state) {
        return state.focusedIndex >= 0 && state.focusedIndex < state.visibleButtons.size()
                ? state.visibleButtons.get(state.focusedIndex)
                : null;
    }

    private static GuiButton backButton(List<GuiButton> buttons) {
        for (int index = buttons.size() - 1; index >= 0; index--) {
            GuiButton button = buttons.get(index);
            String label = clean(button.displayString).toLowerCase(java.util.Locale.ROOT);
            if (label.contains("done")
                    || label.contains("back")
                    || label.contains("cancel")
                    || label.contains("return")
                    || label.equals("no")) {
                return button;
            }
        }
        return null;
    }

    private static void moveFocus(State state, int delta) {
        int count = state.visibleButtons.size();
        if (count == 0) {
            return;
        }
        state.focusedIndex = ((state.focusedIndex + delta) % count + count) % count;
        clampScroll(state);
    }

    private static void clampScroll(State state) {
        if (state.focusedIndex < state.scrollTop) {
            state.scrollTop = state.focusedIndex;
        } else if (state.focusedIndex >= state.scrollTop + state.visibleCount) {
            state.scrollTop = state.focusedIndex - state.visibleCount + 1;
        }
        int max = Math.max(0, state.visibleButtons.size() - state.visibleCount);
        state.scrollTop = Math.max(0, Math.min(state.scrollTop, max));
    }

    private State state(GuiScreen gui) {
        State state = states.get(gui);
        if (state == null) {
            state = new State();
            states.put(gui, state);
        }
        return state;
    }

    private static boolean shouldTerminalize(GuiScreen gui) {
        if (gui == null || gui instanceof OpusClientScreen) {
            return false;
        }
        Package owner = gui.getClass().getPackage();
        if (owner != null && owner.getName().startsWith("org.polydevs.opusmc.client")) {
            return false;
        }
        // Gameplay containers and text authoring are not menu chrome. Keeping
        // them interactive avoids destroying inventory, chat, book and sign
        // mechanics while every title/options/loading/system surface is TUI.
        return !(gui instanceof GuiContainer)
                && !(gui instanceof GuiChat)
                && !(gui instanceof GuiScreenBook)
                && !gui.getClass().getName().endsWith("GuiEditSign");
    }

    private static String title(GuiScreen gui) {
        if (gui instanceof GuiConnecting) return "CONNECTING";
        if (gui instanceof GuiDownloadTerrain) return "LOADING WORLD";
        if (gui instanceof GuiScreenWorking) return "WORKING";
        if (gui instanceof GuiDisconnected) return "DISCONNECTED";
        if (gui instanceof GuiErrorScreen) return "ERROR";
        if (gui instanceof GuiYesNo) return "CONFIRM";
        if (gui instanceof GuiVideoSettings) return "VIDEO / OPTIFINE";
        String name = gui.getClass().getSimpleName();
        if (name.contains("Shader")) return "SHADERS";
        if (name.contains("Quality")) return "QUALITY";
        if (name.contains("Detail")) return "DETAILS";
        if (name.contains("Performance")) return "PERFORMANCE";
        if (name.contains("Animation")) return "ANIMATIONS";
        if (name.contains("Other")) return "OTHER OPTIONS";
        if (name.startsWith("Gui")) name = name.substring(3);
        return clean(splitCamel(name)).toUpperCase(java.util.Locale.ROOT);
    }

    private static String processingLine() {
        String[] stages = {
            "Resolving runtime state",
            "Applying session data",
            "Waiting for game engine",
            "Finalizing client state",
        };
        long index = (System.currentTimeMillis() / 700L) % stages.length;
        return stages[(int) index];
    }

    private static List<String> textFields(GuiScreen gui) {
        List<String> values = new ArrayList<String>();
        for (Class<?> current = gui.getClass();
                current != null && GuiScreen.class.isAssignableFrom(current);
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (values.size() >= 4) {
                    return values;
                }
                Class<?> type = field.getType();
                if (type != String.class && !IChatComponent.class.isAssignableFrom(type)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object raw = field.get(gui);
                    String value = raw instanceof IChatComponent
                            ? ((IChatComponent) raw).getUnformattedText()
                            : (String) raw;
                    value = clean(value);
                    if (!value.isEmpty() && !values.contains(value)) {
                        values.add(value);
                    }
                } catch (Throwable ignored) {
                    // Text extraction is optional; the compatibility renderer
                    // must never block a functional screen.
                }
            }
        }
        return values;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String withoutFormatting = EnumChatFormatting.getTextWithoutFormattingCodes(value);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < withoutFormatting.length(); index++) {
            char character = withoutFormatting.charAt(index);
            if (character == '\n' || character == '\r' || character == '\t') {
                character = ' ';
            }
            if (character >= 32 && character != 127) {
                result.append(character);
            }
        }
        return result.toString().trim();
    }

    private static String ellipsize(String value, int maxCharacters) {
        if (value.length() <= maxCharacters) {
            return value;
        }
        return maxCharacters <= 3 ? value.substring(0, maxCharacters) : value.substring(0, maxCharacters - 3) + "...";
    }

    private static String splitCamel(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private static int toGui(int value, int productExtent, int guiExtent) {
        return productExtent <= 0 ? value : Math.round(value * guiExtent / (float) productExtent);
    }

    private static int toProduct(int value, int guiExtent, int productExtent) {
        return guiExtent <= 0 ? value : Math.round(value * productExtent / (float) guiExtent);
    }
}
