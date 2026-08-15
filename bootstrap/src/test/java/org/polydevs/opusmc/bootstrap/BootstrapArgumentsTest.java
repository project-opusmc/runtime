package org.polydevs.opusmc.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

final class BootstrapArgumentsTest {
    @Test
    void parsesOnlyNonSecretBootstrapArguments() {
        BootstrapArguments parsed = BootstrapArguments.parse(new String[] {
                "--opus-game-main",
                "example.Main",
                "--opus-game-classpath-file",
                "/tmp/classpath.txt",
                "--opus-game-arguments-stdin"
        });

        assertEquals("example.Main", parsed.gameMainClass());
        assertEquals(Paths.get("/tmp/classpath.txt"), parsed.gameClasspathFile());
    }

    @Test
    void rejectsMissingStdinProtocolFlag() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BootstrapArguments.parse(new String[] {
                        "--opus-game-main", "example.Main", "--opus-game-classpath-file", "cp.txt"
                }));
    }
}

