package org.polydevs.opusmc.bootstrap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

final class GameLaunchStatus {
    static final String PROPERTY = "opus.game.statusFile";

    private final Path statusFile;
    private volatile String phase = "starting";

    private GameLaunchStatus(Path statusFile) {
        this.statusFile = statusFile;
    }

    static GameLaunchStatus fromSystemProperty() {
        String configuredPath = System.getProperty(PROPERTY);
        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            return new GameLaunchStatus(null);
        }
        try {
            return new GameLaunchStatus(Paths.get(configuredPath).toAbsolutePath().normalize());
        } catch (RuntimeException ignored) {
            return new GameLaunchStatus(null);
        }
    }

    static GameLaunchStatus forPath(Path statusFile) {
        return new GameLaunchStatus(statusFile.toAbsolutePath().normalize());
    }

    void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if ("starting".equals(phase) || "running".equals(phase)) {
                mark("terminated");
            }
        }, "opus-game-status"));
    }

    void markRunning() {
        mark("running");
    }

    void markExited() {
        mark("exited");
    }

    void markFailed() {
        mark("failed");
    }

    String phase() {
        return phase;
    }

    private synchronized void mark(String nextPhase) {
        phase = nextPhase;
        if (statusFile == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(statusFile, StandardOpenOption.WRITE)) {
            channel.truncate(0);
            channel.write(ByteBuffer.wrap((nextPhase + "\n").getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        } catch (IOException error) {
            System.err.println("[OPUS/BOOT] could not update game status: " + error.getMessage());
        }
    }
}
