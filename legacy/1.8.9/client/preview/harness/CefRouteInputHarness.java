package org.polydevs.opusmc.client;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;
import org.polydevs.opusmc.client.interop.OpusBridgeProvider;
import org.polydevs.opusmc.client.interop.OpusInteropServer;
import org.polydevs.opusmc.client.interop.UiNavigationManager;

/** Production React + native CEF input; all game writes end at a disposable provider. */
public final class CefRouteInputHarness {
    private static final long TIMEOUT_MS = 15000L;
    private static final int WIDTH = 900;
    private static final int HEIGHT = 600;

    public static void main(String[] args) throws Exception {
        Logger log = LogManager.getLogger("CefRouteInputHarness");
        String helper = OpusWebViewClient.resolveHelperPath(log);
        require(helper != null, "CEF helper unavailable");
        Path catalog = Files.createTempFile("opus-cef-input-catalog-", ".json");
        Files.write(catalog, "{\"schemaVersion\":1,\"accounts\":[]}".getBytes(StandardCharsets.UTF_8));
        String previous = System.getProperty("opus.accounts.file");
        System.setProperty("opus.accounts.file", catalog.toString());
        final List<String> actions = new CopyOnWriteArrayList<String>();
        OpusBridgeProvider provider = (OpusBridgeProvider) Proxy.newProxyInstance(
                OpusBridgeProvider.class.getClassLoader(), new Class<?>[] {OpusBridgeProvider.class},
                new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method, Object[] values) {
                        String name = method.getName();
                        if ("performUiAction".equals(name)) {
                            actions.add("action:" + values[0] + ":" + values[1]);
                            return true;
                        }
                        if ("connectServer".equals(name)) actions.add("connect:" + values[0]);
                        if ("addServer".equals(name)) actions.add("add:" + values[0] + ":" + values[1]);
                        if (List.class.isAssignableFrom(method.getReturnType())) return Collections.emptyList();
                        return null;
                    }
                });
        OpusInteropServer server = new OpusInteropServer(log);
        UiNavigationManager navigation = new UiNavigationManager(log, server);
        OpusWebViewClient client = null;
        try {
            server.setBridgeProvider(provider);
            server.setNavigationManager(navigation);
            require(server.start(), "Interop server did not start");
            client = new OpusWebViewClient(log, helper);
            require(client.start(WIDTH, HEIGHT, 2.0F, server.urlForRoute("title")), "CEF did not start");
            transition(client, navigation, server, "title");
            for (int cycle = 0; cycle < 6; cycle++) {
                actions.clear();
                // The production focus/lost-release cleanup cancels outside
                // the surface. Verify that CEF does not activate the old
                // button, and that the following fresh click still works.
                client.sendMouseMove(238, 438);
                client.sendMouseButton(238, 438, 0, true);
                client.sendMouseLeave(WIDTH + 1, HEIGHT + 1);
                client.sendMouseButton(WIDTH + 1, HEIGHT + 1, 0, false);
                long quietUntil = System.currentTimeMillis() + 250L;
                while (System.currentTimeMillis() < quietUntil) {
                    drain(client);
                    require(actions.isEmpty(), "Canceled pointer press activated Home: " + actions);
                    Thread.sleep(10L);
                }
                click(client, 238, 438);
                awaitAction(actions, "action:navigate:singleplayer");
                transition(client, navigation, server, "singleplayer");
                click(client, 70, 28);
                awaitAction(actions, "action:back:null");
                transition(client, navigation, server, "title");
                actions.clear();
                click(client, 662, 438);
                awaitAction(actions, "action:navigate:multiplayer");
                transition(client, navigation, server, "multiplayer");

                // Local composers do not require a route change. Their fields
                // must accept actual CEF char/key events after returning from
                // Singleplayer, including selection and focus restoration.
                // Interior centers observed in the settled 900x600 DOM.
                // The old y556/y541 points lay below the current controls.
                click(client, 484, 531);
                Thread.sleep(200L);
                String address = "cycle" + cycle + ".example.invalid";
                type(client, address);
                click(client, 760, 516);
                awaitAction(actions, "connect:" + address);
                key(client, 27, 0);
                Thread.sleep(100L);
                click(client, 634, 531);
                Thread.sleep(200L);
                String label = "CaseSensitive" + cycle;
                key(client, 0x41, 2); // Ctrl+A is DOM selection, never clipboard access.
                type(client, label);
                key(client, 9, 0);
                key(client, 0x41, 8); // Cmd+A also leaves the OS clipboard untouched.
                type(client, address);
                client.sendMouseMove(540, 516);
                client.sendMouseMove(580, 516); // no held button must not select/drag text
                click(client, 760, 516);
                awaitAction(actions, "add:" + label + ":" + address);
                System.out.println("PASS CEF cycle " + cycle + ": canceled press stays inert; fresh Singleplayer -> Home -> Multiplayer; Direct connect/Add server; exact typed values and Ctrl/Cmd selection");
                navigation.close();
                client.setSurfaceVisible(false);
                transition(client, navigation, server, "title");
            }
        } finally {
            if (client != null) client.stop();
            navigation.shutdown();
            server.stop();
            if (previous == null) System.clearProperty("opus.accounts.file");
            else System.setProperty("opus.accounts.file", previous);
            Files.deleteIfExists(catalog);
        }
        System.out.println("CEF ROUTE INPUT: 6 cycles passed; no physical input, clipboard operations, real game writes or external connections");
    }

    private static void transition(OpusWebViewClient client, UiNavigationManager navigation,
            OpusInteropServer server, String route) throws Exception {
        navigation.commit(route, null, navigation.revision() + 1L, "title", "none", "opaque",
                "not_applicable", !"title".equals(route), false);
        client.navigate(server.urlForRoute(route));
        client.setSurfaceVisible(true);
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!navigation.isConfirmed() && System.currentTimeMillis() < deadline) {
            drain(client);
            Thread.sleep(10L);
        }
        require(navigation.isConfirmed(), "React did not ACK " + route);
        long generation = client.requestCommittedFrame();
        while (System.currentTimeMillis() < deadline) {
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                try {
                    if (frame.generation >= generation && client.isCurrent(frame, WIDTH, HEIGHT, 2.0F)) return;
                } finally { client.release(frame); }
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("No committed CEF frame for " + route);
    }

    private static void awaitAction(List<String> actions, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (actions.contains(expected)) return;
            Thread.sleep(10L);
        }
        throw new IllegalStateException("CEF action missing: " + expected + "; observed=" + actions);
    }

    private static void click(OpusWebViewClient client, int x, int y) {
        client.sendMouseMove(x, y);
        client.sendMouseButton(x, y, 0, true);
        client.sendMouseButton(x, y, 0, false);
    }

    private static void key(OpusWebViewClient client, int vk, int modifiers) {
        client.sendKey(vk, true, modifiers);
        client.sendKey(vk, false, modifiers);
    }

    private static void type(OpusWebViewClient client, String text) {
        for (int i = 0; i < text.length(); i++) client.sendChar(text.charAt(i));
    }

    private static void drain(OpusWebViewClient client) {
        client.release(client.consumeFrame());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
