package net.minecraftforge.fml.common.event;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FMLPreInitializationEvent extends FMLEvent {
    private final Logger logger = LogManager.getLogger("Opus Client");

    public Logger getModLog() {
        return logger;
    }
}
