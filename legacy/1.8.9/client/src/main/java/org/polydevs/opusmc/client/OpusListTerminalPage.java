package org.polydevs.opusmc.client;

import java.util.List;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.ui.UiRoute;
import org.polydevs.opusmc.client.ui.UiRuntime;
import org.lwjgl.input.Keyboard;

/**
 * Shared terminal (TUI) list surface for the Opus singleplayer and multiplayer
 * screens. It renders the same black, full-screen pane layout as the title and
 * pause menus: a header with a rule, a scrollable MENU list on the left, a
 * STATUS detail pane on the right, and a command-hint footer. Nothing from the
 * vanilla world/server screens remains.
 *
 * <p>Rows come from {@link #rows()} as {@code {primary, secondary}} pairs.
 * Selecting a row (Enter/Space/click) calls {@link #activate(int)}. An optional
 * single-line text field supports direct entry (a world action or a server
 * address) and is edited with typed characters, Backspace and Enter.</p>
 */
abstract class OpusListTerminalPage extends OpusUiPage {
    private static final int BACKGROUND = 0xFF000000;
    private static final int SURFACE = 0xFF050505;
    private static final int BORDER_STRONG = 0xFF565656;
    private static final int BORDER_SOFT = 0xFF262626;
    private static final int ACCENT = 0xFF55A7FF;
    private static final int FOREGROUND = 0xFFF5F5F5;
    private static final int MUTED = 0xFFB5B5B5;
    private static final int QUIET = 0xFF707070;
    private static final int SELECT_BG = 0xFF10151D;

    private final String badge;
    private List<String[]> cachedRows;
    private int focusedIndex;
    private int scrollTop;
    private int visibleRows = 1;
    private int lastMouseX = Integer.MIN_VALUE;
    private int lastMouseY = Integer.MIN_VALUE;

    // Optional direct-entry text field (e.g. server address / new world name).
    private boolean editing;
    private final StringBuilder input = new StringBuilder();

    private UiBounds headerBounds = new UiBounds(0, 0, 0, 0);
    private UiBounds menuPane = new UiBounds(0, 0, 0, 0);
    private UiBounds statusPane = new UiBounds(0, 0, 0, 0);
    private UiBounds footerBounds = new UiBounds(0, 0, 0, 0);
    private UiBounds inputBounds = new UiBounds(0, 0, 0, 0);
    private UiBounds[] rowBounds = new UiBounds[0];
    private float uiScale = 1.0F;
    private float bodyFont = 12.0F;
    private int rowHeight = 22;
    private int listTop;

    OpusListTerminalPage(ClientOverlayController controller, UiRuntime runtime, String badge) {
        super(controller, runtime);
        this.badge = badge;
    }

    /** Terminal title shown after "OPUS CLIENT |". */
    abstract String title();

    /** MENU pane label, e.g. "WORLDS" or "SERVERS". */
    abstract String menuLabel();

    /** Rows as {primary, secondary}. Re-read when {@link #reload()} is called. */
    abstract List<String[]> rows();

    /** Invoked when a row is chosen. */
    abstract void activate(int index);

    /** STATUS pane rows for the current selection. */
    abstract String[][] statusRows(int selectedIndex);

    /** Footer hint text. */
    abstract String footerHint();

    /** Route used by ESC. World/server lists return to title by default. */
    UiRoute backRoute() {
        return UiRoute.mainMenu();
    }

    /** Whether the direct-entry field is offered on this page. */
    boolean supportsInput() {
        return false;
    }

    /** Prompt shown in front of the direct-entry field. */
    String inputPrompt() {
        return "> ";
    }

    /** Called with the typed text when the user presses Enter in edit mode. */
    void submitInput(String text) {
    }

    final void reload() {
        cachedRows = rows();
        if (focusedIndex >= size()) {
            focusedIndex = Math.max(0, size() - 1);
        }
        clampScroll();
    }

    private int size() {
        return cachedRows == null ? 0 : cachedRows.size();
    }

    @Override
    public void layout(UiBounds nextViewport) {
        super.layout(nextViewport);
        if (cachedRows == null) {
            cachedRows = rows();
        }
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
        int menuWidth = Math.round(bodyWidth * 0.5F);

        menuPane = new UiBounds(bodyLeft, bodyTop, menuWidth, bodyHeight);
        statusPane = new UiBounds(menuPane.right() + pad, bodyTop, bodyRight - (menuPane.right() + pad), bodyHeight);

        int paneHeaderHeight = Math.round(30 * uiScale);
        listTop = menuPane.y + paneHeaderHeight;
        int inputHeight = supportsInput() ? Math.round(22 * uiScale) : 0;
        if (supportsInput()) {
            inputBounds = new UiBounds(
                    menuPane.x + Math.round(8 * uiScale),
                    menuPane.bottom() - inputHeight - Math.round(6 * uiScale),
                    menuPane.width - Math.round(16 * uiScale),
                    inputHeight);
        }
        rowHeight = Math.round(Math.max(18, 22 * uiScale));
        int listBottom = supportsInput() ? inputBounds.y - Math.round(6 * uiScale) : menuPane.bottom() - Math.round(8 * uiScale);
        int listHeight = Math.max(rowHeight, listBottom - listTop);
        visibleRows = Math.max(1, listHeight / (rowHeight + gap));

        rowBounds = new UiBounds[visibleRows];
        int rowInset = Math.round(8 * uiScale);
        for (int index = 0; index < visibleRows; index++) {
            rowBounds[index] = new UiBounds(
                    menuPane.x + rowInset,
                    listTop + index * (rowHeight + gap),
                    menuPane.width - rowInset * 2,
                    rowHeight);
        }
        clampScroll();
    }

    private void clampScroll() {
        int count = size();
        if (focusedIndex < scrollTop) {
            scrollTop = focusedIndex;
        } else if (focusedIndex >= scrollTop + visibleRows) {
            scrollTop = focusedIndex - visibleRows + 1;
        }
        int maxTop = Math.max(0, count - visibleRows);
        if (scrollTop > maxTop) {
            scrollTop = maxTop;
        }
        if (scrollTop < 0) {
            scrollTop = 0;
        }
    }

    @Override
    public void render(UiRenderer renderer, UiInput input) {
        syncFocusToMouse(input);
        renderer.fill(viewport, BACKGROUND);
        renderHeader(renderer);
        renderPane(renderer, menuPane, menuLabel());
        renderPane(renderer, statusPane, "STATUS");
        renderRows(renderer);
        renderStatus(renderer);
        if (supportsInput()) {
            renderInput(renderer);
        }
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
        renderer.monoText(badge, x + titleWidth + cell * 3, y, titleFont, ACCENT);

        String right = title();
        float rightWidth = renderer.measureMonoText(right, bodyFont);
        renderer.monoText(right, headerBounds.right() - Math.round(14 * uiScale) - rightWidth,
                headerBounds.y + (headerBounds.height - bodyFont) / 2.0F, bodyFont, MUTED);
    }

    private void renderPane(UiRenderer renderer, UiBounds pane, String label) {
        renderer.fill(pane, SURFACE);
        renderer.border(pane, Math.max(1, Math.round(uiScale)), BORDER_STRONG);
        float labelFont = Math.max(9.0F, 10.0F * uiScale);
        int headerH = Math.round(24 * uiScale);
        renderer.monoText(label, pane.x + renderer.monoCellWidth(labelFont) + Math.round(4 * uiScale),
                pane.y + (headerH - labelFont) / 2.0F, labelFont, QUIET);
        renderer.fill(new UiBounds(pane.x + 1, pane.y + headerH, pane.width - 2, Math.max(1, Math.round(uiScale))), BORDER_SOFT);
    }

    private void renderRows(UiRenderer renderer) {
        int count = size();
        float cell = renderer.monoCellWidth(bodyFont);
        if (count == 0) {
            renderer.monoText("(empty)", menuPane.x + Math.round(12 * uiScale), listTop + Math.round(6 * uiScale),
                    bodyFont, QUIET);
            return;
        }
        for (int visible = 0; visible < rowBounds.length; visible++) {
            int index = scrollTop + visible;
            if (index >= count) {
                break;
            }
            UiBounds row = rowBounds[visible];
            boolean selected = index == focusedIndex;
            if (selected) {
                renderer.fill(row, SELECT_BG);
                renderer.fill(new UiBounds(row.x, row.y, Math.max(2, Math.round(2 * uiScale)), row.height), ACCENT);
            }
            float textY = row.y + (row.height - bodyFont) / 2.0F;
            float caretX = row.x + cell;
            renderer.monoText(selected ? "\u25B8" : " ", caretX, textY, bodyFont, ACCENT);
            String[] data = cachedRows.get(index);
            String primary = data.length > 0 && data[0] != null ? data[0] : "";
            renderer.monoText(primary, caretX + cell * 2, textY, bodyFont, selected ? FOREGROUND : MUTED);
        }
        // Scroll indicator.
        if (count > visibleRows) {
            String meta = (focusedIndex + 1) + "/" + count;
            float metaWidth = renderer.measureMonoText(meta, bodyFont);
            renderer.monoText(meta, menuPane.right() - metaWidth - Math.round(8 * uiScale),
                    menuPane.y + Math.round(8 * uiScale), bodyFont, QUIET);
        }
    }

    private void renderStatus(UiRenderer renderer) {
        String[][] rows = statusRows(size() == 0 ? -1 : focusedIndex);
        if (rows == null) {
            return;
        }
        float cell = renderer.monoCellWidth(bodyFont);
        float rowH = renderer.monoLineHeight(bodyFont) + Math.round(4 * uiScale);
        float top = statusPane.y + Math.round(34 * uiScale);
        float labelX = statusPane.x + cell + Math.round(4 * uiScale);
        for (int index = 0; index < rows.length; index++) {
            float rowY = top + index * rowH;
            renderer.monoText(rows[index][0], labelX, rowY, bodyFont, QUIET);
            String value = rows[index].length > 1 && rows[index][1] != null ? rows[index][1] : "";
            float valueWidth = renderer.measureMonoText(value, bodyFont);
            renderer.monoText(value, statusPane.right() - cell - Math.round(4 * uiScale) - valueWidth, rowY, bodyFont, FOREGROUND);
        }
    }

    private void renderInput(UiRenderer renderer) {
        renderer.fill(inputBounds, editing ? SELECT_BG : SURFACE);
        renderer.border(inputBounds, Math.max(1, Math.round(uiScale)), editing ? ACCENT : BORDER_SOFT);
        float cell = renderer.monoCellWidth(bodyFont);
        float textY = inputBounds.y + (inputBounds.height - bodyFont) / 2.0F;
        String shown = inputPrompt() + input.toString() + (editing ? "_" : "");
        renderer.monoText(shown, inputBounds.x + cell, textY, bodyFont, editing ? FOREGROUND : QUIET);
    }

    private void renderFooter(UiRenderer renderer) {
        renderer.fill(footerBounds, SURFACE);
        renderer.fill(new UiBounds(footerBounds.x, footerBounds.y, footerBounds.width, Math.max(1, Math.round(uiScale))), BORDER_STRONG);
        float footFont = Math.max(8.0F, 10.0F * uiScale);
        float y = footerBounds.y + (footerBounds.height - footFont) / 2.0F;
        renderer.monoText(footerHint(), footerBounds.x + Math.round(14 * uiScale), y, footFont, QUIET);
    }

    @Override
    public boolean mouseDown(int mouseX, int mouseY, int button) {
        if (button != 0) {
            return false;
        }
        if (supportsInput() && inputBounds.contains(mouseX, mouseY)) {
            editing = true;
            return true;
        }
        int index = rowAt(mouseX, mouseY);
        if (index < 0) {
            return false;
        }
        editing = false;
        focusedIndex = index;
        clampScroll();
        activate(index);
        return true;
    }

    @Override
    public boolean scroll(int amount) {
        if (amount == 0 || size() == 0) {
            return false;
        }
        moveFocus(amount > 0 ? -1 : 1);
        return true;
    }

    @Override
    public boolean keyTyped(char typedCharacter, int keyCode) {
        if (editing) {
            return editKey(typedCharacter, keyCode);
        }
        switch (keyCode) {
            case Keyboard.KEY_UP:
            case Keyboard.KEY_W:
                moveFocus(-1);
                return true;
            case Keyboard.KEY_DOWN:
            case Keyboard.KEY_S:
                moveFocus(1);
                return true;
            case Keyboard.KEY_TAB:
                if (supportsInput()) {
                    editing = true;
                    return true;
                }
                moveFocus(1);
                return true;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
            case Keyboard.KEY_SPACE:
                if (size() > 0) {
                    activate(focusedIndex);
                }
                return true;
            case Keyboard.KEY_ESCAPE:
                runtime.navigate(backRoute());
                return true;
            default:
                break;
        }
        return super.keyTyped(typedCharacter, keyCode);
    }

    private boolean editKey(char typedCharacter, int keyCode) {
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE:
                editing = false;
                return true;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                editing = false;
                submitInput(input.toString().trim());
                input.setLength(0);
                reload();
                return true;
            case Keyboard.KEY_BACK:
                if (input.length() > 0) {
                    input.setLength(input.length() - 1);
                }
                return true;
            default:
                if (typedCharacter >= 0x20 && typedCharacter != 0x7f && input.length() < 128) {
                    input.append(typedCharacter);
                }
                return true;
        }
    }

    private void moveFocus(int delta) {
        int count = size();
        if (count == 0) {
            return;
        }
        focusedIndex = ((focusedIndex + delta) % count + count) % count;
        clampScroll();
    }

    private void syncFocusToMouse(UiInput in) {
        if (in.mouseX == lastMouseX && in.mouseY == lastMouseY) {
            return;
        }
        lastMouseX = in.mouseX;
        lastMouseY = in.mouseY;
        int hovered = rowAt(in.mouseX, in.mouseY);
        if (hovered >= 0) {
            focusedIndex = hovered;
        }
    }

    private int rowAt(int mouseX, int mouseY) {
        for (int visible = 0; visible < rowBounds.length; visible++) {
            int index = scrollTop + visible;
            if (index >= size()) {
                break;
            }
            if (rowBounds[visible].contains(mouseX, mouseY)) {
                return index;
            }
        }
        return -1;
    }

    final String selectedSecondary() {
        if (size() == 0) {
            return "";
        }
        String[] data = cachedRows.get(focusedIndex);
        return data.length > 1 && data[1] != null ? data[1] : "";
    }

    final String selectedPrimary() {
        if (size() == 0) {
            return "";
        }
        String[] data = cachedRows.get(focusedIndex);
        return data.length > 0 && data[0] != null ? data[0] : "";
    }

    final int selectedIndex() {
        return size() == 0 ? -1 : focusedIndex;
    }
}
