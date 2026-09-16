package org.polydevs.opusmc.nativeui;

/** Pure Java 8 regression checks for the Lunar-inspired shell geometry. */
public final class OpusShellLayoutContractTest {
    private OpusShellLayoutContractTest() {
    }

    public static void main(String[] args) {
        for (int[] viewport : new int[][]{
                {200, 150},
                {320, 180},
                {427, 240},
                {854, 480},
                {1280, 720},
                {1920, 1080},
        }) {
            verifyViewport(viewport[0], viewport[1], OpusShellLayout.PAUSE_BUTTON_COUNT);
            verifyViewport(viewport[0], viewport[1], OpusShellLayout.MAIN_MENU_BUTTON_COUNT);
        }
        verifyResponsiveWidths();
        verifyReferenceVisualDensity();
        verifyLunarRows();
        verifyContinuousResizeGeometry();
        verifySmoothTextScaleInterpolation();
        verifyRejectsInvalidInputs();
        System.out.println("verified responsive Lunar-inspired Opus shell layout contract");
    }

    private static void verifyResponsiveWidths() {
        OpusShellLayout narrow = OpusShellLayout.calculate(
                320, 360, OpusShellLayout.MAIN_MENU_BUTTON_COUNT);
        OpusShellLayout standard = OpusShellLayout.calculate(
                854, 480, OpusShellLayout.MAIN_MENU_BUTTON_COUNT);
        OpusShellLayout wide = OpusShellLayout.calculate(
                1920, 1080, OpusShellLayout.MAIN_MENU_BUTTON_COUNT);
        require(narrow.panelWidth < standard.panelWidth,
                "narrow layout did not reflow its content width");
        require(standard.panelWidth < wide.panelWidth,
                "wide layout did not grow within its maximum width");
        require(narrow.buttonWidth(0) < standard.buttonWidth(0),
                "narrow primary controls did not reflow");
        require(standard.buttonWidth(0) < wide.buttonWidth(0),
                "wide primary controls did not grow within bounds");
        require(standard.panelHeight < standard.viewportHeight,
                "standard shell consumes the whole viewport");
    }

    private static void verifyReferenceVisualDensity() {
        OpusShellLayout reference = OpusShellLayout.calculate(
                854, 480, OpusShellLayout.MAIN_MENU_BUTTON_COUNT);
        require(reference.density >= 1.99f && reference.density <= 2.01f,
                "reference window did not preserve the former 2x visual density");
        require(reference.panelWidth >= 470f && reference.panelWidth <= 550f,
                "reference content width is outside the intended visual range");
        require(reference.panelHeight >= 240f && reference.panelHeight <= 280f,
                "reference content height is outside the intended visual range");
        require(reference.buttonHeight(0) >= 32f && reference.buttonHeight(0) <= 48f,
                "reference button height is outside the intended visual range");
        require(reference.logoWidth >= 250f && reference.logoWidth <= 340f,
                "reference lockup width is outside the intended visual range");
        require(reference.logoHeight >= 90f && reference.logoHeight <= 120f,
                "reference lockup height is outside the intended visual range");
        requireFloat(
                reference.logoWidth / reference.logoHeight,
                OpusShellLayout.LOCKUP_ASPECT,
                "reference lockup aspect ratio");
    }

    private static void verifyLunarRows() {
        OpusShellLayout pause = OpusShellLayout.calculate(
                854, 480, OpusShellLayout.PAUSE_BUTTON_COUNT);
        requireFloat(pause.buttonX(0), pause.buttonX(1),
                "primary buttons are not aligned");
        requireFloat(pause.buttonWidth(0), pause.buttonWidth(1),
                "primary buttons have different widths");
        requireFloat(pause.buttonWidth(2), pause.buttonWidth(3),
                "secondary buttons have different widths");
        requireFloat(pause.buttonY(2), pause.buttonY(3),
                "secondary buttons are not on one row");
        require(pause.buttonWidth(0) > pause.buttonWidth(2),
                "secondary buttons did not form a two-column row");
        require(pause.buttonY(1) + pause.buttonHeight(1) <= pause.buttonY(2),
                "secondary row overlaps the primary stack");
        require(pause.logoY + pause.logoHeight <= pause.buttonY(0),
                "lockup overlaps the primary stack");
        requireFloat(
                pause.panelY + pause.panelHeight,
                pause.buttonY(2) + pause.buttonHeight(2),
                "removed footer still occupies layout space");

        OpusShellLayout main = OpusShellLayout.calculate(
                854, 480, OpusShellLayout.MAIN_MENU_BUTTON_COUNT);
        requireFloat(main.buttonWidth(2), main.buttonWidth(3),
                "main-menu settings controls have different widths");
        requireFloat(main.buttonY(2), main.buttonY(3),
                "main-menu settings controls are not on one row");
        requireFloat(main.buttonY(3), main.buttonY(4),
                "main-menu power control is not on the secondary row");
        requireFloat(main.buttonWidth(4), main.buttonHeight(4),
                "main-menu power control is not square");
        require(main.buttonX(4) + main.buttonWidth(4)
                        <= main.panelX + main.panelWidth + 0.01f,
                "main-menu power control escapes the content width");
    }

    private static void verifyContinuousResizeGeometry() {
        for (int buttonCount : new int[]{
                OpusShellLayout.PAUSE_BUTTON_COUNT,
                OpusShellLayout.MAIN_MENU_BUTTON_COUNT,
        }) {
            OpusShellLayout previous = null;
            for (int width = 480; width <= 1500; width++) {
                OpusShellLayout current = OpusShellLayout.calculate(
                        width, 700, buttonCount);
                if (previous != null) {
                    requireContinuousStep(previous, current, "horizontal", width);
                }
                previous = current;
            }

            previous = null;
            for (int height = 320; height <= 960; height++) {
                OpusShellLayout current = OpusShellLayout.calculate(
                        1100, height, buttonCount);
                if (previous != null) {
                    requireContinuousStep(previous, current, "vertical", height);
                }
                previous = current;
            }
        }
    }

    private static void verifySmoothTextScaleInterpolation() {
        OpusSmoothScale scale = new OpusSmoothScale();
        require(scale.setTarget(2f), "smooth scale did not initialize");
        requireFloat(scale.getCurrent(), 2f, "smooth scale initial value");

        require(!scale.setTarget(3f), "smooth scale reinitialized on target change");
        float firstFrame = scale.update(1f / 60f);
        require(firstFrame > 2f && firstFrame < 3f,
                "smooth scale jumped directly to its target");
        require(firstFrame - 2f <= OpusSmoothScale.MAX_STEP_PER_UPDATE + OpusSmoothScale.EPSILON,
                "smooth scale exceeded its per-frame visual step bound");

        float previous = firstFrame;
        for (int frame = 0; frame < 240; frame++) {
            float current = scale.update(1f / 60f);
            require(current >= previous,
                    "smooth scale moved backward while approaching a larger target");
            require(current <= 3f,
                    "smooth scale overshot its target");
            previous = current;
        }
        requireFloat(scale.getCurrent(), 3f, "smooth scale did not settle");

        scale.setTarget(1f);
        float reverseFrame = scale.update(1f / 60f);
        require(reverseFrame < 3f && reverseFrame > 1f,
                "smooth scale jumped directly on reverse resize");

        float boundedStep = scale.update(10f);
        require(boundedStep < reverseFrame,
                "smooth scale ignored its frame-delta bound");
        require(boundedStep > 1f,
                "smooth scale reached the reverse target too early");
    }

    private static void requireContinuousStep(
            OpusShellLayout previous,
            OpusShellLayout current,
            String direction,
            int extent) {
        float maximumDelta = 3f;
        requireDelta(previous.panelX, current.panelX, maximumDelta, direction, extent, "panelX");
        requireDelta(previous.panelY, current.panelY, maximumDelta, direction, extent, "panelY");
        requireDelta(previous.panelWidth, current.panelWidth, maximumDelta, direction, extent, "panelWidth");
        requireDelta(previous.panelHeight, current.panelHeight, maximumDelta, direction, extent, "panelHeight");
        require(previous.buttonCount == current.buttonCount,
                direction + " resize changed the button count");
        for (int index = 0; index < previous.buttonCount; index++) {
            requireDelta(
                    previous.buttonX(index),
                    current.buttonX(index),
                    maximumDelta,
                    direction,
                    extent,
                    "button" + index + "X");
            requireDelta(
                    previous.buttonY(index),
                    current.buttonY(index),
                    maximumDelta,
                    direction,
                    extent,
                    "button" + index + "Y");
            requireDelta(
                    previous.buttonWidth(index),
                    current.buttonWidth(index),
                    maximumDelta,
                    direction,
                    extent,
                    "button" + index + "Width");
            requireDelta(
                    previous.buttonHeight(index),
                    current.buttonHeight(index),
                    maximumDelta,
                    direction,
                    extent,
                    "button" + index + "Height");
        }
        requireDelta(
                previous.logoWidth,
                current.logoWidth,
                8f,
                direction,
                extent,
                "logoWidth");
        requireDelta(
                previous.logoHeight,
                current.logoHeight,
                maximumDelta,
                direction,
                extent,
                "logoHeight");
        requireDelta(
                previous.buttonTextScale,
                current.buttonTextScale,
                0.05f,
                direction,
                extent,
                "buttonTextScale");
    }

    private static void requireDelta(
            float previous,
            float current,
            float maximum,
            String direction,
            int extent,
            String field) {
        float delta = Math.abs(current - previous);
        require(delta <= maximum,
                direction + " resize jumped " + field + " by " + delta + " at " + extent);
    }

    private static void verifyViewport(int width, int height, int buttonCount) {
        OpusShellLayout layout = OpusShellLayout.calculate(width, height, buttonCount);
        require(layout.panelX >= 0f, "content escapes left edge at " + width + "x" + height);
        require(layout.panelY >= 0f, "content escapes top edge at " + width + "x" + height);
        require(layout.panelX + layout.panelWidth <= width + 0.01f,
                "content escapes right edge at " + width + "x" + height);
        require(layout.panelY + layout.panelHeight <= height + 0.01f,
                "content escapes bottom edge at " + width + "x" + height);
        require(layout.logoX >= 0f && layout.logoY >= 0f,
                "logo escapes the viewport at " + width + "x" + height);
        require(layout.logoX + layout.logoWidth <= width + 0.01f,
                "logo escapes right edge at " + width + "x" + height);
        require(layout.logoY + layout.logoHeight <= height + 0.01f,
                "logo escapes bottom edge at " + width + "x" + height);

        require(layout.buttonWidth(0) > 0f && layout.buttonHeight(0) > 0f,
                "button has no usable size at " + width + "x" + height);

        for (int index = 0; index < buttonCount; index++) {
            float buttonX = layout.buttonX(index);
            float buttonY = layout.buttonY(index);
            require(buttonX >= layout.panelX, "button starts outside content horizontally");
            require(buttonY >= layout.panelY, "button starts outside content vertically");
            require(buttonX + layout.buttonWidth(index)
                            <= layout.panelX + layout.panelWidth + 0.01f,
                    "button ends outside content horizontally");
            require(buttonY + layout.buttonHeight(index)
                            <= layout.panelY + layout.panelHeight + 0.01f,
                    "button ends outside content vertically");
        }
    }

    private static void verifyRejectsInvalidInputs() {
        expectFailure(() -> OpusShellLayout.calculate(0, 100, 4));
        expectFailure(() -> OpusShellLayout.calculate(100, 0, 4));
        expectFailure(() -> OpusShellLayout.calculate(100, 100, 0));
        expectFailure(() -> OpusShellLayout.calculate(100, 100, 3));
        expectFailure(() -> OpusShellLayout.calculate(100, 100, 6));
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("invalid layout input was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected contract failure.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void requireFloat(float actual, float expected, String message) {
        require(Math.abs(actual - expected) <= 0.0005f,
                message + ": expected " + expected + " but was " + actual);
    }
}
