package org.polydevs.opusmc.client;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import org.polydevs.opusmc.client.interop.ConnectionUiState;

/** Keeps Minecraft's system lifecycle running beneath its CEF presentation. */
final class NativeSystemScreenSession {
    private final GuiScreen delegate;
    private final Method primaryAction;
    private final GuiScreen returnParent;
    private ConnectionUiState state;
    private boolean actionConsumed;
    private boolean closed;
    private boolean authorizing;

    NativeSystemScreenSession(GuiScreen delegate, ConnectionUiState state) {
        this(delegate, state, null);
    }

    NativeSystemScreenSession(GuiScreen delegate, ConnectionUiState state, GuiScreen returnParent) {
        this.delegate = delegate;
        this.state = state;
        this.returnParent = returnParent;
        this.primaryAction = state.canCancel ? findPrimaryAction() : null;
    }

    static boolean supports(GuiScreen screen) {
        return screen instanceof GuiConnecting || screen instanceof GuiDisconnected
                || screen instanceof GuiDownloadTerrain;
    }

    static NativeSystemScreenSession create(GuiScreen screen, ServerData server) {
        String name = server == null ? "" : server.serverName;
        String address = server == null ? "" : server.serverIP;
        ConnectionUiState state;
        if (screen instanceof GuiDisconnected) {
            String title = ObfuscationReflectionHelper.getPrivateValue(
                    GuiDisconnected.class, (GuiDisconnected) screen, "reason", "field_146306_a");
            IChatComponent message = ObfuscationReflectionHelper.getPrivateValue(
                    GuiDisconnected.class, (GuiDisconnected) screen, "message", "field_146304_f");
            state = new ConnectionUiState("disconnected", clean(title),
                    message == null ? "" : clean(message.getUnformattedText()), name, address, true);
        } else if (screen instanceof GuiConnecting) {
            state = new ConnectionUiState("connecting", I18n.format("connect.connecting"),
                    "", name, address, true);
        } else if (screen instanceof GuiDownloadTerrain) {
            state = new ConnectionUiState("loading", I18n.format("multiplayer.downloadingTerrain"),
                    "", name, address, false);
        } else {
            throw new IllegalArgumentException("Not a supported Minecraft system screen");
        }
        return new NativeSystemScreenSession(screen, state, nativeReturnParent(screen));
    }

    static GuiScreen nativeReturnParent(GuiScreen screen) {
        if (screen instanceof GuiConnecting) {
            return ObfuscationReflectionHelper.getPrivateValue(GuiConnecting.class,
                    (GuiConnecting) screen, "previousGuiScreen", "field_146374_i");
        }
        if (screen instanceof GuiDisconnected) {
            return ObfuscationReflectionHelper.getPrivateValue(GuiDisconnected.class,
                    (GuiDisconnected) screen, "parentScreen", "field_146307_h");
        }
        return null;
    }

    ConnectionUiState state() { return state; }

    static GuiConnecting connectorForWorker(Thread worker) {
        if (worker == null) return null;
        // Vanilla GuiConnecting's anonymous Thread retains its exact outer
        // instance. Resolve by field type, not the obfuscated/synthetic name.
        for (Class<?> type = worker.getClass(); type != null && type != Thread.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && GuiConnecting.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        return (GuiConnecting) field.get(worker);
                    } catch (IllegalAccessException unavailable) {
                        return null;
                    } catch (RuntimeException unavailable) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    static boolean acceptsDeferred(GuiScreen expectedScreen, NativeSystemScreenSession expectedSession,
            GuiScreen currentScreen, NativeSystemScreenSession currentSession) {
        return acceptsDeferred(expectedScreen, expectedSession, currentScreen, currentSession, true, null);
    }

    static boolean acceptsDeferred(GuiScreen expectedScreen, NativeSystemScreenSession expectedSession,
            GuiScreen currentScreen, NativeSystemScreenSession currentSession,
            boolean idleRevisionUnchanged, GuiScreen failureConnector) {
        if (failureConnector != null) {
            // The worker may fail before its screen is installed, or after
            // Cancel/retry has reused the same CEF parent. Only its exact
            // native delegate is evidence of the failure's owner.
            return currentSession != null && currentSession.delegate == failureConnector
                    && "connecting".equals(currentSession.state.phase)
                    && !currentSession.closed && !currentSession.actionConsumed;
        }
        if (expectedSession != null) {
            return currentScreen == expectedScreen && currentSession == expectedSession
                    && !(expectedSession.delegate instanceof GuiConnecting)
                    && !expectedSession.closed && !expectedSession.actionConsumed;
        }
        if (currentSession != null) return false;
        return currentScreen == expectedScreen && idleRevisionUnchanged;
    }

    void initialize(Minecraft minecraft, int width, int height) {
        if (!closed) delegate.setWorldAndResolution(minecraft, width, height);
    }

    void tick() {
        if (closed) return;
        delegate.updateScreen();
        if (!closed && !authorizing && delegate instanceof GuiConnecting) {
            Object manager = ObfuscationReflectionHelper.getPrivateValue(
                    GuiConnecting.class, (GuiConnecting) delegate, "networkManager", "field_146371_g");
            if (manager != null) {
                authorizing = true;
                state = new ConnectionUiState("connecting", I18n.format("connect.authorizing"),
                        "", state.serverName, state.serverAddress, true);
            }
        }
    }

    boolean primaryAction() throws IOException {
        if (closed || actionConsumed || primaryAction == null) return false;
        actionConsumed = true;
        try {
            // Vanilla's button 0 owns both cancellation and the actual parent.
            primaryAction.invoke(delegate, new GuiButton(0, 0, 0, ""));
            return true;
        } catch (IllegalAccessException error) {
            actionConsumed = false;
            throw new IOException("Native system action is inaccessible", error);
        } catch (InvocationTargetException error) {
            actionConsumed = false;
            Throwable cause = error.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("Native system action failed", cause);
        }
    }

    void draw(int mouseX, int mouseY, float partialTicks) {
        if (!closed) delegate.drawScreen(mouseX, mouseY, partialTicks);
    }

    void mouseInput() throws IOException {
        if (!closed) delegate.handleMouseInput();
    }

    void close() {
        if (closed) return;
        closed = true;
        delegate.onGuiClosed();
    }

    private static String clean(String text) {
        return text == null ? "" : EnumChatFormatting.getTextWithoutFormattingCodes(text);
    }

    private static Method findPrimaryAction() {
        for (String name : new String[] {"actionPerformed", "func_146284_a", "a"}) {
            try {
                Method method = GuiScreen.class.getDeclaredMethod(name, GuiButton.class);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new IllegalStateException("Minecraft system button action could not be resolved");
    }
}
