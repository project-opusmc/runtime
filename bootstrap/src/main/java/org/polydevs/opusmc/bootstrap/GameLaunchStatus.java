package org.polydevs.opusmc.bootstrap;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

final class GameLaunchStatus {
    static final String PROPERTY = "opus.game.statusFile";
    static final String PID_FILE_NAME = "game.pid";

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
        writeProcessId();
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

    /**
     * Record the game JVM's own process id beside the status file so the
     * launcher can offer an explicit "kill instance" control. Java 8 has no
     * {@code ProcessHandle}, so the pid is parsed from the runtime MXBean name
     * ("pid@host"). Best effort: the launch must still proceed if this fails.
     */
    private void writeProcessId() {
        if (statusFile == null) {
            return;
        }
        long pid = currentProcessId();
        if (pid <= 0) {
            return;
        }
        Path pidFile = statusFile.resolveSibling(PID_FILE_NAME);
        try (FileChannel channel = FileChannel.open(
                pidFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap((pid + "\n").getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        } catch (IOException error) {
            System.err.println("[OPUS/BOOT] could not record game pid: " + error.getMessage());
        }
    }

    static long currentProcessId() {
        try {
            String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
            int separator = runtimeName.indexOf('@');
            String pidText = separator > 0 ? runtimeName.substring(0, separator) : runtimeName;
            return Long.parseLong(pidText.trim());
        } catch (RuntimeException | LinkageError ignored) {
            return -1L;
        }
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
