package dev.rbw.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;
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
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

import java.util.List;
import java.util.Arrays;
import dev.rbw.client.hud.HudManager;
import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.UiInput;
import dev.rbw.client.ui.UiRenderer;
import dev.rbw.client.module.ModuleRegistry;
import dev.rbw.client.ui.UiRoute;

/**
 * Owns all interaction with the typed Forge client APIs. No ASM, reflection,
 * or access to obfuscated Minecraft members is used for RBW's overlay surface.
 */
/**
 * Public because Forge generates its event-handler class in a different
 * package at runtime. Keeping this package-private prevents that generated
 * handler from invoking the subscribed methods and crashes Minecraft during
 * the first pause-menu initialization.
 */
public final class ClientOverlayController {
    private static final int CLIENT_OPTIONS_BUTTON_ID = 0x524257;
    private static final String CLIENT_OPTIONS_LABEL = "Client Options";

    private final Logger log;
    private final KeyBinding openOptionsKey = new KeyBinding(
            "key.rbwclient.open_options",
            Keyboard.KEY_RSHIFT,
            "key.categories.rbwclient");
    private final UtilitySettingsStore settingsStore;
    private final FpsModule fpsModule;
    private final ArmorStatusModule armorStatusModule;
    private final ModuleRegistry moduleRegistry;
    private final HudManager hudManager;
    private final UiThemeStore themeStore;
    private final UiPreviewSession previewSession;
    private final String[] captureRouteNames = captureRoutes(System.getProperty("rbw.ui.capture.route"));
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
    }

    void initialize() {
        settingsStore.reload();
        themeStore.poll();
        ClientRegistry.registerKeyBinding(openOptionsKey);
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        log.info("RBW client options registered: Right Shift and the pause-menu Client Options button open the real utility surface.");
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

    @SubscribeEvent
    public void onGuiOpening(GuiOpenEvent event) {
        if (event.gui instanceof GuiMainMenu) {
            event.gui = new RbwClientScreen(this, UiRoute.mainMenu());
        }
    }

    @SubscribeEvent
    public void onRenderHudText(RenderGameOverlayEvent.Text event) {
        // The workspace is translucent live context, not a modal blackout:
        // enabled RBW widgets remain part of the player's HUD beneath it.
        // The editor draws the same widget itself so it can attach its hover
        // controls without a second FPS pass.
        if (Minecraft.getMinecraft().currentScreen instanceof RbwClientScreen
                && ((RbwClientScreen) Minecraft.getMinecraft().currentScreen).isHudEditor()) {
            return;
        }
        hudManager.render(Minecraft.getMinecraft(), event.resolution);
    }

    @SubscribeEvent
    public void onRenderHudPre(RenderGameOverlayEvent.Pre event) {
        // Deliberately left intact. Vanilla HUD is live world context and
        // must stay visible around the RBW workspace, like the HUD editor.
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

    void openVanillaOptions() {
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.displayGuiScreen(new GuiOptions(mainMenuScreen(), minecraft.gameSettings));
    }

    void openClientModHub() {
        Minecraft.getMinecraft().displayGuiScreen(new RbwClientScreen(this, UiRoute.modHub()));
    }

    void quitGame() {
        Minecraft.getMinecraft().shutdown();
    }

    /**
     * Preview Mode only. It drives the same route renderer used by Right Shift
     * and Client Options, never a separate UI or a desktop/window screenshot.
     */
    void openPreviewRoute(UiRoute route) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen instanceof RbwClientScreen) {
            ((RbwClientScreen) minecraft.currentScreen).previewNavigate(route);
            return;
        }
        settingsStore.reload();
        minecraft.displayGuiScreen(new RbwClientScreen(this, route));
    }

    void setPreviewPointer(int x, int y) {
        RbwClientScreen screen = previewScreen();
        if (screen != null) {
            screen.setPreviewPointer(x, y);
        }
    }

    void clearPreviewPointer() {
        RbwClientScreen screen = previewScreen();
        if (screen != null) {
            screen.clearPreviewPointer();
        }
    }

    boolean previewClick(int x, int y, int button) {
        RbwClientScreen screen = previewScreen();
        return screen != null && screen.previewClick(x, y, button);
    }

    boolean previewDrag(int x, int y, int toX, int toY, int button) {
        RbwClientScreen screen = previewScreen();
        return screen != null && screen.previewDrag(x, y, toX, toY, button);
    }

    /** Preview Mode only: posts the same FML action event as the pause-menu button. */
    boolean previewOpenPauseMenuClientOptions() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!(minecraft.currentScreen instanceof RbwPreviewPauseMenu)) {
            return false;
        }
        RbwPreviewPauseMenu pauseMenu = (RbwPreviewPauseMenu) minecraft.currentScreen;
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
        return minecraft.currentScreen instanceof RbwClientScreen
                && ((RbwClientScreen) minecraft.currentScreen).previewRoute().equals(UiRoute.modHub());
    }

    /** Preview Mode only: invokes the same route method used by Right Shift. */
    boolean previewOpenRightShiftHudEditor() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen != null) {
            return false;
        }
        openOptions(UiRoute.hudEditor());
        return minecraft.currentScreen instanceof RbwClientScreen
                && ((RbwClientScreen) minecraft.currentScreen).previewRoute().equals(UiRoute.hudEditor());
    }

    String previewRouteName() {
        RbwClientScreen screen = previewScreen();
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
        log.info("RBW UI Preview framebuffer written to {}/screenshots/{}", minecraft.mcDataDir, fileName);
    }

    private void openOptions(UiRoute route) {
        Minecraft minecraft = Minecraft.getMinecraft();
        GuiScreen current = minecraft.currentScreen;
        if (current instanceof RbwClientScreen) {
            return;
        }

        settingsStore.reload();
        minecraft.displayGuiScreen(new RbwClientScreen(this, route));
    }

    private RbwClientScreen previewScreen() {
        return Minecraft.getMinecraft().currentScreen instanceof RbwClientScreen
                ? (RbwClientScreen) Minecraft.getMinecraft().currentScreen
                : null;
    }

    private RbwClientScreen mainMenuScreen() {
        return new RbwClientScreen(this, UiRoute.mainMenu());
    }

    private void runCaptureProbe() {
        if (captureRouteNames.length == 0 || captureComplete) {
            return;
        }
        String captureRouteName = captureRouteNames[captureRouteIndex];
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!captureRouteOpened) {
            minecraft.displayGuiScreen(new RbwClientScreen(this, captureRoute(captureRouteName.trim())));
            captureRouteOpened = true;
            captureTicks = 0;
            return;
        }
        captureTicks++;
        if (captureTicks < 40) {
            return;
        }
        String fileName = "rbw-ui-" + captureRouteName + ".png";
        ScreenShotHelper.saveScreenshot(
                minecraft.mcDataDir,
                fileName,
                minecraft.displayWidth,
                minecraft.displayHeight,
                minecraft.getFramebuffer());
        log.info("RBW UI capture written to {}/screenshots/{}", minecraft.mcDataDir, fileName);
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
        throw new IllegalArgumentException("unknown RBW UI capture route: " + routeName);
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
