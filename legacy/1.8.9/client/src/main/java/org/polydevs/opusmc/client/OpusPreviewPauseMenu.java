package org.polydevs.opusmc.client;

import java.util.List;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;

/** Preview-only access to the genuine pause menu's FML-populated controls. */
final class OpusPreviewPauseMenu extends GuiIngameMenu {
    List<GuiButton> buttons() {
        return buttonList;
    }
}
