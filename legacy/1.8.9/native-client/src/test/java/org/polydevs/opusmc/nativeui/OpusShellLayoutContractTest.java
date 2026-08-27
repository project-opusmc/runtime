package org.polydevs.opusmc.nativeui;

/** Pure Java 8 regression checks for resize-safe shell geometry. */
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
            verifyViewport(viewport[0], viewport[1], 5);
            verifyViewport(viewport[0], viewport[1], 4);
        }
        verifyResponsiveWidths();
        verifyReferenceVisualDensity();
        verifyContinuousResizeGeometry();
        verifySmoothTextScaleInterpolation();
        verifyRejectsInvalidInputs();
        System.out.println("verified responsive Opus shell layout contract");
    }

    private static void verifyResponsiveWidths() {
        OpusShellLayout narrow = OpusShellLayout.calculate(320, 360, 5);
        OpusShellLayout standard = OpusShellLayout.calculate(854, 480, 5);
        OpusShellLayout wide = OpusShellLayout.calculate(1920, 1080, 5);
        require(narrow.panelWidth < standard.panelWidth,
                "narrow layout did not reflow its panel width");
        require(standard.panelWidth < wide.panelWidth,
                "wide layout did not grow within its maximum width");
        require(narrow.buttonWidth < standard.buttonWidth,
                "narrow controls did not reflow");
        require(standard.buttonWidth < wide.buttonWidth,
                "wide controls did not grow within bounds");
        require(standard.panelHeight < standard.viewportHeight,
                "standard shell consumes the whole viewport");
    }

    private static void verifyReferenceVisualDensity() {
        OpusShellLayout reference = OpusShellLayout.calculate(854, 480, 5);
        require(reference.density >= 1.99f && reference.density <= 2.01f,
                "reference window did not preserve the former 2x visual density");
        require(reference.panelWidth >= 500f && reference.panelWidth <= 550f,
                "reference panel width is outside the intended visual range");
        require(reference.panelHeight >= 340f && reference.panelHeight <= 390f,
                "reference panel height is outside the intended visual range");
        require(reference.buttonHeight >= 38f && reference.buttonHeight <= 48f,
                "reference button height is outside the intended visual range");
        require(reference.showSubtitle && reference.showFooter,
                "reference shell unexpectedly hid supporting text");
    }

    private static void verifyContinuousResizeGeometry() {
        OpusShellLayout previous = null;
        for (int width = 480; width <= 1500; width++) {
            OpusShellLayout current = OpusShellLayout.calculate(width, 700, 5);
            if (previous != null) {
                requireContinuousStep(previous, current, "horizontal", width);
            }
            previous = current;
        }

        previous = null;
        for (int height = 320; height <= 960; height++) {
            OpusShellLayout current = OpusShellLayout.calculate(1100, height, 5);
            if (previous != null) {
                requireContinuousStep(previous, current, "vertical", height);
            }
            previous = current;
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
        requireDelta(
                previous.panelWidth,
                current.panelWidth,
                maximumDelta,
                direction,
                extent,
                "panelWidth");
        requireDelta(
                previous.panelHeight,
                current.panelHeight,
                maximumDelta,
                direction,
                extent,
                "panelHeight");
        requireDelta(
                previous.buttonHeight,
                current.buttonHeight,
                maximumDelta,
                direction,
                extent,
                "buttonHeight");
        requireDelta(
                previous.titleScale,
                current.titleScale,
                0.05f,
                direction,
                extent,
                "titleScale");
        require(previous.showSubtitle == current.showSubtitle,
                direction + " resize toggled subtitle at ordinary extent " + extent);
        require(previous.showFooter == current.showFooter,
                direction + " resize toggled footer at ordinary extent " + extent);
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
        require(layout.panelX >= 0f, "panel escapes left edge at " + width + "x" + height);
        require(layout.panelY >= 0f, "panel escapes top edge at " + width + "x" + height);
        require(layout.panelX + layout.panelWidth <= width + 0.01f,
                "panel escapes right edge at " + width + "x" + height);
        require(layout.panelY + layout.panelHeight <= height + 0.01f,
                "panel escapes bottom edge at " + width + "x" + height);
        require(layout.buttonWidth > 0f && layout.buttonHeight > 0f,
                "button has no usable size at " + width + "x" + height);

        float previousBottom = -1f;
        for (int index = 0; index < buttonCount; index++) {
            float buttonY = layout.buttonY(index);
            require(buttonY >= layout.panelY, "button starts outside panel");
            require(buttonY + layout.buttonHeight <= layout.panelY + layout.panelHeight + 0.01f,
                    "button ends outside panel");
            require(index == 0 || buttonY >= previousBottom,
                    "button order is not monotonic");
            previousBottom = buttonY + layout.buttonHeight;
        }
    }

    private static void verifyRejectsInvalidInputs() {
        expectFailure(() -> OpusShellLayout.calculate(0, 100, 4));
        expectFailure(() -> OpusShellLayout.calculate(100, 0, 4));
        expectFailure(() -> OpusShellLayout.calculate(100, 100, 0));
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
