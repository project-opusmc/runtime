package org.polydevs.opusmc.client;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

/**
 * The typed Forge boundary for Opus's in-game client implementation.
 *
 * <p>The first production slice is intentionally narrow: a player can open a
 * real client-options screen and opt into a real FPS reading. Other utilities
 * do not appear until their data and persistence paths exist.</p>
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

    private ClientOverlayController overlayController;
    private Logger log;

    @Mod.EventHandler
    public void onPreInitialization(FMLPreInitializationEvent event) {
        log = event.getModLog();
        overlayController = new ClientOverlayController(log);
    }

    @Mod.EventHandler
    public void onInitialization(FMLInitializationEvent event) {
        overlayController.initialize();
        log.info("Opus Forge client module loaded; the typed Client Options surface is ready.");
    }
}
