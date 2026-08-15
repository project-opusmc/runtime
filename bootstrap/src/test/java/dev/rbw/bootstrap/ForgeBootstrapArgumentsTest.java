package dev.rbw.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ForgeBootstrapArgumentsTest {
    @Test
    void acceptsOnlyTheStdinProtocolMarker() {
        assertDoesNotThrow(() -> ForgeBootstrapArguments.validate(new String[] {
                "--rbw-game-arguments-stdin"
        }));
    }

    @Test
    void rejectsACommandLineGameArgument() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ForgeBootstrapArguments.validate(new String[] {
                        "--rbw-game-arguments-stdin", "--accessToken", "secret"
                }));
    }

    @Test
    void acceptsTheLegacyForgeLaunchMainForCurrentLauncherCompatibility() {
        assertDoesNotThrow(() -> ForgeBootstrapArguments.validate(new String[] {
                "--rbw-game-main",
                "net.minecraft.launchwrapper.Launch",
                "--rbw-game-arguments-stdin"
        }));
    }

    @Test
    void addsTheForgeTweakerWhenItIsMissing() {
        assertArrayEquals(
                new String[] {
                        "--version", "RBW", "--tweakClass", ForgeBootstrapMain.FML_TWEAKER
                },
                ForgeBootstrapMain.withFmlTweaker(new String[] {"--version", "RBW"}));
    }

    @Test
    void preservesAnExistingForgeTweaker() {
        String[] arguments = {
                "--tweakClass", ForgeBootstrapMain.FML_TWEAKER, "--version", "RBW"
        };

        assertArrayEquals(arguments, ForgeBootstrapMain.withFmlTweaker(arguments));
    }

    @Test
    void rejectsATweakClassWithoutAValue() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ForgeBootstrapMain.withFmlTweaker(new String[] {"--tweakClass"}));
    }
}
