package org.polydevs.opusmc.client;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;
import org.polydevs.opusmc.client.interop.OpusBridgeProvider;
import org.polydevs.opusmc.client.interop.OpusInteropServer;
import org.polydevs.opusmc.client.interop.UiNavigationManager;
import org.polydevs.opusmc.client.interop.ConnectionUiState;
import org.polydevs.opusmc.client.ui.UiNavigationState;
import org.polydevs.opusmc.client.ui.UiRoute;

public final class HostNavigationAckHarness {
    private static final long TIMEOUT_MS = 15000L;

    public static void main(String[] args) throws Exception {
        MediaProbe mediaProbe = new MediaProbe();
        Logger log = logger(mediaProbe);
        String helper = OpusWebViewClient.resolveHelperPath(log);
        require(helper != null, "CEF helper unavailable");

        // All account UI proof uses a disposable catalog, never the user's
        // launcher catalog. The host continues to own the active session.
        String previousAccountCatalog = System.getProperty("opus.accounts.file");
        Path accountCatalog = Files.createTempFile("opus-cef-account-menu-", ".json");
        Files.write(accountCatalog, ("{\"schemaVersion\":1,\"selectedAccountId\":\"offline:lower\","
                + "\"accounts\":[{\"id\":\"offline:lower\",\"username\":\"zvwgvx\",\"uuid\":\"11111111111111111111111111111111\",\"kind\":\"offline\",\"badge\":\"unofficial\"},"
                + "{\"id\":\"offline:upper\",\"username\":\"Zvwgvx\",\"uuid\":\"22222222222222222222222222222222\",\"kind\":\"offline\",\"badge\":\"unofficial\"}]}\n").getBytes(StandardCharsets.UTF_8));
        System.setProperty("opus.accounts.file", accountCatalog.toString());

        String previousUtilitySettings = System.getProperty("opus.utility.settings.file");
        Path utilitySettings = Files.createTempFile("opus-cef-fps-settings-", ".json");
        Files.write(utilitySettings, "{\"schemaVersion\":1,\"utilities\":{\"fps\":{\"enabled\":true}}}\n".getBytes(StandardCharsets.UTF_8));
        System.setProperty("opus.utility.settings.file", utilitySettings.toString());
        OpusInteropServer server = new OpusInteropServer(log);
        UiNavigationManager navigation = new UiNavigationManager(log, server);
        OpusWebViewClient client = null;
        try {
            server.setNavigationManager(navigation);
            StubBridgeProvider provider = new StubBridgeProvider(log, navigation);
            server.setBridgeProvider(provider);
            require(server.start(), "interop server did not start");

            client = new OpusWebViewClient(log, helper);
            require(client.start(427, 240, 2.0F, mediaProbeUrl(server, "title")),
                    "CEF helper did not start");
            awaitFrame(client, 427, 240, 2.0F);
            require(navigation.revision() == 0L, "prewarm must precede the first Java route");
            mediaProbe.awaitState(false, true, 0);
            // Reproduce game boot: the SPA first reads revision 0 / "none"
            // while the CEF URL is already #/title. Java then opens title at
            // the same URL, which does not dispatch a hashchange event.
            Thread.sleep(500L);
            navigation.open("title");
            awaitAck(navigation, "title");
            mediaProbe.awaitRouteCommit(navigation.revision(), "title");
            mediaProbe.awaitState(true, false, 0);
            pass("prewarmed React Home acknowledges same-URL Java title commit");

            provider.clearAction();
            long accountRevision = navigation.revision();
            int accountCursor = mediaProbe.accountCount();
            clickControl(client, mediaProbe, "account-trigger");
            mediaProbe.awaitAccountMenu(true, accountCursor);
            captureLocalAccountFrame(client, mediaProbe, "account-menu", true);
            require(provider.lastAction == null && navigation.revision() == accountRevision,
                    "opening an account popover must not invoke route navigation");
            client.sendKey(27, true);
            client.sendKey(27, false);
            mediaProbe.awaitAccountMenu(false, accountCursor + 1);
            captureLocalAccountFrame(client, mediaProbe, "account-menu-dismissed", false);
            require(provider.lastAction == null && navigation.revision() == accountRevision,
                    "popover Escape must not navigate back or park Home");
            clickControl(client, mediaProbe, "account-trigger");
            mediaProbe.awaitAccountMenu(true, accountCursor + 2);
            captureLocalAccountFrame(client, mediaProbe, "account-menu-reopened", true);
            focusControl(client, mediaProbe, "account-manage"); pressKey(client, 13);
            awaitAction(provider, "navigate", "accounts");
            require(provider.lastRevision == accountRevision,
                    "Manage Accounts must preserve the visible Home revision");
            pass("CEF account popover opens/dismisses locally; Manage Accounts reaches Java at Retina geometry");

            // Exercise the real React -> HTTP -> disposable catalog write,
            // not just the endpoint and a mocked browser boundary separately.
            provider.clearAction();
            client.resize(900, 600, 1.0F);
            awaitFrame(client, 900, 600, 1.0F);
            int selectionCursor = mediaProbe.accountCount();
            int readyCursor = mediaProbe.accountReadyCount();
            clickControl(client, mediaProbe, "account-trigger");
            mediaProbe.awaitAccountMenu(true, selectionCursor);
            // An open popover can still be reading its catalog. Its pending
            // rows are intentionally disabled; wait for the real DOM commit
            // before testing the fixture's four-control keyboard path.
            mediaProbe.awaitSelectableAccount(readyCursor);
            // The focused dialog's tab order is Close, Settings, Add, then
            // the unselected saved account (the selected row is disabled).
            focusControl(client, mediaProbe, "account-select");
            client.sendKey(13, true);
            client.sendKey(13, false);
            awaitCatalogSelection(accountCatalog, "offline:upper");
            require(provider.lastAction == null && navigation.revision() == accountRevision,
                    "account switch must not navigate or relaunch the client");
            client.sendKey(27, true);
            client.sendKey(27, false);
            mediaProbe.awaitAccountMenu(false, selectionCursor + 1);
            client.resize(427, 240, 2.0F);
            awaitFrame(client, 427, 240, 2.0F);
            require("Zvwgvx".equals(provider.activeName), "Account switch did not reach the live session fixture");
            pass("CEF keyboard selection persists the exact id and changes the session fixture without navigation");

            String previewPath = System.getenv("OPUS_PREVIEW_PNG");
            if (previewPath != null && !previewPath.trim().isEmpty()) {
                capturePreview(client, 427, 240, 2.0F, new File(previewPath.trim()));
                pass("headless Home preview written to " + previewPath.trim());
            }

            // At the compact 427x240 Home layout, exercise every primary
            // launch action through native CEF input. Centers are sampled
            // from the rendered 427x240 ranked-style menu: side-by-side play
            // buttons below bounded media, settings in the following row.
            // The native bridge assertions remain unchanged.
            provider.clearAction();
            clickControl(client, mediaProbe, "home-singleplayer");
            awaitAction(provider, "navigate", "singleplayer");
            pass("Home Singleplayer click reaches Java navigation bridge at live Retina geometry");

            provider.clearAction();
            clickControl(client, mediaProbe, "home-multiplayer");
            awaitAction(provider, "navigate", "multiplayer");
            pass("Home Multiplayer click reaches Java navigation bridge at live Retina geometry");

            provider.clearAction();
            clickControl(client, mediaProbe, "home-options");
            awaitAction(provider, "navigate", "settings");
            pass("Home Minecraft Settings click reaches Java bridge at live Retina geometry");

            provider.clearAction();
            clickControl(client, mediaProbe, "home-modules");
            awaitAction(provider, "navigate", "mods_catalog");
            pass("Home Modules click reaches Java navigation bridge at live Retina geometry");

            // The initial poll has stopped after revision 1. A second commit
            // at the same URL must therefore arrive over the event socket.
            navigation.open("title");
            awaitAck(navigation, "title repeat");
            pass("same-URL title recommit acknowledges after bootstrap polling stops");

            navigation.open("settings");
            client.navigate(mediaProbeUrl(server, "settings"));
            awaitFrame(client, 427, 240, 2.0F);
            awaitAck(navigation, "settings");
            pass("host React hash navigation acknowledges Java revision");

            // Client Settings is a real host-backed control center. Commit the
            // same state shape used when it is opened from an in-world pause
            // menu so both Back and Close-to-game remain native authority.
            UiNavigationState clientSettingsFlow = new UiNavigationState(
                    UiRoute.gameOptions(UiRoute.SettingsSection.INTERFACE),
                    UiNavigationState.EntryPoint.PAUSE,
                    UiRoute.pauseMenu());
            navigation.commit(clientSettingsFlow, "singleplayer", "workspace", "paused", true);
            client.resize(900, 600, 1.0F);
            client.navigate(mediaProbeUrl(server, "settings"));
            awaitFrame(client, 900, 600, 1.0F);
            awaitAck(navigation, "in-game Client Settings");
            mediaProbe.awaitRouteCommit(navigation.revision(), "settings");
            captureSystemState(client, "client-settings", 900, 600, 1.0F);

            exerciseClientSettingsControls(client, provider, mediaProbe, navigation.revision());
            pass("CEF Client Settings controls persist module toggles and string settings through Java");

            provider.clearAction();
            pressKey(client, 0x1B);
            awaitAction(provider, "back", null);
            require(provider.lastRevision == navigation.revision(),
                    "nested Client Settings Escape did not preserve the committed render revision");
            pass("CEF nested Client Settings Escape follows authoritative canGoBack");

            provider.fps.setEnabled(true);
            require(provider.fps.applySetting("scale", "100"), "HUD fixture could not reset the earlier Settings scale edit");
            UiNavigationState hudRoot = new UiNavigationState(UiRoute.hudEditor(), UiNavigationState.EntryPoint.HOTKEY);
            navigation.commit(hudRoot, "singleplayer", "world", "editor", true);
            client.navigate(mediaProbeUrl(server, "hud_editor"));
            awaitAck(navigation, "RShift HUD root");
            mediaProbe.awaitRouteCommit(navigation.revision(), "hud_editor");
            long hudDeadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (provider.hudReport == null || provider.hudReport.revision != navigation.revision()) {
                require(System.currentTimeMillis() < hudDeadline, "CEF did not report native editor geometry");
                Thread.sleep(10L);
            }
            HudReport hudReport = provider.hudReport;
            require(hudReport.x == 0 && hudReport.y == 0 && hudReport.width == 900 && hudReport.height == 600
                            && hudReport.exclusions.size() == 3,
                    "Real CEF HUD canvas/interactive holes disagree with the actual game viewport");
            BufferedImage hudRaster = captureSystemState(client, "hud-editor", 900, 600, 1.0F);
            for (int[] point : new int[][] {{0,0}, {899,599}, {60,300}, {800,300}}) {
                require((hudRaster.getRGB(point[0], point[1]) >>> 24) == 0,
                        "HUD editor raster tints/blurs/replaces the game outside its chrome");
            }
            int[] centerChrome = hudReport.exclusions.get(0);
            provider.clearAction();
            // Bottom 46px of the published central chrome is the visible Mods
            // button. Use the CEF-published geometry, not a second layout model.
            click(client, centerChrome[0] + centerChrome[2] / 2,
                    centerChrome[1] + centerChrome[3] - 23, 0);
            awaitAction(provider, "navigate", "mods_catalog");
            require(provider.lastRevision == navigation.revision(), "HUD Mods click lost its committed revision");
            provider.clearAction();
            pressKey(client, 0x1B);
            awaitAction(provider, "close", null);
            require(provider.lastRevision == navigation.revision(), "Root HUD Escape ignored current native authority");
            pass("real CEF full-game HUD is transparent, publishes chrome exclusions, Mods clicks and root Escape work");

            provider.clearAction();
            // A player can still hold their sprint modifier when opening
            // RShift. The physical left button must activate Mods, not become
            // macOS Control-click's unsupported browser context menu. Exercise
            // the real game screen -> CEF path, not direct helper input alone.
            try (HudScreenInputFixture screenInput = new HudScreenInputFixture(
                    log, client, navigation, hudRoot, hudReport, 900, 600, 1.0F)) {
                screenInput.click(centerChrome[0] + centerChrome[2] / 2,
                        centerChrome[1] + centerChrome[3] - 23, false);
                awaitAction(provider, "navigate", "mods_catalog");
                pass("actual game screen routes an unmodified HUD Mods click through its CEF chrome exclusion");
                provider.clearAction();
                screenInput.click(centerChrome[0] + centerChrome[2] / 2,
                        centerChrome[1] + centerChrome[3] - 23, true);
                awaitAction(provider, "navigate", "mods_catalog");
            }
            pass("game-screen HUD Mods physical-primary contract works while the sprint Control key is held");

            clickControl(client,mediaProbe,"hud-widgets");
            clickControl(client,mediaProbe,"hud-select-fps");
            focusControl(client,mediaProbe,"hud-x");
            pressKey(client,38); Thread.sleep(25L); pressKey(client,13);
            awaitHudValue(provider,"x",13);
            focusControl(client,mediaProbe,"hud-scale");pressKey(client,38);
            awaitHudValue(provider,"scale",101);
            awaitHudDom(mediaProbe,13,101);
            clickControl(client,mediaProbe,"hud-undo");
            awaitHudValue(provider,"x",13);
            awaitHudValue(provider,"scale",100);
            awaitHudDom(mediaProbe,13,100);
            provider.fpsStore.reload();
            require(provider.fps.settings().offsetX()==13&&provider.fps.settings().scale()==100,"CEF inspector edits and Undo did not persist in native settings");
            captureSystemState(client,"hud-inspector",900,600,1.0F);
            pass("CEF HUD property fields, proportional scale and Undo operate the real native HUD/persistence owner");

            UiNavigationState pauseRoot = new UiNavigationState(
                    UiRoute.pauseMenu(), UiNavigationState.EntryPoint.PAUSE);
            navigation.commit(pauseRoot, "singleplayer", "opaque", "paused", true);
            client.navigate(mediaProbeUrl(server, "game_menu"));
            awaitAck(navigation, "authoritative root game_menu");
            mediaProbe.awaitRouteCommit(navigation.revision(), "game_menu");
            provider.clearAction();
            pressKey(client, 0x1B);
            awaitAction(provider, "close", null);
            require(provider.lastRevision == navigation.revision(),
                    "root game_menu Escape did not preserve the committed render revision");
            pass("CEF root game_menu Escape follows authoritative canCloseToGame");

            client.resize(427, 240, 2.0F);
            awaitFrame(client, 427, 240, 2.0F);

            // Ranked-style data pages intentionally contain no ambient
            // video. Exercise parking with Home media actually present;
            // waiting for a video event on Settings would test a removed
            // decoration rather than the helper visibility contract.
            int homeMediaCursor = mediaProbe.count();
            navigation.open("title");
            client.navigate(mediaProbeUrl(server, "title"));
            awaitAck(navigation, "title before park");
            mediaProbe.awaitRouteCommit(navigation.revision(), "title");
            mediaProbe.awaitState(true, false, homeMediaCursor);

            // Esc resumes gameplay while the helper remains parked. Reopening
            // game_menu twice must ACK each new Java revision, including the
            // second open whose CEF fragment is unchanged.
            int mediaCursor = mediaProbe.count();
            navigation.close();
            client.setSurfaceVisible(false);
            require(navigation.stateJson().contains("\"id\":\"none\""),
                    "closed host state must be none");
            require(!client.isSurfaceVisible(), "helper should park after close");
            mediaProbe.awaitState(false, true, mediaCursor);

            navigation.open("game_menu");
            client.navigate(mediaProbeUrl(server, "game_menu"));
            client.setSurfaceVisible(true);
            awaitFrame(client, 427, 240, 2.0F);
            awaitAck(navigation, "first game_menu open");
            mediaProbe.awaitRouteCommit(navigation.revision(), "game_menu");
            assertPauseTransparency(client);
            pass("first parked game_menu open ACKs with a transparent current CEF frame");

            assertPauseControlVisible(mediaProbe, "pause-heading");
            assertPauseControlVisible(mediaProbe, "pause-resume");
            assertPauseControlVisible(mediaProbe, "pause-hud");
            assertPauseControlVisible(mediaProbe, "pause-disconnect");
            focusControl(client, mediaProbe, "pause-hud");
            assertPauseControlVisible(mediaProbe, "pause-hud");
            focusControl(client, mediaProbe, "pause-disconnect");
            assertPauseControlVisible(mediaProbe, "pause-disconnect");
            captureSystemState(client, "pause-bottom");
            pass("compact Pause starts below its topbar and keyboard scrolling reveals its last controls");

            provider.leaveDelayMillis = 3200L;
            provider.afterLeave = new Runnable() {
                @Override public void run() { navigation.open("title"); }
            };
            com.google.gson.JsonArray disconnectRect=mediaProbe.control("pause-disconnect").getAsJsonArray("rect");
            int disconnectX=(int)(disconnectRect.get(0).getAsDouble()+disconnectRect.get(2).getAsDouble()/2);
            int disconnectY=(int)(disconnectRect.get(1).getAsDouble()+disconnectRect.get(3).getAsDouble()/2);
            clickControl(client, mediaProbe, "pause-disconnect");
            long leaveDeadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (provider.leaveCalls.get() == 0 && System.currentTimeMillis() < leaveDeadline) {
                Thread.sleep(10L);
            }
            require(provider.leaveCalls.get() == 1, "Pause Disconnect did not reach the bridge");
            Thread.sleep(2200L);
            String pendingPreview = System.getenv("OPUS_DISCONNECT_PENDING_PNG");
            if (pendingPreview != null && !pendingPreview.trim().isEmpty()) {
                // A preview reload destroys React's in-flight guard. Request
                // a fresh raster only; never navigate/reload the active test.
                BufferedImage pending = captureSystemState(client, "disconnect-pending");
                File output = new File(pendingPreview.trim());
                if (output.getParentFile() != null) output.getParentFile().mkdirs();
                ImageIO.write(pending, "png", output);
            }
            // Intentionally click the disabled in-flight button, rather than
            // waiting for an enabled control on the next (title) route.
            click(client, disconnectX, disconnectY, 0);
            Thread.sleep(300L);
            require(provider.leaveCalls.get() == 1,
                    "Repeated Disconnect clicks submitted overlapping world-unload requests");
            Thread.sleep(900L);
            awaitAck(navigation, "title after slow Disconnect");
            require(provider.leaveCalls.get() == 1,
                    "Slow Disconnect accepted a second unload after its title commit");
            provider.leaveDelayMillis = 0L;
            provider.afterLeave = null;
            pass("CEF Disconnect stays single-flight beyond two seconds and ACKs the eventual title commit");

            navigation.close();
            client.setSurfaceVisible(false);
            require(navigation.stateJson().contains("\"id\":\"none\""),
                    "second close must return to none");
            require(!client.isSurfaceVisible(), "pause helper should park after close");
            navigation.open("game_menu");
            // The URL is already #/game_menu: no hashchange can rescue a
            // missed uiNavigationChanged event on this reopen.
            client.navigate(mediaProbeUrl(server, "game_menu"));
            client.setSurfaceVisible(true);
            awaitFrame(client, 427, 240, 2.0F);
            awaitAck(navigation, "same-hash game_menu reopen");
            mediaProbe.awaitRouteCommit(navigation.revision(), "game_menu");
            assertPauseTransparency(client);
            pass("same-hash game_menu reopen ACKs a fresh transparent Java revision and frame");

            // A route ACK is a DOM commit, not permission to publish a cached
            // Home raster as the first frame of a reopened in-game overlay.
            // Repeating the transition exposes compositor timing races that
            // a single successful park/show cycle can miss.
            for (int cycle = 0; cycle < 20; cycle++) {
                navigation.open("title");
                client.navigate(mediaProbeUrl(server, "title"));
                awaitAck(navigation, "title before pause stress");
                mediaProbe.awaitRouteCommit(navigation.revision(), "title");
                captureSystemState(client, "title-before-pause-" + cycle);
                navigation.close();
                client.setSurfaceVisible(false);
                navigation.open("game_menu");
                client.navigate(mediaProbeUrl(server, "game_menu"));
                client.setSurfaceVisible(true);
                awaitAck(navigation, "pause stress " + cycle);
                mediaProbe.awaitRouteCommit(navigation.revision(), "game_menu");
                assertPauseTransparency(client, "pause-stress-" + cycle);
            }
            pass("20 Home/park/Pause transitions publish a transparent first committed CEF frame");

            UiNavigationState connectionFlow = new UiNavigationState(
                    UiRoute.connection(), UiNavigationState.EntryPoint.TITLE, UiRoute.multiplayer());
            navigation.commit(connectionFlow, "none", "opaque", "not_applicable", false,
                    new ConnectionUiState("connecting", "Connecting to server", "", "Local test", "127.0.0.1:25565", true));
            client.navigate(mediaProbeUrl(server, "connection"));
            awaitAck(navigation, "connection");
            mediaProbe.awaitRouteCommit(navigation.revision(), "connection");
            captureSystemState(client, "connecting");
            provider.clearAction();
            // Use the measured production control, including compact layouts.
            clickControl(client, mediaProbe, "connection-action");
            awaitAction(provider, "back", null);
            require(provider.lastRevision == navigation.revision(),
                    "Cancel did not submit the committed render revision");
            pass("CEF Cancel button reaches the native primary-action bridge at compact Retina geometry");
            provider.clearAction();
            client.sendKey(0x1B, true, 0);
            client.sendKey(0x1B, false, 0);
            awaitAction(provider, "back", null);
            require(provider.lastRevision == navigation.revision(),
                    "Escape did not use the connection's current committed render");
            pass("CEF connection Escape dispatches native Back/Cancel bridge action");

            long connectingRevision = navigation.revision();
            UiNavigationState terrainFlow = new UiNavigationState(
                    UiRoute.connection(), UiNavigationState.EntryPoint.TITLE);
            navigation.commit(terrainFlow, "multiplayer", "opaque", "live", false,
                    new ConnectionUiState("loading", "Downloading terrain", "", "Local test", "127.0.0.1:25565", false));
            awaitAck(navigation, "same-route terrain load");
            mediaProbe.awaitRouteCommit(navigation.revision(), "connection");
            require(navigation.revision() > connectingRevision, "terrain did not revoke old connection actions");
            captureSystemState(client, "loading");
            provider.clearAction();
            client.sendKey(0x1B, true, 0);
            client.sendKey(0x1B, false, 0);
            click(client, 360, 202, 0);
            Thread.sleep(300L);
            require(provider.lastAction == null, "CEF terrain loading fabricated Back/Cancel");
            pass("CEF terrain state commits without browser recreation or hash change");
            pass("CEF terrain loading exposes no clickable or Escape cancellation");

            navigation.commit(terrainFlow, "none", "opaque", "not_applicable", false,
                    new ConnectionUiState("loading", "Loading world", "", "Opus test world", "", false, "world"));
            awaitAck(navigation, "same-route singleplayer startup");
            mediaProbe.awaitRouteCommit(navigation.revision(), "connection");
            captureSystemState(client, "loading-world");
            provider.clearAction();
            client.sendKey(0x1B, true, 0);
            client.sendKey(0x1B, false, 0);
            Thread.sleep(300L);
            require(provider.lastAction == null, "CEF singleplayer startup invented Escape cancellation");
            pass("CEF singleplayer startup commits at the same helper lease without multiplayer stages or Escape cancellation");

            navigation.commit(connectionFlow, "none", "opaque", "not_applicable", false,
                    new ConnectionUiState("disconnected", "Disconnected by server",
                            "Kicked: Test message\nQuoted \"reason\" and <script>plain text</script>",
                            "Local test", "127.0.0.1:25565", true));
            awaitAck(navigation, "same-route disconnect");
            mediaProbe.awaitRouteCommit(navigation.revision(), "connection");
            captureSystemState(client, "disconnected");
            provider.clearAction();
            clickControl(client, mediaProbe, "connection-action");
            awaitAction(provider, "back", null);
            require(provider.lastRevision == navigation.revision(),
                    "Disconnect Back retained the old connecting render revision");
            pass("CEF Back button reaches the actual native return bridge after a kick");
            pass("CEF native kick reason transition ACKs at the same route and helper lease");

            client.resize(1280, 720, 1.0F);
            awaitFrame(client, 1280, 720, 1.0F);
            captureSystemState(client, "disconnected-wide", 1280, 720, 1.0F);
            pass("connection surface also publishes a current wide desktop CEF frame");

            long nativeSizeStart = System.nanoTime();
            client.resize(1470, 923, 2.0F);
            navigation.open("title");
            awaitAck(navigation, "native-size Home without a SPA reload");
            mediaProbe.awaitRouteCommit(navigation.revision(), "title");
            captureSystemState(client, "home-native-size", 1470, 923, 2.0F);
            long nativeSizeMillis = (System.nanoTime() - nativeSizeStart) / 1000000L;
            require(nativeSizeMillis < 8000L, "Native-size CEF frame exceeded the frame recovery boundary");
            pass("current Home frame at actual 1470x923 @2.0 Retina geometry without reload: " + nativeSizeMillis + " ms");
        } finally {
            if (client != null) client.stop();
            navigation.shutdown();
            server.stop();
            if (previousAccountCatalog == null) System.clearProperty("opus.accounts.file");
            else System.setProperty("opus.accounts.file", previousAccountCatalog);
            Files.deleteIfExists(accountCatalog);
            if (previousUtilitySettings == null) System.clearProperty("opus.utility.settings.file");
            else System.setProperty("opus.utility.settings.file", previousUtilitySettings);
            Files.deleteIfExists(utilitySettings);
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

    private static void capturePreview(OpusWebViewClient client, int w, int h, float scale,
            File output) throws Exception {
        long generation = client.reload();
        require(generation > 0L, "CEF reload for preview failed");
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        BufferedImage latest = null;
        while (System.currentTimeMillis() < deadline) {
            require(!client.isFailed(), "CEF helper entered failed state during preview");
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                try {
                    if (client.isCurrent(frame, w, h, scale)) {
                        latest = image(frame);
                        if (client.hasPublishedGeneration(generation)) {
                            Thread.sleep(250L);
                            OpusWebViewClient.Frame settled = client.consumeFrame();
                            if (settled != null) {
                                try {
                                    if (client.isCurrent(settled, w, h, scale)) latest = image(settled);
                                } finally {
                                    client.release(settled);
                                }
                            }
                            break;
                        }
                    }
                } finally {
                    client.release(frame);
                }
            }
            Thread.sleep(10L);
        }
        require(latest != null, "no CEF frame available for preview");
        File parent = output.getParentFile();
        if (parent != null) parent.mkdirs();
        ImageIO.write(latest, "png", output);
    }

    private static BufferedImage image(OpusWebViewClient.Frame frame) {
        BufferedImage image = new BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB);
        java.nio.ByteBuffer pixels = frame.pixels.duplicate();
        for (int y = 0; y < frame.height; y++) {
            for (int x = 0; x < frame.width; x++) {
                int offset = (y * frame.width + x) * 4;
                int blue = pixels.get(offset) & 0xFF;
                int green = pixels.get(offset + 1) & 0xFF;
                int red = pixels.get(offset + 2) & 0xFF;
                int alpha = pixels.get(offset + 3) & 0xFF;
                image.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }

    private static void assertPauseTransparency(OpusWebViewClient client) throws Exception {
        assertPauseTransparency(client, "pause");
    }

    private static void assertPauseTransparency(OpusWebViewClient client, String phase) throws Exception {
        BufferedImage frame = captureSystemState(client, phase);
        int[] corners = {
            frame.getRGB(0, 0), frame.getRGB(frame.getWidth() - 1, 0),
            frame.getRGB(0, frame.getHeight() - 1),
            frame.getRGB(frame.getWidth() - 1, frame.getHeight() - 1)
        };
        for (int pixel : corners) {
            if ((pixel >>> 24) >= 200) {
                diagnoseLaterPauseFrame(client, phase);
            }
            require((pixel >>> 24) < 200,
                    "pause corners must reveal the Minecraft scene, not an opaque ambient background: " + phase);
        }
    }

    // Diagnosis only: even if the subsequent raster is correct, the caller
    // still fails the first-frame contract above. Never wait-away a bad frame.
    private static void diagnoseLaterPauseFrame(OpusWebViewClient client, String phase) throws Exception {
        long started = System.currentTimeMillis();
        int observed = 0;
        while (System.currentTimeMillis() - started < 2000L) {
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                try {
                    if (client.isCurrent(frame, 427, 240, 2.0F)) {
                        observed++;
                        BufferedImage snapshot = image(frame);
                        if ((snapshot.getRGB(0, 0) >>> 24) < 200
                                && (snapshot.getRGB(snapshot.getWidth() - 1, snapshot.getHeight() - 1) >>> 24) < 200) {
                            String directory = System.getenv("OPUS_SYSTEM_PNG_DIR");
                            if (directory != null && !directory.trim().isEmpty()) {
                                File output = new File(directory, "connection-" + phase + "-later.png");
                                output.getParentFile().mkdirs();
                                ImageIO.write(snapshot, "png", output);
                            }
                            System.out.println("CEF DIAG later Pause raster after "
                                    + (System.currentTimeMillis() - started) + "ms / " + observed + " frames");
                            return;
                        }
                    }
                } finally { client.release(frame); }
            }
            Thread.sleep(10L);
        }
        System.out.println("CEF DIAG Pause remained opaque for 2000ms / " + observed + " frames");
    }

    private static BufferedImage captureSystemState(OpusWebViewClient client, String phase) throws Exception {
        return captureSystemState(client, phase, 427, 240, 2.0F);
    }

    private static void captureLocalAccountFrame(OpusWebViewClient client, MediaProbe probe, String phase, boolean open)
            throws Exception {
        // A local disclosure is not a host route commit. Observe Chromium's
        // ordinary paint without issuing VIEW or advancing frame ownership.
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        BufferedImage latest = null;
        int frameCount = 0;
        int currentFrameCount = 0;
        while (System.currentTimeMillis() < deadline) {
            require(!client.isFailed(), "CEF failed while painting the account popover");
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                frameCount++;
                try {
                    if (client.isCurrent(frame, 427, 240, 2.0F)) {
                        currentFrameCount++;
                        BufferedImage snapshot = image(frame);
                        latest = snapshot;
                        // Sample the panel's empty left padding, not its right
                        // scrollbar (which is #52525b on this compact layout).
                        double[] bounds=probe.accountBounds;
                        require(bounds!=null && bounds[3]>=50, "Account panel has no visible header geometry");
                        int x=(int)(bounds[0]+12)*2, y=(int)(bounds[1]+20)*2;
                        boolean panelPainted=(snapshot.getRGB(x,y)&0xFFFFFF)==0x1B1B1B;
                        if (panelPainted == open) {
                            String directory = System.getenv("OPUS_SYSTEM_PNG_DIR");
                            if (directory != null && !directory.trim().isEmpty()) {
                                File output = new File(directory, "connection-" + phase + ".png");
                                output.getParentFile().mkdirs();
                                ImageIO.write(snapshot, "png", output);
                            }
                            return;
                        }
                    }
                } finally { client.release(frame); }
            }
            Thread.sleep(10L);
        }
        String directory = System.getenv("OPUS_SYSTEM_PNG_DIR");
        if (latest != null && directory != null && !directory.trim().isEmpty()) {
            File output = new File(directory, "connection-" + phase + "-failure.png");
            output.getParentFile().mkdirs();
            ImageIO.write(latest, "png", output);
        }
        throw new IllegalStateException("CEF did not paint local account menu open=" + open
                + "; frames=" + frameCount + ", current=" + currentFrameCount
                + ", pixel=" + (latest == null ? "none" : Integer.toHexString(latest.getRGB(20 * 2, 80 * 2))));
    }

    private static BufferedImage captureSystemState(OpusWebViewClient client, String phase,
            int width, int height, float scale) throws Exception {
        long generation = client.requestCommittedFrame();
        require(generation > 0L, "system state could not request a fresh frame");
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            require(!client.isFailed(), "CEF failed while rendering system state");
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                try {
                    if (client.isCurrent(frame, width, height, scale) && client.hasPublishedGeneration(generation)) {
                        BufferedImage snapshot = image(frame);
                        String directory = System.getenv("OPUS_SYSTEM_PNG_DIR");
                        if (directory != null && !directory.trim().isEmpty()) {
                            File output = new File(directory, "connection-" + phase + ".png");
                            output.getParentFile().mkdirs();
                            ImageIO.write(snapshot, "png", output);
                        }
                        return snapshot;
                    }
                } finally { client.release(frame); }
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("system state did not publish a current CEF frame: " + phase);
    }

    private static void click(OpusWebViewClient client, int x, int y, int button) {
        client.sendMouseMove(x, y);
        client.sendMouseButton(x, y, button, true);
        client.sendMouseButton(x, y, button, false);
    }

    private static void exerciseClientSettingsControls(
            OpusWebViewClient client, StubBridgeProvider provider, MediaProbe probe, long revision)
            throws Exception {
        provider.clearModuleCalls();
        focusControl(client, probe, "module-fps"); pressKey(client, 13);
        focusControl(client, probe, "toggle-fps");
        int reads = provider.moduleReads.get(); pressKey(client, 32);
        awaitModuleRoundTrip(provider, provider.moduleToggleCalls, "fps=false", reads, "FPS switch");
        focusControl(client, probe, "fps-scale");
        reads = provider.moduleReads.get(); pressKey(client, 38);
        awaitModuleRoundTrip(provider, provider.moduleSettingCalls, "fps:scale=101", reads, "FPS scale");
        focusControl(client, probe, "fps-order");
        reads = provider.moduleReads.get(); pressKey(client, 32);
        awaitModuleRoundTrip(provider, provider.moduleSettingCalls, "fps:reverseOrder=0", reads, "FPS order");
        provider.fpsStore.reload();
        require(provider.fps.settings().scale() == 101 && !provider.fps.settings().appearance().reverseOrder(),
                "CEF changes did not survive native settings reload");
        focusControl(client, probe, "module-armor"); pressKey(client, 13);
        focusControl(client, probe, "toggle-armor");
        reads = provider.moduleReads.get(); pressKey(client, 32);
        awaitModuleRoundTrip(provider, provider.moduleToggleCalls, "armor-status=true", reads, "Armor switch");
        String armorAnchor = provider.armorAnchor;
        focusControl(client, probe, "armor-scale");
        reads = provider.moduleReads.get(); pressKey(client, 38); Thread.sleep(25L); pressKey(client, 13);
        awaitModuleRoundTrip(provider, provider.moduleSettingCalls, "armor-status:scale=101", reads, "Armor scale");
        focusControl(client, probe, "armor-durability");
        reads = provider.moduleReads.get(); pressKey(client, 32);
        awaitModuleRoundTrip(provider, provider.moduleSettingCalls, "armor-status:showDurability=0", reads, "Armor durability");
        require(armorAnchor.equals(provider.armorAnchor), "Appearance edits changed the saved HUD placement");
        focusControl(client, probe, "client-hud");
        provider.clearAction(); pressKey(client, 13);
        awaitAction(provider, "navigate", "hud_editor");
        require(provider.lastRevision == revision, "Edit HUD used a stale render revision");
        focusControl(client, probe, "client-close");
        provider.clearAction(); pressKey(client, 13); awaitAction(provider, "back", null);
        require(provider.lastRevision == revision, "Close Client settings used a stale render revision");
    }

    private static void awaitHudValue(StubBridgeProvider provider,String key,int expected) throws Exception {
        long deadline=System.currentTimeMillis()+TIMEOUT_MS;
        while(System.currentTimeMillis()<deadline){
            com.google.gson.JsonObject data=new com.google.gson.JsonParser().parse(provider.hudEditorStateJson()).getAsJsonObject();
            if(data.has("selected")&&!data.get("selected").isJsonNull()&&data.getAsJsonObject("selected").get(key).getAsInt()==expected)return;
            Thread.sleep(10L);
        }
        throw new AssertionError("CEF HUD edit did not reach the native owner: "+key+"="+expected);
    }

    private static void awaitHudDom(MediaProbe probe,int x,int scale) throws Exception {
        long deadline=System.currentTimeMillis()+TIMEOUT_MS;
        while(System.currentTimeMillis()<deadline){
            com.google.gson.JsonObject data=probe.geometry;
            if(data!=null&&data.has("hud")&&!data.get("hud").isJsonNull()){
                com.google.gson.JsonObject hud=data.getAsJsonObject("hud");
                com.google.gson.JsonObject control=data.getAsJsonObject("items").getAsJsonObject("hud-scale");
                if(hud.get("x").getAsInt()==x&&hud.get("scale").getAsInt()==scale&&control!=null&&control.get("enabled").getAsBoolean())return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("CEF inspector did not commit the native HUD geometry: x="+x+" scale="+scale);
    }

    private static void clickControl(OpusWebViewClient client, MediaProbe probe, String id) throws Exception {
        com.google.gson.JsonObject item = probe.control(id);
        com.google.gson.JsonArray rect = item.getAsJsonArray("rect");
        double x = rect.get(0).getAsDouble() + rect.get(2).getAsDouble()/2;
        double y = rect.get(1).getAsDouble() + rect.get(3).getAsDouble()/2;
        com.google.gson.JsonArray viewport = probe.geometry.getAsJsonArray("viewport");
        require(x >= 0 && y >= 0 && x < viewport.get(0).getAsInt() && y < viewport.get(1).getAsInt(),
                "Visible control is outside the CEF viewport: " + id + " rect=" + rect);
        click(client, (int)x, (int)y, 0);
    }

    private static void focusControl(OpusWebViewClient client, MediaProbe probe, String id) throws Exception {
        probe.control(id);
        for (int attempt=0; attempt<80; attempt++) {
            if (id.equals(probe.geometry.get("focused").getAsString())) return;
            pressTab(client, false); Thread.sleep(40L);
        }
        throw new AssertionError("Control is unreachable by keyboard: " + id);
    }

    private static void assertPauseControlVisible(MediaProbe probe, String id) throws Exception {
        com.google.gson.JsonArray rect = probe.control(id).getAsJsonArray("rect");
        com.google.gson.JsonArray header = probe.control("pause-topbar").getAsJsonArray("rect");
        com.google.gson.JsonArray viewport = probe.geometry.getAsJsonArray("viewport");
        double headerBottom = header.get(1).getAsDouble() + header.get(3).getAsDouble();
        require(rect.get(1).getAsDouble() >= headerBottom
                        && rect.get(1).getAsDouble() + rect.get(3).getAsDouble() <= viewport.get(1).getAsDouble()
                        && rect.get(0).getAsDouble() >= 0
                        && rect.get(0).getAsDouble() + rect.get(2).getAsDouble() <= viewport.get(0).getAsDouble(),
                "Pause control is clipped or hidden behind the topbar: " + id + " rect=" + rect);
    }

    private static int changeSettingWithArrow(
            OpusWebViewClient client,
            StubBridgeProvider provider,
            String expected,
            int arrowKey,
            int tabs,
            boolean commitOnEnter,
            String label) throws Exception {
        for (int tab = 0; tab < tabs; tab++) pressTab(client, false);
        int readsBefore = provider.moduleReads.get();
        pressKey(client, arrowKey);
        // The in-DOM radio group advances on one arrow; it does not rely on
        // platform select popups that are absent from the CEF PET_VIEW raster.
        // Number inputs save on Enter; radio groups publish on selection.
        // Do not activate an already-selected radio again: on a fast native
        // roundtrip that would start a second save before the next Tab.
        if (commitOnEnter) {
            // Let React retain the new controlled numeric value before commit.
            Thread.sleep(25L);
            pressKey(client, 0x0D);
        }
        awaitModuleRoundTrip(provider, provider.moduleSettingCalls, expected, readsBefore, label);
        return tabs;
    }

    private static int changeBooleanSetting(
            OpusWebViewClient client,
            StubBridgeProvider provider,
            String expected,
            int tabs,
            String label) throws Exception {
        for (int tab = 0; tab < tabs; tab++) pressTab(client, false);
        int readsBefore = provider.moduleReads.get();
        pressKey(client, 0x20);
        awaitModuleRoundTrip(provider, provider.moduleSettingCalls, expected, readsBefore, label);
        return tabs;
    }

    private static void pressTab(OpusWebViewClient client, boolean reverse) {
        client.sendKey(0x09, true, reverse ? 1 : 0);
        client.sendKey(0x09, false, reverse ? 1 : 0);
    }

    private static void pressKey(OpusWebViewClient client, int windowsVirtualKey) {
        client.sendKey(windowsVirtualKey, true, 0);
        client.sendKey(windowsVirtualKey, false, 0);
    }

    private static void awaitRecordedCall(List<String> calls, String expected, String label)
            throws Exception {
        require(awaitRecordedCall(calls, expected, TIMEOUT_MS),
                label + " did not reach the Java provider; calls=" + calls);
    }

    private static boolean awaitRecordedCall(
            List<String> calls, String expected, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (calls.contains(expected)) return true;
            Thread.sleep(10L);
        }
        return calls.contains(expected);
    }

    private static void awaitModuleRoundTrip(
            StubBridgeProvider provider,
            List<String> calls,
            String expected,
            int readsBefore,
            String label) throws Exception {
        awaitRecordedCall(calls, expected, label);
        awaitModuleRefresh(provider, readsBefore, label);
    }

    private static void awaitModuleRefresh(
            StubBridgeProvider provider, int readsBefore, String label) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (provider.moduleReads.get() > readsBefore) {
                // The fetch response has reached the SPA; allow its confirmed
                // values to commit before delivering the next native key.
                Thread.sleep(50L);
                return;
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException(label + " was not re-read from the Java module fixture");
    }

    private static void awaitCatalogSelection(Path catalog, String id) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            com.google.gson.JsonObject data = new com.google.gson.JsonParser()
                    .parse(new String(Files.readAllBytes(catalog), StandardCharsets.UTF_8)).getAsJsonObject();
            if (data.has("selectedAccountId") && id.equals(data.get("selectedAccountId").getAsString())) {
                require(data.getAsJsonArray("accounts").size() == 2,
                        "selection must preserve both literal, case-distinct saved accounts");
                return;
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("CEF did not persist the selected next-launch account");
    }

    private static void awaitAction(StubBridgeProvider provider, String expectedAction,
            String expectedRouteId) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (expectedAction.equals(provider.lastAction)
                    && (expectedRouteId == null || expectedRouteId.equals(provider.lastRouteId))) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("React surface did not dispatch " + expectedAction
                + (expectedRouteId == null ? "" : " for " + expectedRouteId));
    }

    private static void awaitAck(UiNavigationManager navigation, String route) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (navigation.isConfirmed()) return;
            Thread.sleep(10L);
        }
        throw new IllegalStateException("React host did not acknowledge " + route);
    }

    private static String mediaProbeUrl(OpusInteropServer server, String route) {
        return server.urlForRoute(route).replace("#/", "&opusMediaProbe=1#/");
    }

    /** Disposable Java input state only; no window, mouse, keyboard or clipboard control. */
    private static final class HudScreenInputFixture implements AutoCloseable {
        private final Object previousMinecraft;
        private final Object previousKeyboardCreated;
        private final ByteBuffer keys;
        private final byte[] previousKeys;
        private final OpusClientScreen screen;

        HudScreenInputFixture(Logger log, OpusWebViewClient view, UiNavigationManager navigation,
                UiNavigationState flow, HudReport report, int width, int height, float scale) throws Exception {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            previousMinecraft = field(net.minecraft.client.Minecraft.class, "theMinecraft").get(null);
            previousKeyboardCreated = field(org.lwjgl.input.Keyboard.class, "created").get(null);
            keys = ((ByteBuffer) field(org.lwjgl.input.Keyboard.class, "keyDownBuffer").get(null)).duplicate();
            keys.clear();
            previousKeys = new byte[keys.capacity()];
            for (int index = 0; index < previousKeys.length; index++) previousKeys[index] = keys.get(index);
            try {
            net.minecraft.client.Minecraft minecraft = (net.minecraft.client.Minecraft)
                    unsafe.allocateInstance(net.minecraft.client.Minecraft.class);
            field(net.minecraft.client.Minecraft.class, "theMinecraft").set(null, minecraft);
            minecraft.gameSettings = (net.minecraft.client.settings.GameSettings)
                    unsafe.allocateInstance(net.minecraft.client.settings.GameSettings.class);
            minecraft.displayWidth = Math.round(width * scale);
            minecraft.displayHeight = Math.round(height * scale);
            ClientOverlayController controller = new ClientOverlayController(log);
            field(ClientOverlayController.class, "uiNavigation").set(controller, navigation);
            field(ClientOverlayController.class, "embeddedWebView").set(controller, view);
            minecraft.theWorld = (net.minecraft.client.multiplayer.WorldClient)
                    unsafe.allocateInstance(net.minecraft.client.multiplayer.WorldClient.class);
            screen = new OpusClientScreen(controller, flow);
            minecraft.currentScreen = screen;
            field(net.minecraft.client.gui.GuiScreen.class, "mc").set(screen, minecraft);
            screen.width = width;
            screen.height = height;
            Method geometry = OpusClientScreen.class.getDeclaredMethod("refreshViewport",
                    int.class, int.class, float.class, boolean.class);
            geometry.setAccessible(true);
            geometry.invoke(screen, minecraft.displayWidth, minecraft.displayHeight, scale, true);
            field(OpusClientScreen.class, "expectedHostRevision").setLong(screen, navigation.revision());
            field(OpusClientScreen.class, "requestedFrameRevision").setLong(screen, navigation.revision());
            field(OpusClientScreen.class, "webView").set(screen, view);
            field(OpusClientScreen.class, "webLeaseId").setLong(screen, controller.claimEmbeddedWebView(view));
            Object surface = field(OpusClientScreen.class, "webSurface").get(screen);
            field(surface.getClass(), "textureId").setInt(surface, 1);
            field(surface.getClass(), "textureWidth").setInt(surface, minecraft.displayWidth);
            field(surface.getClass(), "textureHeight").setInt(surface, minecraft.displayHeight);
            Field webState = field(OpusClientScreen.class, "webState");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object ready = Enum.valueOf((Class) webState.getType(), "READY");
            webState.set(screen, ready);
            java.util.List<org.polydevs.opusmc.client.ui.UiBounds> chrome = new java.util.ArrayList<>();
            for (int[] rect : report.exclusions) chrome.add(new org.polydevs.opusmc.client.ui.UiBounds(rect[0], rect[1], rect[2], rect[3]));
            screen.acceptHudEditorCanvas(navigation.revision(),
                    new org.polydevs.opusmc.client.ui.UiBounds(report.x, report.y, report.width, report.height), chrome);
            field(org.lwjgl.input.Keyboard.class, "created").setBoolean(null, true);
            for (int index = 0; index < keys.capacity(); index++) keys.put(index, (byte) 0);
            } catch (Exception failure) {
                restoreGlobals();
                throw failure;
            } catch (Error failure) {
                restoreGlobals();
                throw failure;
            }
        }

        void click(int x, int y, boolean controlHeld) throws Exception {
            try {
                keys.put(org.lwjgl.input.Keyboard.KEY_LCONTROL, (byte) (controlHeld ? 1 : 0));
                screen.setPreviewPointer(x, y);
                screen.mouseClicked(x, y, 0);
                screen.mouseReleased(x, y, 0);
            } finally {
                screen.clearPreviewPointer();
                keys.put(org.lwjgl.input.Keyboard.KEY_LCONTROL, (byte) 0);
            }
        }

        @Override public void close() throws Exception {
            screen.clearPreviewPointer();
            restoreGlobals();
        }

        private void restoreGlobals() throws Exception {
            for (int index = 0; index < previousKeys.length; index++) keys.put(index, previousKeys[index]);
            field(org.lwjgl.input.Keyboard.class, "created").set(null, previousKeyboardCreated);
            field(net.minecraft.client.Minecraft.class, "theMinecraft").set(null, previousMinecraft);
        }

        private static Field field(Class<?> type, String name) throws Exception {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
    }

    static final class MediaProbe {
        private volatile com.google.gson.JsonObject geometry;
        private volatile double[] accountBounds;
        private final List<String> observations = new CopyOnWriteArrayList<String>();
        private final List<String> routeCommits = new CopyOnWriteArrayList<String>();
        private final List<String> accountStates = new CopyOnWriteArrayList<String>();
        private final List<String> accountReadyStates = new CopyOnWriteArrayList<String>();

        boolean focused(String id) {
            com.google.gson.JsonObject current = geometry;
            return current != null && current.has("focused")
                    && id.equals(current.get("focused").getAsString());
        }

        void record(String line) {
            if (line == null) return;
            int geometryMarker = line.indexOf("OPUS_CONTROL_GEOMETRY ");
            if (geometryMarker >= 0) {
                String json = line.substring(geometryMarker + "OPUS_CONTROL_GEOMETRY ".length());
                int source = json.indexOf(" (http");
                com.google.gson.JsonObject row = new com.google.gson.JsonParser().parse(source < 0 ? json : json.substring(0, source)).getAsJsonObject();
                if(row.has("reset") || geometry==null) geometry=row;
                else {
                    com.google.gson.JsonObject current=geometry;
                    current.add("focused",row.get("focused"));
                    for(java.util.Map.Entry<String,com.google.gson.JsonElement> entry:row.getAsJsonObject("items").entrySet())current.getAsJsonObject("items").add(entry.getKey(),entry.getValue());
                }
            }
            int domMarker=line.indexOf("OPUS_ACCOUNT_DOM ");
            if(domMarker>=0){
                String json=line.substring(domMarker+"OPUS_ACCOUNT_DOM ".length());
                int source=json.indexOf(" (http");
                com.google.gson.JsonObject data=new com.google.gson.JsonParser().parse(source<0?json:json.substring(0,source)).getAsJsonObject();
                if(data.has("bounds")&&!data.get("bounds").isJsonNull()){
                    com.google.gson.JsonArray bounds=data.getAsJsonArray("bounds");
                    accountBounds=new double[]{bounds.get(0).getAsDouble(),bounds.get(1).getAsDouble(),bounds.get(2).getAsDouble(),bounds.get(3).getAsDouble()};
                }
            }
            int accountMarker = line.indexOf("OPUS_ACCOUNT_MENU");
            if (accountMarker >= 0) accountStates.add(line.substring(accountMarker));
            int readyMarker = line.indexOf("OPUS_ACCOUNT_READY");
            if (readyMarker >= 0) accountReadyStates.add(line.substring(readyMarker));
            int marker = line.indexOf("OPUS_MEDIA_STATE");
            if (marker >= 0) {
                int source = line.indexOf(" (", marker);
                observations.add(line.substring(marker, source < 0 ? line.length() : source));
            }
            int routeMarker = line.indexOf("OPUS_ROUTE_COMMITTED");
            if (routeMarker >= 0) {
                int source = line.indexOf(" (", routeMarker);
                routeCommits.add(line.substring(routeMarker,
                        source < 0 ? line.length() : source));
            }
        }

        com.google.gson.JsonObject control(String id) throws Exception {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                com.google.gson.JsonObject current = geometry;
                if (current != null && current.getAsJsonObject("items").has(id)) {
                    com.google.gson.JsonObject item = current.getAsJsonObject("items").getAsJsonObject(id);
                    if (item.get("enabled").getAsBoolean()) return item;
                }
                Thread.sleep(10L);
            }
            throw new AssertionError("CEF did not publish an enabled control: " + id);
        }

        int count() { return observations.size(); }

        int accountCount() { return accountStates.size(); }

        void awaitAccountMenu(boolean open, int from) throws Exception {
            String expected = "OPUS_ACCOUNT_MENU open=" + open;
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                for (int i = from; i < accountStates.size(); i++) {
                    if (accountStates.get(i).contains(expected)) return;
                }
                Thread.sleep(10L);
            }
            throw new IllegalStateException("CEF account menu did not commit " + expected
                    + "; observed=" + accountStates);
        }

        int accountReadyCount() { return accountReadyStates.size(); }

        void awaitSelectableAccount(int from) throws Exception {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                for (int i = from; i < accountReadyStates.size(); i++) {
                    if (accountReadyStates.get(i).contains("ready=true selectable=1 focused=true")) return;
                }
                Thread.sleep(10L);
            }
            throw new IllegalStateException("CEF account catalog did not commit its selectable fixture row");
        }

        void awaitRouteCommit(long revision, String route) throws Exception {
            String expected = "OPUS_ROUTE_COMMITTED revision=" + revision + " route=" + route;
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                for (String observation : routeCommits) {
                    if (observation.contains(expected)) return;
                }
                Thread.sleep(10L);
            }
            throw new IllegalStateException("React did not commit route before ACK: " + expected
                    + "; observed=" + routeCommits);
        }

        void awaitState(boolean active, boolean paused, int from) throws Exception {
            String expected = "OPUS_MEDIA_STATE active=" + active + " paused=" + paused;
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                for (int i = from; i < observations.size(); i++) {
                    if (observations.get(i).contains(expected)) return;
                }
                Thread.sleep(10L);
            }
            throw new IllegalStateException("CEF media state did not become " + expected
                    + " after observation " + from + "; observed=" + observations);
        }
    }

    private static Logger logger(final MediaProbe mediaProbe) {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] {Logger.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (method.getName().startsWith("is")
                                && method.getReturnType() == boolean.class) return Boolean.FALSE;
                        if ("info".equals(method.getName()) && args != null) {
                            for (Object arg : args) {
                                Object[] values = arg instanceof Object[] ? (Object[]) arg
                                        : new Object[] { arg };
                                for (Object value : values) {
                                    String line = String.valueOf(value);
                                    mediaProbe.record(line);
                                    int marker = line.indexOf("OPUS_MEDIA_STATE");
                                    if (marker < 0) marker = line.indexOf("OPUS_ACCOUNT_");
                                    if (marker < 0) marker = line.indexOf("OPUS_ROUTE_COMMITTED");
                                    if (marker >= 0) {
                                        int source = line.indexOf(" (", marker);
                                        System.out.println("CEF DIAG " + line.substring(marker,
                                                source < 0 ? line.length() : source));
                                    }
                                }
                            }
                        }
                        if (("warn".equals(method.getName()) || "error".equals(method.getName()))
                                && args != null && args.length > 0) {
                            System.out.println(method.getName().toUpperCase(Locale.ROOT)
                                    + " " + String.valueOf(args[0]));
                            for (Object arg : args) {
                                if (arg instanceof Throwable) {
                                    ((Throwable) arg).printStackTrace(System.out);
                                }
                            }
                        }
                        return null;
                    }
                });
    }

    private static final class StubBridgeProvider implements OpusBridgeProvider {
        volatile HudReport hudReport;
        volatile String lastAction;
        volatile String lastRouteId;
        volatile long lastRevision = -1L;
        volatile boolean armorEnabled;
        volatile String activeName="zvwgvx";
        @Override public String[] currentAccount(){return new String[]{activeName,"unofficial"};}
        @Override public boolean switchAccount(String id,String username,String kind){activeName=username;return "offline".equals(kind);}
        final UtilitySettingsStore fpsStore;
        final FpsModule fps;
        final org.polydevs.opusmc.client.hud.HudManager hud;
        final UiNavigationManager navigation;
        final org.polydevs.opusmc.client.ui.UiRenderer drawing=(org.polydevs.opusmc.client.ui.UiRenderer) Proxy.newProxyInstance(
                org.polydevs.opusmc.client.ui.UiRenderer.class.getClassLoader(),new Class<?>[]{org.polydevs.opusmc.client.ui.UiRenderer.class},
                (proxy,method,args)->{
                    if("measureText".equals(method.getName()))return ((String)args[0]).length()*6;
                    if("lineHeight".equals(method.getName()))return 9;
                    return null;
                });
        StubBridgeProvider(Logger log,UiNavigationManager navigation) {
            fpsStore = UtilitySettingsStore.fromSystemProperty(log);
            fpsStore.reload();
            fps = new FpsModule(fpsStore);
            this.navigation=navigation;
            hud=new org.polydevs.opusmc.client.hud.HudManager(Arrays.asList(fps.widget()));
        }
        @Override public synchronized String hudEditorStateJson(){
            hud.renderEditor(null,drawing,new org.polydevs.opusmc.client.ui.UiBounds(0,0,900,600),new org.polydevs.opusmc.client.ui.UiInput(-1,-1,0));
            return hud.editorStateJson();
        }
        @Override public synchronized boolean updateHudEditor(long revision,String operation,String id,int x,int y){
            boolean result=revision==navigation.revision()&&navigation.isConfirmed()&&navigation.stateJson().contains("\"id\":\"hud_editor\"")&&hud.editorCommand(operation,id,x,y);
            System.out.println("CEF DIAG HUD command="+operation+" x="+x+" y="+y+" accepted="+result);
            return result;
        }
        volatile String armorScale = "100";
        volatile String armorOpacity = "100";
        volatile String armorAnchor = "top-right";
        volatile String armorDurability = "1";
        final java.util.concurrent.atomic.AtomicInteger moduleReads =
                new java.util.concurrent.atomic.AtomicInteger();
        final List<String> moduleToggleCalls = new CopyOnWriteArrayList<String>();
        final List<String> moduleSettingCalls = new CopyOnWriteArrayList<String>();
        final java.util.concurrent.atomic.AtomicInteger leaveCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        volatile long leaveDelayMillis;
        volatile Runnable afterLeave;
        void clearAction() {
            lastAction = null;
            lastRouteId = null;
            lastRevision = -1L;
        }
        void clearModuleCalls() {
            moduleToggleCalls.clear();
            moduleSettingCalls.clear();
        }
        @Override public boolean performUiAction(String action, String routeId, String moduleId,
                String settingsSection, long expectedRevision) {
            if("hud-undo".equals(action))hud.undo();
            if("hud-edit".equals(action))hud.setEditing(true);
            if("hud-preview".equals(action))hud.setEditing(false);
            lastRouteId = routeId;
            lastRevision = expectedRevision;
            lastAction = action;
            return true;
        }
        @Override public List<String[]> listWorlds() { return Collections.emptyList(); }
        @Override public void loadWorld(String fileName, String displayName) {}
        @Override public List<String[]> listServers() { return Collections.emptyList(); }
        @Override public void connectServer(String address) {}
        @Override public void addServer(String name, String address) {}
        @Override public List<String[]> gameOptions() { return Collections.emptyList(); }
        @Override public void adjustGameOption(String key, int delta) {}
        @Override public void setGameOptionFloat(String key, float value) {}
        @Override public void leaveWorld() {
            leaveCalls.incrementAndGet();
            try {
                Thread.sleep(leaveDelayMillis);
                Runnable completed = afterLeave;
                if (completed != null) completed.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        @Override public List<String[]> modules() {
            moduleReads.incrementAndGet();
            return Arrays.asList(
                    new String[] {"fps", fps.displayName(), String.valueOf(fps.isEnabled())},
                    new String[] {"armor-status", "Armor Status", String.valueOf(armorEnabled)});
        }
        @Override public void setModuleEnabled(String moduleId, boolean enabled) {
            if ("fps".equals(moduleId) && !fps.setEnabled(enabled)) throw new IllegalStateException("FPS save failed");
            if ("armor-status".equals(moduleId)) armorEnabled = enabled;
            moduleToggleCalls.add(moduleId + "=" + enabled);
        }
        @Override public List<String[]> moduleSettings(String moduleId) {
            if ("fps".equals(moduleId)) return fps.moduleSettings();
            if ("armor-status".equals(moduleId)) {
                return Arrays.asList(
                        new String[] {"enabled", "Enabled", "boolean", armorEnabled ? "1" : "0", "0", "1", "1", ""},
                        new String[] {"scale", "Scale", "integer", armorScale, "50", "150", "1", ""},
                        new String[] {"opacity", "Opacity", "integer", armorOpacity, "25", "100", "1", ""},
                        new String[] {"anchor", "Anchor", "enum", armorAnchor, "0", "0", "1",
                                "top-left,top-right,bottom-left,bottom-right"},
                        new String[] {"offsetX", "Offset X", "integer", "12", "0", "4096", "1", ""},
                        new String[] {"offsetY", "Offset Y", "integer", "12", "0", "4096", "1", ""},
                        new String[] {"showDurability", "Durability", "boolean", armorDurability, "0", "1", "1", ""});
            }
            return Collections.emptyList();
        }
        @Override public void setModuleSetting(String moduleId, String key, String value) {
            if ("fps".equals(moduleId)) {
                if (!fps.applySetting(key, value)) throw new IllegalArgumentException("Invalid FPS setting");
            } else if ("armor-status".equals(moduleId)) {
                if ("scale".equals(key)) armorScale = value;
                else if ("opacity".equals(key)) armorOpacity = value;
                else if ("anchor".equals(key)) armorAnchor = value;
                else if ("showDurability".equals(key)) armorDurability = value;
            }
            moduleSettingCalls.add(moduleId + ":" + key + "=" + value);
        }
        @Override public List<String[]> hudModules() { return Collections.emptyList(); }
        @Override public void moveHudModule(String moduleId, int x, int y) {}
        @Override public void updateHudEditorCanvas(long navigationRevision, int x, int y,
                int width, int height) {}
        @Override public void updateHudEditorCanvas(long revision, int x, int y, int width, int height, List<int[]> exclusions) {
            hudReport = new HudReport(revision, x, y, width, height, exclusions);
        }
    }

    private static final class HudReport {
        final long revision;
        final int x, y, width, height;
        final List<int[]> exclusions;
        HudReport(long revision, int x, int y, int width, int height, List<int[]> exclusions) {
            this.revision = revision; this.x = x; this.y = y; this.width = width; this.height = height;
            this.exclusions = exclusions;
        }
    }

    private static void pass(String message) { System.out.println("PASS " + message); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
