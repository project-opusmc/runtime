package org.polydevs.opusmc.client;

import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.ui.UiRuntime;
import org.lwjgl.input.Keyboard;

/**
 * In-game title surface rendered as a centered terminal/TUI console so it
 * matches the Opus desktop launcher: a bordered panel with a header rule, a
 * single-column MENU pane whose rows carry a caret and a highlighted selection
 * bar, a live STATUS block, and a keyboard-hint command bar. It is fully
 * operable by both keyboard (arrow keys + Enter) and mouse (hover + click),
 * and uses the shared monospace atlas rather than any HTML/CSS surface.
 */
final class OpusMainMenuPage extends OpusUiPage {
    private static final int DESIGN_WIDTH = 720;
    private static final int DESIGN_HEIGHT = 450;

    // Launcher palette, expressed as ARGB so the in-game console reads exactly
    // like the desktop TUI: near-black surfaces, graphite borders, accent blue.
    private static final int PANEL_FILL = 0xF2050505;
    private static final int PANEL_VEIL = 0xB0060A0F;
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

    private UiBounds panel = new UiBounds(0, 0, 0, 0);
    private UiBounds menuPane = new UiBounds(0, 0, 0, 0);
    private UiBounds statusPane = new UiBounds(0, 0, 0, 0);
    private float layoutScale = 1.0F;
    private float bodyFont = 12.0F;

    OpusMainMenuPage(ClientOverlayController controller, UiRuntime runtime) {
        super(controller, runtime);
        actions = new Action[] {
            new Action("Singleplayer", new Runnable() { public void run() { controller.openSingleplayer(); } }),
            new Action("Multiplayer", new Runnable() { public void run() { controller.openMultiplayer(); } }),
            new Action("Client Options", new Runnable() { public void run() { controller.openClientModHub(); } }),
            new Action("Game Options", new Runnable() { public void run() { controller.openVanillaOptions(); } }),
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
        layoutScale = Math.min(
                viewport.width / (float) DESIGN_WIDTH,
                viewport.height / (float) DESIGN_HEIGHT);

        int panelWidth = Math.round(560 * layoutScale);
        int panelHeight = Math.round(320 * layoutScale);
        int panelX = viewport.x + (viewport.width - panelWidth) / 2;
        int panelY = viewport.y + (viewport.height - panelHeight) / 2;
        panel = new UiBounds(panelX, panelY, panelWidth, panelHeight);

        bodyFont = Math.max(9.0F, 12.0F * layoutScale);
        int pad = Math.round(14 * layoutScale);
        int headerHeight = Math.round(46 * layoutScale);
        int footerHeight = Math.round(30 * layoutScale);

        int contentTop = panel.y + headerHeight;
        int contentBottom = panel.bottom() - footerHeight;
        int contentHeight = Math.max(1, contentBottom - contentTop);
        int gap = Math.max(1, Math.round(layoutScale));
        int menuWidth = Math.round((panelWidth - pad * 2) * 0.52F);

        menuPane = new UiBounds(panel.x + pad, contentTop, menuWidth, contentHeight);
        statusPane = new UiBounds(
                menuPane.right() + pad, contentTop,
                panel.right() - pad - (menuPane.right() + pad), contentHeight);

        int rowHeight = Math.round(Math.max(20, 30 * layoutScale));
        int rowsTop = menuPane.y + Math.round(10 * layoutScale);
        for (int index = 0; index < rowBounds.length; index++) {
            rowBounds[index] = new UiBounds(
                    menuPane.x + Math.round(6 * layoutScale),
                    rowsTop + index * (rowHeight + gap),
                    menuPane.width - Math.round(12 * layoutScale),
                    rowHeight);
        }
    }

    @Override
    public void render(UiRenderer renderer, UiInput input) {
        syncFocusToMouse(input);

        // Soft veil so the blurred panorama reads as a backdrop, not a menu.
        renderer.fill(viewport, PANEL_VEIL);

        // Panel body + double frame (graphite outer, soft inner) like the panes.
        renderer.fill(panel, PANEL_FILL);
        renderer.border(panel, Math.max(1, Math.round(layoutScale)), BORDER_STRONG);

        renderHeader(renderer);
        renderMenu(renderer);
        renderStatus(renderer);
        renderFooter(renderer);
    }

    private void renderHeader(UiRenderer renderer) {
        float titleFont = Math.max(11.0F, 15.0F * layoutScale);
        float x = panel.x + Math.round(16 * layoutScale);
        float y = panel.y + Math.round(14 * layoutScale);
        renderer.monoText("OPUS CLIENT", x, y, titleFont, FOREGROUND);
        float titleWidth = renderer.measureMonoText("OPUS CLIENT", titleFont);
        renderer.monoText("READY", x + titleWidth + renderer.monoCellWidth(titleFont) * 2, y + (titleFont - bodyFont) * 0.5F, bodyFont, ACCENT);
        // Header rule under the title.
        int ruleY = panel.y + Math.round(44 * layoutScale);
        renderer.fill(new UiBounds(panel.x, ruleY, panel.width, Math.max(1, Math.round(layoutScale))), BORDER_SOFT);
    }

    private void renderMenu(UiRenderer renderer) {
        renderPaneLabel(renderer, menuPane, "MENU");
        for (int index = 0; index < actions.length; index++) {
            UiBounds row = rowBounds[index];
            boolean selected = index == focusedIndex;
            if (selected) {
                renderer.fill(row, SELECT_BG);
                renderer.fill(new UiBounds(row.x, row.y, Math.max(2, Math.round(2 * layoutScale)), row.height), ACCENT);
            }
            float textY = row.y + (row.height - bodyFont) / 2.0F;
            float caretX = row.x + renderer.monoCellWidth(bodyFont);
            renderer.monoText(selected ? "\u25B8" : " ", caretX, textY, bodyFont, ACCENT);
            float labelX = caretX + renderer.monoCellWidth(bodyFont) * 2;
            String label = "[" + (index + 1) + "] " + actions[index].label;
            renderer.monoText(label, labelX, textY, bodyFont, selected ? FOREGROUND : MUTED);
        }
    }

    private void renderStatus(UiRenderer renderer) {
        renderPaneLabel(renderer, statusPane, "STATUS");
        String[][] rows = {
            {"Client", "Opus v0.0.1"},
            {"Minecraft", "1.8.9"},
            {"Forge", "11.15.1.2318"},
            {"OptiFine", "HD U M5"},
            {"Session", "In game"},
        };
        float rowHeight = renderer.monoLineHeight(bodyFont) + Math.round(3 * layoutScale);
        float top = statusPane.y + Math.round(14 * layoutScale);
        float labelX = statusPane.x + renderer.monoCellWidth(bodyFont);
        for (int index = 0; index < rows.length; index++) {
            float rowY = top + index * rowHeight;
            renderer.monoText(rows[index][0], labelX, rowY, bodyFont, QUIET);
            float valueWidth = renderer.measureMonoText(rows[index][1], bodyFont);
            renderer.monoText(rows[index][1], statusPane.right() - renderer.monoCellWidth(bodyFont) - valueWidth, rowY, bodyFont, FOREGROUND);
        }
    }

    private void renderFooter(UiRenderer renderer) {
        int ruleY = panel.bottom() - Math.round(30 * layoutScale);
        renderer.fill(new UiBounds(panel.x, ruleY, panel.width, Math.max(1, Math.round(layoutScale))), BORDER_SOFT);
        float footFont = Math.max(8.0F, 10.0F * layoutScale);
        float y = panel.bottom() - Math.round(21 * layoutScale);
        String hint = "UP/DOWN navigate   ENTER select   1-5 quick   ESC back";
        renderer.monoText(hint, panel.x + Math.round(16 * layoutScale), y, footFont, QUIET);
    }

    private void renderPaneLabel(UiRenderer renderer, UiBounds pane, String label) {
        float labelFont = Math.max(8.0F, 10.0F * layoutScale);
        renderer.monoText(label, pane.x + renderer.monoCellWidth(bodyFont), pane.y - labelFont - Math.round(2 * layoutScale), labelFont, QUIET);
        renderer.border(pane, Math.max(1, Math.round(layoutScale)), BORDER_SOFT);
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
