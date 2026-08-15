package dev.rbw.bootstrap;

import java.nio.file.Path;
import java.nio.file.Paths;

final class BootstrapArguments {
    private final String gameMainClass;
    private final Path gameClasspathFile;

    private BootstrapArguments(String gameMainClass, Path gameClasspathFile) {
        this.gameMainClass = gameMainClass;
        this.gameClasspathFile = gameClasspathFile;
    }

    static BootstrapArguments parse(String[] arguments) {
        String gameMain = null;
        Path classpathFile = null;
        boolean readsGameArgumentsFromStdin = false;

        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if ("--rbw-game-main".equals(argument)) {
                gameMain = requireValue(arguments, ++index, argument);
            } else if ("--rbw-game-classpath-file".equals(argument)) {
                classpathFile = Paths.get(requireValue(arguments, ++index, argument));
            } else if ("--rbw-game-arguments-stdin".equals(argument)) {
                if (readsGameArgumentsFromStdin) {
                    throw new IllegalArgumentException("Duplicate " + argument);
                }
                readsGameArgumentsFromStdin = true;
            } else {
                throw new IllegalArgumentException("Unknown bootstrap argument: " + argument);
            }
        }

        if (gameMain == null || gameMain.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing --rbw-game-main");
        }
        if (classpathFile == null) {
            throw new IllegalArgumentException("Missing --rbw-game-classpath-file");
        }
        if (!readsGameArgumentsFromStdin) {
            throw new IllegalArgumentException("Missing --rbw-game-arguments-stdin");
        }

        return new BootstrapArguments(gameMain, classpathFile);
    }

    private static String requireValue(String[] arguments, int index, String option) {
        if (index >= arguments.length || arguments[index].startsWith("--rbw-")) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return arguments[index];
    }

    String gameMainClass() {
        return gameMainClass;
    }

    Path gameClasspathFile() {
        return gameClasspathFile;
    }
}

