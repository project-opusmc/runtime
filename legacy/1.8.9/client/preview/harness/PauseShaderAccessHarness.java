package org.polydevs.opusmc.client;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

/** Executes the real shader host with vanilla's protected GuiScreen.mc access. */
public final class PauseShaderAccessHarness {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = new ClassLoader(PauseShaderAccessHarness.class.getClassLoader()) {
            @Override
            protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                // Keep game types in one loader so Minecraft.currentScreen
                // and GuiScreen retain the same type identity.
                if (!name.startsWith("net.minecraft.")
                        && !name.startsWith("net.minecraftforge.")
                        && !name.startsWith("org.polydevs.opusmc.")) {
                    return super.loadClass(name, resolve);
                }
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (InputStream input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
                        byte[] bytecode = bytes.toByteArray();
                        if (name.equals("net.minecraft.client.gui.GuiScreen")) {
                            ClassWriter writer = new ClassWriter(0);
                            new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM5, writer) {
                                @Override
                                public FieldVisitor visitField(int access, String field, String descriptor,
                                                               String signature, Object value) {
                                    if (field.equals("mc")) {
                                        access = (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE)) | Opcodes.ACC_PROTECTED;
                                    }
                                    return super.visitField(access, field, descriptor, signature, value);
                                }
                            }, 0);
                            bytecode = writer.toByteArray();
                        }
                        loaded = defineClass(name, bytecode, 0, bytecode.length);
                    } catch (java.io.IOException error) {
                        throw new ClassNotFoundException(name, error);
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        };
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Class<?> screenType = loader.loadClass("org.polydevs.opusmc.client.OpusClientScreen");
        Object screen = unsafe.allocateInstance(screenType);
        Class<?> hostType = loader.loadClass("org.polydevs.opusmc.client.OpusClientScreen$1");
        Constructor<?> constructor = hostType.getDeclaredConstructor(screenType);
        constructor.setAccessible(true);
        Object host = constructor.newInstance(screen);
        require(invoke(host, "currentShader") == null, "Uninitialized pause host must have no shader");
        require(invoke(host, "loadPauseShader") == null, "Uninitialized pause host must skip loading");
        Class<?> minecraftType = loader.loadClass("net.minecraft.client.Minecraft");
        Object minecraft = unsafe.allocateInstance(minecraftType);
        Field renderer = minecraftType.getDeclaredField("entityRenderer");
        renderer.setAccessible(true);
        renderer.set(minecraft, unsafe.allocateInstance(renderer.getType()));
        Field mc = screenType.getSuperclass().getDeclaredField("mc");
        mc.setAccessible(true);
        mc.set(screen, minecraft);
        require(invoke(host, "currentShader") == null, "Pause shader inspection must respect vanilla access");
        Field supported = loader.loadClass("net.minecraft.client.renderer.OpenGlHelper").getDeclaredField("shadersSupported");
        supported.setAccessible(true);
        boolean shadersSupported = supported.getBoolean(null);
        try {
            supported.setBoolean(null, false);
            require(invoke(host, "loadPauseShader") == null, "Unsupported shaders must safely skip blur");
            invoke(host, "stopShader");
        } finally {
            supported.setBoolean(null, shadersSupported);
        }
        System.out.println("PASS: real pause shader host reads, loads and releases with protected GuiScreen.mc");
    }

    private static Object invoke(Object host, String name) throws Exception {
        Method method = host.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(host);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
