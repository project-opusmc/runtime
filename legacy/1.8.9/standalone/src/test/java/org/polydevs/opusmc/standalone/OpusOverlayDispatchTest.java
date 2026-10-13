package org.polydevs.opusmc.standalone;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the real overlay hook with SRG-remapped events and Notch-only game classes. */
public class OpusOverlayDispatchTest {
    @Test
    public void dispatchesGameplayHudTextAgainstObfuscatedMinecraft() throws Exception {
        Map<String, byte[]> classes = new HashMap<String, byte[]>();
        classes.put("ave", gameClass());
        classes.put("avr", resolutionClass());
        OpusUiRemappingTransformer transformer = new OpusUiRemappingTransformer();
        for (String suffix : new String[] {"", "$Text", "$ElementType"}) {
            String name = "net.minecraftforge.client.event.RenderGameOverlayEvent" + suffix;
            try (InputStream input = getClass().getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                assertNotNull(input);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                classes.put(name, transformer.transform(name, name, output.toByteArray()));
            }
        }
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (!classes.containsKey(name)) return super.loadClass(name, resolve);
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        byte[] bytes = classes.get(name);
                        type = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
        EventBus bus = new EventBus();
        HudListener listener = new HudListener();
        bus.register(listener);
        String[] names = {"clientModInitialized", "eventBus", "postMethod"};
        Object[] values = {Boolean.TRUE, bus, EventBus.class.getMethod("post", Event.class)};
        Object[] previous = new Object[names.length];
        Thread thread = Thread.currentThread();
        ClassLoader oldLoader = thread.getContextClassLoader();
        try {
            for (int index = 0; index < names.length; index++) {
                Field field = OpusEventHooks.class.getDeclaredField(names[index]);
                field.setAccessible(true);
                previous[index] = field.get(null);
                field.set(null, values[index]);
            }
            thread.setContextClassLoader(loader);
            OpusEventHooks.onRenderOverlay(0.5F);
            assertNotNull(listener.event, "Standalone overlay silently dropped the gameplay HUD event");
            Class<?> type = listener.event.getClass();
            assertEquals("net.minecraftforge.client.event.RenderGameOverlayEvent$Text", type.getName());
            assertEquals(0.5F, type.getField("partialTicks").getFloat(listener.event));
            assertEquals("TEXT", type.getField("type").get(listener.event).toString());
            assertEquals("avr", type.getField("resolution").get(listener.event).getClass().getName());
        } finally {
            thread.setContextClassLoader(oldLoader);
            for (int index = 0; index < names.length; index++) {
                Field field = OpusEventHooks.class.getDeclaredField(names[index]);
                field.setAccessible(true);
                field.set(null, previous[index]);
            }
        }
    }

    public static final class HudListener {
        Event event;
        @SubscribeEvent
        public void onHud(Event value) { event = value; }
    }

    private static byte[] gameClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "ave", null, "java/lang/Object", null);
        constructor(writer, "ave", "()V");
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "A", "()Lave;", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "ave");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "ave", "<init>", "()V", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(2, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] resolutionClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "avr", null, "java/lang/Object", null);
        constructor(writer, "avr", "(Lave;)V");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void constructor(ClassWriter writer, String owner, String descriptor) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", descriptor, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, "()V".equals(descriptor) ? 1 : 2);
        method.visitEnd();
    }
}
