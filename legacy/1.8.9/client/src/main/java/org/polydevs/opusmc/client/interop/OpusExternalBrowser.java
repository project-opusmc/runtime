package org.polydevs.opusmc.client.interop;

import java.awt.Desktop;
import java.net.URI;
import java.util.Locale;
import org.apache.logging.log4j.Logger;

/** Opens URLs in the system browser (the "external" backend). */
public final class OpusExternalBrowser {
    private OpusExternalBrowser() {
    }

    public static boolean open(Logger log, String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Throwable failure) {
            log.debug("AWT desktop browse unavailable", failure);
        }

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
