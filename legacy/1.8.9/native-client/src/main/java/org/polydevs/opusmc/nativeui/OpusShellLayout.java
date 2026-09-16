package org.polydevs.opusmc.nativeui;

/**
 * Pure responsive layout model for the Elementa-owned Minecraft shell.
 *
 * <p>The main and pause routes intentionally share one Lunar-inspired
 * composition: a centered horizontal OPUS lockup, two full-width primary
 * controls, and a compact secondary row. The pause route uses two equal
 * secondary controls; the main route preserves vanilla's Quit action as a
 * square power control beside the two settings controls. The model exposes
 * every button bound separately so drawing and hit testing consume the same
 * geometry during a live resize.</p>
 */
final class OpusShellLayout {
    enum SizeClass {
        MICRO,
        COMPACT,
        REGULAR,
        SPACIOUS
    }

    static final int PAUSE_BUTTON_COUNT = 4;
    static final int MAIN_MENU_BUTTON_COUNT = 5;
    static final int LOCKUP_TEXTURE_WIDTH = 1599;
    static final int LOCKUP_TEXTURE_HEIGHT = 566;
    static final float LOCKUP_ASPECT =
            LOCKUP_TEXTURE_WIDTH / (float) LOCKUP_TEXTURE_HEIGHT;

    final int viewportWidth;
    final int viewportHeight;
    final int buttonCount;
    final SizeClass sizeClass;
    final float panelX;
    final float panelY;
    final float panelWidth;
    final float panelHeight;
    final float logoX;
    final float logoY;
    final float logoWidth;
    final float logoHeight;
    final float buttonTextScale;
    final float density;

    private final float[] buttonX;
    private final float[] buttonY;
    private final float[] buttonWidth;
    private final float[] buttonHeight;

    private OpusShellLayout(
            int viewportWidth,
            int viewportHeight,
            int buttonCount,
            SizeClass sizeClass,
            float panelX,
            float panelY,
            float panelWidth,
            float panelHeight,
            float logoX,
            float logoY,
            float logoWidth,
            float logoHeight,
            float buttonTextScale,
            float density,
            float[] buttonX,
            float[] buttonY,
            float[] buttonWidth,
            float[] buttonHeight) {
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.buttonCount = buttonCount;
        this.sizeClass = sizeClass;
        this.panelX = panelX;
        this.panelY = panelY;
        this.panelWidth = panelWidth;
        this.panelHeight = panelHeight;
        this.logoX = logoX;
        this.logoY = logoY;
        this.logoWidth = logoWidth;
        this.logoHeight = logoHeight;
        this.buttonTextScale = buttonTextScale;
        this.density = density;
        this.buttonX = buttonX;
        this.buttonY = buttonY;
        this.buttonWidth = buttonWidth;
        this.buttonHeight = buttonHeight;
    }

    static OpusShellLayout calculate(int viewportWidth, int viewportHeight, int buttonCount) {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException(
                    "viewport must be positive: " + viewportWidth + "x" + viewportHeight);
        }
        if (buttonCount != PAUSE_BUTTON_COUNT
                && buttonCount != MAIN_MENU_BUTTON_COUNT) {
            throw new IllegalArgumentException(
                    "Lunar shell requires "
                            + PAUSE_BUTTON_COUNT
                            + " or "
                            + MAIN_MENU_BUTTON_COUNT
                            + " buttons: "
                            + buttonCount);
        }

        /*
         * Keep Minecraft's GUI scale latched at one while this screen is open.
         * This continuous density curve preserves the old shell's visual
         * weight without crossing ScaledResolution's discontinuous integer
         * scale thresholds during a native edge drag.
         */
        float density = clamp(
                Math.min(viewportWidth / 427f, viewportHeight / 240f),
                1f,
                2.35f);
        float designWidth = viewportWidth / density;
        float designHeight = viewportHeight / density;
        SizeClass sizeClass = sizeClass(designWidth, designHeight);

        float widthComfort = smoothstep(240f, 760f, designWidth);
        float heightComfort = smoothstep(160f, 500f, designHeight);
        float comfort = Math.min(widthComfort, heightComfort);

        float horizontalMargin = lerp(10f, 30f, comfort);
        float verticalMargin = lerp(8f, 24f, comfort);
        float contentWidth = Math.min(
                lerp(240f, 330f, comfort),
                Math.max(1f, designWidth - horizontalMargin * 2f));

        float logoHeight = lerp(48f, 72f, comfort);
        float logoWidth = logoHeight * LOCKUP_ASPECT;
        float logoGap = lerp(5f, 12f, comfort);
        float primaryHeight = lerp(18f, 28f, comfort);
        float primaryGap = lerp(3f, 7f, comfort);
        float secondaryGap = lerp(4f, 8f, comfort);
        float rowGap = lerp(4f, 8f, comfort);

        /*
         * Compress the complete composition as one unit in extremely short
         * windows. This keeps the lockup, gaps, and controls together instead
         * of allowing only the last row to overlap the viewport.
         */
        float desiredHeight = logoHeight
                + logoGap
                + primaryHeight * 2f
                + primaryGap
                + rowGap
                + primaryHeight;
        float maximumHeight = Math.max(1f, designHeight - verticalMargin * 2f);
        float compositionScale = Math.min(1f, maximumHeight / desiredHeight);
        logoWidth *= compositionScale;
        logoHeight *= compositionScale;
        logoGap *= compositionScale;
        primaryHeight *= compositionScale;
        primaryGap *= compositionScale;
        secondaryGap *= compositionScale;
        rowGap *= compositionScale;

        float contentHeight = logoHeight
                + logoGap
                + primaryHeight * 2f
                + primaryGap
                + rowGap
                + primaryHeight;
        float panelX = (designWidth - contentWidth) / 2f;
        float panelY = (designHeight - contentHeight) / 2f;
        float logoX = panelX + (contentWidth - logoWidth) / 2f;
        float logoY = panelY;

        float primaryX = panelX;
        float primaryY = logoY + logoHeight + logoGap;
        float secondaryY = primaryY + primaryHeight * 2f + primaryGap + rowGap;
        float[] buttonX = new float[buttonCount];
        float[] buttonY = new float[buttonCount];
        float[] buttonWidth = new float[buttonCount];
        float[] buttonHeight = new float[buttonCount];

        buttonX[0] = primaryX;
        buttonX[1] = primaryX;
        buttonY[0] = primaryY;
        buttonY[1] = primaryY + primaryHeight + primaryGap;
        buttonWidth[0] = contentWidth;
        buttonWidth[1] = contentWidth;

        if (buttonCount == MAIN_MENU_BUTTON_COUNT) {
            float utilityWidth = primaryHeight;
            float settingsWidth = Math.max(
                    1f,
                    (contentWidth - utilityWidth - secondaryGap * 2f) / 2f);
            buttonX[2] = primaryX;
            buttonX[3] = primaryX + settingsWidth + secondaryGap;
            buttonX[4] = primaryX + settingsWidth * 2f + secondaryGap * 2f;
            buttonWidth[2] = settingsWidth;
            buttonWidth[3] = settingsWidth;
            buttonWidth[4] = utilityWidth;
        } else {
            float secondaryWidth = Math.max(
                    1f,
                    (contentWidth - secondaryGap) / 2f);
            buttonX[2] = primaryX;
            buttonX[3] = primaryX + secondaryWidth + secondaryGap;
            buttonWidth[2] = secondaryWidth;
            buttonWidth[3] = secondaryWidth;
        }

        for (int index = 2; index < buttonCount; index++) {
            buttonY[index] = secondaryY;
        }
        for (int index = 0; index < buttonCount; index++) {
            buttonHeight[index] = primaryHeight;
        }

        return new OpusShellLayout(
                viewportWidth,
                viewportHeight,
                buttonCount,
                sizeClass,
                panelX * density,
                panelY * density,
                contentWidth * density,
                contentHeight * density,
                logoX * density,
                logoY * density,
                logoWidth * density,
                logoHeight * density,
                lerp(0.92f, 1.08f, comfort) * density,
                density,
                scale(buttonX, density),
                scale(buttonY, density),
                scale(buttonWidth, density),
                scale(buttonHeight, density));
    }

    float buttonX(int index) {
        checkIndex(index);
        return buttonX[index];
    }

    float buttonY(int index) {
        checkIndex(index);
        return buttonY[index];
    }

    float buttonWidth(int index) {
        checkIndex(index);
        return buttonWidth[index];
    }

    float buttonHeight(int index) {
        checkIndex(index);
        return buttonHeight[index];
    }

    String diagnosticValue() {
        return sizeClass.name()
                + "|viewport=" + viewportWidth + "x" + viewportHeight
                + "|panel=" + rounded(panelX) + "," + rounded(panelY)
                + "," + rounded(panelWidth) + "x" + rounded(panelHeight)
                + "|logo=" + rounded(logoWidth) + "x" + rounded(logoHeight)
                + "|primary=" + rounded(buttonWidth(0)) + "x" + rounded(buttonHeight(0))
                + "|secondary=" + rounded(buttonWidth(2)) + "x" + rounded(buttonHeight(2))
                + (buttonCount == MAIN_MENU_BUTTON_COUNT
                        ? "|utility=" + rounded(buttonWidth(4)) + "x" + rounded(buttonHeight(4))
                        : "")
                + "|density=" + roundedHundredths(density)
                + "|count=" + buttonCount;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= buttonCount) {
            throw new IndexOutOfBoundsException("button index: " + index);
        }
    }

    private static SizeClass sizeClass(float width, float height) {
        if (height < 205 || width < 250) {
            return SizeClass.MICRO;
        }
        if (height < 340 || width < 420) {
            return SizeClass.COMPACT;
        }
        if (height >= 520) {
            return SizeClass.SPACIOUS;
        }
        return SizeClass.REGULAR;
    }

    private static float[] scale(float[] values, float factor) {
        float[] scaled = new float[values.length];
        for (int index = 0; index < values.length; index++) {
            scaled[index] = values[index] * factor;
        }
        return scaled;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private static float smoothstep(float start, float end, float value) {
        float progress = clamp((value - start) / (end - start), 0f, 1f);
        return progress * progress * (3f - 2f * progress);
    }

    private static int rounded(float value) {
        return Math.round(value);
    }

    private static String roundedHundredths(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
