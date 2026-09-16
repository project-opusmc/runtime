package org.polydevs.opusmc.websurface;

public final class CoordinateContractHarness {
    private static final int[][] MATRIX = {
            {800, 600, 800, 600},
            {1280, 720, 1280, 720},
            {1920, 1080, 1920, 1080},
            {800, 500, 1000, 625},
            {480, 320, 720, 480},
            {1280, 800, 2560, 1600},
            {1440, 900, 2880, 1800},
            {1728, 1117, 3456, 2234},
            {1920, 1080, 3840, 2160},
            {2560, 1440, 5120, 2880}
    };

    public static void main(String[] args) {
        for (int[] row : MATRIX) verifyMatrix(row[0], row[1], row[2], row[3]);
        verifyGenerationContract();
        verifyGuiScaleIndependence();
        verifyInvalidGeometryRejected();
        System.out.println("W2 COORDINATE CONTRACT: ALL PASSED");
    }

    private static void verifyMatrix(int logicalWidth, int logicalHeight,
            int framebufferWidth, int framebufferHeight) {
        OpusDisplayMetrics metrics = new OpusDisplayMetrics(
                1L, logicalWidth, logicalHeight, framebufferWidth, framebufferHeight);
        require(close(metrics.scaleX(), framebufferWidth / (double) logicalWidth), "scaleX");
        require(close(metrics.scaleY(), framebufferHeight / (double) logicalHeight), "scaleY");

        int[][] logicalPoints = {
                {0, 0},
                {logicalWidth - 1, 0},
                {0, logicalHeight - 1},
                {logicalWidth - 1, logicalHeight - 1},
                {logicalWidth / 2, logicalHeight / 2},
                {logicalWidth / 3, logicalHeight / 3},
                {(logicalWidth * 7) / 11, (logicalHeight * 5) / 13}
        };

        for (int[] point : logicalPoints) {
            int fx = metrics.logicalToFramebufferX(point[0]);
            int fyTop = metrics.logicalToFramebufferTopY(point[1]);
            int fyBottom = metrics.logicalToFramebufferBottomY(point[1]);
            require(metrics.framebufferToLogicalX(fx) == point[0], "X roundtrip");
            require(metrics.framebufferTopToLogicalY(fyTop) == point[1], "top-Y roundtrip");
            require(metrics.framebufferBottomToLogicalY(fyBottom) == point[1], "bottom-Y roundtrip");
            require(fyTop + fyBottom == framebufferHeight - 1, "Y origin inversion");
        }

        require(metrics.framebufferToLogicalX(0) == 0, "left edge");
        require(metrics.framebufferToLogicalX(framebufferWidth - 1) == logicalWidth - 1, "right edge");
        require(metrics.framebufferBottomToLogicalY(framebufferHeight - 1) == 0, "top edge");
        require(metrics.framebufferBottomToLogicalY(0) == logicalHeight - 1, "bottom edge");

        System.out.println("PASS " + logicalWidth + "x" + logicalHeight + " -> "
                + framebufferWidth + "x" + framebufferHeight + " @ "
                + metrics.scaleX() + "x" + metrics.scaleY());
    }

    private static void verifyGenerationContract() {
        OpusDisplayMetricsTracker tracker = new OpusDisplayMetricsTracker();
        OpusDisplayMetrics first = tracker.update(800, 600, 1600, 1200);
        OpusDisplayMetrics same = tracker.update(800, 600, 1600, 1200);
        OpusDisplayMetrics resized = tracker.update(801, 600, 1602, 1200);
        OpusDisplayMetrics dprChanged = tracker.update(801, 600, 801, 600);
        require(first.generation() == 1L, "initial generation");
        require(same.generation() == 1L, "stable geometry must not advance generation");
        require(resized.generation() == 2L, "resize must advance generation");
        require(dprChanged.generation() == 3L, "DPR/raster change must advance generation");
        System.out.println("PASS generation contract");
    }

    private static void verifyGuiScaleIndependence() {
        OpusDisplayMetrics baseline = new OpusDisplayMetrics(1L, 1440, 900, 2880, 1800);
        for (int minecraftGuiScale = 0; minecraftGuiScale <= 4; minecraftGuiScale++) {
            OpusDisplayMetrics candidate = new OpusDisplayMetrics(1L, 1440, 900, 2880, 1800);
            require(baseline.sameGeometry(candidate),
                    "Minecraft GUI scale changed Web Surface geometry: " + minecraftGuiScale);
            require(candidate.framebufferToLogicalX(1440) == 720, "GUI scale X invariance");
            require(candidate.framebufferBottomToLogicalY(899) == 450, "GUI scale Y invariance");
        }
        System.out.println("PASS Minecraft GUI scale is not part of the Web Surface transform");
    }

    private static void verifyInvalidGeometryRejected() {
        boolean rejected = false;
        try {
            new OpusDisplayMetrics(1L, 0, 900, 2880, 1800);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "invalid geometry accepted");
        System.out.println("PASS invalid geometry rejected");
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 0.0000001D;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
