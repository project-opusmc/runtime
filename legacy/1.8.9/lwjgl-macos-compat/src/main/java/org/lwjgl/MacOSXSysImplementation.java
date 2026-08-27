package org.lwjgl;

import java.lang.reflect.Method;

/**
 * LWJGL 2 macOS system adapter without the upstream eager AWT initialization.
 *
 * <p>Java 8 launched by a LaunchServices application with
 * {@code -XstartOnFirstThread} cannot initialize AWT before LWJGL creates its
 * display. The upstream class does exactly that from a static initializer even
 * though display creation itself does not require AWT. Keeping this class in a
 * sealed compatibility copy of LWJGL's root package preserves every other
 * implementation detail while allowing AppKit to stay on Java's first
 * thread.</p>
 */
final class MacOSXSysImplementation extends J2SESysImplementation {
    @Override
    public int getRequiredJNIVersion() {
        return 25;
    }

    @Override
    public boolean openURL(String url) {
        try {
            Class<?> fileManager = Class.forName("com.apple.eio.FileManager");
            Method openUrl = fileManager.getMethod("openURL", String.class);
            openUrl.invoke(null, url);
            return true;
        } catch (Exception error) {
            LWJGLUtil.log("Exception occurred while trying to invoke browser: " + error);
            return false;
        }
    }
}
