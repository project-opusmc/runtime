package org.polydevs.opusmc.client;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

/**
 * The typed Forge boundary for Opus's in-game client implementation.
 *
 * <p>The production client boundary is currently inert by default so the
 * stock Minecraft 1.8.9 GUI remains authoritative while the Core Mod is
 * stabilized. The former CEF surface is an explicit development opt-in only.
 * </p>
 */
@Mod(
        modid = OpusClientMod.MOD_ID,
        name = "Opus Client",
        version = OpusClientMod.VERSION,
        clientSideOnly = true,
        acceptedMinecraftVersions = "[1.8.9]")
public final class OpusClientMod {
    public static final String MOD_ID = "opusclient";
    public static final String VERSION = "0.0.1-preview.3";
    /**
     * Production is intentionally vanilla-first while Core Mod work is in
     * progress.  The old CEF client remains in the source tree for later
     * review, but it must never register Forge GUI/key/HUD hooks implicitly.
     * A preview or future development run has to opt in explicitly with
     * {@code -Dopus.client.ui.enabled=true}.
     */
    static final String UI_ENABLE_PROPERTY = "opus.client.ui.enabled";

    private ClientOverlayController overlayController;
    private Logger log;

    @Mod.EventHandler
    public void onPreInitialization(FMLPreInitializationEvent event) {
        log = event.getModLog();
        if (isUiEnabled()) {
            overlayController = new ClientOverlayController(log);
        }
    }

    @Mod.EventHandler
    public void onInitialization(FMLInitializationEvent event) {
        if (!isUiEnabled()) {
            log.info("Opus client UI is disabled; vanilla Minecraft GUI remains authoritative. "
                    + "Core Mod/lifecycle work continues independently.");
            return;
        }
        overlayController.initialize();
        log.info("Opus Forge client module loaded; the canonical CEF product surface is ready.");
    }

    public void initializeStandalone() {
        this.log = org.apache.logging.log4j.LogManager.getLogger(MOD_ID);
        if (isUiEnabled()) {
            this.overlayController = new ClientOverlayController(this.log);
            this.overlayController.initialize();
            this.log.info("Opus Standalone client module loaded; the canonical CEF product surface is ready.");
        }
    }

    static boolean isUiEnabled() {
        return Boolean.getBoolean(UI_ENABLE_PROPERTY);
    }

}
