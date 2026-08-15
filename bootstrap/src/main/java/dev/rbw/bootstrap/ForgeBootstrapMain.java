package dev.rbw.bootstrap;

import java.util.Arrays;
import net.minecraft.launchwrapper.Launch;

/**
 * Starts Forge's LaunchWrapper after receiving the normal game arguments from
 * the launcher's stdin protocol.
 *
 * <p>The launcher keeps account and game arguments out of its command line.
 * This entry point therefore accepts the protocol marker (and the fixed
 * legacy LaunchWrapper main marker during migration) as JVM arguments and
 * forwards the decoded game arguments to {@link Launch#main}. It also
 * guarantees the Forge tweaker is present without changing the order of any
 * additional user-approved tweakers.</p>
 */
public final class ForgeBootstrapMain {
    static final String FML_TWEAKER = "net.minecraftforge.fml.common.launcher.FMLTweaker";

    private ForgeBootstrapMain() {
    }

    public static void main(String[] args) throws Throwable {
        GameLaunchStatus launchStatus = GameLaunchStatus.fromSystemProperty();
        launchStatus.installShutdownHook();
        try {
            ForgeBootstrapArguments.validate(args);
            String[] launchArguments = withFmlTweaker(GameArgumentProtocol.read(System.in));

            System.out.println("[RBW/FORGE] bootstrap loaded");
            System.out.println("[RBW/FORGE] launch arguments=" + launchArguments.length);
            launchStatus.markRunning();
            Launch.main(launchArguments);
            launchStatus.markExited();
        } catch (Throwable failure) {
            launchStatus.markFailed();
            throw failure;
        }
    }

    static String[] withFmlTweaker(String[] gameArguments) {
        for (int index = 0; index < gameArguments.length; index++) {
            if (!"--tweakClass".equals(gameArguments[index])) {
                continue;
            }
            if (index + 1 >= gameArguments.length) {
                throw new IllegalArgumentException("Missing value for --tweakClass");
            }
            if (FML_TWEAKER.equals(gameArguments[index + 1])) {
                return gameArguments;
            }
            index++;
        }

        String[] launchArguments = Arrays.copyOf(gameArguments, gameArguments.length + 2);
        launchArguments[gameArguments.length] = "--tweakClass";
        launchArguments[gameArguments.length + 1] = FML_TWEAKER;
        return launchArguments;
    }
}
