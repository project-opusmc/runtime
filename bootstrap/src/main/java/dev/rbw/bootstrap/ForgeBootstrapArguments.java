package dev.rbw.bootstrap;

/** Validates the small, secret-free control protocol for the Forge bridge. */
final class ForgeBootstrapArguments {
    private static final String FORGE_LAUNCH_MAIN = "net.minecraft.launchwrapper.Launch";

    private ForgeBootstrapArguments() {
    }

    static void validate(String[] arguments) {
        boolean readsGameArgumentsFromStdin = false;
        boolean hasLegacyGameMain = false;

        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if ("--rbw-game-arguments-stdin".equals(argument)) {
                if (readsGameArgumentsFromStdin) {
                    throw new IllegalArgumentException("Duplicate " + argument);
                }
                readsGameArgumentsFromStdin = true;
            } else if ("--rbw-game-main".equals(argument)) {
                if (hasLegacyGameMain) {
                    throw new IllegalArgumentException("Duplicate " + argument);
                }
                String gameMain = requireValue(arguments, ++index, argument);
                if (!FORGE_LAUNCH_MAIN.equals(gameMain)) {
                    throw new IllegalArgumentException("Forge bootstrap requires " + FORGE_LAUNCH_MAIN);
                }
                hasLegacyGameMain = true;
            } else {
                throw new IllegalArgumentException("Unknown Forge bootstrap argument: " + argument);
            }
        }

        if (!readsGameArgumentsFromStdin) {
            throw new IllegalArgumentException("Missing --rbw-game-arguments-stdin");
        }
    }

    private static String requireValue(String[] arguments, int index, String option) {
        if (index >= arguments.length || arguments[index].startsWith("--rbw-")) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return arguments[index];
    }
}
