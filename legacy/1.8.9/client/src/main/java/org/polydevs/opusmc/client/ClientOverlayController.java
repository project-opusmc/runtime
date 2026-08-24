package org.polydevs.opusmc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.gui.GuiScreenWorking;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.SaveFormatComparator;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.polydevs.opusmc.client.hud.HudManager;
import org.polydevs.opusmc.client.interop.OpusInteropServer;
import org.polydevs.opusmc.client.interop.OpusBridgeProvider;
import org.polydevs.opusmc.client.interop.UiNavigationManager;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.module.ModuleRegistry;
import org.polydevs.opusmc.client.module.ClientModule;
import org.polydevs.opusmc.client.ui.UiNavigationState;
import org.polydevs.opusmc.client.ui.UiRoute;

/**
 * Owns all interaction with the typed Forge client APIs. No ASM, reflection,
 * or access to obfuscated Minecraft members is used for Opus's overlay surface.
 */
/**
 * Public because Forge generates its event-handler class in a different
 * package at runtime. Keeping this package-private prevents that generated
 * handler from invoking the subscribed methods and crashes Minecraft during
 * the first pause-menu initialization.
 */
public final class ClientOverlayController implements OpusBridgeProvider {
    private final Logger log;
    private final KeyBinding openOptionsKey = new KeyBinding(
            "key.opusclient.open_options",
            Keyboard.KEY_RSHIFT,
            "key.categories.opusclient");
    private final UtilitySettingsStore settingsStore;
    private final FpsModule fpsModule;
    private final ArmorStatusModule armorStatusModule;
    private final ModuleRegistry moduleRegistry;
    private final HudManager hudManager;
    private final UiPreviewSession previewSession;
    private final OpusInteropServer interopServer;
    private final UiNavigationManager uiNavigation;
    private final String[] captureRouteNames = captureRoutes(System.getProperty("opus.ui.capture.route"));
    private int captureRouteIndex;
    private boolean captureRouteOpened;
    private boolean captureComplete;
    private int captureTicks;
    /**
     * Owns the Escape transition after a hotkey-owned product route closes.
     *
     * <p>Minecraft 1.8.9 can still drain a repeat or delayed Escape event after
     * {@code currentScreen} has become {@code null}. A wall-clock cooldown is
     * not sufficient: it either leaks a late event or eats a legitimate next
     * press. Keep the guard until the original key is released, then allow a
     * fresh non-repeat press to establish a new pause request.</p>
     */
    private boolean escapePauseGuard;
    private boolean escapeReleaseObserved;

    // Embedded web-view surface: renders the real Opus web UI inside the
    // game window as an OpenGL texture, painted by an out-of-process CEF
    // helper (CEF cannot be hosted in this Java 8 / macOS arm64 JVM itself).
    // Any failure enters the explicit CEF failed state; OpusClientScreen owns
    // the minimal loading/error shell.
    private boolean embeddedHelperResolved;
    private String embeddedHelperPath;
    private OpusWebViewClient embeddedWebView;
    private String embeddedRoute = "title";
    private volatile boolean shuttingDown;

    ClientOverlayController(Logger log) {
        this.log = log;
        this.settingsStore = UtilitySettingsStore.fromSystemProperty(log);
        this.fpsModule = new FpsModule(settingsStore);
        this.armorStatusModule = new ArmorStatusModule(settingsStore);
        this.moduleRegistry = new ModuleRegistry();
        this.moduleRegistry.register(fpsModule);
        this.moduleRegistry.register(armorStatusModule);
        this.hudManager = new HudManager(Arrays.asList(fpsModule.widget(), armorStatusModule.widget()));
        this.previewSession = UiPreviewSession.fromSystemProperties(log);
        this.interopServer = new OpusInteropServer(log);
        this.uiNavigation = new UiNavigationManager(log, interopServer);
        this.interopServer.setNavigationManager(uiNavigation);
        // HTTP/WebSocket handlers run on worker threads. Keep all Minecraft and
        // FML reads/writes on the client thread before they reach this owner.
        this.interopServer.setBridgeProvider(new ClientThreadBridge());
    }

    void initialize() {
        settingsStore.reload();
        ClientRegistry.registerKeyBinding(openOptionsKey);
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        if (!Boolean.getBoolean("opus.interop.skip") && interopServer.start()) {
            uiNavigation.setReloadAction(new Runnable() {
                @Override
                public void run() {
                    // Never substitute another product surface. If the embedded
                    // web view is live, force a genuine reload of the current
                    // route; otherwise the CEF screen owns its failed lifecycle
                    // shell and remains the only visible product path.
                    if (embeddedWebView != null && !embeddedWebView.isFailed()) {
                        embeddedWebView.reload();
                    }
                }
            });
            log.info("Opus web UI bridge listening on {}", interopServer.url());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        }, "opus-client-shutdown"));
        log.info("Opus UI route entry points registered: Right Shift and the canonical CEF pause route are ready.");
    }

    void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        OpusWebViewClient webView = embeddedWebView;
        embeddedWebView = null;
        if (webView != null) {
            webView.stop();
        }
        uiNavigation.shutdown();
        interopServer.stop();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        updateEscapePauseGuard();

        while (openOptionsKey.isPressed()) {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft.currentScreen == null) {
                openHotkeyFlow();
            } else if (minecraft.currentScreen instanceof OpusClientScreen) {
                OpusClientScreen screen = (OpusClientScreen) minecraft.currentScreen;
                if (screen.isHotkeyFlow()) {
                    screen.closeToGame();
                }
            }
        }
        previewSession.tick(this);
        runCaptureProbe();
    }

    @SubscribeEvent
    public void onGuiOpening(GuiOpenEvent event) {
        boolean previewPause = event.gui instanceof OpusPreviewPauseMenu
                && previewSession.isEnabled();
        if (event.gui instanceof GuiMainMenu) {
            event.gui = new OpusClientScreen(this, UiRoute.mainMenu());
        } else if (event.gui instanceof GuiIngameMenu && !previewPause) {
            if (shouldSuppressVanillaPause()) {
                log.info("Opus suppressed a queued vanilla Escape pause request after Quick Hub close");
                event.gui = null;
                return;
            }
            // Replace the vanilla pause menu with the canonical CEF product screen.
            // The preview-only subclass is excluded so Preview Mode still drives
            // the genuine FML-populated controls.
            log.info("Opus intercepted vanilla pause request (currentScreen={})",
                    Minecraft.getMinecraft().currentScreen == null
                            ? "none"
                            : Minecraft.getMinecraft().currentScreen.getClass().getName());
            event.gui = new OpusClientScreen(this, UiRoute.pauseMenu());
        } else if (event.gui instanceof GuiSelectWorld) {
            // Any path that would show the vanilla world list is redirected to
            // the canonical CEF singleplayer route.
            event.gui = new OpusClientScreen(this, UiRoute.singleplayer());
        } else if (event.gui instanceof GuiMultiplayer) {
            // Same for the canonical CEF multiplayer route.
            event.gui = new OpusClientScreen(this, UiRoute.multiplayer());
        } else if (event.gui instanceof GuiOptions) {
            // Vanilla options is a product-owned entry point. Its child pages
            // are not intercepted here; the CEF settings route is authoritative.
            event.gui = new OpusClientScreen(this, UiRoute.gameOptions());
        } else if (event.gui != null && isSystemScreen(event.gui)) {
            // Loading/disconnect/confirmation screens take ownership from the
            // product surface. Clear bridge state before Minecraft replaces it.
            if (Minecraft.getMinecraft().currentScreen instanceof OpusClientScreen) {
                uiNavigation.close();
            }
        } else if (event.gui != null
                && !(event.gui instanceof OpusClientScreen)
                && !previewPause
                && !isSystemScreen(event.gui)) {
            // Gameplay-owned screens such as inventory/container, sign editing,
            // merchant, and chat-adjacent dialogs must retain their vanilla or
            // mod owner. CEF owns only the explicit product entry points above.
            log.debug("Opus preserved non-product GUI screen {}",
                    event.gui.getClass().getName());
        }
    }

    /**
     * Screens Opus must NOT replace even in strict mode: transitional and
     * system dialogs whose absence would break connecting, disconnecting,
     * world load, or confirm prompts. Product settings, including video
     * settings, remain CEF-owned routes.
     */
    private static boolean isSystemScreen(GuiScreen gui) {
        return gui instanceof GuiConnecting
                || gui instanceof GuiDisconnected
                || gui instanceof GuiScreenWorking
                || gui instanceof GuiDownloadTerrain
                || gui instanceof GuiYesNo;
    }

    /**
     * Mirrors an in-game Opus route change to the web UI. Route IDs are the
     * same structured IDs exposed by ui-state; there is no second screen-name
     * contract.
     */
    void onNavigationChanged(UiNavigationState navigation) {
        UiRoute route = navigation.current();
        String screen = routeId(route);
        if (screen != null) {
            uiNavigation.commit(
                    navigation,
                    worldContext(),
                    presentation(route),
                    pausePolicy(navigation),
                    canCloseToGame(route));
        }
    }

    long currentUiNavigationRevision() {
        return uiNavigation.revision();
    }

    /**
     * Whether the in-game embedded web-view surface should be attempted. True
     * only when a native helper is available (macOS) and the loopback bridge is
     * running. Missing CEF enters the explicit lifecycle shell; it never
     * selects another product compositor.
     */
    boolean isEmbeddedUiEnabled() {
        if (interopServer.port() <= 0) {
            return false;
        }
        return resolveEmbeddedHelper() != null;
    }

    /**
     * Returns the shared embedded web-view client, starting the helper process
     * on first use. Returns null if the helper cannot start; the screen then
     * renders the minimal failed-state shell.
     *
     * @param scale device scale factor the browser should rasterize at, so the
     *              texture matches the game's framebuffer resolution.
     */
    OpusWebViewClient acquireEmbeddedWebView(
            int width,
            int height,
            float scale,
            String route,
            String moduleId,
            String settingsSection) {
        if (!isEmbeddedUiEnabled()) {
            return null;
        }
        embeddedRoute = (route == null || route.isEmpty()) ? "title" : route;
        String url = interopServer.urlForRoute(embeddedRoute, moduleId, settingsSection);
        if (embeddedWebView == null) {
            OpusWebViewClient client = new OpusWebViewClient(log, resolveEmbeddedHelper());
            if (!client.start(width, height, scale, url)) {
                return null;
            }
            embeddedWebView = client;
        } else if (embeddedWebView.isFailed()) {
            // Tear the dead helper down (freeing its shared buffer). Retry is an
            // explicit lifecycle action; normal draw/open paths never respawn it.
            log.warn("Opus embedded web view failed; entering the UI error state");
            embeddedWebView.stop();
            embeddedWebView = null;
            return null;
        } else {
            embeddedWebView.resize(width, height, scale);
            embeddedWebView.navigate(url);
        }
        return embeddedWebView;
    }

    /** Explicitly tears down a failed helper so the lifecycle shell can retry. */
    void retryEmbeddedWebView() {
        if (embeddedWebView != null) {
            embeddedWebView.stop();
            embeddedWebView = null;
        }
        embeddedHelperResolved = false;
        embeddedHelperPath = null;
    }

    private String resolveEmbeddedHelper() {
        if (!embeddedHelperResolved) {
            embeddedHelperPath = OpusWebViewClient.resolveHelperPath(log);
            embeddedHelperResolved = true;
        }
        return embeddedHelperPath;
    }

    String embeddedUrlFor(UiRoute route) {
        return interopServer.urlForRoute(
                routeId(route),
                route.moduleId(),
                route.settingsSection() == null ? null : route.settingsSection().wireValue());
    }

    /** Single Java-side close owner after the navigation stack is exhausted. */
    void closeNavigation(UiNavigationState navigation) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.theWorld != null) {
            log.info("Opus closing product route {} to gameplay",
                    navigation == null || navigation.current() == null
                            ? "none"
                            : routeId(navigation.current()));
            uiNavigation.close();
            minecraft.displayGuiScreen(null);
            minecraft.setIngameFocus();
        } else if (navigation.current().kind() != UiRoute.Kind.MAIN_MENU) {
            minecraft.displayGuiScreen(new OpusClientScreen(this, UiRoute.mainMenu()));
        }
    }

    /** Close invoked by the product screen's Escape handler. */
    void closeNavigationFromEscape(UiNavigationState navigation) {
        if (isHotkeyQuickHub(navigation)) {
            armEscapePauseGuard();
        }
        closeNavigation(navigation);
    }

    private void armEscapePauseGuard() {
        escapePauseGuard = true;
        // Preview mode calls the same screen method without a native keyboard
        // event. In that case the release has already happened conceptually;
        // a stale vanilla request is still suppressed until a fresh press.
        escapeReleaseObserved = !Keyboard.isKeyDown(Keyboard.KEY_ESCAPE);
    }

    private void updateEscapePauseGuard() {
        if (escapePauseGuard && !Keyboard.isKeyDown(Keyboard.KEY_ESCAPE)) {
            escapeReleaseObserved = true;
        }
    }

    private boolean shouldSuppressVanillaPause() {
        if (!escapePauseGuard) {
            return false;
        }
        boolean escapeDown = Keyboard.isKeyDown(Keyboard.KEY_ESCAPE);
        if (escapeReleaseObserved && escapeDown && !Keyboard.isRepeatEvent()) {
            // The original key was released and this is a new physical press;
            // let the normal canonical pause route handle it.
            escapePauseGuard = false;
            escapeReleaseObserved = false;
            return false;
        }
        return true;
    }

    private static boolean isHotkeyQuickHub(UiNavigationState navigation) {
        return navigation != null
                && navigation.isRootHotkeyFlow()
                && navigation.current() != null
                && navigation.current().kind() == UiRoute.Kind.QUICK_HUB;
    }

    void navigateFromHudEditorToModule(String moduleId) {
        if (moduleId == null || moduleId.trim().isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen instanceof OpusClientScreen) {
            ((OpusClientScreen) minecraft.currentScreen).navigate(UiRoute.moduleDetail(moduleId));
        }
    }

    @Override
    public boolean performUiAction(
            final String action,
            final String routeId,
            final String moduleId,
            final String settingsSection,
            final long expectedRevision) {
        // The loopback bridge normally calls from its worker thread, but a
        // preview/in-process caller can already be on the Minecraft thread.
        // Queueing and then waiting in that case deadlocks until the timeout,
        // leaving CEF navigation one action behind the authoritative screen.
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.isCallingFromMinecraftThread()) {
            return applyUiAction(action, routeId, moduleId, settingsSection, expectedRevision);
        }
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean accepted = new AtomicBoolean(false);
        try {
            minecraft.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    try {
                        accepted.set(applyUiAction(
                                action,
                                routeId,
                                moduleId,
                                settingsSection,
                                expectedRevision));
                    } finally {
                        completed.countDown();
                    }
                }
            });
            if (!completed.await(2L, TimeUnit.SECONDS)) {
                log.warn("Opus UI action timed out before the Minecraft client thread committed it");
                return false;
            }
            return accepted.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable failure) {
            log.warn("Opus UI action could not be scheduled", failure);
            return false;
        }
    }

    private boolean applyUiAction(
            String action,
            String routeId,
            String moduleId,
            String settingsSection,
            long expectedRevision) {
        if (!"quit".equals(action)
                && !uiNavigation.isCurrentRevision(expectedRevision)) {
            log.info("Opus rejected stale UI action at revision {} (current {})",
                    expectedRevision, uiNavigation.revision());
            return false;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if ("quit".equals(action)) {
            minecraft.shutdown();
            return true;
        }
        if (!(minecraft.currentScreen instanceof OpusClientScreen)) {
            return false;
        }
        OpusClientScreen screen = (OpusClientScreen) minecraft.currentScreen;
        if ("back".equals(action)) {
            // The SPA's global Escape handler uses the same bridge action as a
            // visible Back control. History navigation is unaffected, while a
            // root Quick Hub close must retain the escape guard so Minecraft's
            // delayed vanilla pause event cannot reopen another screen.
            screen.backOrClose(true);
            return true;
        } else if ("close".equals(action)) {
            screen.closeToGame();
            return true;
        } else if ("navigate".equals(action)) {
            UiRoute route = routeFromWeb(routeId, moduleId, settingsSection);
            if (route != null) {
                screen.navigate(route);
                return true;
            }
        }
        return false;
    }

    boolean canNavigateTo(UiRoute route) {
        if (route == null) {
            return false;
        }
        boolean inWorld = Minecraft.getMinecraft().theWorld != null;
        switch (route.kind()) {
            case HUD_EDITOR:
            case QUICK_HUB:
            case PAUSE_MENU:
                return inWorld;
            case MAIN_MENU:
            case SINGLEPLAYER:
            case MULTIPLAYER:
            case ACCOUNTS:
                return !inWorld;
            default:
                return true;
        }
    }

    /** Package accessor so the in-game screen can resolve its own route name. */
    String routeIdFor(UiRoute route) {
        return routeId(route);
    }

    private static String routeId(UiRoute route) {
        switch (route.kind()) {
            case MAIN_MENU:
                return "title";
            case SINGLEPLAYER:
                return "singleplayer";
            case MULTIPLAYER:
                return "multiplayer";
            case GAME_OPTIONS:
                return "settings";
            case ACCOUNTS:
                return "accounts";
            case PAUSE_MENU:
                return "game_menu";
            case QUICK_HUB:
                return "quick_hub";
            case HUD_EDITOR:
                return "hud_editor";
            case MOD_HUB:
                return "mods_catalog";
            case MODULE_DETAIL:
                return "module_detail";
            default:
                return null;
        }
    }

    private static UiRoute routeFromWeb(
            String routeId,
            String moduleId,
            String settingsSection) {
        if ("title".equals(routeId)) return UiRoute.mainMenu();
        if ("singleplayer".equals(routeId)) return UiRoute.singleplayer();
        if ("multiplayer".equals(routeId)) return UiRoute.multiplayer();
        if ("settings".equals(routeId)) {
            UiRoute.SettingsSection section = UiRoute.SettingsSection.fromWireValue(settingsSection);
            return UiRoute.gameOptions(section == null ? UiRoute.SettingsSection.GAME : section);
        }
        if ("accounts".equals(routeId)) return UiRoute.accounts();
        if ("game_menu".equals(routeId)) return UiRoute.pauseMenu();
        if ("quick_hub".equals(routeId)) return UiRoute.quickHub();
        if ("mods_catalog".equals(routeId)) return UiRoute.modHub();
        if ("hud_editor".equals(routeId)) return UiRoute.hudEditor();
        if ("module_detail".equals(routeId)
                && moduleId != null && !moduleId.trim().isEmpty()) {
            return UiRoute.moduleDetail(moduleId);
        }
        return null;
    }

    private static boolean canCloseToGame(UiRoute route) {
        if (Minecraft.getMinecraft().theWorld == null || route == null) {
            return false;
        }
        switch (route.kind()) {
            case MAIN_MENU:
            case SINGLEPLAYER:
            case MULTIPLAYER:
            case ACCOUNTS:
                return false;
            default:
                return true;
        }
    }

    private static String presentation(UiRoute route) {
        switch (route.kind()) {
            case QUICK_HUB:
                return "quick";
            case HUD_EDITOR:
                return "hud_editor";
            case MOD_HUB:
            case MODULE_DETAIL:
            case GAME_OPTIONS:
                return Minecraft.getMinecraft().theWorld == null ? "opaque" : "workspace";
            default:
                return "opaque";
        }
    }

    private static String pausePolicy(UiNavigationState navigation) {
        UiRoute route = navigation == null ? null : navigation.current();
        if (route == null) {
            return "not_applicable";
        }
        if (Minecraft.getMinecraft().theWorld == null) {
            return "not_applicable";
        }
        return route.kind() == UiRoute.Kind.PAUSE_MENU
                || (route.kind() == UiRoute.Kind.GAME_OPTIONS
                        && navigation.entryPoint() == UiNavigationState.EntryPoint.PAUSE)
                ? "paused"
                : "live";
    }

    private static String worldContext() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.theWorld == null) {
            return "none";
        }
        return minecraft.isSingleplayer() ? "singleplayer" : "multiplayer";
    }

    @SubscribeEvent
    public void onRenderHudText(RenderGameOverlayEvent.Text event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        // The native layer is the live in-world HUD only. Never paint it over
        // opaque title/list/settings routes when a Forge overlay event happens
        // to fire during a no-world transition.
        if (minecraft.theWorld == null) {
            return;
        }
        // The workspace is translucent live context, not a modal blackout:
        // enabled Opus widgets remain part of the player's HUD beneath it.
        // The editor draws the same widget itself so it can attach its hover
        // controls without a second FPS pass.
        if (minecraft.currentScreen instanceof OpusClientScreen
                && ((OpusClientScreen) minecraft.currentScreen).isHudEditor()) {
            return;
        }
        hudManager.render(minecraft, event.resolution);
    }

    PerformanceOverlaySettings performanceOverlay() {
        return fpsModule.settings();
    }

    String lastSaveError() {
        return settingsStore.lastSaveError();
    }

    boolean updatePerformanceOverlay(PerformanceOverlaySettings next) {
        return fpsModule.update(next);
    }

    ArmorStatusSettings armorStatus() {
        return armorStatusModule.settings();
    }

    boolean updateArmorStatus(ArmorStatusSettings next) {
        return armorStatusModule.update(next);
    }

    void renderHudEditor(UiRenderer renderer, UiBounds viewport, UiInput input) {
        hudManager.renderEditor(Minecraft.getMinecraft(), renderer, viewport, input);
    }

    HudManager.EditorAction hudEditorMouseDown(int mouseX, int mouseY, int button) {
        return hudManager.editorMouseDown(mouseX, mouseY, button);
    }

    boolean hudEditorOwnsPoint(int mouseX, int mouseY) {
        return hudManager.editorOwnsPoint(mouseX, mouseY);
    }

    boolean hudEditorHasPointerCapture() {
        return hudManager.hasPointerCapture();
    }

    boolean hudEditorMouseDrag(UiBounds viewport, int mouseX, int mouseY, int button) {
        return hudManager.editorMouseDrag(viewport, mouseX, mouseY, button);
    }

    boolean hudEditorMouseUp(int button) {
        return hudManager.editorMouseUp(button);
    }

    void cancelHudEditorPointerCapture() {
        hudManager.cancelPointerCapture();
    }

    /**
     * Stops the product compositor when no Opus screen owns it. A later screen
     * starts a fresh helper and mapping; no browser frame or input owner remains
     * alive behind gameplay or a system screen.
     */
    void releaseEmbeddedWebView(OpusWebViewClient owner) {
        if (owner == null || embeddedWebView != owner) {
            return;
        }
        embeddedWebView = null;
        owner.stop();
    }

    @Override
    public void updateHudEditorCanvas(
            final long navigationRevision,
            final int x,
            final int y,
            final int width,
            final int height) {
        log.info("Opus HUD editor input region queued: revision {} at {}x{}+{},{}",
                navigationRevision, width, height, x, y);
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (Minecraft.getMinecraft().currentScreen instanceof OpusClientScreen) {
                    log.info("Opus HUD editor input region applying to current screen: revision {}",
                            navigationRevision);
                    OpusClientScreen screen = (OpusClientScreen) Minecraft.getMinecraft().currentScreen;
                    long currentRevision = screen.previewNavigationRevision();
                    boolean hudEditorRoute = screen.isHudEditor();
                    screen.acceptHudEditorCanvas(
                            navigationRevision,
                            new UiBounds(x, y, width, height));
                    log.info("Opus HUD editor input region accepted: {} (route={}, currentRevision={}, reportRevision={})",
                            screen.previewNativeHudCanvasReady(), hudEditorRoute,
                            currentRevision, navigationRevision);
                } else {
                    log.info("Opus HUD editor input region dropped; current screen is {}",
                            Minecraft.getMinecraft().currentScreen == null
                                    ? "none"
                                    : Minecraft.getMinecraft().currentScreen.getClass().getName());
                }
            }
        });
    }

    /**
     * Read the real singleplayer save list through the typed save-format API so
     * the canonical CEF route can list worlds without a mock data source.
     * Each row is {fileName, displayName}. Failures degrade to an empty list so
     * the CEF route can show an explicit empty/error state.
     */
    @Override
    public java.util.List<String[]> listWorlds() {
        java.util.List<String[]> worlds = new java.util.ArrayList<String[]>();
        try {
            ISaveFormat saveFormat = Minecraft.getMinecraft().getSaveLoader();
            java.util.List<SaveFormatComparator> summaries = saveFormat.getSaveList();
            java.util.Collections.sort(summaries);
            for (SaveFormatComparator summary : summaries) {
                worlds.add(new String[] {summary.getFileName(), summary.getDisplayName()});
            }
        } catch (Throwable failure) {
            log.warn("Opus could not read the singleplayer save list", failure);
        }
        return worlds;
    }

    /**
     * Load a singleplayer world exactly as vanilla's world list would, through
     * FML's loader (so mod save data and conversions are handled). The product
     * screen then gives way to Minecraft's loading lifecycle.
     */
    @Override
    public void loadWorld(String fileName, String displayName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return;
        }
        try {
            ISaveFormat saveFormat = Minecraft.getMinecraft().getSaveLoader();
            if (!saveFormat.canLoadWorld(fileName)) {
                log.warn("Opus refused to load an unreadable world: {}", fileName);
                return;
            }
            String label = displayName == null || displayName.trim().isEmpty() ? fileName : displayName;
            uiNavigation.close();
            FMLClientHandler.instance().tryLoadExistingWorld(null, fileName, label);
        } catch (Throwable failure) {
            log.warn("Opus failed to load world {}", fileName, failure);
        }
    }

    /**
     * Read the saved multiplayer server list through the typed ServerList API.
     * Each row is {name, ip}. Failures degrade to an empty list.
     */
    @Override
    public java.util.List<String[]> listServers() {
        java.util.List<String[]> servers = new java.util.ArrayList<String[]>();
        try {
            ServerList serverList = new ServerList(Minecraft.getMinecraft());
            serverList.loadServerList();
            for (int index = 0; index < serverList.countServers(); index++) {
                ServerData data = serverList.getServerData(index);
                servers.add(new String[] {data.serverName, data.serverIP});
            }
        } catch (Throwable failure) {
            log.warn("Opus could not read the multiplayer server list", failure);
        }
        return servers;
    }

    /**
     * Connect to a multiplayer server by address through the typed
     * GuiConnecting flow (host/port parsing, ping, join). Cancelling connect
     * returns to the canonical CEF multiplayer route rather than a vanilla
     * multiplayer list.
     */
    @Override
    public void connectServer(String address) {
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            ServerData data = new ServerData("Opus Server", address.trim(), false);
            // The connection progress screen is a system surface, not an Opus
            // product route. Close the acknowledged route before handing
            // ownership to it; a later Opus screen commits its own route.
            uiNavigation.close();
            minecraft.displayGuiScreen(
                    new GuiConnecting(new OpusClientScreen(this, UiRoute.multiplayer()), minecraft, data));
        } catch (Throwable failure) {
            log.warn("Opus failed to connect to {}", address, failure);
        }
    }

    /** Persist a new server entry so it appears in the CEF server list. */
    @Override
    public void addServer(String name, String address) {
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        try {
            ServerList serverList = new ServerList(Minecraft.getMinecraft());
            serverList.loadServerList();
            String label = name == null || name.trim().isEmpty() ? "Opus Server" : name.trim();
            serverList.addServerData(new ServerData(label, address.trim(), false));
            serverList.saveServerList();
        } catch (Throwable failure) {
            log.warn("Opus failed to save server {}", address, failure);
        }
    }

    // ---- CEF game-options bridge (typed GameSettings access) ----------------
    // These option keys map to net.minecraft GameSettings.Options constants by
    // name. Rendering, cycling and persistence all go through the typed public
    // API, so the CEF route never needs the vanilla GuiOptions screen.
    private static final String[] OPTION_KEYS = {
        "FOV", "GAMMA", "SENSITIVITY", "RENDER_DISTANCE", "FRAMERATE_LIMIT",
        "GUI_SCALE", "PARTICLES", "GRAPHICS", "RENDER_CLOUDS", "AMBIENT_OCCLUSION",
        "VIEW_BOBBING", "ENABLE_VSYNC", "USE_VBO", "ENTITY_SHADOWS", "USE_FULLSCREEN",
    };

    private static GameSettings.Options gameOption(String key) {
        if ("FOV".equals(key)) return GameSettings.Options.FOV;
        if ("GAMMA".equals(key)) return GameSettings.Options.GAMMA;
        if ("SENSITIVITY".equals(key)) return GameSettings.Options.SENSITIVITY;
        if ("RENDER_DISTANCE".equals(key)) return GameSettings.Options.RENDER_DISTANCE;
        if ("FRAMERATE_LIMIT".equals(key)) return GameSettings.Options.FRAMERATE_LIMIT;
        if ("GUI_SCALE".equals(key)) return GameSettings.Options.GUI_SCALE;
        if ("PARTICLES".equals(key)) return GameSettings.Options.PARTICLES;
        if ("GRAPHICS".equals(key)) return GameSettings.Options.GRAPHICS;
        if ("RENDER_CLOUDS".equals(key)) return GameSettings.Options.RENDER_CLOUDS;
        if ("AMBIENT_OCCLUSION".equals(key)) return GameSettings.Options.AMBIENT_OCCLUSION;
        if ("VIEW_BOBBING".equals(key)) return GameSettings.Options.VIEW_BOBBING;
        if ("ENABLE_VSYNC".equals(key)) return GameSettings.Options.ENABLE_VSYNC;
        if ("USE_VBO".equals(key)) return GameSettings.Options.USE_VBO;
        if ("ENTITY_SHADOWS".equals(key)) return GameSettings.Options.ENTITY_SHADOWS;
        if ("USE_FULLSCREEN".equals(key)) return GameSettings.Options.USE_FULLSCREEN;
        throw new IllegalArgumentException("Unknown game option: " + key);
    }

    /**
     * Cycle a boolean/enum option (delta ignored) or nudge a float slider by
     * delta steps. Persists immediately, matching vanilla's button behavior.
     */
    @Override
    public void adjustGameOption(String key, int delta) {
        try {
            GameSettings settings = Minecraft.getMinecraft().gameSettings;
            GameSettings.Options option = gameOption(key);
            if (option.getEnumFloat()) {
                float current = settings.getOptionFloatValue(option);
                float normalized = option.normalizeValue(current);
                float next = option.denormalizeValue(Math.max(0.0F, Math.min(1.0F, normalized + delta * 0.05F)));
                settings.setOptionFloatValue(option, next);
            } else {
                settings.setOptionValue(option, delta >= 0 ? 1 : -1);
            }
            settings.saveOptions();
        } catch (Throwable failure) {
            log.warn("Opus failed to adjust option {}", key, failure);
        }
    }

    @Override
    public List<String[]> gameOptions() {
        List<String[]> rows = new ArrayList<String[]>(OPTION_KEYS.length);
        try {
            GameSettings settings = Minecraft.getMinecraft().gameSettings;
            for (String key : OPTION_KEYS) {
                GameSettings.Options option = gameOption(key);
                boolean slider = option.getEnumFloat();
                boolean bool = option.getEnumBoolean();
                String type = slider ? "float" : (bool ? "boolean" : "enum");
                // Normalized 0..1 contract so the web UI never needs to know
                // per-option min/max; the client denormalizes on write.
                String value = String.valueOf(
                        option.normalizeValue(settings.getOptionFloatValue(option)));
                String min = "0";
                String max = "1";
                String step = slider ? "0.05" : "1";
                rows.add(new String[] {
                        key, option.name(), type, value, min, max, step,
                });
            }
        } catch (Throwable failure) {
            log.warn("Opus could not read game options", failure);
        }
        return rows;
    }

    @Override
    public void setGameOptionFloat(String key, float value) {
        try {
            GameSettings settings = Minecraft.getMinecraft().gameSettings;
            GameSettings.Options option = gameOption(key);
            if (!option.getEnumFloat()) {
                return;
            }
            settings.setOptionFloatValue(option, option.denormalizeValue(value));
            settings.saveOptions();
        } catch (Throwable failure) {
            log.warn("Opus could not set option {}", key, failure);
        }
    }

    /**
     * Pause-menu Disconnect: quit the current world through the typed client
     * API and return to the canonical CEF title route, mirroring vanilla's flow
     * without a vanilla product screen. Works for both integrated
     * (singleplayer) and remote play.
     */
    @Override
    public void leaveWorld() {
        Minecraft minecraft = Minecraft.getMinecraft();
        // Clear the product route before Forge/Minecraft begins asynchronous
        // world teardown; loading/disconnect screens are not CEF-owned.
        uiNavigation.close();
        if (minecraft.theWorld != null) {
            minecraft.theWorld.sendQuittingDisconnectingPacket();
        }
        minecraft.loadWorld(null);
        minecraft.displayGuiScreen(new OpusClientScreen(this, UiRoute.mainMenu()));
    }

    @Override
    public List<String[]> modules() {
        List<String[]> rows = new ArrayList<String[]>();
        for (ClientModule module : moduleRegistry.all()) {
            rows.add(new String[] {
                    module.id(), module.displayName(), String.valueOf(module.isEnabled()),
            });
        }
        return rows;
    }

    @Override
    public void setModuleEnabled(String moduleId, boolean enabled) {
        ClientModule module = moduleRegistry.byId(moduleId);
        if (module != null) {
            module.setEnabled(enabled);
        }
    }

    @Override
    public List<String[]> moduleSettings(String moduleId) {
        List<String[]> rows = new ArrayList<String[]>();
        if ("fps".equals(moduleId)) {
            FpsModule module = (FpsModule) moduleRegistry.byId(moduleId);
            if (module == null) {
                return rows;
            }
            PerformanceOverlaySettings settings = module.settings();
            rows.add(settingRow("enabled", "Enabled", "boolean",
                    settings.enabled() ? "1" : "0", "0", "1", "1", ""));
            rows.add(settingRow("scale", "Scale", "integer",
                    String.valueOf(settings.scale()),
                    String.valueOf(PerformanceOverlaySettings.MIN_SCALE),
                    String.valueOf(PerformanceOverlaySettings.MAX_SCALE), "1", ""));
            rows.add(settingRow("opacity", "Opacity", "integer",
                    String.valueOf(settings.opacity()),
                    String.valueOf(PerformanceOverlaySettings.MIN_OPACITY),
                    String.valueOf(PerformanceOverlaySettings.MAX_OPACITY), "1", ""));
            rows.add(settingRow("anchor", "Anchor", "enum",
                    settings.anchor().storageValue(), "0", "0", "1",
                    "top-left,top-right,bottom-left,bottom-right"));
            rows.add(settingRow("offsetX", "Offset X", "integer",
                    String.valueOf(settings.offsetX()), "0", "4096", "1", ""));
            rows.add(settingRow("offsetY", "Offset Y", "integer",
                    String.valueOf(settings.offsetY()), "0", "4096", "1", ""));
        } else if ("armor-status".equals(moduleId)) {
            ArmorStatusModule module = (ArmorStatusModule) moduleRegistry.byId(moduleId);
            if (module == null) {
                return rows;
            }
            ArmorStatusSettings settings = module.settings();
            rows.add(settingRow("enabled", "Enabled", "boolean",
                    settings.enabled() ? "1" : "0", "0", "1", "1", ""));
            rows.add(settingRow("scale", "Scale", "integer",
                    String.valueOf(settings.scale()),
                    String.valueOf(PerformanceOverlaySettings.MIN_SCALE),
                    String.valueOf(PerformanceOverlaySettings.MAX_SCALE), "1", ""));
            rows.add(settingRow("opacity", "Opacity", "integer",
                    String.valueOf(settings.opacity()),
                    String.valueOf(PerformanceOverlaySettings.MIN_OPACITY),
                    String.valueOf(PerformanceOverlaySettings.MAX_OPACITY), "1", ""));
            rows.add(settingRow("anchor", "Anchor", "enum",
                    settings.anchor().storageValue(), "0", "0", "1",
                    "top-left,top-right,bottom-left,bottom-right"));
            rows.add(settingRow("offsetX", "Offset X", "integer",
                    String.valueOf(settings.offsetX()), "0", "4096", "1", ""));
            rows.add(settingRow("offsetY", "Offset Y", "integer",
                    String.valueOf(settings.offsetY()), "0", "4096", "1", ""));
            rows.add(settingRow("showDurability", "Durability", "boolean",
                    settings.showDurability() ? "1" : "0", "0", "1", "1", ""));
        }
        return rows;
    }

    @Override
    public void setModuleSetting(String moduleId, String key, String value) {
        if (key == null || value == null) {
            return;
        }
        if ("enabled".equals(key)) {
            setModuleEnabled(moduleId, "1".equals(value) || "true".equals(value));
            return;
        }
        try {
            if ("fps".equals(moduleId)) {
                FpsModule module = (FpsModule) moduleRegistry.byId(moduleId);
                if (module == null) {
                    return;
                }
                PerformanceOverlaySettings current = module.settings();
                PerformanceOverlaySettings next = current;
                if ("scale".equals(key)) {
                    next = current.withScale(Integer.parseInt(value));
                } else if ("opacity".equals(key)) {
                    next = current.withOpacity(Integer.parseInt(value));
                } else if ("anchor".equals(key)) {
                    next = current.withAnchor(PerformanceOverlaySettings.Anchor.fromStorage(value));
                } else if ("offsetX".equals(key)) {
                    next = current.withOffset(Integer.parseInt(value), current.offsetY());
                } else if ("offsetY".equals(key)) {
                    next = current.withOffset(current.offsetX(), Integer.parseInt(value));
                }
                module.update(next);
            } else if ("armor-status".equals(moduleId)) {
                ArmorStatusModule module = (ArmorStatusModule) moduleRegistry.byId(moduleId);
                if (module == null) {
                    return;
                }
                ArmorStatusSettings current = module.settings();
                ArmorStatusSettings next = current;
                if ("scale".equals(key)) {
                    next = current.withScale(Integer.parseInt(value));
                } else if ("opacity".equals(key)) {
                    next = current.withOpacity(Integer.parseInt(value));
                } else if ("anchor".equals(key)) {
                    next = current.withAnchor(PerformanceOverlaySettings.Anchor.fromStorage(value));
                } else if ("offsetX".equals(key)) {
                    next = current.withOffset(Integer.parseInt(value), current.offsetY());
                } else if ("offsetY".equals(key)) {
                    next = current.withOffset(current.offsetX(), Integer.parseInt(value));
                } else if ("showDurability".equals(key)) {
                    next = current.withShowDurability(
                            "1".equals(value) || "true".equals(value));
                }
                module.update(next);
            }
        } catch (NumberFormatException ignored) {
            log.warn("Opus ignored invalid module setting {}={}", key, value);
        }
    }

    private static String[] settingRow(
            String key,
            String label,
            String type,
            String value,
            String min,
            String max,
            String step,
            String options) {
        return new String[] {key, label, type, value, min, max, step, options};
    }

    @Override
    public List<String[]> hudModules() {
        List<String[]> rows = new ArrayList<String[]>();
        for (String[] module : modules()) {
            String id = module[0];
            String name = module[1];
            String enabled = module[2];
            String offsetX = "12";
            String offsetY = "12";
            String scale = "100";
            String anchor = "top-left";
            List<String[]> settings = moduleSettings(id);
            for (String[] setting : settings) {
                String key = setting[0];
                String value = setting[3];
                if ("offsetX".equals(key)) {
                    offsetX = value;
                } else if ("offsetY".equals(key)) {
                    offsetY = value;
                } else if ("scale".equals(key)) {
                    scale = value;
                } else if ("anchor".equals(key)) {
                    anchor = value;
                }
            }
            rows.add(new String[] {
                    id, name, enabled, offsetX, offsetY, scale, anchor,
            });
        }
        return rows;
    }

    @Override
    public void moveHudModule(String moduleId, int x, int y) {
        setModuleSetting(moduleId, "offsetX", String.valueOf(Math.max(0, x)));
        setModuleSetting(moduleId, "offsetY", String.valueOf(Math.max(0, y)));
    }

    /**
     * Preview Mode only. It drives the same route renderer used by Right Shift
     * and the production pause transition, never a separate UI or a
     * desktop/window screenshot.
     */
    void openPreviewRoute(UiRoute route) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen instanceof OpusClientScreen) {
            ((OpusClientScreen) minecraft.currentScreen).previewNavigate(route);
            return;
        }
        settingsStore.reload();
        minecraft.displayGuiScreen(new OpusClientScreen(this, route));
    }

    void setPreviewPointer(int x, int y) {
        OpusClientScreen screen = previewScreen();
        if (screen != null) {
            screen.setPreviewPointer(x, y);
        }
    }

    void clearPreviewPointer() {
        OpusClientScreen screen = previewScreen();
        if (screen != null) {
            screen.clearPreviewPointer();
        }
    }

    boolean previewClick(int x, int y, int button) {
        OpusClientScreen screen = previewScreen();
        return screen != null && screen.previewClick(x, y, button);
    }

    boolean previewDrag(int x, int y, int toX, int toY, int button) {
        OpusClientScreen screen = previewScreen();
        return screen != null && screen.previewDrag(x, y, toX, toY, button);
    }

    boolean previewKey(String key) {
        OpusClientScreen screen = previewScreen();
        return screen != null && screen.previewKey(key);
    }

    boolean previewProductSurfaceReady() {
        OpusClientScreen screen = previewScreen();
        return screen != null && screen.previewProductSurfaceReady();
    }

    boolean previewProductSurfaceFailed() {
        OpusClientScreen screen = previewScreen();
        return screen != null && screen.previewProductSurfaceFailed();
    }

    String previewProductSurfaceState() {
        OpusClientScreen screen = previewScreen();
        return screen == null ? "closed" : screen.previewProductSurfaceState();
    }

    UiBounds previewProductViewport() {
        OpusClientScreen screen = previewScreen();
        return screen == null ? new UiBounds(0, 0, 0, 0) : screen.previewProductViewport();
    }

    boolean previewNativeHudCanvasReady() {
        OpusClientScreen screen = previewScreen();
        return screen != null && screen.previewNativeHudCanvasReady();
    }

    /** Preview Mode only: opens the same CEF catalog route as the pause flow. */
    boolean previewOpenPauseMenuCatalog() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!(minecraft.currentScreen instanceof OpusPreviewPauseMenu)) {
            return false;
        }
        minecraft.displayGuiScreen(new OpusClientScreen(
                this,
                new UiNavigationState(
                        UiRoute.modHub(),
                        UiNavigationState.EntryPoint.PAUSE,
                        UiRoute.pauseMenu())));
        return minecraft.currentScreen instanceof OpusClientScreen
                && ((OpusClientScreen) minecraft.currentScreen).previewRoute().equals(UiRoute.modHub());
    }

    /** Preview Mode only: invokes the same route method used by Right Shift. */
    boolean previewOpenRightShiftQuickHub() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen != null) {
            // World startup can leave the genuine pause screen open for one
            // tick while the integrated server finishes joining. Preview
            // closes that transitional screen before invoking the exact
            // production hotkey flow; it must not turn the pause screen into
            // a second product route or bypass openHotkeyFlow().
            if (minecraft.currentScreen instanceof OpusClientScreen
                    && ((OpusClientScreen) minecraft.currentScreen).route().kind()
                            == UiRoute.Kind.PAUSE_MENU) {
                minecraft.displayGuiScreen(null);
            }
            if (minecraft.currentScreen != null) {
                return false;
            }
        }
        openHotkeyFlow();
        return minecraft.currentScreen instanceof OpusClientScreen
                && ((OpusClientScreen) minecraft.currentScreen).previewRoute().equals(UiRoute.quickHub());
    }

    String previewRouteName() {
        OpusClientScreen screen = previewScreen();
        return screen == null
                ? "none"
                : routeId(screen.previewRoute());
    }

    void capturePreview(String fileName) {
        Minecraft minecraft = Minecraft.getMinecraft();
        ScreenShotHelper.saveScreenshot(
                minecraft.mcDataDir,
                fileName,
                minecraft.displayWidth,
                minecraft.displayHeight,
                minecraft.getFramebuffer());
        log.info("Opus UI Preview framebuffer written to {}/screenshots/{}", minecraft.mcDataDir, fileName);
    }

    private void openOptions(UiRoute route) {
        Minecraft minecraft = Minecraft.getMinecraft();
        GuiScreen current = minecraft.currentScreen;
        if (current instanceof OpusClientScreen) {
            return;
        }

        settingsStore.reload();
        minecraft.displayGuiScreen(new OpusClientScreen(this, route));
    }

    private void openHotkeyFlow() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen != null) {
            return;
        }
        settingsStore.reload();
        minecraft.displayGuiScreen(new OpusClientScreen(
                this,
                new UiNavigationState(
                        UiRoute.quickHub(),
                        UiNavigationState.EntryPoint.HOTKEY)));
    }

    private OpusClientScreen previewScreen() {
        return Minecraft.getMinecraft().currentScreen instanceof OpusClientScreen
                ? (OpusClientScreen) Minecraft.getMinecraft().currentScreen
                : null;
    }

    private OpusClientScreen mainMenuScreen() {
        return new OpusClientScreen(this, UiRoute.mainMenu());
    }

    private void runCaptureProbe() {
        if (captureRouteNames.length == 0 || captureComplete) {
            return;
        }
        String captureRouteName = captureRouteNames[captureRouteIndex];
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!captureRouteOpened) {
            minecraft.displayGuiScreen(new OpusClientScreen(this, captureRoute(captureRouteName.trim())));
            captureRouteOpened = true;
            captureTicks = 0;
            return;
        }
        captureTicks++;
        if (captureTicks < 40) {
            return;
        }
        String fileName = "opus-ui-" + captureRouteName + ".png";
        ScreenShotHelper.saveScreenshot(
                minecraft.mcDataDir,
                fileName,
                minecraft.displayWidth,
                minecraft.displayHeight,
                minecraft.getFramebuffer());
        log.info("Opus UI capture written to {}/screenshots/{}", minecraft.mcDataDir, fileName);
        captureRouteIndex++;
        if (captureRouteIndex >= captureRouteNames.length) {
            captureComplete = true;
        } else {
            captureRouteOpened = false;
            captureTicks = 0;
        }
    }

    private static UiRoute captureRoute(String routeName) {
        String normalized = routeName.toLowerCase(java.util.Locale.ROOT);
        if ("main".equals(normalized)) {
            return UiRoute.mainMenu();
        }
        if ("hud".equals(normalized)) {
            return UiRoute.hudEditor();
        }
        if ("mods".equals(normalized)) {
            return UiRoute.modHub();
        }
        if ("detail".equals(normalized)) {
            return UiRoute.moduleDetail(FpsModule.ID);
        }
        if ("armor".equals(normalized)) {
            return UiRoute.moduleDetail(ArmorStatusModule.ID);
        }
        throw new IllegalArgumentException("unknown Opus UI capture route: " + routeName);
    }

    private static String[] captureRoutes(String rawValue) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return new String[0];
        }
        String normalized = rawValue.trim().toLowerCase(java.util.Locale.ROOT);
        if ("all".equals(normalized)) {
            return new String[] {"main", "hud", "mods", "detail", "armor"};
        }
        return new String[] {normalized};
    }

    private interface ClientCall<T> {
        T call();
    }

    /**
     * Synchronously reads a value on the Minecraft client thread. Bridge
     * requests are short-lived, so a bounded wait is safer than allowing a
     * worker to touch a live world or HUD collection concurrently.
     */
    private <T> T callOnClientThread(
            final String operation,
            final ClientCall<T> call,
            T fallback) {
        final Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.isCallingFromMinecraftThread()) {
            try {
                return call.call();
            } catch (Throwable failure) {
                log.warn("Opus client-thread read failed: {}", operation, failure);
                return fallback;
            }
        }
        if (shuttingDown) {
            return fallback;
        }
        final AtomicReference<T> result = new AtomicReference<T>(fallback);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final CountDownLatch completed = new CountDownLatch(1);
        try {
            minecraft.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    try {
                        result.set(call.call());
                    } catch (Throwable problem) {
                        failure.set(problem);
                    } finally {
                        completed.countDown();
                    }
                }
            });
            if (!completed.await(2L, TimeUnit.SECONDS)) {
                log.warn("Opus client-thread read timed out: {}", operation);
                return fallback;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Opus client-thread read interrupted: {}", operation);
            return fallback;
        } catch (Throwable schedulingFailure) {
            log.warn("Opus client-thread read could not be scheduled: {}", operation,
                    schedulingFailure);
            return fallback;
        }
        if (failure.get() != null) {
            log.warn("Opus client-thread read failed: {}", operation, failure.get());
        }
        return result.get();
    }

    private void runOnClientThread(final String operation, final Runnable action) {
        callOnClientThread(operation, new ClientCall<Object>() {
            @Override
            public Object call() {
                action.run();
                return null;
            }
        }, null);
    }

    /** Worker-thread-safe facade used only by the loopback bridge server. */
    private final class ClientThreadBridge implements OpusBridgeProvider {
        @Override
        public boolean performUiAction(
                String action,
                String routeId,
                String moduleId,
                String settingsSection,
                long expectedRevision) {
            // The owner already marshals this operation and waits for the
            // authoritative route result; avoid scheduling it twice.
            return ClientOverlayController.this.performUiAction(
                    action, routeId, moduleId, settingsSection, expectedRevision);
        }

        @Override
        public List<String[]> listWorlds() {
            return callOnClientThread("listWorlds", new ClientCall<List<String[]>>() {
                @Override
                public List<String[]> call() {
                    return ClientOverlayController.this.listWorlds();
                }
            }, java.util.Collections.<String[]>emptyList());
        }

        @Override
        public void loadWorld(final String fileName, final String displayName) {
            runOnClientThread("loadWorld", new Runnable() {
                @Override
                public void run() {
                    ClientOverlayController.this.loadWorld(fileName, displayName);
                }
            });
        }

        @Override
        public List<String[]> listServers() {
            return callOnClientThread("listServers", new ClientCall<List<String[]>>() {
                @Override
                public List<String[]> call() {
                    return ClientOverlayController.this.listServers();
                }
            }, java.util.Collections.<String[]>emptyList());
        }

        @Override
        public void connectServer(final String address) {
            runOnClientThread("connectServer", new Runnable() {
                @Override
                public void run() {
                    ClientOverlayController.this.connectServer(address);
                }
            });
        }

        @Override
        public void addServer(final String name, final String address) {
            runOnClientThread("addServer", new Runnable() {
                @Override
                public void run() {
                    ClientOverlayController.this.addServer(name, address);
                }
            });
        }

        @Override
        public List<String[]> gameOptions() {
            return callOnClientThread("gameOptions", new ClientCall<List<String[]>>() {
                @Override
                public List<String[]> call() {
                    return ClientOverlayController.this.gameOptions();
                }
            }, java.util.Collections.<String[]>emptyList());
        }

        @Override
        public void adjustGameOption(final String key, final int delta) {
            runOnClientThread("adjustGameOption", new Runnable() {
                @Override
                public void run() {
                    ClientOverlayController.this.adjustGameOption(key, delta);
                }
            });
        }

        @Override
        public void setGameOptionFloat(final String key, final float value) {
            runOnClientThread("setGameOptionFloat", new Runnable() {
                @Override
                public void run() {
                    ClientOverlayController.this.setGameOptionFloat(key, value);
                }
            });
        }

        @Override
        public void leaveWorld() {
            runOnClientThread("leaveWorld", new Runnable() {
                @Override
                public void run() {
                    ClientOverlayController.this.leaveWorld();
                }
            });
        }

        @Override
        public List<String[]> modules() {
            return callOnClientThread("modules", new ClientCall<List<String[]>>() {
                @Override
                public List<String[]> call() {
                    return ClientOverlayController.this.modules();
                }
            }, java.util.Collections.<String[]>emptyList());
        }

        @Override
        public void setModuleEnabled(final String moduleId, final boolean enabled) {
            runOnClientThread("setModuleEnabled", new Runnable() {
                @Override
                public void run() {
                    ClientOverlayController.this.setModuleEnabled(moduleId, enabled);
                }
            });
        }

        @Override
        public List<String[]> moduleSettings(final String moduleId) {
            return callOnClientThread("moduleSettings", new ClientCall<List<String[]>>() {
                @Override
                public List<String[]> call() {
                    return ClientOverlayController.this.moduleSettings(moduleId);
                }
            }, java.util.Collections.<String[]>emptyList());
        }

        @Override
        public void setModuleSetting(
                final String moduleId,
                final String key,
                final String value) {
            runOnClientThread("setModuleSetting", new Runnable() {
                @Override
                public void run() {
                    ClientOverlayController.this.setModuleSetting(moduleId, key, value);
                }
            });
        }

        @Override
        public List<String[]> hudModules() {
            return callOnClientThread("hudModules", new ClientCall<List<String[]>>() {
                @Override
                public List<String[]> call() {
                    return ClientOverlayController.this.hudModules();
                }
            }, java.util.Collections.<String[]>emptyList());
        }

        @Override
        public void moveHudModule(final String moduleId, final int x, final int y) {
            runOnClientThread("moveHudModule", new Runnable() {
                @Override
                public void run() {
                    ClientOverlayController.this.moveHudModule(moduleId, x, y);
                }
            });
        }

        @Override
        public void updateHudEditorCanvas(
                long navigationRevision,
                int x,
                int y,
                int width,
                int height) {
            // The owner already schedules and applies this revision-guarded
            // canvas update on the client thread.
            ClientOverlayController.this.updateHudEditorCanvas(
                    navigationRevision, x, y, width, height);
        }
    }

}
