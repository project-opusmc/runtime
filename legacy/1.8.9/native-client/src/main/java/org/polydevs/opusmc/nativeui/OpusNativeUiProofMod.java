package org.polydevs.opusmc.nativeui;

import cc.polyfrost.oneconfig.gui.OneConfigGui;
import cc.polyfrost.oneconfig.utils.gui.GuiUtils;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

@Mod(
        modid = OpusNativeUiProofMod.MOD_ID,
        name = "Opus Native UI Compatibility Proof",
        version = OpusNativeUiProofMod.VERSION,
        acceptedMinecraftVersions = "[1.8.9]",
        clientSideOnly = true)
public final class OpusNativeUiProofMod {
    public static final String MOD_ID = "opusnativeuiproof";
    public static final String VERSION = "0.1.0-proof.2";

    private KeyBinding openOneConfig;
    private KeyBinding openElementa;
    private OpusOneConfigProof oneConfigProof;
    private String activeProofScreen;

    @Mod.EventHandler
    public void preInitialize(FMLPreInitializationEvent event) {
        NativeUiRuntimeDiagnostics.initialize(event.getModConfigurationDirectory().getParentFile());
        NativeUiRuntimeDiagnostics.recordLifecycle("opus-pre-init");
        oneConfigProof = new OpusOneConfigProof();
    }

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        NativeUiRuntimeDiagnostics.recordLifecycle("opus-init");
        if (!Boolean.getBoolean("opus.native.proof.enabled")) {
            NativeUiRuntimeDiagnostics.record("proofEnabled", "false");
            return;
        }

        openOneConfig = new KeyBinding(
                "Open stock OneConfig proof",
                Keyboard.KEY_O,
                "Opus Native UI Proof");
        openElementa = new KeyBinding(
                "Open Elementa V11 proof",
                Keyboard.KEY_P,
                "Opus Native UI Proof");
        ClientRegistry.registerKeyBinding(openOneConfig);
        ClientRegistry.registerKeyBinding(openElementa);
        MinecraftForge.EVENT_BUS.register(this);
        boolean sharedEventBus = FMLCommonHandler.instance().bus() == MinecraftForge.EVENT_BUS;
        if (!sharedEventBus) {
            FMLCommonHandler.instance().bus().register(this);
        }
        NativeUiRuntimeDiagnostics.record("sharedForgeEventBus", Boolean.toString(sharedEventBus));
        NativeUiRuntimeDiagnostics.record("proofEnabled", "true");
        NativeUiRuntimeDiagnostics.record("controls", "O=OneConfig,P=Elementa");
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (openOneConfig != null && openOneConfig.isPressed()) {
            NativeUiRuntimeDiagnostics.record("lastInput", "open-oneconfig-request");
            GuiUtils.displayScreen(OneConfigGui.create());
        }
        if (openElementa != null && openElementa.isPressed()) {
            NativeUiRuntimeDiagnostics.record("lastInput", "open-elementa-request");
            Minecraft.getMinecraft().displayGuiScreen(new OpusElementaProofScreen());
        }
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        String next = proofScreenName(event.gui);
        if (activeProofScreen != null && !activeProofScreen.equals(next)) {
            NativeUiRuntimeDiagnostics.recordScreenClose(activeProofScreen);
        }
        if (next != null && !next.equals(activeProofScreen)) {
            activeProofScreen = next;
            NativeUiRuntimeDiagnostics.recordScreenOpen(next);
        } else if (next == null) {
            activeProofScreen = null;
        }
    }

    @SubscribeEvent
    public void onProofDrawPre(GuiScreenEvent.DrawScreenEvent.Pre event) {
        String screen = proofScreenName(event.gui);
        if (screen == null) return;
        NativeUiRuntimeDiagnostics.recordScreenDraw(screen, "pre", GL11.glGetError());
        NativeUiRuntimeDiagnostics.recordDisplayState(screen);
    }

    @SubscribeEvent
    public void onProofDrawPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        String screen = proofScreenName(event.gui);
        if (screen == null) return;
        NativeUiRuntimeDiagnostics.recordScreenDraw(screen, "post", GL11.glGetError());
        NativeUiRuntimeDiagnostics.recordDisplayState(screen);
    }

    private static String proofScreenName(GuiScreen screen) {
        if (screen instanceof OneConfigGui) return "oneconfig";
        if (screen instanceof OpusElementaProofScreen) return "elementa";
        return null;
    }

    public OpusOneConfigProof getOneConfigProof() {
        return oneConfigProof;
    }
}
