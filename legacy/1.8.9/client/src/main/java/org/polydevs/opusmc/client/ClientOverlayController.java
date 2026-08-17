package org.polydevs.opusmc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.resources.Language;
import net.minecraft.client.resources.LanguageManager;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.SaveFormatComparator;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import org.polydevs.opusmc.client.hud.HudManager;
import org.polydevs.opusmc.client.interop.OpusExternalBrowser;
import org.polydevs.opusmc.client.interop.OpusInteropServer;
import org.polydevs.opusmc.client.interop.OpusBridgeProvider;
import org.polydevs.opusmc.client.interop.OpusVirtualScreenManager;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.module.ModuleRegistry;
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
    private static final int CLIENT_OPTIONS_BUTTON_ID = 0x524257;
    private static final String CLIENT_OPTIONS_LABEL = "Client Options";

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
    private final UiThemeStore themeStore;
    private final UiPreviewSession previewSession;
    private final OpusVanillaTerminalOverlay vanillaTerminal;
    private final OpusInteropServer interopServer;
    private final OpusVirtualScreenManager virtualScreens;
    private final String[] captureRouteNames = captureRoutes(System.getProperty("opus.ui.capture.route"));
    private int captureRouteIndex;
    private boolean captureRouteOpened;
    private boolean captureComplete;
    private int captureTicks;

    ClientOverlayController(Logger log) {
        this.log = log;
        this.settingsStore = UtilitySettingsStore.fromSystemProperty(log);
        this.fpsModule = new FpsModule(settingsStore);
        this.armorStatusModule = new ArmorStatusModule(settingsStore);
        this.moduleRegistry = new ModuleRegistry();
        this.moduleRegistry.register(fpsModule);
        this.moduleRegistry.register(armorStatusModule);
        this.hudManager = new HudManager(Arrays.asList(fpsModule.widget(), armorStatusModule.widget()));
        this.themeStore = UiThemeStore.fromSystemProperties(log);
        this.previewSession = UiPreviewSession.fromSystemProperties(log);
        this.vanillaTerminal = new OpusVanillaTerminalOverlay(log);
        this.interopServer = new OpusInteropServer(log);
        this.virtualScreens = new OpusVirtualScreenManager(log, interopServer);
        this.interopServer.setScreenManager(virtualScreens);
        this.interopServer.setBridgeProvider(this);
    }

    void initialize() {
        settingsStore.reload();
        themeStore.poll();
        ClientRegistry.registerKeyBinding(openOptionsKey);
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        if (!Boolean.getBoolean("opus.interop.skip") && interopServer.start()) {
            virtualScreens.setReloadAction(new Runnable() {
                @Override
                public void run() {
                    OpusExternalBrowser.open(log, interopServer.url());
                }
            });
            log.info("Opus web UI bridge listening on {}", interopServer.url());
        }
        log.info("Opus client options registered: Right Shift and the pause-menu Client Options button open the real utility surface.");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        while (openOptionsKey.isPressed()) {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft.currentScreen == null) {
                openOptions(UiRoute.hudEditor());
            }
        }
        themeStore.poll();
        previewSession.tick(this);
        runCaptureProbe();
    }

    @SubscribeEvent
    public void onPauseMenuInitialized(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(event.gui instanceof GuiIngameMenu) || hasClientOptionsButton(event.buttonList)) {
            return;
        }

        int x = event.gui.width / 2 - 100;
        // This is the real unused row between Achievements/Statistics and
        // Options/Share to LAN in Minecraft 1.8.9's pause menu.
        int y = event.gui.height / 4 + 56;
        event.buttonList.add(new GuiButton(CLIENT_OPTIONS_BUTTON_ID, x, y, 200, 20, CLIENT_OPTIONS_LABEL));
    }

    @SubscribeEvent
    public void onPauseMenuAction(GuiScreenEvent.ActionPerformedEvent.Post event) {
        if (event.gui instanceof GuiIngameMenu
                && event.button != null
                && event.button.id == CLIENT_OPTIONS_BUTTON_ID) {
            openOptions(UiRoute.modHub());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onVanillaGuiInitialized(GuiScreenEvent.InitGuiEvent.Post event) {
        vanillaTerminal.initialized(event.gui, event.buttonList);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onVanillaGuiDraw(GuiScreenEvent.DrawScreenEvent.Pre event) {
        vanillaTerminal.draw(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onVanillaGuiKeyboard(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        vanillaTerminal.keyboard(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onVanillaGuiMouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        vanillaTerminal.mouse(event);
    }

    @SubscribeEvent
    public void onGuiOpening(GuiOpenEvent event) {
        if (event.gui instanceof GuiMainMenu) {
            event.gui = new OpusClientScreen(this, UiRoute.mainMenu());
            virtualScreens.open("title");
        } else if (event.gui instanceof GuiIngameMenu && !(event.gui instanceof OpusPreviewPauseMenu)) {
            // Replace the vanilla pause menu with the Opus terminal pause page.
            // The preview-only subclass is excluded so Preview Mode still drives
            // the genuine FML-populated controls.
            event.gui = new OpusClientScreen(this, UiRoute.pauseMenu());
            virtualScreens.open("game_menu");
        } else if (event.gui instanceof GuiSelectWorld) {
            // Any path that would show the vanilla world list (including other
            // mods) is redirected to the Opus terminal singleplayer page.
            event.gui = new OpusClientScreen(this, UiRoute.singleplayer());
            virtualScreens.open("singleplayer");
        } else if (event.gui instanceof GuiMultiplayer) {
            // Same for the vanilla server list.
            event.gui = new OpusClientScreen(this, UiRoute.multiplayer());
            virtualScreens.open("multiplayer");
        }
    }

    /**
     * Mirrors an in-game Opus route change to the web UI. Route names follow
     * the LiquidBounce virtual-screen contract (title, singleplayer,
     * multiplayer, settings, hud...).
     */
    void onRouteChanged(UiRoute.Kind kind) {
        String screen = virtualScreenName(kind);
        if (screen != null) {
            virtualScreens.open(screen);
        }
    }

    void onScreenClosed() {
        virtualScreens.close();
    }

    /** Dev entry point: opens the bundled Opus web UI in the system browser. */
    void openExternalUi() {
        OpusExternalBrowser.open(log, interopServer.url());
    }

    private static String virtualScreenName(UiRoute.Kind kind) {
        switch (kind) {
            case MAIN_MENU:
                return "title";
            case SINGLEPLAYER:
                return "singleplayer";
            case MULTIPLAYER:
                return "multiplayer";
            case GAME_OPTIONS:
            case CONTROLS:
            case LANGUAGE:
                return "settings";
            case PAUSE_MENU:
                return "game_menu";
            case HUD_EDITOR:
                return "hud";
            case MOD_HUB:
            case MODULE_DETAIL:
                return "settings";
            default:
                return null;
        }
    }

    @SubscribeEvent
    public void onRenderHudText(RenderGameOverlayEvent.Text event) {
        // The workspace is translucent live context, not a modal blackout:
        // enabled Opus widgets remain part of the player's HUD beneath it.
        // The editor draws the same widget itself so it can attach its hover
        // controls without a second FPS pass.
        if (Minecraft.getMinecraft().currentScreen instanceof OpusClientScreen
                && ((OpusClientScreen) Minecraft.getMinecraft().currentScreen).isHudEditor()) {
            return;
        }
        hudManager.render(Minecraft.getMinecraft(), event.resolution);
    }

    @SubscribeEvent
    public void onRenderHudPre(RenderGameOverlayEvent.Pre event) {
        // Deliberately left intact. Vanilla HUD is live world context and
        // must stay visible around the Opus workspace, like the HUD editor.
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

    boolean hudEditorMouseDrag(UiBounds viewport, int mouseX, int mouseY, int button) {
        return hudManager.editorMouseDrag(viewport, mouseX, mouseY, button);
    }

    boolean hudEditorMouseUp(int button) {
        return hudManager.editorMouseUp(button);
    }

    void openSingleplayer() {
        Minecraft.getMinecraft().displayGuiScreen(new GuiSelectWorld(mainMenuScreen()));
    }

    void openMultiplayer() {
        Minecraft.getMinecraft().displayGuiScreen(new GuiMultiplayer(mainMenuScreen()));
    }

    /**
     * Read the real singleplayer save list through the typed save-format API so
     * the Opus TUI can list worlds without any vanilla GuiSelectWorld screen.
     * Each row is {fileName, displayName}. Failures degrade to an empty list so
     * the terminal always renders.
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
     * FML's loader (so mod save data and conversions are handled). After this
     * the world loads and the Opus terminal closes.
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
     * returns to the Opus TUI multiplayer page rather than a vanilla screen.
     */
    @Override
    public void connectServer(String address) {
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            ServerData data = new ServerData("Opus Server", address.trim(), false);
            minecraft.displayGuiScreen(
                    new GuiConnecting(new OpusClientScreen(this, UiRoute.multiplayer()), minecraft, data));
        } catch (Throwable failure) {
            log.warn("Opus failed to connect to {}", address, failure);
        }
    }

    /** Persist a new server entry so it appears in the Opus TUI server list. */
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

    // ---- Opus terminal Game Options (typed GameSettings access) -------------
    // These option keys map to net.minecraft GameSettings.Options constants by
    // name. Rendering, cycling and persistence all go through the typed public
    // API, so the Opus terminal never needs the vanilla GuiOptions screen.
    private static final String[] OPTION_KEYS = {
        "FOV", "GAMMA", "SENSITIVITY", "RENDER_DISTANCE", "FRAMERATE_LIMIT",
        "GUI_SCALE", "PARTICLES", "GRAPHICS", "RENDER_CLOUDS", "AMBIENT_OCCLUSION",
        "VIEW_BOBBING", "ENABLE_VSYNC", "USE_VBO", "ENTITY_SHADOWS", "USE_FULLSCREEN",
    };

    String[] gameOptionKeys() {
        return OPTION_KEYS.clone();
    }

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
     * Where the terminal Game Options should return on Back/ESC: the pause menu
     * while a world is loaded, otherwise the title. Keeps the flow correct for
     * both in-game and main-menu entry without a vanilla screen.
     */
    UiRoute optionsBackRoute() {
        return Minecraft.getMinecraft().theWorld != null ? UiRoute.pauseMenu() : UiRoute.mainMenu();
    }

    /** Human label + current value string for an option key, via getKeyBinding. */
    String gameOptionDisplay(String key) {
        try {
            GameSettings settings = Minecraft.getMinecraft().gameSettings;
            GameSettings.Options option = gameOption(key);
            return settings.getKeyBinding(option);
        } catch (Throwable failure) {
            return key;
        }
    }

    boolean gameOptionIsSlider(String key) {
        try {
            return gameOption(key).getEnumFloat();
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Cycle a boolean/enum option (delta ignored) or nudge a float slider by
     * delta steps. Persists immediately, matching vanilla's button behavior.
     */
    void adjustGameOption(String key, int delta) {
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

    /**
     * OptiFine owns the runtime behavior of its video settings tree. The
     * original screen object stays active, but OpusVanillaTerminalOverlay
     * cancels its complete draw pass and exposes its controls as terminal rows.
     */
    void openVideoScreen() {
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.displayGuiScreen(new GuiVideoSettings(new OpusClientScreen(this, UiRoute.gameOptions()), minecraft.gameSettings));
    }

    // ---- Opus terminal Controls -------------------------------------------

    List<String[]> keyBindingRows() {
        List<KeyBinding> bindings = sortedKeyBindings();
        List<String[]> rows = new ArrayList<String[]>(bindings.size());
        for (KeyBinding binding : bindings) {
            rows.add(new String[] {
                translated(binding.getKeyDescription()),
                GameSettings.getKeyDisplayString(binding.getKeyCode()),
            });
        }
        return rows;
    }

    String keyBindingCategory(int index) {
        KeyBinding binding = keyBinding(index);
        return binding == null ? "" : translated(binding.getKeyCategory());
    }

    String keyBindingConflict(int index) {
        KeyBinding selected = keyBinding(index);
        if (selected == null || selected.getKeyCode() == 0) {
            return "none";
        }
        for (KeyBinding candidate : sortedKeyBindings()) {
            if (candidate != selected && candidate.getKeyCode() == selected.getKeyCode()) {
                return translated(candidate.getKeyDescription());
            }
        }
        return "none";
    }

    void rebindKey(int index, int keyCode) {
        KeyBinding binding = keyBinding(index);
        if (binding == null) {
            return;
        }
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        settings.setOptionKeyBinding(binding, keyCode);
        KeyBinding.resetKeyBindingArrayAndHash();
        settings.saveOptions();
    }

    void resetKeyBinding(int index) {
        KeyBinding binding = keyBinding(index);
        if (binding != null) {
            rebindKey(index, binding.getKeyCodeDefault());
        }
    }

    void resetAllKeyBindings() {
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        for (KeyBinding binding : settings.keyBindings) {
            settings.setOptionKeyBinding(binding, binding.getKeyCodeDefault());
        }
        KeyBinding.resetKeyBindingArrayAndHash();
        settings.saveOptions();
    }

    private KeyBinding keyBinding(int index) {
        List<KeyBinding> bindings = sortedKeyBindings();
        return index >= 0 && index < bindings.size() ? bindings.get(index) : null;
    }

    private List<KeyBinding> sortedKeyBindings() {
        KeyBinding[] source = Minecraft.getMinecraft().gameSettings.keyBindings;
        List<KeyBinding> bindings = new ArrayList<KeyBinding>(Arrays.asList(source));
        Collections.sort(bindings, new Comparator<KeyBinding>() {
            @Override
            public int compare(KeyBinding left, KeyBinding right) {
                int category = left.getKeyCategory().compareTo(right.getKeyCategory());
                return category != 0
                        ? category
                        : left.getKeyDescription().compareTo(right.getKeyDescription());
            }
        });
        return bindings;
    }

    // ---- Opus terminal Language -------------------------------------------

    List<String[]> languageRows() {
        List<Language> languages = languages();
        List<String[]> rows = new ArrayList<String[]>(languages.size());
        for (Language language : languages) {
            rows.add(new String[] {language.toString(), language.getLanguageCode()});
        }
        return rows;
    }

    String currentLanguageCode() {
        Language current = Minecraft.getMinecraft().getLanguageManager().getCurrentLanguage();
        return current == null ? Minecraft.getMinecraft().gameSettings.language : current.getLanguageCode();
    }

    void selectLanguage(int index) {
        List<Language> languages = languages();
        if (index < 0 || index >= languages.size()) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        LanguageManager manager = minecraft.getLanguageManager();
        Language selected = languages.get(index);
        manager.setCurrentLanguage(selected);
        minecraft.gameSettings.language = selected.getLanguageCode();
        minecraft.refreshResources();
        minecraft.fontRendererObj.setUnicodeFlag(
                manager.isCurrentLocaleUnicode() || minecraft.gameSettings.forceUnicodeFont);
        minecraft.fontRendererObj.setBidiFlag(manager.isCurrentLanguageBidirectional());
        minecraft.gameSettings.saveOptions();
    }

    private List<Language> languages() {
        return new ArrayList<Language>(
                Minecraft.getMinecraft().getLanguageManager().getLanguages());
    }

    private static String translated(String key) {
        String value = I18n.format(key);
        return value == null || value.trim().isEmpty() ? key : value;
    }

    void openClientModHub() {
        Minecraft.getMinecraft().displayGuiScreen(new OpusClientScreen(this, UiRoute.modHub()));
    }

    void quitGame() {
        Minecraft.getMinecraft().shutdown();
    }

    /** Pause-menu Resume: close the Opus surface and recapture mouse/keyboard. */
    void resumeGame() {
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.displayGuiScreen(null);
        if (minecraft.currentScreen == null) {
            minecraft.setIngameFocus();
        }
    }

    /**
     * Pause-menu Disconnect: quit the current world through the typed client
     * API and return to the Opus TUI title, mirroring vanilla's flow without a
     * vanilla screen. Works for both integrated (singleplayer) and remote play.
     */
    void leaveWorld() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.theWorld != null) {
            minecraft.theWorld.sendQuittingDisconnectingPacket();
        }
        minecraft.loadWorld(null);
        minecraft.displayGuiScreen(new OpusClientScreen(this, UiRoute.mainMenu()));
    }

    /**
     * Preview Mode only. It drives the same route renderer used by Right Shift
     * and Client Options, never a separate UI or a desktop/window screenshot.
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

    /** Preview Mode only: posts the same FML action event as the pause-menu button. */
    boolean previewOpenPauseMenuClientOptions() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!(minecraft.currentScreen instanceof OpusPreviewPauseMenu)) {
            return false;
        }
        OpusPreviewPauseMenu pauseMenu = (OpusPreviewPauseMenu) minecraft.currentScreen;
        GuiButton clientOptions = null;
        for (GuiButton button : pauseMenu.buttons()) {
            if (button.id == CLIENT_OPTIONS_BUTTON_ID) {
                clientOptions = button;
                break;
            }
        }
        if (clientOptions == null) {
            return false;
        }
        MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.ActionPerformedEvent.Post(
                minecraft.currentScreen,
                clientOptions,
                pauseMenu.buttons()));
        return minecraft.currentScreen instanceof OpusClientScreen
                && ((OpusClientScreen) minecraft.currentScreen).previewRoute().equals(UiRoute.modHub());
    }

    /** Preview Mode only: invokes the same route method used by Right Shift. */
    boolean previewOpenRightShiftHudEditor() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen != null) {
            return false;
        }
        openOptions(UiRoute.hudEditor());
        return minecraft.currentScreen instanceof OpusClientScreen
                && ((OpusClientScreen) minecraft.currentScreen).previewRoute().equals(UiRoute.hudEditor());
    }

    String previewRouteName() {
        OpusClientScreen screen = previewScreen();
        return screen == null
                ? null
                : screen.previewRoute().kind().name().toLowerCase(java.util.Locale.ROOT);
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

    private static boolean hasClientOptionsButton(List<GuiButton> buttons) {
        for (GuiButton button : buttons) {
            if (button.id == CLIENT_OPTIONS_BUTTON_ID) {
                return true;
            }
        }
        return false;
    }
}
