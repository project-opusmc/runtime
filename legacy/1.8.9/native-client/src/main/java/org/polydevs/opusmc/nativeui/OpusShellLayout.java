package org.polydevs.opusmc.nativeui;

/**
 * Pure responsive layout model for the Elementa-owned Minecraft shell.
 *
 * <p>The calculations deliberately have no Minecraft or renderer dependency,
 * so every draw and hit-test bound can be verified before the screen is
 * launched. All values are stable shell pixels. The Elementa screen uses GUI
 * scale 1 while it is open, so these coordinates map one-to-one to the live
 * drawable instead of jumping at Minecraft's integer Auto-scale thresholds.</p>
 */
final class OpusShellLayout {
    enum SizeClass {
        MICRO,
        COMPACT,
        REGULAR,
        SPACIOUS
    }

    final int viewportWidth;
    final int viewportHeight;
    final int buttonCount;
    final SizeClass sizeClass;
    final float panelX;
    final float panelY;
    final float panelWidth;
    final float panelHeight;
    final float titleY;
    final float subtitleY;
    final float buttonX;
    final float buttonStartY;
    final float buttonWidth;
    final float buttonHeight;
    final float buttonGap;
    final float footerY;
    final float titleScale;
    final float secondaryScale;
    final float buttonTextScale;
    final float density;
    final boolean showSubtitle;
    final boolean showFooter;

    private OpusShellLayout(
            int viewportWidth,
            int viewportHeight,
            int buttonCount,
            SizeClass sizeClass,
            float panelX,
            float panelY,
            float panelWidth,
            float panelHeight,
            float titleY,
            float subtitleY,
            float buttonX,
            float buttonStartY,
            float buttonWidth,
            float buttonHeight,
            float buttonGap,
            float footerY,
            float titleScale,
            float secondaryScale,
            float buttonTextScale,
            float density,
            boolean showSubtitle,
            boolean showFooter) {
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.buttonCount = buttonCount;
        this.sizeClass = sizeClass;
        this.panelX = panelX;
        this.panelY = panelY;
        this.panelWidth = panelWidth;
        this.panelHeight = panelHeight;
        this.titleY = titleY;
        this.subtitleY = subtitleY;
        this.buttonX = buttonX;
        this.buttonStartY = buttonStartY;
        this.buttonWidth = buttonWidth;
        this.buttonHeight = buttonHeight;
        this.buttonGap = buttonGap;
        this.footerY = footerY;
        this.titleScale = titleScale;
        this.secondaryScale = secondaryScale;
        this.buttonTextScale = buttonTextScale;
        this.density = density;
        this.showSubtitle = showSubtitle;
        this.showFooter = showFooter;
    }

    static OpusShellLayout calculate(int viewportWidth, int viewportHeight, int buttonCount) {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException(
                    "viewport must be positive: " + viewportWidth + "x" + viewportHeight);
        }
        if (buttonCount <= 0) {
            throw new IllegalArgumentException("buttonCount must be positive: " + buttonCount);
        }

        /*
         * Match the shell's former apparent size without borrowing
         * ScaledResolution's discontinuous 1x/2x/3x steps. The function is
         * continuous across edge and corner drags and deliberately capped so
         * a large desktop window still leaves generous negative space.
         */
        float density = clamp(
                Math.min(viewportWidth / 427f, viewportHeight / 240f),
                1f,
                2.35f);
        float designWidth = viewportWidth / density;
        float designHeight = viewportHeight / density;
        SizeClass sizeClass = sizeClass(designWidth, designHeight);

        /*
         * Use one continuous layout curve instead of changing every metric at
         * MICRO/COMPACT/REGULAR/SPACIOUS boundaries. SizeClass remains useful
         * in diagnostics, but crossing a class cannot make the UI jump.
         */
        float widthComfort = smoothstep(240f, 760f, designWidth);
        float heightComfort = smoothstep(160f, 500f, designHeight);
        float comfort = Math.min(widthComfort, heightComfort);

        float horizontalMargin = lerp(8f, 28f, comfort);
        float verticalMargin = lerp(6f, 24f, comfort);
        float panelPadding = lerp(8f, 18f, comfort);
        float maximumPanelWidth = lerp(250f, 340f, comfort);
        float titleLineHeight = lerp(14f, 22f, comfort);
        float subtitleLineHeight = lerp(8f, 10f, comfort);
        float titleToSubtitleGap = lerp(3f, 4f, comfort);
        float headerGap = lerp(6f, 14f, comfort);
        float footerGap = lerp(4f, 8f, comfort);
        float footerLineHeight = lerp(6f, 8f, comfort);
        float buttonGap = lerp(3f, 6f, comfort);
        float targetButtonHeight = lerp(20f, 28f, comfort);
        float titleScale = lerp(1.1f, 1.45f, comfort);
        float secondaryScale = lerp(0.72f, 0.86f, comfort);
        float buttonTextScale = lerp(0.92f, 1.04f, comfort);

        float availablePanelWidth = Math.max(
                1f,
                designWidth - horizontalMargin * 2f);
        float panelWidth = Math.min(maximumPanelWidth, availablePanelWidth);
        float effectivePadding = Math.min(
                panelPadding,
                Math.max(4f, (panelWidth - 1f) / 4f));
        float buttonWidth = Math.max(1f, panelWidth - effectivePadding * 2f);

        boolean showSubtitle = designHeight >= 178f && panelWidth >= 220f;
        boolean showFooter = designHeight >= 196f && panelWidth >= 210f;
        if (!showSubtitle) {
            subtitleLineHeight = 0f;
            titleToSubtitleGap = 0f;
        }
        if (!showFooter) {
            footerGap = 0f;
            footerLineHeight = 0f;
        }

        float fixedHeight = effectivePadding
                + titleLineHeight
                + titleToSubtitleGap
                + subtitleLineHeight
                + headerGap
                + footerGap
                + footerLineHeight
                + effectivePadding;
        float gapsHeight = buttonGap * (buttonCount - 1);
        float maximumPanelHeight = Math.max(
                1f,
                designHeight - verticalMargin * 2f);
        float availableButtonHeight = Math.max(
                1f,
                (maximumPanelHeight - fixedHeight - gapsHeight) / buttonCount);
        float buttonHeight = Math.min(targetButtonHeight, availableButtonHeight);
        float panelHeight = fixedHeight + gapsHeight + buttonHeight * buttonCount;
        float panelX = (designWidth - panelWidth) / 2f;
        float panelY = (designHeight - panelHeight) / 2f;
        float titleY = panelY + effectivePadding;
        float subtitleY = titleY + titleLineHeight + titleToSubtitleGap;
        float buttonStartY = subtitleY + subtitleLineHeight + headerGap;
        float footerY = buttonStartY
                + buttonHeight * buttonCount
                + gapsHeight
                + footerGap;

        return new OpusShellLayout(
                viewportWidth,
                viewportHeight,
                buttonCount,
                sizeClass,
                panelX * density,
                panelY * density,
                panelWidth * density,
                panelHeight * density,
                titleY * density,
                subtitleY * density,
                (panelX + effectivePadding) * density,
                buttonStartY * density,
                buttonWidth * density,
                buttonHeight * density,
                buttonGap * density,
                footerY * density,
                titleScale * density,
                secondaryScale * density,
                buttonTextScale * density,
                density,
                showSubtitle,
                showFooter);
    }

    float buttonY(int index) {
        if (index < 0 || index >= buttonCount) {
            throw new IndexOutOfBoundsException("button index: " + index);
        }
        return buttonStartY + index * (buttonHeight + buttonGap);
    }

    String diagnosticValue() {
        return sizeClass.name()
                + "|viewport=" + viewportWidth + "x" + viewportHeight
                + "|panel=" + rounded(panelX) + "," + rounded(panelY)
                + "," + rounded(panelWidth) + "x" + rounded(panelHeight)
                + "|button=" + rounded(buttonWidth) + "x" + rounded(buttonHeight)
                + "|density=" + roundedHundredths(density)
                + "|count=" + buttonCount;
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
