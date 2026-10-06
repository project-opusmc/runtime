package org.polydevs.opusmc.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RetinaDisplayTest {
    private static final int PLATFORM_LINUX = 1;
    private static final int PLATFORM_MACOSX = 2;
    private static final int PLATFORM_WINDOWS = 3;

    @Test
    void scaleTwoKeepsTheNativeGuiButtonUnderThePointer() {
        int framebufferX = RetinaDisplay.mouseCoordinate(
                600, PLATFORM_MACOSX, 2.0F);
        int minecraftGuiX = framebufferX * 800 / 1600;

        assertEquals(1200, framebufferX);
        assertEquals(600, minecraftGuiX);
        assertTrue(minecraftGuiX >= 560 && minecraftGuiX < 640);
    }

    @Test
    void followsTheCurrentMacScaleWithoutCachingThePreviousScale() {
        assertEquals(1200, RetinaDisplay.mouseCoordinate(
                600, PLATFORM_MACOSX, 2.0F));
        assertEquals(600, RetinaDisplay.mouseCoordinate(
                600, PLATFORM_MACOSX, 1.0F));
        assertEquals(900, RetinaDisplay.mouseCoordinate(
                600, PLATFORM_MACOSX, 1.5F));
    }

    @Test
    void leavesNonMacCoordinatesUnscaled() {
        assertEquals(600, RetinaDisplay.mouseCoordinate(
                600, PLATFORM_WINDOWS, 2.0F));
        assertEquals(-12, RetinaDisplay.mouseCoordinate(
                -12, PLATFORM_LINUX, 2.0F));
    }

    @Test
    void invalidMacScalesFallBackToUnscaledCoordinates() {
        assertEquals(600, RetinaDisplay.mouseCoordinate(
                600, PLATFORM_MACOSX, Float.NaN));
        assertEquals(600, RetinaDisplay.mouseCoordinate(
                600, PLATFORM_MACOSX, Float.POSITIVE_INFINITY));
        assertEquals(600, RetinaDisplay.mouseCoordinate(
                600, PLATFORM_MACOSX, 0.0F));
        assertEquals(-12, RetinaDisplay.mouseCoordinate(
                -12, PLATFORM_MACOSX, -2.0F));
    }

    @Test
    void scaledCoordinatesSaturateInsteadOfOverflowing() {
        assertEquals(Integer.MAX_VALUE, RetinaDisplay.mouseCoordinate(
                Integer.MAX_VALUE, PLATFORM_MACOSX, 2.0F));
        assertEquals(Integer.MIN_VALUE, RetinaDisplay.mouseCoordinate(
                Integer.MIN_VALUE, PLATFORM_MACOSX, 2.0F));
    }
}
