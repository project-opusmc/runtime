package org.polydevs.opusmc.websurface;

public final class InputContractHarness {
    private static final int[][] MATRIX = {
            {800, 600, 800, 600},
            {800, 500, 1000, 625},
            {480, 320, 720, 480},
            {1440, 900, 2880, 1800},
            {2560, 1440, 5120, 2880}
    };

    public static void main(String[] args) {
        for (int[] row : MATRIX) verifyPointerMatrix(row[0], row[1], row[2], row[3]);
        verifyButtons();
        verifyModifiers();
        verifyKeys();
        verifyWheel();
        System.out.println("W4 INPUT CONTRACT: ALL PASSED");
    }

    private static void verifyPointerMatrix(int logicalWidth, int logicalHeight,
            int framebufferWidth, int framebufferHeight) {
        OpusDisplayMetrics metrics = new OpusDisplayMetrics(
                1L, logicalWidth, logicalHeight, framebufferWidth, framebufferHeight);
        OpusInputMapper mapper = new OpusInputMapper(metrics);
        int[][] logicalPoints = {
                {0, 0},
                {logicalWidth - 1, 0},
                {0, logicalHeight - 1},
                {logicalWidth - 1, logicalHeight - 1},
                {logicalWidth / 2, logicalHeight / 2},
                {(logicalWidth * 7) / 13, (logicalHeight * 5) / 11}
        };
        for (int[] point : logicalPoints) {
            int rawX = metrics.logicalToFramebufferX(point[0]);
            int rawBottomY = metrics.logicalToFramebufferBottomY(point[1]);
            OpusInputMapper.Pointer mapped = mapper.fromLwjglFramebuffer(rawX, rawBottomY);
            require(mapped.x == point[0], "X mapping mismatch");
            require(mapped.y == point[1], "Y mapping mismatch");
        }
        OpusInputMapper.Pointer clampedTopLeft = mapper.fromLwjglFramebuffer(-100, framebufferHeight + 100);
        require(clampedTopLeft.x == 0 && clampedTopLeft.y == 0, "top-left clamp mismatch");
        OpusInputMapper.Pointer clampedBottomRight = mapper.fromLwjglFramebuffer(
                framebufferWidth + 100, -100);
        require(clampedBottomRight.x == logicalWidth - 1
                && clampedBottomRight.y == logicalHeight - 1, "bottom-right clamp mismatch");
        System.out.println("PASS raw LWJGL framebuffer -> CSS mapping "
                + framebufferWidth + "x" + framebufferHeight + " -> "
                + logicalWidth + "x" + logicalHeight);
    }

    private static void verifyButtons() {
        require(OpusInputMapper.pointerButtonFromLwjgl(0) == 0, "left button");
        require(OpusInputMapper.pointerButtonFromLwjgl(1) == 1, "right button");
        require(OpusInputMapper.pointerButtonFromLwjgl(2) == 2, "middle button");
        require(OpusInputMapper.pointerButtonFromLwjgl(3) == -1, "invalid button accepted");
        System.out.println("PASS LWJGL left/right/middle button contract");
    }

    private static void verifyModifiers() {
        require(OpusInputMapper.modifiers(true, false, false, false) == OpusInputMapper.MOD_SHIFT,
                "shift modifier");
        require(OpusInputMapper.modifiers(false, true, false, false) == OpusInputMapper.MOD_CONTROL,
                "control modifier");
        require(OpusInputMapper.modifiers(false, false, true, false) == OpusInputMapper.MOD_ALT,
                "alt modifier");
        require(OpusInputMapper.modifiers(false, false, false, true) == OpusInputMapper.MOD_COMMAND,
                "command modifier");
        require(OpusInputMapper.modifiers(true, true, true, true) == 15, "combined modifiers");
        System.out.println("PASS modifier mask contract");
    }

    private static void verifyKeys() {
        require(OpusInputMapper.windowsVirtualKeyFromLwjgl(1) == 0x1B, "escape");
        require(OpusInputMapper.windowsVirtualKeyFromLwjgl(14) == 0x08, "backspace");
        require(OpusInputMapper.windowsVirtualKeyFromLwjgl(15) == 0x09, "tab");
        require(OpusInputMapper.windowsVirtualKeyFromLwjgl(28) == 0x0D, "return");
        require(OpusInputMapper.windowsVirtualKeyFromLwjgl(203) == 0x25, "left arrow");
        require(OpusInputMapper.windowsVirtualKeyFromLwjgl(205) == 0x27, "right arrow");
        require(OpusInputMapper.windowsVirtualKeyFromLwjgl(30) == 0x41, "A");
        require(OpusInputMapper.windowsVirtualKeyFromLwjgl(46) == 0x43, "C");
        require(OpusInputMapper.windowsVirtualKeyFromLwjgl(47) == 0x56, "V");
        require(OpusInputMapper.windowsVirtualKeyFromLwjgl(44) == 0x5A, "Z");
        require(OpusInputMapper.windowsVirtualKeyFromLwjgl(9999) == 0, "unknown key accepted");
        System.out.println("PASS LWJGL -> Windows virtual-key contract");
    }

    private static void verifyWheel() {
        OpusInputMapper mapper = new OpusInputMapper(new OpusDisplayMetrics(1L, 800, 500, 1600, 1000));
        require(mapper.wheelDeltaFromLwjgl(120) == 120, "positive wheel changed");
        require(mapper.wheelDeltaFromLwjgl(-120) == -120, "negative wheel changed");
        System.out.println("PASS wheel sign/magnitude contract");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
