package org.polydevs.opusmc.nativeui;

import cc.polyfrost.oneconfig.gui.OneConfigGui;
import cc.polyfrost.oneconfig.utils.gui.GuiUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

/**
 * Production native UI entry point for the Forge 1.8.9 client.
 *
 * <p>OneConfig remains the owner of module/config/profile/HUD routes. Elementa
 * owns the selected Minecraft shell routes. This class is deliberately small:
 * it only registers the user entry point and keeps the two route owners from
 * competing for a screen or a key event.</p>
 */
@Mod(
        modid = OpusNativeUiMod.MOD_ID,
        name = "Opus Native UI",
        version = OpusNativeUiMod.VERSION,
        acceptedMinecraftVersions = "[1.8.9]",
        clientSideOnly = true)
public final class OpusNativeUiMod {
    public static final String MOD_ID = "opusnativeui";
    public static final String VERSION = "0.1.0";
    public static final int OPEN_KEY = Keyboard.KEY_RSHIFT;

    private static final long TOGGLE_DEBOUNCE_NANOS = 150_000_000L;
    private static GuiScreen returnScreen;

    private KeyBinding openOpusConfig;
    private OpusConfigModule opusConfig;
    private long lastToggleNanos;
    private String activeScreen;

    @Mod.EventHandler
    public void preInitialize(FMLPreInitializationEvent event) {
        NativeUiRuntimeDiagnostics.initialize(event.getModConfigurationDirectory().getParentFile());
        NativeUiRuntimeDiagnostics.recordLifecycle("opus-native-ui-pre-init");
        opusConfig = new OpusConfigModule();
    }

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        NativeUiRuntimeDiagnostics.recordLifecycle("opus-native-ui-init");

        openOpusConfig = new KeyBinding(
                "Open OpusConfig",
                OPEN_KEY,
                "Opus");
        ClientRegistry.registerKeyBinding(openOpusConfig);

        MinecraftForge.EVENT_BUS.register(this);
        boolean sharedEventBus = FMLCommonHandler.instance().bus() == MinecraftForge.EVENT_BUS;
        if (!sharedEventBus) {
            FMLCommonHandler.instance().bus().register(this);
        }

        NativeUiRuntimeDiagnostics.record("sharedEventBus", Boolean.toString(sharedEventBus));
        NativeUiRuntimeDiagnostics.record("nativeUiEnabled", "true");
        NativeUiRuntimeDiagnostics.record("controls", "RIGHT_SHIFT=OpusConfig");
        NativeUiRuntimeDiagnostics.record("shellRoutes", "main-menu,pause-menu,minecraft-settings");
        NativeUiLiveProof.installIfRequested(this, Minecraft.getMinecraft().mcDataDir);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (openOpusConfig == null || !openOpusConfig.isPressed()) {
            return;
        }
        handlePhysicalToggle("game");
    }

    /** Forge's ordinary key event is not posted while a GuiScreen owns input. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onGuiKeyInput(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!Keyboard.getEventKeyState()) {
            return;
        }

        if (handleGuiKey(Keyboard.getEventKey(), "gui")) {
            event.setCanceled(true);
        }
    }

    /** Opens or closes the real OneConfig screen through its supported host. */
    public static void openOpusConfig() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen instanceof OneConfigGui) {
            restorePreviousScreen();
            NativeUiRuntimeDiagnostics.record("lastInput", "opusconfig-close");
            return;
        }
        returnScreen = minecraft.currentScreen;
        GuiUtils.displayScreen(OneConfigGui.create());
        NativeUiRuntimeDiagnostics.record("lastInput", "opusconfig-open");
    }

    private static boolean restorePreviousScreen() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!(minecraft.currentScreen instanceof OneConfigGui)) {
            return false;
        }
        minecraft.displayGuiScreen(returnScreen);
        returnScreen = null;
        NativeUiRuntimeDiagnostics.record("lastInput", "opusconfig-restore");
        return true;
    }

    private boolean handleGuiKey(int eventKey, String source) {
        if (eventKey == OPEN_KEY) {
            return handlePhysicalToggle(source);
        }
        return eventKey == Keyboard.KEY_ESCAPE
                && Minecraft.getMinecraft().currentScreen instanceof OneConfigGui
                && restorePreviousScreen();
    }

    boolean handleLiveProofKey(int eventKey) {
        return handleGuiKey(eventKey, "live-proof");
    }

    private boolean handlePhysicalToggle(String source) {
        if (!canHandleGlobalToggle()) {
            NativeUiRuntimeDiagnostics.record("lastInput", "right-shift-ignored-focus");
            return false;
        }
        long now = System.nanoTime();
        if (now - lastToggleNanos < TOGGLE_DEBOUNCE_NANOS) {
            NativeUiRuntimeDiagnostics.record("lastInput", "right-shift-debounced");
            return false;
        }
        lastToggleNanos = now;
        NativeUiRuntimeDiagnostics.record("lastInput", "right-shift-" + source);
        openOpusConfig();
        return true;
    }

    private boolean canHandleGlobalToggle() {
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        // Chat and the controls screen own their keyboard input. In
        // particular, do not steal Right Shift while a key binding is being
        // captured by vanilla Minecraft.
        return !(screen instanceof GuiChat) && !(screen instanceof GuiControls);
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (event.gui instanceof GuiMainMenu) {
            event.gui = new OpusElementaShellScreen(OpusElementaShellScreen.Route.MAIN_MENU);
        } else if (event.gui instanceof GuiIngameMenu) {
            event.gui = new OpusElementaShellScreen(OpusElementaShellScreen.Route.PAUSE_MENU);
        }

        String next = screenName(event.gui);
        if (activeScreen != null && !activeScreen.equals(next)) {
            NativeUiRuntimeDiagnostics.recordScreenClose(activeScreen);
        }
        if (next != null && !next.equals(activeScreen)) {
            activeScreen = next;
            NativeUiRuntimeDiagnostics.recordScreenOpen(next);
        } else if (next == null) {
            activeScreen = null;
        }
    }

    @SubscribeEvent
    public void onNativeUiDrawPre(GuiScreenEvent.DrawScreenEvent.Pre event) {
        String screen = screenName(event.gui);
        if (screen == null) return;
        NativeUiRuntimeDiagnostics.recordScreenDraw(screen, "pre", GL11.glGetError());
        NativeUiRuntimeDiagnostics.recordDisplayState(screen);
    }

    @SubscribeEvent
    public void onNativeUiDrawPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        String screen = screenName(event.gui);
        if (screen == null) return;
        NativeUiRuntimeDiagnostics.recordScreenDraw(screen, "post", GL11.glGetError());
        NativeUiRuntimeDiagnostics.recordDisplayState(screen);
    }

    private static String screenName(GuiScreen screen) {
        if (screen instanceof OneConfigGui) return "opusconfig";
        if (screen instanceof OpusElementaShellScreen) return "elementa";
        return null;
    }

    public OpusConfigModule getOpusConfig() {
        return opusConfig;
    }

}
