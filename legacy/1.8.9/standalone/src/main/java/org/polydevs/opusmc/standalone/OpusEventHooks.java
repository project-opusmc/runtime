package org.polydevs.opusmc.standalone;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Static event dispatch hooks invoked by ASM transformers in standalone mode.
 */
public final class OpusEventHooks {
    private OpusEventHooks() {
    }

    private static volatile boolean clientModInitialized = false;

    public static synchronized void onGameStarted() {
        ensureClientModInitialized();
    }

    public static synchronized void ensureClientModInitialized() {
        if (clientModInitialized) {
            return;
        }
        clientModInitialized = true;
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Class<?> modClass = Class.forName("org.polydevs.opusmc.client.OpusClientMod", true, cl);
            Object mod = modClass.newInstance();

            try {
                Method standaloneInit = modClass.getMethod("initializeStandalone");
                standaloneInit.invoke(mod);
                System.out.println("[OPUS/STANDALONE] OpusClientMod initialized via initializeStandalone!");
                return;
            } catch (NoSuchMethodException ignored) {
            }

            Class<?> preInitEventClass = Class.forName("net.minecraftforge.fml.common.event.FMLPreInitializationEvent", true, cl);
            Constructor<?> preCtor = preInitEventClass.getConstructors()[0];
            Object preInitEvent = preCtor.newInstance((Object) new Object[0]);
            Method onPreInit = modClass.getMethod("onPreInitialization", preInitEventClass);
            onPreInit.invoke(mod, preInitEvent);

            Class<?> initEventClass = Class.forName("net.minecraftforge.fml.common.event.FMLInitializationEvent", true, cl);
            Constructor<?> initCtor = initEventClass.getConstructors()[0];
            Object initEvent = initCtor.newInstance((Object) new Object[0]);
            Method onInit = modClass.getMethod("onInitialization", initEventClass);
            onInit.invoke(mod, initEvent);

            System.out.println("[OPUS/STANDALONE] OpusClientMod initialized successfully on game startup!");
        } catch (Throwable failure) {
            System.err.println("[OPUS/STANDALONE] Failed to initialize OpusClientMod: " + failure);
            failure.printStackTrace();
        }
    }

    public static void onClientTickStart() {
        ensureClientModInitialized();
        postTickEvent("ClientTickEvent", "START", null);
    }

    public static void onClientTickEnd() {
        postTickEvent("ClientTickEvent", "END", null);
    }

    public static void onRenderTickStart(float partialTicks) {
        postTickEvent("RenderTickEvent", "START", partialTicks);
    }

    public static void onRenderTickEnd(float partialTicks) {
        postTickEvent("RenderTickEvent", "END", partialTicks);
    }

    private static volatile Object phaseStart;
    private static volatile Object phaseEnd;
    private static volatile Constructor<?> clientTickConstructor;
    private static volatile Constructor<?> renderTickConstructor;
    private static volatile Object eventBus;
    private static volatile Method postMethod;

    private static void initTickCache(ClassLoader cl) throws Exception {
        if (eventBus == null) {
            Class<?> forgeClass = Class.forName("net.minecraftforge.common.MinecraftForge", true, cl);
            eventBus = forgeClass.getField("EVENT_BUS").get(null);
            Class<?> baseEventClass = Class.forName("net.minecraftforge.fml.common.eventhandler.Event", true, cl);
            postMethod = eventBus.getClass().getMethod("post", baseEventClass);

            Class<?> phaseEnum = Class.forName("net.minecraftforge.fml.common.gameevent.TickEvent$Phase", true, cl);
            for (Object constant : phaseEnum.getEnumConstants()) {
                if ("START".equals(((Enum<?>) constant).name())) {
                    phaseStart = constant;
                } else if ("END".equals(((Enum<?>) constant).name())) {
                    phaseEnd = constant;
                }
            }

            Class<?> clientTickClass = Class.forName("net.minecraftforge.fml.common.gameevent.TickEvent$ClientTickEvent", true, cl);
            clientTickConstructor = clientTickClass.getConstructor(phaseEnum);

            Class<?> renderTickClass = Class.forName("net.minecraftforge.fml.common.gameevent.TickEvent$RenderTickEvent", true, cl);
            renderTickConstructor = renderTickClass.getConstructor(phaseEnum, float.class);
        }
    }

    private static void postTickEvent(String className, String phaseName, Float partialTicks) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (eventBus == null) {
                initTickCache(cl);
            }
            Object phase = "START".equals(phaseName) ? phaseStart : phaseEnd;
            Object event;
            if (partialTicks != null && renderTickConstructor != null) {
                event = renderTickConstructor.newInstance(phase, partialTicks);
            } else if (clientTickConstructor != null) {
                event = clientTickConstructor.newInstance(phase);
            } else {
                return;
            }
            if (postMethod != null && eventBus != null) {
                postMethod.invoke(eventBus, event);
            }
        } catch (Throwable t) {
            // Suppress repeating tick errors
        }
    }

    public static void onRenderOverlay(float partialTicks) {
        ensureClientModInitialized();
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (eventBus == null) {
                initTickCache(cl);
            }
            Class<?> aveClass = Class.forName("ave", true, cl);
            Object mc = aveClass.getMethod("A").invoke(null);
            Class<?> resClass = Class.forName("avr", true, cl);
            Object resolution = resClass.getConstructor(aveClass).newInstance(mc);

            Class<?> parentEventClass = Class.forName("net.minecraftforge.client.event.RenderGameOverlayEvent", true, cl);
            Object parentEvent = parentEventClass.getConstructor(float.class, resClass).newInstance(partialTicks, resolution);

            Class<?> textEventClass = Class.forName("net.minecraftforge.client.event.RenderGameOverlayEvent$Text", true, cl);
            Object event = textEventClass.getConstructor(parentEventClass, ArrayList.class, ArrayList.class)
                    .newInstance(parentEvent, new ArrayList<String>(), new ArrayList<String>());
            if (postMethod != null && eventBus != null) {
                postMethod.invoke(eventBus, event);
            }
        } catch (Throwable ignored) {
        }
    }

    public static final Object GUI_CANCELLED = new Object();

    public static Object onGuiOpen(Object gui) {
        ensureClientModInitialized();
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Class<?> eventClass = Class.forName("net.minecraftforge.client.event.GuiOpenEvent", true, cl);
            Constructor<?> ctor = null;
            for (Constructor<?> c : eventClass.getConstructors()) {
                if (c.getParameterTypes().length == 1) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) {
                ctor = eventClass.getConstructors()[0];
            }
            Object event = ctor.newInstance(gui);
            boolean canceled = postEvent(event, cl);
            if (canceled) {
                System.out.println("[OPUS/STANDALONE] onGuiOpen: " + (gui == null ? "null" : gui.getClass().getSimpleName()) + " -> CANCELLED");
                return GUI_CANCELLED;
            }
            Field guiField = eventClass.getField("gui");
            Object result = guiField.get(event);
            System.out.println("[OPUS/STANDALONE] onGuiOpen: " + (gui == null ? "null" : gui.getClass().getSimpleName())
                    + " -> " + (result == null ? "null" : result.getClass().getSimpleName()));
            return result;
        } catch (Throwable e) {
            System.err.println("[OPUS/STANDALONE] onGuiOpen error: " + e);
            e.printStackTrace();
            return gui;
        }
    }

    private static boolean postEvent(Object event, ClassLoader cl) throws Exception {
        if (eventBus == null) {
            initTickCache(cl);
        }
        if (postMethod != null && eventBus != null) {
            Object result = postMethod.invoke(eventBus, event);
            if (result instanceof Boolean) {
                return ((Boolean) result).booleanValue();
            }
        }
        return false;
    }
}
