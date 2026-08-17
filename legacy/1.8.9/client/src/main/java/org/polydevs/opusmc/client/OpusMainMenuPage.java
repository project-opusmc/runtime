package org.polydevs.opusmc.client;

import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.ui.UiRuntime;
import org.lwjgl.input.Keyboard;

/**
 * The in-game title screen rendered as a full-screen terminal (TUI) surface
 * that matches the Opus desktop launcher. Nothing from the vanilla Minecraft
 * title screen remains: there is no panorama, no blurred scene, no vanilla
 * widgets. The whole viewport is an opaque terminal painted with the shared
 * monospace atlas: a header row with a rule, a MENU pane and a STATUS pane
 * filling the body, and a command-hint bar at the bottom.
 *
 * <p>Fully operable by keyboard (arrows/W-S/Tab to move, Enter/Space or 1-5 to
 * activate) and by mouse (hover syncs focus, click activates).</p>
 */
final class OpusMainMenuPage extends OpusUiPage {
    // Launcher palette (ARGB).
    private static final int BACKGROUND = 0xFF000000;
    private static final int SURFACE = 0xFF050505;
    private static final int SURFACE_RAISED = 0xFF0A0A0A;
    private static final int BORDER_STRONG = 0xFF565656;
    private static final int BORDER_SOFT = 0xFF262626;
    private static final int ACCENT = 0xFF55A7FF;
    private static final int FOREGROUND = 0xFFF5F5F5;
    private static final int MUTED = 0xFFB5B5B5;
    private static final int QUIET = 0xFF707070;
    private static final int SELECT_BG = 0xFF10151D;

    private static final class Action {
        final String label;
        final Runnable run;
        Action(String label, Runnable run) {
            this.label = label;
            this.run = run;
        }
    }

    private final Action[] actions;
    private final UiBounds[] rowBounds;
    private int focusedIndex;
    private int lastMouseX = Integer.MIN_VALUE;
    private int lastMouseY = Integer.MIN_VALUE;

    private UiBounds headerBounds = new UiBounds(0, 0, 0, 0);
    private UiBounds menuPane = new UiBounds(0, 0, 0, 0);
    private UiBounds statusPane = new UiBounds(0, 0, 0, 0);
    private UiBounds footerBounds = new UiBounds(0, 0, 0, 0);
    private float uiScale = 1.0F;
    private float bodyFont = 12.0F;

    OpusMainMenuPage(ClientOverlayController controller, UiRuntime runtime) {
        super(controller, runtime);
        actions = new Action[] {
            new Action("Singleplayer", new Runnable() { public void run() { OpusMainMenuPage.this.runtime.navigate(org.polydevs.opusmc.client.ui.UiRoute.singleplayer()); } }),
            new Action("Multiplayer", new Runnable() { public void run() { OpusMainMenuPage.this.runtime.navigate(org.polydevs.opusmc.client.ui.UiRoute.multiplayer()); } }),
            new Action("Client Options", new Runnable() { public void run() { controller.openClientModHub(); } }),
            new Action("Game Options", new Runnable() { public void run() { OpusMainMenuPage.this.runtime.navigate(org.polydevs.opusmc.client.ui.UiRoute.gameOptions()); } }),
            new Action("Open Web UI", new Runnable() { public void run() { controller.openExternalUi(); } }),
            new Action("Quit Game", new Runnable() { public void run() { controller.quitGame(); } }),
        };
        rowBounds = new UiBounds[actions.length];
        for (int index = 0; index < rowBounds.length; index++) {
            rowBounds[index] = new UiBounds(0, 0, 0, 0);
        }
    }

    @Override
    public void layout(UiBounds nextViewport) {
        super.layout(nextViewport);
        // Scale relative to a nominal terminal so cell sizes track window size.
        uiScale = Math.max(0.75F, Math.min(2.0F, viewport.height / 320.0F));
        bodyFont = Math.max(9.0F, 11.0F * uiScale);

        int headerHeight = Math.round(38 * uiScale);
        int footerHeight = Math.round(26 * uiScale);
        int gap = Math.max(1, Math.round(uiScale));
        int pad = Math.round(10 * uiScale);

        headerBounds = new UiBounds(viewport.x, viewport.y, viewport.width, headerHeight);
        footerBounds = new UiBounds(viewport.x, viewport.bottom() - footerHeight, viewport.width, footerHeight);

        int bodyTop = headerBounds.bottom() + gap;
        int bodyBottom = footerBounds.y - gap;
        int bodyHeight = Math.max(1, bodyBottom - bodyTop);
        int bodyLeft = viewport.x + pad;
        int bodyRight = viewport.right() - pad;
        int bodyWidth = Math.max(1, bodyRight - bodyLeft);
        int menuWidth = Math.round(bodyWidth * 0.4F);

        menuPane = new UiBounds(bodyLeft, bodyTop, menuWidth, bodyHeight);
        statusPane = new UiBounds(menuPane.right() + pad, bodyTop, bodyRight - (menuPane.right() + pad), bodyHeight);

        int rowHeight = Math.round(Math.max(22, 30 * uiScale));
        int rowsTop = menuPane.y + Math.round(34 * uiScale);
        int rowInset = Math.round(10 * uiScale);
        for (int index = 0; index < rowBounds.length; index++) {
            rowBounds[index] = new UiBounds(
                    menuPane.x + rowInset,
                    rowsTop + index * (rowHeight + gap),
                    menuPane.width - rowInset * 2,
                    rowHeight);
        }
    }

    @Override
    public void render(UiRenderer renderer, UiInput input) {
        syncFocusToMouse(input);

        // Opaque terminal background edge to edge: no Minecraft scene at all.
        renderer.fill(viewport, BACKGROUND);

        renderHeader(renderer);
        renderPane(renderer, menuPane, "MENU");
        renderPane(renderer, statusPane, "STATUS");
        renderMenuRows(renderer);
        renderStatusRows(renderer);
        renderFooter(renderer);
    }

    private void renderHeader(UiRenderer renderer) {
        renderer.fill(headerBounds, SURFACE);
        renderer.fill(new UiBounds(headerBounds.x, headerBounds.bottom() - Math.max(1, Math.round(uiScale)),
                headerBounds.width, Math.max(1, Math.round(uiScale))), BORDER_STRONG);
        float titleFont = Math.max(11.0F, 14.0F * uiScale);
        float cell = renderer.monoCellWidth(titleFont);
        float x = headerBounds.x + Math.round(14 * uiScale);
        float y = headerBounds.y + (headerBounds.height - titleFont) / 2.0F;
        renderer.monoText("OPUS CLIENT", x, y, titleFont, FOREGROUND);
        float titleWidth = renderer.measureMonoText("OPUS CLIENT", titleFont);
        renderer.monoText("|", x + titleWidth + cell, y, titleFont, BORDER_STRONG);
        renderer.monoText("READY", x + titleWidth + cell * 3, y, titleFont, ACCENT);

        String right = "UNIFIED CLIENT v0.0.1";
        float rightWidth = renderer.measureMonoText(right, bodyFont);
        renderer.monoText(right, headerBounds.right() - Math.round(14 * uiScale) - rightWidth,
                headerBounds.y + (headerBounds.height - bodyFont) / 2.0F, bodyFont, MUTED);
    }

    private void renderPane(UiRenderer renderer, UiBounds pane, String label) {
        renderer.fill(pane, SURFACE);
        renderer.border(pane, Math.max(1, Math.round(uiScale)), BORDER_STRONG);
        // Header rule + label inside the pane, echoing the launcher pane header.
        float labelFont = Math.max(9.0F, 10.0F * uiScale);
        int headerH = Math.round(24 * uiScale);
        renderer.monoText(label, pane.x + renderer.monoCellWidth(labelFont) + Math.round(4 * uiScale),
                pane.y + (headerH - labelFont) / 2.0F, labelFont, QUIET);
        renderer.fill(new UiBounds(pane.x + 1, pane.y + headerH, pane.width - 2, Math.max(1, Math.round(uiScale))), BORDER_SOFT);
    }

    private void renderMenuRows(UiRenderer renderer) {
        for (int index = 0; index < actions.length; index++) {
            UiBounds row = rowBounds[index];
            boolean selected = index == focusedIndex;
            if (selected) {
                renderer.fill(row, SELECT_BG);
                renderer.fill(new UiBounds(row.x, row.y, Math.max(2, Math.round(2 * uiScale)), row.height), ACCENT);
            }
            float textY = row.y + (row.height - bodyFont) / 2.0F;
            float cell = renderer.monoCellWidth(bodyFont);
            float caretX = row.x + cell;
            renderer.monoText(selected ? "\u25B8" : " ", caretX, textY, bodyFont, ACCENT);
            String label = "[" + (index + 1) + "] " + actions[index].label;
            renderer.monoText(label, caretX + cell * 2, textY, bodyFont, selected ? FOREGROUND : MUTED);
        }
    }

    private void renderStatusRows(UiRenderer renderer) {
        String[][] rows = {
            {"Client", "Opus v0.0.1"},
            {"Minecraft", "1.8.9"},
            {"Forge", "11.15.1.2318"},
            {"OptiFine", "HD U M5"},
            {"Session", "In game"},
            {"Isolation", "Per identity"},
        };
        float cell = renderer.monoCellWidth(bodyFont);
        float rowHeight = renderer.monoLineHeight(bodyFont) + Math.round(4 * uiScale);
        float top = statusPane.y + Math.round(34 * uiScale);
        float labelX = statusPane.x + cell + Math.round(4 * uiScale);
        for (int index = 0; index < rows.length; index++) {
            float rowY = top + index * rowHeight;
            renderer.monoText(rows[index][0], labelX, rowY, bodyFont, QUIET);
            float valueWidth = renderer.measureMonoText(rows[index][1], bodyFont);
            renderer.monoText(rows[index][1], statusPane.right() - cell - Math.round(4 * uiScale) - valueWidth, rowY, bodyFont, FOREGROUND);
        }
    }

    private void renderFooter(UiRenderer renderer) {
        renderer.fill(footerBounds, SURFACE);
        renderer.fill(new UiBounds(footerBounds.x, footerBounds.y, footerBounds.width, Math.max(1, Math.round(uiScale))), BORDER_STRONG);
        float footFont = Math.max(8.0F, 10.0F * uiScale);
        float y = footerBounds.y + (footerBounds.height - footFont) / 2.0F;
        String hint = "ARROW navigate   ENTER select   1-6 quick   ESC back";
        renderer.monoText(hint, footerBounds.x + Math.round(14 * uiScale), y, footFont, QUIET);
    }

    @Override
    public boolean mouseDown(int mouseX, int mouseY, int button) {
        if (button != 0) {
            return false;
        }
        int index = rowAt(mouseX, mouseY);
        if (index < 0) {
            return false;
        }
        focusedIndex = index;
        actions[index].run.run();
        return true;
    }

    @Override
    public boolean keyTyped(char typedCharacter, int keyCode) {
        switch (keyCode) {
            case Keyboard.KEY_UP:
            case Keyboard.KEY_W:
                moveFocus(-1);
                return true;
            case Keyboard.KEY_DOWN:
            case Keyboard.KEY_S:
            case Keyboard.KEY_TAB:
                moveFocus(1);
                return true;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
            case Keyboard.KEY_SPACE:
                actions[focusedIndex].run.run();
                return true;
            default:
                break;
        }
        int quick = quickIndex(keyCode);
        if (quick >= 0 && quick < actions.length) {
            focusedIndex = quick;
            actions[quick].run.run();
            return true;
        }
        return super.keyTyped(typedCharacter, keyCode);
    }

    private static int quickIndex(int keyCode) {
        switch (keyCode) {
            case Keyboard.KEY_1: return 0;
            case Keyboard.KEY_2: return 1;
            case Keyboard.KEY_3: return 2;
            case Keyboard.KEY_4: return 3;
            case Keyboard.KEY_5: return 4;
            case Keyboard.KEY_6: return 5;
            default: return -1;
        }
    }

    private void moveFocus(int delta) {
        int count = actions.length;
        focusedIndex = ((focusedIndex + delta) % count + count) % count;
    }

    private void syncFocusToMouse(UiInput input) {
        if (input.mouseX == lastMouseX && input.mouseY == lastMouseY) {
            return;
        }
        lastMouseX = input.mouseX;
        lastMouseY = input.mouseY;
        int hovered = rowAt(input.mouseX, input.mouseY);
        if (hovered >= 0) {
            focusedIndex = hovered;
        }
    }

    private int rowAt(int mouseX, int mouseY) {
        for (int index = 0; index < rowBounds.length; index++) {
            if (rowBounds[index].contains(mouseX, mouseY)) {
                return index;
            }
        }
        return -1;
    }
}
