package org.polydevs.opusmc.standalone;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.net.URL;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OpusTweakerTest {
    @Test
    public void testOwnsLaunchArgumentsWhenOptiFineIsAbsent() {
        Launch.blackboard = new HashMap<String, Object>();
        OpusTweaker tweaker = new OpusTweaker();
        List<String> args = Arrays.asList("--username", "TestPlayer", "--version", "1.8.9");
        tweaker.acceptOptions(args, new File("."), new File("."), "1.8.9");
        String[] launchArgs = tweaker.getLaunchArguments();
        assertArrayEquals(args.toArray(new String[0]), launchArgs);
    }

    @Test
    public void testDoesNotDuplicateArgumentsWhenOptiFineIsDeclared() {
        Launch.blackboard = new HashMap<String, Object>();
        Launch.blackboard.put(
                "Tweaks",
                Arrays.asList(new optifine.OptiFineTweaker()));
        OpusTweaker tweaker = new OpusTweaker();
        List<String> args = Arrays.asList(
                "--username", "TestPlayer", "--userProperties", "{}", "--version", "1.8.9");
        tweaker.acceptOptions(args, new File("."), new File("."), "1.8.9");
        assertArrayEquals(new String[0], tweaker.getLaunchArguments());
    }

    @Test
    public void testLoadedTweakLookupDoesNotDependOnClassVisibility() {
        Launch.blackboard = new HashMap<String, Object>();
        Launch.blackboard.put("Tweaks", Arrays.asList(new optifine.OptiFineTweaker()));
        assertTrue(OpusTweaker.hasLoadedTweaker(optifine.OptiFineTweaker.class.getName()));
        assertFalse(OpusTweaker.hasLoadedTweaker("missing.Tweaker"));
    }

    @Test
    public void testDoesNotDuplicateArgumentsWhenLaunchWrapperConsumedTweakerList() throws Exception {
        Launch.blackboard = new HashMap<String, Object>();
        Launch.blackboard.put("Tweaks", Arrays.asList());
        LaunchClassLoader previousClassLoader = Launch.classLoader;
        URL testClasses = optifine.OptiFineTweaker.class.getProtectionDomain()
                .getCodeSource().getLocation();
        try {
            Launch.classLoader = new LaunchClassLoader(new URL[] {testClasses});
            OpusTweaker tweaker = new OpusTweaker();
            List<String> args = Arrays.asList(
                    "--username", "TestPlayer", "--userProperties", "{}", "--version", "1.8.9");
            tweaker.acceptOptions(args, new File("."), new File("."), "1.8.9");
            assertTrue(OpusTweaker.hasTweakerClassOnLaunchClassPath(
                    optifine.OptiFineTweaker.class.getName()));
            assertArrayEquals(new String[0], tweaker.getLaunchArguments());
        } finally {
            Launch.classLoader = previousClassLoader;
        }
    }
}
