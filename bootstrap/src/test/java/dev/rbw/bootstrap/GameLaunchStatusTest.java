package dev.rbw.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GameLaunchStatusTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesLifecyclePhasesToExistingStatusFile() throws Exception {
        Path statusFile = temporaryDirectory.resolve("game.status");
        Files.write(statusFile, "starting\n".getBytes(StandardCharsets.UTF_8));
        GameLaunchStatus status = GameLaunchStatus.forPath(statusFile);

        status.markRunning();
        assertEquals("running\n", new String(Files.readAllBytes(statusFile), StandardCharsets.UTF_8));

        status.markExited();
        assertEquals("exited\n", new String(Files.readAllBytes(statusFile), StandardCharsets.UTF_8));
        assertEquals("exited", status.phase());
    }
}
