package net.minecraftforge.client.event;

import java.util.ArrayList;
import net.minecraftforge.fml.common.eventhandler.Event;

public class RenderGameOverlayEvent extends Event {
    public enum ElementType {
        ALL,
        HELMET,
        PORTAL,
        CROSSHAIRS,
        BOSSHEALTH,
        ARMOR,
        HEALTH,
        FOOD,
        AIR,
        HOTBAR,
        EXPERIENCE,
        TEXT,
        HEALTHMOUNT,
        JUMPBAR,
        CHAT,
        PLAYER_LIST,
        DEBUG
    }

    public final float partialTicks;
    public final ElementType type;
    public net.minecraft.client.gui.ScaledResolution resolution;

    public RenderGameOverlayEvent(float partialTicks, net.minecraft.client.gui.ScaledResolution resolution) {
        this(partialTicks, resolution, ElementType.ALL);
    }

    protected RenderGameOverlayEvent(float partialTicks,
            net.minecraft.client.gui.ScaledResolution resolution, ElementType type) {
        this.partialTicks = partialTicks;
        this.type = type;
        this.resolution = resolution;
    }

    public RenderGameOverlayEvent(float partialTicks, ElementType type) {
        this.partialTicks = partialTicks;
        this.type = type;
        this.resolution = new net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft());
    }

    public static class Text extends RenderGameOverlayEvent {
        public final ArrayList<String> left;
        public final ArrayList<String> right;

        public Text(RenderGameOverlayEvent parent, ArrayList<String> left, ArrayList<String> right) {
            // The standalone hook already resolved the actual Notch viewport.
            // Rebuilding it here calls a compile-only MCP Minecraft method.
            super(parent.partialTicks, parent.resolution, ElementType.TEXT);
            this.left = left;
            this.right = right;
        }

        public Text(float partialTicks, ArrayList<String> left, ArrayList<String> right) {
            super(partialTicks, ElementType.TEXT);
            this.left = left;
            this.right = right;
        }
    }
}
