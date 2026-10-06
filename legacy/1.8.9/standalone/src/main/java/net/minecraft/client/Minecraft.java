package net.minecraft.client;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.GameSettings;

public class Minecraft {
    public GameSettings gameSettings;
    public GuiScreen currentScreen;

    public static Minecraft getMinecraft() {
        return null;
    }

    public void displayGuiScreen(GuiScreen screen) {
    }

    public void launchIntegratedServer(String folderName, String worldName, Object settings) {
    }
}
