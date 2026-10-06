package org.polydevs.opusmc.standalone;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

/**
 * Primary tweaker for Opus Client standalone mode.
 * Boots into LaunchWrapper and registers the standalone transformers.
 */
public final class OpusTweaker implements ITweaker {
    private static final String OPTIFINE_TWEAKER = "optifine.OptiFineTweaker";
    private static final String TWEAKS_BLACKBOARD_KEY = "Tweaks";
    private List<String> launchArguments = new ArrayList<String>();
    private boolean anotherTweakerOwnsLaunchArguments;

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        if (args != null) {
            this.launchArguments.addAll(args);
        }
        this.anotherTweakerOwnsLaunchArguments = hasLoadedTweaker(OPTIFINE_TWEAKER)
                || hasTweakerClassOnLaunchClassPath(OPTIFINE_TWEAKER);
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        System.out.println("[OPUS/STANDALONE] injecting OpusTweaker into LaunchClassLoader");
        classLoader.addClassLoaderExclusion("org.polydevs.opusmc.standalone.");
        classLoader.addClassLoaderExclusion("org.polydevs.opusmc.bootstrap.");
        classLoader.registerTransformer("org.polydevs.opusmc.standalone.OpusUiRemappingTransformer");
        classLoader.registerTransformer("org.polydevs.opusmc.standalone.OpusStandaloneClassTransformer");

        StandaloneTelemetryLifecycle.loaderStarted(classLoader);
    }

    @Override
    public String getLaunchTarget() {
        return "net.minecraft.client.main.Main";
    }

    @Override
    public String[] getLaunchArguments() {
        if (anotherTweakerOwnsLaunchArguments) {
            System.out.println("[OPUS/STANDALONE] OptiFineTweaker owns game arguments; OpusTweaker is transformer-only");
            return new String[0];
        }
        System.out.println("[OPUS/STANDALONE] OpusTweaker owns game arguments (" + launchArguments.size() + ")");
        return launchArguments.toArray(new String[0]);
    }

    static boolean hasLoadedTweaker(String tweakClassName) {
        if (Launch.blackboard == null || tweakClassName == null) {
            return false;
        }
        Object loaded = Launch.blackboard.get(TWEAKS_BLACKBOARD_KEY);
        if (!(loaded instanceof Collection<?>)) {
            return false;
        }
        for (Object value : (Collection<?>) loaded) {
            if (value != null && tweakClassName.equals(value.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    static boolean hasTweakerClassOnLaunchClassPath(String tweakClassName) {
        if (Launch.classLoader == null || tweakClassName == null) {
            return false;
        }
        String resourceName = tweakClassName.replace('.', '/') + ".class";
        try {
            return Launch.classLoader.getResource(resourceName) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

}
