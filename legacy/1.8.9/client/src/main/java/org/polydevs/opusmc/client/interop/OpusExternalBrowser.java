package org.polydevs.opusmc.client.interop;

import java.util.Locale;
import org.apache.logging.log4j.Logger;

/**
 * Opens URLs in the system browser (the "external" backend).
 *
 * <p>Deliberately avoids java.awt.Desktop: initializing AWT inside the
 * Minecraft JVM on modern macOS is a known crash vector (objc_release during
 * LWJGL display setup), so every platform uses a plain subprocess instead.</p>
 */
public final class OpusExternalBrowser {
    private OpusExternalBrowser() {
    }

    public static boolean open(Logger log, String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                new ProcessBuilder("/usr/bin/open", url).start();
                return true;
            }
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url)
                        .start();
                return true;
            }
            new ProcessBuilder("xdg-open", url).start();
            return true;
        } catch (Throwable failure) {
            log.warn("Opus could not open the external browser", failure);
            return false;
        }
    }
}
