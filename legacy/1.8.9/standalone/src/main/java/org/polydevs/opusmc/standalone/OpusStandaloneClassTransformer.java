package org.polydevs.opusmc.standalone;

import org.polydevs.opusmc.bootstrap.ClassTransformer;
import org.polydevs.opusmc.bootstrap.TransformerChain;
import org.polydevs.opusmc.patches.CombatTelemetryTransformer;
import org.polydevs.opusmc.patches.MinecraftTelemetryTransformer;
import org.polydevs.opusmc.patches.NetworkTelemetryTransformer;
import org.polydevs.opusmc.patches.OptiFineCloudRendererTransformer;
import org.polydevs.opusmc.patches.RenderTelemetryTransformer;
import org.polydevs.opusmc.patches.RetinaBackbufferTransformer;
import org.polydevs.opusmc.patches.RetinaGuiInputTransformer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.launchwrapper.IClassTransformer;

/** Adapts Opus's verified 1.8.9 bytecode patches to LaunchWrapper in standalone mode. */
public final class OpusStandaloneClassTransformer implements IClassTransformer {
    private static final int BASE_TRANSFORMER_COUNT = 9;
    private static final String VANILLA_GUI_PREFIX = "net.minecraft.client.gui.";
    private static final Set<String> BASE_TARGET_CLASSES = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(
                    "ave",
                    "avi",
                    "bfk",
                    "bda",
                    "bcy",
                    "ek",
                    "avo",
                    OptiFineCloudRendererTransformer.TARGET_CLASS)));
    private final TransformerChain transformerChain;

    public OpusStandaloneClassTransformer() {
        this.transformerChain = new TransformerChain(transformers());
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }

        String className = targetClassName(name, transformedName);
        if (!BASE_TARGET_CLASSES.contains(className)
                && !className.startsWith(VANILLA_GUI_PREFIX)) {
            return basicClass;
        }

        try {
            System.out.println(
                    "[OPUS/STANDALONE] transforming class=" + className
                            + " raw=" + normalize(name)
                            + " mapped=" + normalize(transformedName)
                            + " bytes=" + basicClass.length);
            byte[] transformed = transformerChain.transform(className, basicClass);
            if (transformed == null) {
                throw new IllegalStateException("Opus transformer chain returned null");
            }
            return transformed;
        } catch (Throwable failure) {
            StandaloneTelemetryLifecycle.reportFailure(failure);
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            throw new IllegalStateException("Opus standalone patch failed for " + className, failure);
        }
    }

    public static int transformerCount() {
        return BASE_TRANSFORMER_COUNT;
    }

    int configuredTransformerCount() {
        return transformerChain.size();
    }

    static String targetClassName(String name, String transformedName) {
        String normalizedName = normalize(name);
        if (BASE_TARGET_CLASSES.contains(normalizedName)) {
            return normalizedName;
        }

        String normalizedTransformedName = normalize(transformedName);
        if (normalizedTransformedName.startsWith(VANILLA_GUI_PREFIX)) {
            return normalizedTransformedName;
        }
        if (normalizedName.startsWith(VANILLA_GUI_PREFIX)) {
            return normalizedName;
        }
        if ("net.minecraft.client.Minecraft".equals(normalizedTransformedName)) {
            return "ave";
        }
        if ("net.minecraft.client.LoadingScreenRenderer".equals(normalizedTransformedName)) {
            return "avi";
        }
        if ("net.minecraft.client.renderer.EntityRenderer".equals(normalizedTransformedName)) {
            return "bfk";
        }
        if ("net.minecraft.client.multiplayer.PlayerControllerMP".equals(normalizedTransformedName)) {
            return "bda";
        }
        if ("net.minecraft.client.network.NetHandlerPlayClient".equals(normalizedTransformedName)) {
            return "bcy";
        }
        if ("net.minecraft.network.NetworkManager".equals(normalizedTransformedName)) {
            return "ek";
        }
        if ("net.minecraft.client.gui.GuiIngame".equals(normalizedTransformedName)) {
            return "avo";
        }
        return normalizedName;
    }

    private static java.util.List<ClassTransformer> transformers() {
        java.util.List<ClassTransformer> transformers = new java.util.ArrayList<ClassTransformer>();
        transformers.add(new StandaloneLifecycleTransformer());
        transformers.add(new StandaloneEventHookTransformer());
        transformers.add(new RetinaBackbufferTransformer());
        transformers.add(new RetinaGuiInputTransformer());
        transformers.add(new MinecraftTelemetryTransformer());
        transformers.add(new RenderTelemetryTransformer());
        transformers.add(new CombatTelemetryTransformer());
        transformers.add(new NetworkTelemetryTransformer());
        transformers.add(new OptiFineCloudRendererTransformer());
        return transformers;
    }

    private static String normalize(String className) {
        return className == null ? "" : className.replace('/', '.');
    }
}
