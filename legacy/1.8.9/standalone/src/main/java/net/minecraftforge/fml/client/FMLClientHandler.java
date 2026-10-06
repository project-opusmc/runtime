package net.minecraftforge.fml.client;

import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiSelectWorld;

public final class FMLClientHandler {
    private static final FMLClientHandler INSTANCE = new FMLClientHandler();

    private FMLClientHandler() {
    }

    public static FMLClientHandler instance() {
        return INSTANCE;
    }

    public void tryLoadExistingWorld(GuiSelectWorld selectWorld, String fileName, String displayName) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                mc.launchIntegratedServer(fileName, displayName, null);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
