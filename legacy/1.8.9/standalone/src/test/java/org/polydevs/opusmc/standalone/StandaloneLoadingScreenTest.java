package org.polydevs.opusmc.standalone;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

final class StandaloneLoadingScreenTest {
    private static final String SCREEN = "org/polydevs/opusmc/client/OpusClientScreen";

    @Test
    void retainedCefLoadingRunsAfterVanillaShutdownAndThrottleGuards() throws Exception {
        byte[] bytes = pinnedLoadingScreen();
        StandaloneEventHookTransformer transformer=new StandaloneEventHookTransformer();
        byte[] patched=transformer.transform("avi",bytes);
        ClassNode type=new ClassNode();new ClassReader(patched).accept(type,0);
        MethodNode progress=null;
        for(Object value:type.methods){MethodNode method=(MethodNode)value;if("a".equals(method.name)&&"(I)V".equals(method.desc))progress=method;}
        assertNotNull(progress);
        int position=0,shutdown=-1,throttle=-1,hook=-1,resolution=-1,hooks=0;
        for(AbstractInsnNode node=progress.instructions.getFirst();node!=null;node=node.getNext(),position++){
            if(node.getOpcode()==Opcodes.ATHROW)shutdown=position;
            if(node instanceof LdcInsnNode&&Long.valueOf(100).equals(((LdcInsnNode)node).cst))throttle=position;
            if(node instanceof MethodInsnNode&&"onLoadingScreen".equals(((MethodInsnNode)node).name)){
                hook=position;hooks++;
                assertEquals("(Ljava/lang/Object;)Z",((MethodInsnNode)node).desc);
                assertEquals(Opcodes.IFEQ,node.getNext().getOpcode());
                assertEquals(Opcodes.RETURN,node.getNext().getNext().getOpcode());
            }
            if(node instanceof TypeInsnNode&&node.getOpcode()==Opcodes.NEW&&"avr".equals(((TypeInsnNode)node).desc))resolution=position;
        }
        assertEquals(1,hooks,"World changes still draw vanilla loading over a current Opus surface");
        assertTrue(shutdown>=0&&throttle>shutdown&&hook>throttle&&resolution>hook,
                "Loading presentation must preserve Minecraft's shutdown/throttle guards and native fallback");
        assertArrayEquals(patched,transformer.transform("avi",patched),"Loading presentation hook must be idempotent");
    }

    @Test
    void jvmExecutesPinnedProgressMethodWithShutdownThrottleAndNativeFallback() throws Exception {
        byte[] patched = new StandaloneEventHookTransformer().transform("avi", pinnedLoadingScreen());
        ClassNode type = new ClassNode();
        new ClassReader(patched).accept(type, 0);
        // Execute the actual patched progress method, including its stack maps,
        // in an isolated container. Renderer dependencies deliberately stop at
        // the first native drawing operation; this fixture performs no GL work.
        type.interfaces.clear();
        for (Iterator<?> methods = type.methods.iterator(); methods.hasNext();) {
            MethodNode method = (MethodNode) methods.next();
            if (!"a".equals(method.name) || !"(I)V".equals(method.desc)) methods.remove();
        }
        ClassWriter container = new ClassWriter(0);
        type.accept(container);
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("avi", container.toByteArray());
        classes.put("axu", simpleClass("axu", "java/lang/Object"));
        classes.put("avk", simpleClass("avk", "java/lang/Error"));
        for (String name : new String[]{"bfw", "bfl", "bqs", "bfx", "bfd", "bmj", "avp", "jy", "bms", "bmu", "avn"}) {
            classes.put(name, simpleClass(name, "java/lang/Object"));
        }
        classes.put("ave", minecraftFixture());
        classes.put("avr", nativeDrawingSentinel());
        classes.put(SCREEN.replace('/', '.'), screenFixture());
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> loadingType = loader.loadClass("avi");
        Class<?> mcType = loader.loadClass("ave");
        Class<?> screenType = loader.loadClass(SCREEN.replace('/', '.'));
        Object mc = mcType.getConstructor().newInstance();
        Object screen = screenType.getConstructor().newInstance();
        // Avoid constructing Minecraft's real framebuffer. The progress method
        // and all of its original guard instructions remain unchanged.
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object loading = unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(unsafeField.get(null), loadingType);
        setField(loadingType, loading, "b", mc);
        mcType.getField("B").setBoolean(mc, true);
        mcType.getField("m").set(mc, screen);
        screenType.getField("ready").setBoolean(screen, true);
        mcType.getField("time").setLong(null, 1000L);
        Method progress = loadingType.getMethod("a", int.class);
        progress.invoke(loading, -1);
        assertEquals(1, screenType.getField("presentations").getInt(screen));

        mcType.getField("time").setLong(null, 1050L);
        progress.invoke(loading, -1);
        assertEquals(1, screenType.getField("presentations").getInt(screen), "Native 100ms throttle must run first");

        mcType.getField("B").setBoolean(mc, false);
        InvocationTargetException stopped = assertThrows(InvocationTargetException.class, () -> progress.invoke(loading, -1));
        assertEquals("avk", stopped.getCause().getClass().getName(), "Minecraft shutdown must still raise its own error");
        setField(loadingType, loading, "e", true);
        progress.invoke(loading, -1);
        assertEquals(1, screenType.getField("presentations").getInt(screen), "Successful shutdown must not draw again");

        mcType.getField("B").setBoolean(mc, true);
        mcType.getField("time").setLong(null, 1200L);
        screenType.getField("ready").setBoolean(screen, false);
        assertNativeDrawing(progress, loading);
        assertEquals(2, screenType.getField("presentations").getInt(screen));

        mcType.getField("time").setLong(null, 1400L);
        mcType.getField("m").set(mc, loader.loadClass("axu").getConstructor().newInstance());
        assertNativeDrawing(progress, loading);
        assertEquals(2, screenType.getField("presentations").getInt(screen), "Native gameplay GUI must not be replaced");
        assertFalse(OpusEventHooks.onLoadingScreen(null));
        assertFalse(OpusEventHooks.onLoadingScreen(new Object()));
    }

    private static void assertNativeDrawing(Method progress, Object loading) {
        InvocationTargetException result = assertThrows(InvocationTargetException.class, () -> progress.invoke(loading, -1));
        assertEquals("native-loading-fallback", result.getCause().getMessage());
    }

    private static void setField(Class<?> owner, Object instance, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static byte[] pinnedLoadingScreen() throws Exception {
        File jar = new File(System.getProperty("user.home"), "Library/Application Support/minecraft/versions/1.8.9/1.8.9.jar");
        assertTrue(jar.isFile(), "This acceptance test requires the installed pinned Minecraft 1.8.9 bytecode");
        try (ZipFile zip = new ZipFile(jar);
                InputStream input = zip.getInputStream(zip.getEntry("avi.class"));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private static ClassWriter classWriter(String name, String parent) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, name, null, parent, null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        return writer;
    }

    private static byte[] simpleClass(String name, String parent) {
        ClassWriter writer = classWriter(name, parent);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] minecraftFixture() {
        ClassWriter writer = classWriter("ave", "java/lang/Object");
        writer.visitField(Opcodes.ACC_PUBLIC, "B", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "m", "Laxu;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "time", "J", null, null).visitEnd();
        MethodVisitor clock = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "J", "()J", null, null);
        clock.visitCode();
        clock.visitFieldInsn(Opcodes.GETSTATIC, "ave", "time", "J");
        clock.visitInsn(Opcodes.LRETURN);
        clock.visitMaxs(0, 0);
        clock.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] screenFixture() {
        ClassWriter writer = classWriter(SCREEN, "axu");
        writer.visitField(Opcodes.ACC_PUBLIC, "ready", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "presentations", "I", null, null).visitEnd();
        MethodVisitor render = writer.visitMethod(Opcodes.ACC_PUBLIC, "renderRetainedLoadingSurface", "()Z", null, null);
        render.visitCode();
        render.visitVarInsn(Opcodes.ALOAD, 0);
        render.visitInsn(Opcodes.DUP);
        render.visitFieldInsn(Opcodes.GETFIELD, SCREEN, "presentations", "I");
        render.visitInsn(Opcodes.ICONST_1);
        render.visitInsn(Opcodes.IADD);
        render.visitFieldInsn(Opcodes.PUTFIELD, SCREEN, "presentations", "I");
        render.visitVarInsn(Opcodes.ALOAD, 0);
        render.visitFieldInsn(Opcodes.GETFIELD, SCREEN, "ready", "Z");
        render.visitInsn(Opcodes.IRETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] nativeDrawingSentinel() {
        ClassWriter writer = classWriter("avr", "java/lang/Object");
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Lave;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitLdcInsn("native-loading-fallback");
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
        constructor.visitInsn(Opcodes.ATHROW);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
