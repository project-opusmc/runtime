package org.polydevs.opusmc.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiCreateWorld;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenWorking;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.resources.I18n;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.util.ChatComponentText;
import org.apache.logging.log4j.LogManager;
import org.polydevs.opusmc.client.interop.OpusInteropServer;
import org.polydevs.opusmc.client.interop.UiNavigationManager;
import org.polydevs.opusmc.client.ui.UiNavigationState;
import org.polydevs.opusmc.client.ui.UiRoute;

/** Runs the system lifecycle without a window, renderer or live server. */
public final class SystemScreenHarness {
    public static void main(String[] args) throws Exception {
        verifyBridgeDispatchWithoutVanillaLock();
        verifySystemActionRevision();
        verifyRunningDisconnectKeepsItsResponsePending();
        verifyDeferredConnectorOrigin();
        verifyFrameTaskCancellation();
        verifySystemPresentationGrace();
        RetainedUiSurfaceHarness.main(new String[0]);
        verifyConnectingResizeGrace();
        verifyNativeOptionsRoundTrip();
        verifyCreateWorldReturnFlow();
        verifyNativeResourceProgress();
        verifyWorldStartupPresentation();
        Class<?> stateType;
        Class<?> sessionType;
        try {
            stateType = Class.forName("org.polydevs.opusmc.client.interop.ConnectionUiState");
            sessionType = Class.forName("org.polydevs.opusmc.client.NativeSystemScreenSession");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("Connection lifecycle and host-state presentation are missing", missing);
        }
        Constructor<?> stateConstructor = stateType.getConstructor(
                String.class, String.class, String.class, String.class, String.class, boolean.class);
        Object connecting = stateConstructor.newInstance(
                "connecting", "Connecting", "Waiting for the server", "Test", "localhost:25565", true);
        Constructor<?> sessionConstructor = sessionType.getDeclaredConstructor(GuiScreen.class, stateType);
        sessionConstructor.setAccessible(true);
        TrackingScreen nativeScreen = new TrackingScreen();
        Object session = sessionConstructor.newInstance(nativeScreen, connecting);
        TrackingScreen retainedHost = new TrackingScreen();
        Method acceptsDeferred = sessionType.getDeclaredMethod("acceptsDeferred", GuiScreen.class,
                sessionType, GuiScreen.class, sessionType);
        acceptsDeferred.setAccessible(true);
        require(Boolean.TRUE.equals(acceptsDeferred.invoke(null, retainedHost, session, retainedHost, session)),
                "Current connection error was discarded before client-thread dispatch");
        require(Boolean.FALSE.equals(acceptsDeferred.invoke(null, retainedHost, session, new TrackingScreen(), session)),
                "Deferred error overwrote a newer screen");
        require(Boolean.FALSE.equals(acceptsDeferred.invoke(null, retainedHost, session, retainedHost, null)),
                "Deferred error overwrote Cancel on the same retained CEF host");
        invoke(session, "initialize", new Class<?>[] {Minecraft.class, int.class, int.class}, null, 800, 450);
        invoke(session, "initialize", new Class<?>[] {Minecraft.class, int.class, int.class}, null, 1200, 675);
        require(nativeScreen.width == 1200 && nativeScreen.height == 675 && nativeScreen.initializations == 2,
                "Native fallback did not receive live geometry");
        invoke(session, "tick", new Class<?>[0]);
        invoke(session, "tick", new Class<?>[0]);
        require(nativeScreen.ticks == 2, "CEF presentation stopped native connection processing");
        require(Boolean.TRUE.equals(invoke(session, "primaryAction", new Class<?>[0])), "Cancel was rejected");
        require(Boolean.FALSE.equals(invoke(session, "primaryAction", new Class<?>[0])), "Repeated Cancel ran twice");
        require(nativeScreen.actions == 1 && nativeScreen.buttonId == 0, "Cancel missed the native button action");
        invoke(session, "close", new Class<?>[0]);
        invoke(session, "close", new Class<?>[0]);
        require(nativeScreen.closes == 1 && nativeScreen.actions == 1,
                "Close either leaked the delegate or cancelled a completed connection");
        require(Boolean.FALSE.equals(acceptsDeferred.invoke(null, retainedHost, session, retainedHost, session)),
                "Closed connection accepted a deferred error");
        pass("native lifecycle continues; action and close each run once");
        pass("deferred network errors cannot overwrite Cancel or a newer screen");

        Constructor<?> parentSessionConstructor = sessionType.getDeclaredConstructor(
                GuiScreen.class, stateType, GuiScreen.class);
        parentSessionConstructor.setAccessible(true);
        TrackingScreen returnParent = new TrackingScreen();
        TrackingScreen earlyConnector = new TrackingScreen();
        Object justInstalled = parentSessionConstructor.newInstance(earlyConnector, connecting, returnParent);
        Method acceptsEarlyFailure = sessionType.getDeclaredMethod("acceptsDeferred", GuiScreen.class,
                sessionType, GuiScreen.class, sessionType, boolean.class, GuiScreen.class);
        acceptsEarlyFailure.setAccessible(true);
        require(Boolean.TRUE.equals(acceptsEarlyFailure.invoke(null,
                retainedHost, null, retainedHost, justInstalled, false, earlyConnector)),
                "Immediate connector failure before wrapper installation was discarded");
        require(Boolean.TRUE.equals(acceptsEarlyFailure.invoke(null,
                null, null, retainedHost, justInstalled, false, earlyConnector)),
                "Immediate startup connector failure was discarded");
        require(Boolean.FALSE.equals(acceptsEarlyFailure.invoke(null,
                retainedHost, null, retainedHost, justInstalled, false, new TrackingScreen())),
                "An unrelated old connector failure overwrote the new connection");
        require(Boolean.FALSE.equals(acceptsEarlyFailure.invoke(null,
                retainedHost, null, retainedHost, null, false, earlyConnector)),
                "Pre-install connector failure overwrote Cancel on a restored host");
        invoke(justInstalled, "primaryAction", new Class<?>[0]);
        require(Boolean.FALSE.equals(acceptsEarlyFailure.invoke(null,
                retainedHost, null, retainedHost, justInstalled, false, earlyConnector)),
                "Cancelled connector accepted an immediate deferred failure");
        pass("immediate constructor failures bind to the native connector, not a reused return parent");

        Object loading = stateConstructor.newInstance("loading", "Loading terrain", "", "Test", "localhost", false);
        TrackingScreen loadingScreen = new TrackingScreen();
        Object loadingSession = sessionConstructor.newInstance(loadingScreen, loading);
        require(Boolean.FALSE.equals(invoke(loadingSession, "primaryAction", new Class<?>[0])),
                "Terrain loading invented a cancellation action");
        require(loadingScreen.actions == 0, "Terrain loading called a native action");
        pass("terrain loading does not fabricate a native button");

        CountingPlayHandler playHandler = new CountingPlayHandler();
        Object terrainSession = sessionConstructor.newInstance(new GuiDownloadTerrain(playHandler), loading);
        for (int tick = 0; tick < 40; tick++) invoke(terrainSession, "tick", new Class<?>[0]);
        require(playHandler.keepAlives == 2, "CEF terrain presentation stopped vanilla keep-alive packets");
        invoke(terrainSession, "close", new Class<?>[0]);
        for (int tick = 0; tick < 20; tick++) invoke(terrainSession, "tick", new Class<?>[0]);
        require(playHandler.keepAlives == 2, "Closed terrain delegate still sent packets");
        pass("real vanilla terrain delegate keeps the connection alive until its screen closes");

        // Exercise the real private vanilla fields used by the production
        // factory; only the game's language resource setup is supplied here.
        Method setLocale = I18n.class.getDeclaredMethod("setLocale", net.minecraft.client.resources.Locale.class);
        setLocale.setAccessible(true);
        setLocale.invoke(null, new net.minecraft.client.resources.Locale());
        TrackingScreen disconnectParent = new TrackingScreen();
        GuiDisconnected nativeDisconnect = new GuiDisconnected(disconnectParent, "disconnect.lost",
                new ChatComponentText("§cServer kick\nActual reason: \"quoted\""));
        Method create = sessionType.getDeclaredMethod("create", GuiScreen.class, ServerData.class);
        create.setAccessible(true);
        Object disconnectSession = create.invoke(null, nativeDisconnect, null);
        Method nativeReturnParent = sessionType.getDeclaredMethod("nativeReturnParent", GuiScreen.class);
        nativeReturnParent.setAccessible(true);
        require(nativeReturnParent.invoke(null, nativeDisconnect) == disconnectParent,
                "Native disconnect return-parent identity was lost");
        Object extractedState = invoke(disconnectSession, "state", new Class<?>[0]);
        require("Server kick\nActual reason: \"quoted\"".equals(stateType.getField("detail").get(extractedState)),
                "Native disconnect factory lost the actual kick component or its formatting cleanup");
        pass("real vanilla disconnect fields produce the actual safe kick reason");

        OpusInteropServer server = new OpusInteropServer(LogManager.getLogger("SystemScreenHarness"));
        UiNavigationManager navigation = new UiNavigationManager(LogManager.getLogger("SystemScreenHarness"), server);
        try {
            UiRoute connectionRoute = (UiRoute) UiRoute.class.getMethod("connection").invoke(null);
            UiNavigationState flow = new UiNavigationState(connectionRoute, UiNavigationState.EntryPoint.TITLE);
            Method commit = UiNavigationManager.class.getMethod("commit", UiNavigationState.class,
                    String.class, String.class, String.class, boolean.class, stateType);
            commit.invoke(navigation, flow, "none", "opaque", "not_applicable", false, connecting);
            long before = navigation.revision();
            navigation.confirm(before);
            Object disconnected = stateConstructor.newInstance("disconnected", "Disconnected",
                    "Kicked: \"quoted\"\\path\nLý do <script>alert(1)</script>", "Test", "localhost", true);
            commit.invoke(navigation, flow, "none", "opaque", "not_applicable", false, disconnected);
            require(navigation.revision() > before && !navigation.isCurrentRevision(before),
                    "A same-route disconnect accepted stale connection actions");
            long disconnectedRevision = navigation.revision();
            navigation.confirm(before);
            require(!navigation.isConfirmed(), "A stale Connecting ACK confirmed Disconnected");
            commit.invoke(navigation, flow, "none", "opaque", "not_applicable", false, disconnected);
            require(navigation.revision() == disconnectedRevision && !navigation.isConfirmed(),
                    "Repeating an unconfirmed identical state restarted its revision");
            navigation.confirm(disconnectedRevision);
            require(navigation.isConfirmed(), "The current Disconnected ACK was rejected");
            commit.invoke(navigation, flow, "none", "opaque", "not_applicable", false, disconnected);
            require(navigation.revision() == disconnectedRevision && navigation.isConfirmed(),
                    "Repeating a confirmed identical state revoked its ACK");
            String json = navigation.stateJson();
            require(json.contains("\"phase\":\"disconnected\"") && json.contains("\"id\":\"connection\""),
                    "Native disconnect state was absent from authoritative navigation");
            JsonObject payload = new JsonParser().parse(json).getAsJsonObject();
            require("Kicked: \"quoted\"\\path\nLý do <script>alert(1)</script>".equals(
                    payload.getAsJsonObject("connection").get("detail").getAsString()),
                    "Kick reason was lost or did not use JSON text escaping");
            navigation.close();
            require(navigation.stateJson().contains("\"connection\":null"), "Closed screen leaked its disconnect reason");
            pass("same-route state changes revoke stale actions and preserve escaped reasons");
        } finally {
            navigation.shutdown();
            server.stop();
        }
        System.out.println("SYSTEM SCREENS: ALL PASSED");
    }

    private static void verifyWorldStartupPresentation() throws Exception {
        Method setLocale = I18n.class.getDeclaredMethod("setLocale", net.minecraft.client.resources.Locale.class);
        setLocale.setAccessible(true);
        setLocale.invoke(null, new net.minecraft.client.resources.Locale());
        Method loadingWorld = NativeSystemScreenSession.class.getDeclaredMethod("loadingWorld", String.class);
        loadingWorld.setAccessible(true);
        NativeSystemScreenSession startup = (NativeSystemScreenSession) loadingWorld.invoke(null, "Test world");
        org.polydevs.opusmc.client.interop.ConnectionUiState state = startup.state();
        require("loading".equals(state.phase) && "world".equals(state.stage)
                && "Test world".equals(state.serverName) && state.serverAddress.isEmpty() && !state.canCancel,
                "Singleplayer startup fabricated multiplayer/account progress or cancellation");
        require(!startup.primaryAction(), "World preparation exposed an unsupported cancellation");

        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        Field singleton = Minecraft.class.getDeclaredField("theMinecraft");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        singleton.set(null, minecraft);
        ClientOverlayController controller = new ClientOverlayController(LogManager.getLogger("WorldStartup"));
        try {
            OpusClientScreen host = new OpusClientScreen(controller, UiRoute.singleplayer());
            minecraft.currentScreen = host;
            host.presentSystemScreen(startup);
            net.minecraftforge.client.event.GuiOpenEvent earlyClose = new CancelableGuiOpenEvent();
            controller.onGuiOpening(earlyClose);
            require(earlyClose.isCanceled(), "World startup cleared the current CEF owner before local login/terrain");

            Method inherit = NativeSystemScreenSession.class.getDeclaredMethod("create", GuiScreen.class, ServerData.class,
                    org.polydevs.opusmc.client.interop.ConnectionUiState.class);
            inherit.setAccessible(true);
            NativeSystemScreenSession terrain = (NativeSystemScreenSession) inherit.invoke(null,
                    new GuiDownloadTerrain(new CountingPlayHandler()), new ServerData("Previous server", "old.example.net", false), state);
            require("world".equals(terrain.state().stage) && "Test world".equals(terrain.state().serverName)
                    && terrain.state().serverAddress.isEmpty(), "Local terrain inherited a stale multiplayer destination");
            host.presentSystemScreen(terrain);
            net.minecraftforge.client.event.GuiOpenEvent terrainDone = new CancelableGuiOpenEvent();
            controller.onGuiOpening(terrainDone);
            require(!terrainDone.isCanceled(), "Completed native terrain could not return to gameplay");

            host.presentSystemScreen((NativeSystemScreenSession) loadingWorld.invoke(null, "Another world"));
            minecraft.theWorld = (net.minecraft.client.multiplayer.WorldClient) unsafe.allocateInstance(net.minecraft.client.multiplayer.WorldClient.class);
            net.minecraftforge.client.event.GuiOpenEvent loaded = new CancelableGuiOpenEvent();
            controller.onGuiOpening(loaded);
            require(!loaded.isCanceled(), "A loaded world could not close its startup presentation");
        } finally {
            ((UiNavigationManager) fieldValue(ClientOverlayController.class, controller, "uiNavigation")).shutdown();
            singleton.set(null, previous);
        }
        pass("singleplayer retains its CEF startup owner through local login; terrain completion and gameplay stay native");
    }

    // Forge adds this override when it loads @Cancelable event subclasses;
    // standalone declares it directly. This harness uses an untransformed
    // Forge classpath, so preserve that real event contract explicitly.
    private static final class CancelableGuiOpenEvent extends net.minecraftforge.client.event.GuiOpenEvent {
        CancelableGuiOpenEvent() { super(null); }
        @Override public boolean isCancelable() { return true; }
    }

    private static void verifySystemActionRevision() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        Field singleton = Minecraft.class.getDeclaredField("theMinecraft");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        singleton.set(null, minecraft);
        ClientOverlayController controller = new ClientOverlayController(LogManager.getLogger("SystemActionRevision"));
        try {
            UiNavigationManager navigation = (UiNavigationManager) fieldValue(
                    ClientOverlayController.class, controller, "uiNavigation");
            UiNavigationState flow = new UiNavigationState(UiRoute.connection(), UiNavigationState.EntryPoint.TITLE);
            org.polydevs.opusmc.client.interop.ConnectionUiState connecting =
                    new org.polydevs.opusmc.client.interop.ConnectionUiState(
                            "connecting", "Connecting", "", "Test", "localhost", true);
            org.polydevs.opusmc.client.interop.ConnectionUiState disconnected =
                    new org.polydevs.opusmc.client.interop.ConnectionUiState(
                            "disconnected", "Disconnected", "Actual kick", "Test", "localhost", true);
            navigation.commit(flow, "none", "opaque", "not_applicable", false, connecting);
            long admittedRevision = navigation.revision();
            OpusClientScreen host = new OpusClientScreen(controller, UiRoute.connection());
            setField(GuiScreen.class, host, "mc", minecraft);
            minecraft.currentScreen = host;
            TrackingScreen replacement = new TrackingScreen();
            setField(OpusClientScreen.class, host, "systemScreen",
                    new NativeSystemScreenSession(replacement, disconnected));
            navigation.commit(flow, "none", "opaque", "not_applicable", false, disconnected);
            Method apply = ClientOverlayController.class.getDeclaredMethod("applyUiAction", String.class,
                    String.class, String.class, String.class, long.class);
            apply.setAccessible(true);
            // Reproduce an HTTP request admitted before the main thread
            // replaced Connecting: execution must re-check its revision.
            for (String action : new String[] {"back", "close"}) {
                require(Boolean.FALSE.equals(apply.invoke(controller, action, null, null, null, admittedRevision))
                                && replacement.actions == 0,
                        "Stale " + action + " executed on a replacement native session");
            }
            require(Boolean.TRUE.equals(apply.invoke(controller, "back", null, null, null, navigation.revision()))
                            && replacement.actions == 1,
                    "Current native Back was rejected");
            require(Boolean.FALSE.equals(apply.invoke(controller, "back", null, null, null, navigation.revision()))
                            && replacement.actions == 1,
                    "Current native action was executed more than once");
            pass("native action execution rechecks revision before replacement-session Back/Close; current action runs once");
        } finally {
            controller.shutdown();
            singleton.set(null, previous);
        }
    }

    private static void verifyBridgeDispatchWithoutVanillaLock() throws Exception {
        // Use the real Minecraft scheduler and bridge marshalling, without
        // constructing a window, loading a world or opening an audio device.
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        final Queue<FutureTask<?>> vanillaQueue = new ArrayDeque<FutureTask<?>>();
        setField(Minecraft.class, minecraft, "scheduledTasks", vanillaQueue);
        setField(Minecraft.class, minecraft, "mcThread", Thread.currentThread());
        Field singleton = Minecraft.class.getDeclaredField("theMinecraft");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        singleton.set(null, minecraft);
        final ClientOverlayController controller = new ClientOverlayController(
                LogManager.getLogger("BridgeDispatchHarness"));
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        final int[] actions = {0};
        final Runnable closeNetwork = new Runnable() {
            @Override public void run() {
                require(Minecraft.getMinecraft().isCallingFromMinecraftThread(),
                        "Bridge action escaped the Minecraft thread");
                require(!Thread.holdsLock(vanillaQueue),
                        "Disconnect/Cancel still holds Minecraft's scheduledTasks monitor");
                final CountDownLatch ioCallback = new CountDownLatch(1);
                Thread io = new Thread(new Runnable() {
                    @Override public void run() {
                        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                            @Override public void run() { }
                        });
                        ioCallback.countDown();
                    }
                }, "test-network-close-callback");
                io.setDaemon(true);
                io.start();
                try {
                    require(ioCallback.await(350L, TimeUnit.MILLISECONDS),
                            "Network-close callback could not enqueue while the UI action waited");
                    actions[0]++;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        };
        final Method bridgeCall = ClientOverlayController.class.getDeclaredMethod(
                "runOnClientThread", String.class, Runnable.class);
        bridgeCall.setAccessible(true);
        Thread request = new Thread(new Runnable() {
            @Override public void run() {
                started.countDown();
                try {
                    bridgeCall.invoke(controller, "test-network-close", closeNetwork);
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    completed.countDown();
                }
            }
        }, "test-ui-bridge-request");
        request.setDaemon(true);
        try {
            request.start();
            require(started.await(1L, TimeUnit.SECONDS), "Bridge worker did not start");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
            while (request.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            synchronized (vanillaQueue) {
                require(vanillaQueue.isEmpty(),
                        "Bridge action was queued inside Minecraft's lock-held scheduledTasks drain");
            }
            Method drain = ClientOverlayController.class.getDeclaredMethod("drainClientTasks");
            drain.setAccessible(true);
            drain.invoke(controller);
            require(completed.await(1L, TimeUnit.SECONDS), "Bridge action never completed");
            require(failure.get() == null && actions[0] == 1,
                    "Bridge action did not complete exactly once: " + failure.get());
            pass("real Minecraft bridge dispatch runs on the client thread without its scheduled-task lock");
        } finally {
            request.interrupt();
            request.join(1000L);
            controller.shutdown();
            singleton.set(null, previous);
        }
    }

    private static void verifyRunningDisconnectKeepsItsResponsePending() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        setField(Minecraft.class, minecraft, "mcThread", Thread.currentThread());
        Field singleton = Minecraft.class.getDeclaredField("theMinecraft");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        singleton.set(null, minecraft);
        final ClientOverlayController controller = new ClientOverlayController(
                LogManager.getLogger("SlowDisconnectHarness"));
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch response = new CountDownLatch(1);
        final AtomicReference<Throwable> requestFailure = new AtomicReference<Throwable>();
        final AtomicReference<Throwable> observationFailure = new AtomicReference<Throwable>();
        final UiNavigationManager navigation = (UiNavigationManager) fieldValue(
                ClientOverlayController.class, controller, "uiNavigation");
        navigation.open("game_menu");
        final Method bridgeCall = ClientOverlayController.class.getDeclaredMethod(
                "runOnClientThread", String.class, Runnable.class);
        bridgeCall.setAccessible(true);
        Thread request = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    bridgeCall.invoke(controller, "leaveWorld", new Runnable() {
                        @Override public void run() {
                            require(Minecraft.getMinecraft().isCallingFromMinecraftThread(),
                                    "Slow Disconnect escaped the Minecraft thread");
                            entered.countDown();
                            try {
                                require(release.await(5L, TimeUnit.SECONDS), "Teardown fixture was not released");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                            navigation.open("title");
                        }
                    });
                } catch (Throwable problem) {
                    requestFailure.set(problem);
                } finally {
                    response.countDown();
                }
            }
        }, "test-slow-disconnect-request");
        Thread observer = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    require(entered.await(1L, TimeUnit.SECONDS), "Slow Disconnect did not start");
                    require(!response.await(2300L, TimeUnit.MILLISECONDS),
                            "Running Disconnect reported failure/completion before native teardown finished");
                    require(navigation.stateJson().contains("\"id\":\"game_menu\""),
                            "Disconnect committed title before teardown finished");
                } catch (Throwable problem) {
                    observationFailure.set(problem);
                } finally {
                    release.countDown();
                }
            }
        }, "test-slow-disconnect-observer");
        request.setDaemon(true);
        observer.setDaemon(true);
        try {
            request.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
            while (request.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            observer.start();
            controller.drainClientTasks();
            require(response.await(1L, TimeUnit.SECONDS), "Completed Disconnect never returned its response");
            observer.join(1000L);
            require(observationFailure.get() == null, String.valueOf(observationFailure.get()));
            require(requestFailure.get() == null, "Slow but completed Disconnect was reported as failed");
            require(navigation.stateJson().contains("\"id\":\"title\""),
                    "Completed Disconnect did not commit title");
            pass("real bridge keeps an executing Disconnect pending beyond two seconds until title commit");
        } finally {
            release.countDown();
            request.interrupt();
            observer.interrupt();
            request.join(1000L);
            observer.join(1000L);
            controller.shutdown();
            singleton.set(null, previous);
        }
    }

    private static Object fieldValue(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void verifyDeferredConnectorOrigin() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        GuiConnecting connectorA = (GuiConnecting) unsafe.allocateInstance(GuiConnecting.class);
        GuiConnecting connectorB = (GuiConnecting) unsafe.allocateInstance(GuiConnecting.class);
        TrackingScreen sharedParent = new TrackingScreen();
        org.polydevs.opusmc.client.interop.ConnectionUiState state =
                new org.polydevs.opusmc.client.interop.ConnectionUiState(
                        "connecting", "Connecting", "", "Test", "localhost", true);
        NativeSystemScreenSession sessionA = new NativeSystemScreenSession(connectorA, state, sharedParent);
        NativeSystemScreenSession sessionB = new NativeSystemScreenSession(connectorB, state, sharedParent);
        require(!NativeSystemScreenSession.acceptsDeferred(sharedParent, sessionB,
                        sharedParent, sessionB, true, connectorA),
                "Late failure from connector A was attributed to already-current connector B");
        require(NativeSystemScreenSession.acceptsDeferred(sharedParent, sessionB,
                        sharedParent, sessionB, true, connectorB),
                "Failure from the actual current connector was discarded");
        require(!NativeSystemScreenSession.acceptsDeferred(sharedParent, sessionA,
                        sharedParent, sessionB, false, connectorA),
                "Cancel/retry reused a native parent and accepted the old connector failure");
        require(NativeSystemScreenSession.acceptsDeferred(null, null,
                        sharedParent, sessionB, false, connectorB),
                "Failure emitted before connector installation lost its actual owner");
        require(!NativeSystemScreenSession.acceptsDeferred(sharedParent, sessionB,
                        sharedParent, sessionB, true, null),
                "An unidentified worker failure was attached to the current connector");
        sessionB.close();
        require(!NativeSystemScreenSession.acceptsDeferred(sharedParent, sessionB,
                        sharedParent, sessionB, true, connectorB),
                "Closed connector accepted its delayed failure");
        require(!NativeSystemScreenSession.acceptsDeferred(sharedParent, null,
                        sharedParent, null, true, connectorA),
                "Old connector failure resurrected Connecting after Cancel");
        // Construct vanilla's real worker, but never start it or open a socket.
        Class<?> workerType = Class.forName("net.minecraft.client.multiplayer.GuiConnecting$1");
        Constructor<?> workerConstructor = workerType.getDeclaredConstructor(
                GuiConnecting.class, String.class, String.class, int.class);
        workerConstructor.setAccessible(true);
        Thread worker = (Thread) workerConstructor.newInstance(connectorA,
                "test-unstarted-connector", "localhost", 25565);
        Method origin = NativeSystemScreenSession.class.getDeclaredMethod("connectorForWorker", Thread.class);
        origin.setAccessible(true);
        require(origin.invoke(null, worker) == connectorA,
                "Vanilla connector worker lost its exact owning GuiConnecting instance");
        require(origin.invoke(null, new Thread()) == null,
                "Unrelated thread was treated as a native connector");
        pass("connector failures bind to their exact vanilla worker owner across startup, Cancel and retry");
    }

    private static void verifySystemPresentationGrace() throws Exception {
        require(!UiLifecycleShellPolicy.showText(0L,false) && !UiLifecycleShellPolicy.showText(179L,false),
                "Warm route changes flash a lifecycle loading label");
        require(!UiLifecycleShellPolicy.showText(180L,false) && !UiLifecycleShellPolicy.showText(5000L,false),
                "A normal CEF frame wait exposes internal embedded-interface loading text");
        require(UiLifecycleShellPolicy.showText(0L,true),
                "A real CEF failure hides its recovery instructions");
        require(!UiLifecycleShellPolicy.frameTimedOut(7999L) && UiLifecycleShellPolicy.frameTimedOut(8000L),
                "A stalled frame has no bounded retry boundary");
        require(UiLifecycleShellPolicy.drawTexture(false,true,true,true),
                "A live pending route discards its previous good CEF frame");
        require(!UiLifecycleShellPolicy.drawTexture(false,true,true,false),
                "A failed lease continues to display its stale CEF frame");
        pass("Warm route transitions retain the live CEF frame; a genuine transport failure exposes recovery");
    }

    private static void verifyFrameTaskCancellation() throws Exception {
        ClientFrameTasks tasks = new ClientFrameTasks();
        final int[] calls = {0};
        Runnable action = new Runnable() {
            @Override public void run() { calls[0]++; }
        };
        FutureTask<Void> expired = tasks.submit(action);
        expired.cancel(false);
        tasks.drain();
        require(calls[0] == 0, "Timed-out work ran on a later frame");
        FutureTask<Void> current = tasks.submit(action);
        tasks.drain();
        tasks.drain();
        require(calls[0] == 1 && current.isDone(), "A frame task ran more than once");
        FutureTask<Void> abandoned = tasks.submit(action);
        tasks.close();
        tasks.drain();
        require(calls[0] == 1 && abandoned.isCancelled(), "Shutdown ran pending world/UI work");
        require(tasks.submit(action).isCancelled(), "Shutdown accepted another client action");
        ClientFrameTasks closingTasks = new ClientFrameTasks();
        FutureTask<Void> removedBeforeShutdown = closingTasks.submit(action);
        ((Queue<?>) fieldValue(ClientFrameTasks.class, closingTasks, "pending")).poll();
        closingTasks.close();
        removedBeforeShutdown.run();
        require(calls[0] == 1 && removedBeforeShutdown.isCancelled(),
                "A polled but not-started task ran after shutdown closed the frame queue");
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        ClientFrameTasks runningTasks = new ClientFrameTasks();
        final FutureTask<Void> executing = runningTasks.submit(new Runnable() {
            @Override public void run() {
                entered.countDown();
                try {
                    finish.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Native client work was interrupted", interrupted);
                }
            }
        });
        Thread client = new Thread(new Runnable() {
            @Override public void run() { executing.run(); }
        }, "test-claimed-frame-task");
        client.setDaemon(true);
        try {
            client.start();
            require(entered.await(1L, TimeUnit.SECONDS), "Client did not claim its frame task");
            require(!executing.cancel(false) && !executing.isDone(),
                    "Timeout marked still-running native work complete/cancelled");
            runningTasks.close();
            require(!executing.isDone(), "Closing the queue forged a running task's completion");
            finish.countDown();
            executing.get(1L, TimeUnit.SECONDS);
            require(!executing.isCancelled(), "Finished native work lost its actual result");
        } finally {
            finish.countDown();
            client.join(1000L);
        }
        pass("expired and shutdown frame tasks cannot execute later; current tasks execute once");
    }

    private static void verifyConnectingResizeGrace() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        OpusClientScreen host = (OpusClientScreen) unsafe.allocateInstance(OpusClientScreen.class);
        ClientOverlayController controller = new ClientOverlayController(
                LogManager.getLogger("ConnectingResizeHarness"));
        setField(OpusClientScreen.class, host, "controller", controller);
        setField(OpusClientScreen.class, host, "displayMetricsTracker",
                new org.polydevs.opusmc.websurface.OpusDisplayMetricsTracker());
        setField(OpusClientScreen.class, host, "webSurface",
                new org.polydevs.opusmc.client.embed.OpusWebTextureSurface());
        setField(OpusClientScreen.class, host, "systemScreen", new NativeSystemScreenSession(
                new TrackingScreen(), new org.polydevs.opusmc.client.interop.ConnectionUiState(
                        "connecting", "Connecting", "", "Test", "localhost", true)));
        Field webState = OpusClientScreen.class.getDeclaredField("webState");
        webState.setAccessible(true);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object ready = Enum.valueOf((Class) webState.getType(), "READY");
        webState.set(host, ready);
        long staleStart = System.nanoTime() - TimeUnit.SECONDS.toNanos(3L);
        setField(OpusClientScreen.class, host, "pendingFrameSinceNanos", staleStart);
        try {
            // Exercise the real geometry transition, without querying or
            // constructing a physical LWJGL display in a headless harness.
            Method resize;
            try {
                resize = OpusClientScreen.class.getDeclaredMethod("refreshViewport",
                        int.class, int.class, float.class, boolean.class);
            } catch (NoSuchMethodException unavailable) {
                throw new AssertionError("Viewport transition has no headless geometry boundary", unavailable);
            }
            resize.setAccessible(true);
            resize.invoke(host, 1600, 900, 2.0F, false);
            long freshStart = (Long) fieldValue(OpusClientScreen.class, host, "pendingFrameSinceNanos");
            require(freshStart > staleStart, "Resize retained the previous frame's elapsed grace");
            resize.invoke(host, 1600, 900, 2.0F, false);
            require((Long) fieldValue(OpusClientScreen.class, host, "pendingFrameSinceNanos") == freshStart,
                    "Unchanged geometry indefinitely renewed the CEF frame wait");
            pass("Connecting resize starts one new CEF frame wait; unchanged geometry does not reset it");
        } finally {
            controller.shutdown();
        }
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void verifyNativeOptionsRoundTrip() throws Exception {
        Class<?> flowType;
        try {
            flowType = Class.forName("org.polydevs.opusmc.client.NativeOptionsFlow");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("Minecraft Settings child screens still lose their native parent", missing);
        }
        Constructor<?> constructor = flowType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object flow = constructor.newInstance();
        GuiOptions root = new GuiOptions(new TrackingScreen(), null);
        GuiOptions unrelated = new GuiOptions(new TrackingScreen(), null);
        invoke(flow, "open", new Class<?>[] {GuiOptions.class}, root);
        require(Boolean.TRUE.equals(invoke(flow, "owns", new Class<?>[] {GuiOptions.class}, root)),
                "Explicit Minecraft Settings entry was redirected to CEF");
        require(Boolean.TRUE.equals(invoke(flow, "owns", new Class<?>[] {GuiOptions.class}, root)),
                "Returning from Video Settings/Controls consumed a one-shot exemption");
        require(Boolean.FALSE.equals(invoke(flow, "owns", new Class<?>[] {GuiOptions.class}, unrelated)),
                "An unrelated options screen inherited the native settings exemption");
        invoke(flow, "close", new Class<?>[0]);
        require(Boolean.FALSE.equals(invoke(flow, "owns", new Class<?>[] {GuiOptions.class}, root)),
                "Leaving Minecraft Settings retained a stale native screen owner");
        pass("Minecraft Settings retains its exact native parent across child-screen round trips");
    }

    private static void verifyCreateWorldReturnFlow() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        Field singleton = Minecraft.class.getDeclaredField("theMinecraft");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        singleton.set(null, minecraft);
        Method setLocale = I18n.class.getDeclaredMethod("setLocale", net.minecraft.client.resources.Locale.class);
        setLocale.setAccessible(true);
        setLocale.invoke(null, new net.minecraft.client.resources.Locale());
        try {
            ClientOverlayController controller = new ClientOverlayController(LogManager.getLogger("CreateWorldHarness"));
            UiNavigationState navigation = new UiNavigationState(UiRoute.mainMenu(), UiNavigationState.EntryPoint.TITLE);
            navigation.navigate(UiRoute.singleplayer());
            OpusClientScreen current = new OpusClientScreen(controller, navigation);
            Method factory;
            try {
                factory = ClientOverlayController.class.getDeclaredMethod("createWorldScreen", OpusClientScreen.class);
            } catch (NoSuchMethodException missing) {
                throw new AssertionError("Singleplayer has no real Minecraft world-creation entry", missing);
            }
            factory.setAccessible(true);
            Object created = factory.invoke(controller, current);
            require(created instanceof GuiCreateWorld, "Create World did not open Minecraft's real creation screen");
            GuiScreen returnParent = null;
            for (Field field : GuiCreateWorld.class.getDeclaredFields()) {
                if (field.getType() == GuiScreen.class) {
                    field.setAccessible(true);
                    returnParent = (GuiScreen) field.get(created);
                }
            }
            require(returnParent instanceof OpusClientScreen && returnParent != current,
                    "Cancel would return to the closed CEF screen rather than a fresh lease owner");
            OpusClientScreen returned = (OpusClientScreen) returnParent;
            require(returned.route().kind() == UiRoute.Kind.SINGLEPLAYER,
                    "Create World lost the Singleplayer return route");
            require(returned.navigationForSystemReturn() == navigation
                    && returned.navigationForSystemReturn().back().kind() == UiRoute.Kind.MAIN_MENU,
                    "Create World lost the previous Home navigation history");
            require(factory.invoke(controller, new OpusClientScreen(controller, UiRoute.mainMenu())) == null,
                    "A non-Singleplayer screen accepted Create World");
            pass("Create World uses vanilla creation with a fresh CEF Singleplayer return parent and intact history");
        } finally {
            singleton.set(null, previous);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            throw new AssertionError("System lifecycle method failed: " + name, error.getCause());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void pass(String message) { System.out.println("PASS " + message); }

    private static void verifyNativeResourceProgress() {
        TrackingWorkingScreen working=new TrackingWorkingScreen();
        working.resetProgressAndMessage("Downloading resource pack");
        working.displayLoadingString("Resource pack");
        working.setLoadingProgress(37);
        require(NativeSystemScreenSession.supports(working), "Resource-pack progress still falls through to vanilla UI");
        NativeSystemScreenSession session=NativeSystemScreenSession.create(working,null);
        require("resources".equals(session.state().stage) && "loading".equals(session.state().phase)
                && !session.state().canCancel, "Native resource progress invented a connection/cancellation owner");
        require("Downloading resource pack".equals(session.state().title)
                && session.state().detail.contains("37%"), "CEF resource progress lost the native task/progress");
        working.setLoadingProgress(66);
        session.tick();
        require(session.state().detail.contains("66%") && working.completions==0,
                "Resource progress did not refresh or drew the vanilla progress screen");
        working.setDoneWorking();
        session.tick();
        require(working.completions==1, "Resource completion no longer delegates to Minecraft's close path");
        session.close(); session.tick();
        require(working.completions==1, "Closed resource task retained its completion callback");
        pass("native resource progress uses Opus presentation and retains Minecraft completion without drawing vanilla");
    }

    private static final class TrackingWorkingScreen extends GuiScreenWorking {
        int completions;
        @Override public void drawScreen(int x,int y,float partialTicks) {
            Boolean done=net.minecraftforge.fml.common.ObfuscationReflectionHelper.getPrivateValue(
                    GuiScreenWorking.class,this,"doneWorking","field_146592_h");
            require(Boolean.TRUE.equals(done), "Vanilla progress drawing was called before native completion");
            completions++;
        }
    }

    private static final class TrackingScreen extends GuiScreen {
        int initializations;
        int ticks;
        int actions;
        int buttonId = -1;
        int closes;
        @Override public void setWorldAndResolution(Minecraft minecraft, int width, int height) {
            this.width = width;
            this.height = height;
            initializations++;
        }
        @Override public void updateScreen() { ticks++; }
        @Override protected void actionPerformed(GuiButton button) { actions++; buttonId = button.id; }
        @Override public void onGuiClosed() { closes++; }
    }

    private static final class CountingPlayHandler extends NetHandlerPlayClient {
        int keepAlives;
        CountingPlayHandler() { super(null, null, null, null); }
        @Override public void addToSendQueue(Packet packet) {
            if (packet instanceof C00PacketKeepAlive) keepAlives++;
        }
    }
}
