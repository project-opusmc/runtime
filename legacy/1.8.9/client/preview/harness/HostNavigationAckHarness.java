package org.polydevs.opusmc.client;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;
import org.polydevs.opusmc.client.interop.OpusBridgeProvider;
import org.polydevs.opusmc.client.interop.OpusInteropServer;
import org.polydevs.opusmc.client.interop.UiNavigationManager;

public final class HostNavigationAckHarness {
    private static final long TIMEOUT_MS = 15000L;

    public static void main(String[] args) throws Exception {
        Logger log = logger();
        String helper = OpusWebViewClient.resolveHelperPath(log);
        require(helper != null, "CEF helper unavailable");

        OpusInteropServer server = new OpusInteropServer(log);
        UiNavigationManager navigation = new UiNavigationManager(log, server);
        OpusWebViewClient client = null;
        try {
            server.setNavigationManager(navigation);
            server.setBridgeProvider(new StubBridgeProvider());
            require(server.start(), "interop server did not start");

            navigation.open("title");
            client = new OpusWebViewClient(log, helper);
            require(client.start(854, 480, 1.0F, server.urlForRoute("title")),
                    "CEF helper did not start");
            awaitFrame(client, 854, 480, 1.0F);
            awaitAck(navigation, "title");
            pass("host React title route acknowledges Java revision");

            navigation.open("settings");
            client.navigate(server.urlForRoute("settings"));
            awaitFrame(client, 854, 480, 1.0F);
            awaitAck(navigation, "settings");
            pass("host React hash navigation acknowledges Java revision");
        } finally {
            if (client != null) client.stop();
            navigation.shutdown();
            server.stop();
        }
        System.out.println("HOST NAVIGATION ACK: ALL PASSED");
    }

    private static void awaitFrame(OpusWebViewClient client, int w, int h, float scale)
            throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            require(!client.isFailed(), "CEF helper entered failed state");
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                try {
                    if (client.isCurrent(frame, w, h, scale)) return;
                } finally {
                    client.release(frame);
                }
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("timed out waiting for current CEF frame");
    }

    private static void awaitAck(UiNavigationManager navigation, String route) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (navigation.isConfirmed()) return;
            Thread.sleep(10L);
        }
        throw new IllegalStateException("React host did not acknowledge " + route);
    }

    private static Logger logger() {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] {Logger.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (method.getName().startsWith("is")
                                && method.getReturnType() == boolean.class) return Boolean.FALSE;
                        if (("warn".equals(method.getName()) || "error".equals(method.getName()))
                                && args != null && args.length > 0) {
                            System.out.println(method.getName().toUpperCase(Locale.ROOT)
                                    + " " + String.valueOf(args[0]));
                        }
                        return null;
                    }
                });
    }

    private static final class StubBridgeProvider implements OpusBridgeProvider {
        @Override public boolean performUiAction(String action, String routeId, String moduleId,
                String settingsSection, long expectedRevision) { return true; }
        @Override public List<String[]> listWorlds() { return Collections.emptyList(); }
        @Override public void loadWorld(String fileName, String displayName) {}
        @Override public List<String[]> listServers() { return Collections.emptyList(); }
        @Override public void connectServer(String address) {}
        @Override public void addServer(String name, String address) {}
        @Override public List<String[]> gameOptions() { return Collections.emptyList(); }
        @Override public void adjustGameOption(String key, int delta) {}
        @Override public void setGameOptionFloat(String key, float value) {}
        @Override public void leaveWorld() {}
        @Override public List<String[]> modules() {
            return Collections.singletonList(new String[] {"fps", "Performance Overlay", "true"});
        }
        @Override public void setModuleEnabled(String moduleId, boolean enabled) {}
        @Override public List<String[]> moduleSettings(String moduleId) { return Collections.emptyList(); }
        @Override public void setModuleSetting(String moduleId, String key, String value) {}
        @Override public List<String[]> hudModules() { return Collections.emptyList(); }
        @Override public void moveHudModule(String moduleId, int x, int y) {}
        @Override public void updateHudEditorCanvas(long navigationRevision, int x, int y,
                int width, int height) {}
    }

    private static void pass(String message) { System.out.println("PASS " + message); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
