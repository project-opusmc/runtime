package org.polydevs.opusmc.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiRenderer;

/** Pure logical layout shared by normal rendering and HUD-editor hit testing. */
final class KeystrokesLayout {
    private static final int MIN_CELL_WIDTH = 22;
    private static final int MIN_CELL_HEIGHT = 18;
    private static final int LABEL_HORIZONTAL_PADDING = 8;
    private static final int LABEL_VERTICAL_PADDING = 8;
    private static final int GAP = 2;

    static final class Cell {
        private final UiBounds bounds;
        private final KeystrokesInputReader.KeyState state;

        private Cell(UiBounds bounds, KeystrokesInputReader.KeyState state) {
            this.bounds = bounds;
            this.state = state;
        }

        UiBounds bounds() {
            return bounds;
        }

        KeystrokesInputReader.KeyState state() {
            return state;
        }
    }

    private static final KeystrokesLayout EMPTY =
            new KeystrokesLayout(0, 0, Collections.<Cell>emptyList());

    private final int width;
    private final int height;
    private final List<Cell> cells;

    private KeystrokesLayout(int width, int height, List<Cell> cells) {
        this.width = width;
        this.height = height;
        this.cells = Collections.unmodifiableList(cells);
    }

    static KeystrokesLayout create(
            UiRenderer renderer,
            KeystrokesInputReader.Snapshot input,
            boolean showMouseButtons,
            boolean showJump) {
        if (renderer == null) {
            throw new IllegalArgumentException("renderer");
        }
        if (input == null || !input.available()) {
            return EMPTY;
        }

        int cellHeight = Math.max(MIN_CELL_HEIGHT, renderer.lineHeight() + LABEL_VERTICAL_PADDING);
        int movementCellWidth = Math.max(
                MIN_CELL_WIDTH,
                widest(renderer, input.forward(), input.left(), input.back(), input.right())
                        + LABEL_HORIZONTAL_PADDING);
        int movementWidth = movementCellWidth * 3 + GAP * 2;
        int totalWidth = movementWidth;

        if (showMouseButtons) {
            int mouseCellWidth = Math.max(
                    MIN_CELL_WIDTH,
                    widest(renderer, input.attack(), input.use()) + LABEL_HORIZONTAL_PADDING);
            totalWidth = Math.max(totalWidth, mouseCellWidth * 2 + GAP);
        }
        if (showJump) {
            totalWidth = Math.max(
                    totalWidth,
                    Math.max(MIN_CELL_WIDTH,
                            renderer.measureText(input.jump().label()) + LABEL_HORIZONTAL_PADDING));
        }

        List<Cell> cells = new ArrayList<Cell>();
        int movementX = (totalWidth - movementWidth) / 2;
        cells.add(new Cell(
                new UiBounds(movementX + movementCellWidth + GAP, 0, movementCellWidth, cellHeight),
                input.forward()));
        int movementY = cellHeight + GAP;
        cells.add(new Cell(new UiBounds(movementX, movementY, movementCellWidth, cellHeight), input.left()));
        cells.add(new Cell(
                new UiBounds(movementX + movementCellWidth + GAP, movementY, movementCellWidth, cellHeight),
                input.back()));
        cells.add(new Cell(
                new UiBounds(movementX + (movementCellWidth + GAP) * 2, movementY,
                        movementCellWidth, cellHeight),
                input.right()));

        int nextY = movementY + cellHeight;
        if (showMouseButtons) {
            nextY += GAP;
            int firstWidth = (totalWidth - GAP) / 2;
            int secondWidth = totalWidth - GAP - firstWidth;
            cells.add(new Cell(new UiBounds(0, nextY, firstWidth, cellHeight), input.attack()));
            cells.add(new Cell(
                    new UiBounds(firstWidth + GAP, nextY, secondWidth, cellHeight), input.use()));
            nextY += cellHeight;
        }
        if (showJump) {
            nextY += GAP;
            cells.add(new Cell(new UiBounds(0, nextY, totalWidth, cellHeight), input.jump()));
            nextY += cellHeight;
        }

        return new KeystrokesLayout(totalWidth, nextY, cells);
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    List<Cell> cells() {
        return cells;
    }

    private static int widest(
            UiRenderer renderer,
            KeystrokesInputReader.KeyState first,
            KeystrokesInputReader.KeyState... rest) {
        int width = renderer.measureText(first.label());
        for (KeystrokesInputReader.KeyState state : rest) {
            width = Math.max(width, renderer.measureText(state.label()));
        }
        return width;
    }
}
