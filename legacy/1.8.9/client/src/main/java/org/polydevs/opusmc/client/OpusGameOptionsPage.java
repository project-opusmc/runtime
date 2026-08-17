package org.polydevs.opusmc.client;

import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.ui.UiRoute;
import org.polydevs.opusmc.client.ui.UiRuntime;
import org.lwjgl.input.Keyboard;

/**
 * The Opus terminal Game Options screen. Common video/input options are read
 * and written through the typed {@code GameSettings} API and cycled in place
 * (Left/Right or click), so no vanilla GuiOptions screen is shown. Controls
 * and Language are native Opus routes. OptiFine keeps ownership of its option
 * logic, while the compatibility shell cancels every pixel of its original
 * draw pass and presents the controls as terminal rows.
 */
final class OpusGameOptionsPage extends OpusUiPage {
    private static final int BACKGROUND = 0xFF000000;
    private static final int SURFACE = 0xFF050505;
    private static final int BORDER_STRONG = 0xFF565656;
    private static final int BORDER_SOFT = 0xFF262626;
    private static final int ACCENT = 0xFF55A7FF;
    private static final int FOREGROUND = 0xFFF5F5F5;
    private static final int MUTED = 0xFFB5B5B5;
    private static final int QUIET = 0xFF707070;
    private static final int SELECT_BG = 0xFF10151D;

    private final String[] optionKeys;
    // Rows = option keys followed by deep-screen action rows.
    private final String[] actionLabels = {"Controls", "Video / OptiFine", "Language", "Back"};
    private final int rowCount;
    private int focusedIndex;
    private int scrollTop;
    private int visibleRows = 1;
    private int lastMouseX = Integer.MIN_VALUE;
    private int lastMouseY = Integer.MIN_VALUE;

    private UiBounds headerBounds = new UiBounds(0, 0, 0, 0);
    private UiBounds menuPane = new UiBounds(0, 0, 0, 0);
    private UiBounds statusPane = new UiBounds(0, 0, 0, 0);
    private UiBounds footerBounds = new UiBounds(0, 0, 0, 0);
    private UiBounds[] rowBounds = new UiBounds[0];
    private float uiScale = 1.0F;
    private float bodyFont = 12.0F;
    private int rowHeight = 22;
    private int listTop;

    OpusGameOptionsPage(ClientOverlayController controller, UiRuntime runtime) {
        super(controller, runtime);
        optionKeys = controller.gameOptionKeys();
        rowCount = optionKeys.length + actionLabels.length;
    }

    private boolean isAction(int index) {
        return index >= optionKeys.length;
    }

    @Override
    public void layout(UiBounds nextViewport) {
        super.layout(nextViewport);
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
        int menuWidth = Math.round(bodyWidth * 0.62F);

        menuPane = new UiBounds(bodyLeft, bodyTop, menuWidth, bodyHeight);
        statusPane = new UiBounds(menuPane.right() + pad, bodyTop, bodyRight - (menuPane.right() + pad), bodyHeight);

        int paneHeaderHeight = Math.round(30 * uiScale);
        listTop = menuPane.y + paneHeaderHeight;
        rowHeight = Math.round(Math.max(18, 22 * uiScale));
        int listBottom = menuPane.bottom() - Math.round(8 * uiScale);
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
        if (focusedIndex < scrollTop) {
            scrollTop = focusedIndex;
        } else if (focusedIndex >= scrollTop + visibleRows) {
            scrollTop = focusedIndex - visibleRows + 1;
        }
        int maxTop = Math.max(0, rowCount - visibleRows);
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
        renderPane(renderer, menuPane, "OPTIONS");
        renderPane(renderer, statusPane, "STATUS");
        renderRows(renderer);
        renderStatus(renderer);
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
        renderer.monoText("OPTIONS", x + titleWidth + cell * 3, y, titleFont, ACCENT);
        String right = "GAME OPTIONS";
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
        float cell = renderer.monoCellWidth(bodyFont);
        for (int visible = 0; visible < rowBounds.length; visible++) {
            int index = scrollTop + visible;
            if (index >= rowCount) {
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
            if (isAction(index)) {
                String label = actionLabels[index - optionKeys.length];
                renderer.monoText(label, caretX + cell * 2, textY, bodyFont, selected ? FOREGROUND : MUTED);
            } else {
                String display = controller.gameOptionDisplay(optionKeys[index]);
                renderer.monoText(display, caretX + cell * 2, textY, bodyFont, selected ? FOREGROUND : MUTED);
                if (selected) {
                    String hint = controller.gameOptionIsSlider(optionKeys[index]) ? "< >" : "ENTER";
                    float hintWidth = renderer.measureMonoText(hint, bodyFont);
                    renderer.monoText(hint, row.right() - hintWidth - cell, textY, bodyFont, ACCENT);
                }
            }
        }
        if (rowCount > visibleRows) {
            String meta = (focusedIndex + 1) + "/" + rowCount;
            float metaWidth = renderer.measureMonoText(meta, bodyFont);
            renderer.monoText(meta, menuPane.right() - metaWidth - Math.round(8 * uiScale),
                    menuPane.y + Math.round(8 * uiScale), bodyFont, QUIET);
        }
    }

    private void renderStatus(UiRenderer renderer) {
        String[][] rows;
        if (isAction(focusedIndex)) {
            rows = new String[][] {
                {"Section", actionLabels[focusedIndex - optionKeys.length]},
                {"Opens", "Full screen"},
                {"Note", "OptiFine inside Video"},
            };
        } else {
            String key = optionKeys[focusedIndex];
            rows = new String[][] {
                {"Option", key},
                {"Type", controller.gameOptionIsSlider(key) ? "Slider" : "Toggle"},
                {"Value", controller.gameOptionDisplay(key)},
            };
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

    private void renderFooter(UiRenderer renderer) {
        renderer.fill(footerBounds, SURFACE);
        renderer.fill(new UiBounds(footerBounds.x, footerBounds.y, footerBounds.width, Math.max(1, Math.round(uiScale))), BORDER_STRONG);
        float footFont = Math.max(8.0F, 10.0F * uiScale);
        float y = footerBounds.y + (footerBounds.height - footFont) / 2.0F;
        String hint = "ARROW navigate   < > adjust   ENTER open   ESC back";
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
        clampScroll();
        activate(index);
        return true;
    }

    @Override
    public boolean scroll(int amount) {
        if (amount == 0) {
            return false;
        }
        moveFocus(amount > 0 ? -1 : 1);
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
            case Keyboard.KEY_LEFT:
            case Keyboard.KEY_A:
                adjust(-1);
                return true;
            case Keyboard.KEY_RIGHT:
            case Keyboard.KEY_D:
                adjust(1);
                return true;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
            case Keyboard.KEY_SPACE:
                activate(focusedIndex);
                return true;
            case Keyboard.KEY_ESCAPE:
                runtime.navigate(controller.optionsBackRoute());
                return true;
            default:
                break;
        }
        return super.keyTyped(typedCharacter, keyCode);
    }

    private void adjust(int delta) {
        if (isAction(focusedIndex)) {
            return;
        }
        controller.adjustGameOption(optionKeys[focusedIndex], delta);
    }

    private void activate(int index) {
        if (!isAction(index)) {
            // Toggles cycle on activate; sliders nudge up.
            controller.adjustGameOption(optionKeys[index], 1);
            return;
        }
        switch (index - optionKeys.length) {
            case 0:
                runtime.navigate(UiRoute.controls());
                break;
            case 1:
                controller.openVideoScreen();
                break;
            case 2:
                runtime.navigate(UiRoute.language());
                break;
            default:
                runtime.navigate(controller.optionsBackRoute());
                break;
        }
    }

    private void moveFocus(int delta) {
        focusedIndex = ((focusedIndex + delta) % rowCount + rowCount) % rowCount;
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
            if (index >= rowCount) {
                break;
            }
            if (rowBounds[visible].contains(mouseX, mouseY)) {
                return index;
            }
        }
        return -1;
    }
}
