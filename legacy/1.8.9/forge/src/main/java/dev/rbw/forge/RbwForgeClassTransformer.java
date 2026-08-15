package dev.rbw.forge;

import dev.rbw.bootstrap.ClassTransformer;
import dev.rbw.bootstrap.TransformerChain;
import dev.rbw.patches.CombatTelemetryTransformer;
import dev.rbw.patches.MinecraftTelemetryTransformer;
import dev.rbw.patches.NetworkTelemetryTransformer;
import dev.rbw.patches.RenderTelemetryTransformer;
import dev.rbw.patches.WindowTitleTransformer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.launchwrapper.IClassTransformer;

/** Adapts Opus's verified 1.8.9 bytecode patches to Forge's class loader. */
public final class RbwForgeClassTransformer implements IClassTransformer {
    private static final int BASE_TRANSFORMER_COUNT = 6;
    private static final Set<String> BASE_TARGET_CLASSES = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(
                    "ave",
                    "bfk",
                    "bda",
                    "bcy",
                    "ek")));
    private final TransformerChain transformerChain;

    public RbwForgeClassTransformer() {
        this.transformerChain = new TransformerChain(transformers());
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }

        String className = targetClassName(name, transformedName);
        if (!BASE_TARGET_CLASSES.contains(className)) {
            return basicClass;
        }

        try {
            System.out.println(
                    "[OPUS/FORGE] transforming class=" + className
                            + " raw=" + normalize(name)
                            + " mapped=" + normalize(transformedName)
                            + " bytes=" + basicClass.length);
            byte[] transformed = transformerChain.transform(className, basicClass);
            if (transformed == null) {
                throw new IllegalStateException("Opus transformer chain returned null");
            }
            return transformed;
        } catch (Throwable failure) {
            ForgeTelemetryLifecycle.reportFailure(failure);
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            throw new IllegalStateException("Opus Forge patch failed for " + className, failure);
        }
    }

    static int transformerCount() {
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

        if ("net.minecraft.client.Minecraft".equals(transformedName)) {
            return "ave";
        }
        if ("net.minecraft.client.renderer.EntityRenderer".equals(transformedName)) {
            return "bfk";
        }
        if ("net.minecraft.client.multiplayer.PlayerControllerMP".equals(transformedName)) {
            return "bda";
        }
        if ("net.minecraft.client.network.NetHandlerPlayClient".equals(transformedName)) {
            return "bcy";
        }
        if ("net.minecraft.network.NetworkManager".equals(transformedName)) {
            return "ek";
        }
        return normalizedName;
    }

    private static java.util.List<ClassTransformer> transformers() {
        java.util.List<ClassTransformer> transformers = new java.util.ArrayList<ClassTransformer>();
        transformers.add(new WindowTitleTransformer());
        transformers.add(new ForgeLifecycleTransformer());
        transformers.add(new MinecraftTelemetryTransformer());
        transformers.add(new RenderTelemetryTransformer());
        transformers.add(new CombatTelemetryTransformer());
        transformers.add(new NetworkTelemetryTransformer());
        return transformers;
    }

    private static String normalize(String className) {
        return className == null ? "" : className.replace('/', '.');
    }
}
