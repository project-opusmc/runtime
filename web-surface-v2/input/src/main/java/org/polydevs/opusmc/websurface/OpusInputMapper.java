package org.polydevs.opusmc.websurface;

public final class OpusInputMapper {
    public static final int MOD_SHIFT = 1;
    public static final int MOD_CONTROL = 2;
    public static final int MOD_ALT = 4;
    public static final int MOD_COMMAND = 8;

    private final OpusDisplayMetrics metrics;

    public OpusInputMapper(OpusDisplayMetrics metrics) {
        if (metrics == null) throw new IllegalArgumentException("metrics are required");
        this.metrics = metrics;
    }

    public Pointer fromLwjglFramebuffer(int framebufferX, int framebufferBottomY) {
        return new Pointer(
                metrics.framebufferToLogicalX(framebufferX),
                metrics.framebufferBottomToLogicalY(framebufferBottomY));
    }

    /**
     * Maps macOS LWJGL 2 native mouse coordinates into CEF CSS coordinates.
     * Native mouse X/Y remain in logical window points when Retina backing is
     * enabled; only the Y origin differs (LWJGL bottom, CSS top).
     */
    public Pointer fromLwjglLogical(int logicalX, int logicalBottomY) {
        int x = clamp(logicalX, 0, metrics.logicalWidth() - 1);
        int bottomY = clamp(logicalBottomY, 0, metrics.logicalHeight() - 1);
        return new Pointer(x, metrics.logicalHeight() - 1 - bottomY);
    }

    public int wheelDeltaFromLwjgl(int lwjglDelta) {
        return lwjglDelta;
    }

    public static int pointerButtonFromLwjgl(int lwjglButton) {
        return lwjglButton >= 0 && lwjglButton <= 2 ? lwjglButton : -1;
    }

    public static int modifiers(boolean shift, boolean control, boolean alt, boolean command) {
        int result = 0;
        if (shift) result |= MOD_SHIFT;
        if (control) result |= MOD_CONTROL;
        if (alt) result |= MOD_ALT;
        if (command) result |= MOD_COMMAND;
        return result;
    }

    public static int windowsVirtualKeyFromLwjgl(int lwjglKey) {
        switch (lwjglKey) {
            case 1: return 0x1B;
            case 14: return 0x08;
            case 15: return 0x09;
            case 28:
            case 156: return 0x0D;
            case 57: return 0x20;
            case 199: return 0x24;
            case 200: return 0x26;
            case 201: return 0x21;
            case 203: return 0x25;
            case 205: return 0x27;
            case 207: return 0x23;
            case 208: return 0x28;
            case 209: return 0x22;
            case 211: return 0x2E;
            case 30: return 0x41;
            case 48: return 0x42;
            case 46: return 0x43;
            case 32: return 0x44;
            case 18: return 0x45;
            case 33: return 0x46;
            case 34: return 0x47;
            case 35: return 0x48;
            case 23: return 0x49;
            case 36: return 0x4A;
            case 37: return 0x4B;
            case 38: return 0x4C;
            case 50: return 0x4D;
            case 49: return 0x4E;
            case 24: return 0x4F;
            case 25: return 0x50;
            case 16: return 0x51;
            case 19: return 0x52;
            case 31: return 0x53;
            case 20: return 0x54;
            case 22: return 0x55;
            case 47: return 0x56;
            case 17: return 0x57;
            case 45: return 0x58;
            case 21: return 0x59;
            case 44: return 0x5A;
            case 11: return 0x30;
            case 2: return 0x31;
            case 3: return 0x32;
            case 4: return 0x33;
            case 5: return 0x34;
            case 6: return 0x35;
            case 7: return 0x36;
            case 8: return 0x37;
            case 9: return 0x38;
            case 10: return 0x39;
            default: return 0;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class Pointer {
        public final int x;
        public final int y;

        public Pointer(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}
