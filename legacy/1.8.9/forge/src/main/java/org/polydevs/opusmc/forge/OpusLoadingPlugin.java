package org.polydevs.opusmc.forge;

import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

/** Registers Opus's transformer bridge with Forge's LaunchWrapper. */
@IFMLLoadingPlugin.MCVersion("1.8.9")
@IFMLLoadingPlugin.Name("Opus Forge Coremod")
// Forge injects FMLDeobfTweaker at 1000. Keep the verified obfuscated-name
// patches ahead of it while allowing OptiFine's earlier tweaker to run first.
@IFMLLoadingPlugin.SortingIndex(900)
@IFMLLoadingPlugin.TransformerExclusions({"org.polydevs.opusmc.", "org.objectweb.asm."})
public final class OpusLoadingPlugin implements IFMLLoadingPlugin {
    @Override
    public String[] getASMTransformerClass() {
        return new String[] {OpusForgeClassTransformer.class.getName()};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        try {
            ForgeTelemetryLifecycle.coremodLoaded(data);
            Object deobfuscation = data == null ? null : data.get("runtimeDeobfuscationEnabled");
            System.out.println(
                    "[OPUS/FORGE] coremod registered runtimeDeobfuscationEnabled=" + deobfuscation);
        } catch (RuntimeException failure) {
            ForgeTelemetryLifecycle.reportFailure(failure);
            throw failure;
        }
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
