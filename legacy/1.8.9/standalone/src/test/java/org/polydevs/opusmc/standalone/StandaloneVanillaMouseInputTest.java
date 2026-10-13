package org.polydevs.opusmc.standalone;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Executes the actual vanilla mouse-coordinate prelude; only native devices are isolated. */
public final class StandaloneVanillaMouseInputTest {
    @Test
    void actualVanillaGuiClickHitsTheHoveredChestSlotOnRetina() throws Exception {
        String configured = System.getenv("OPUS_TEST_MINECRAFT_JAR");
        Path jar = configured == null
                ? Paths.get(System.getProperty("user.home"), ".opus-launcher/minecraft/versions/1.8.9/1.8.9.jar")
                : Paths.get(configured);
        if (configured == null) Assumptions.assumeTrue(Files.isRegularFile(jar), "Vanilla 1.8.9 JAR unavailable");
        byte[] vanilla;
        try (ZipFile archive = new ZipFile(jar.toFile()); InputStream input = archive.getInputStream(archive.getEntry("axu.class"))) {
            vanilla = read(input);
        }
        byte[] patched = new OpusStandaloneClassTransformer().transform("axu", "axu", vanilla);
        Map<String, byte[]> classes = new HashMap<String, byte[]>();
        classes.put("axu", coordinatePrelude(patched));
        classes.put("ave", minecraftDimensions());
        classes.put("org.lwjgl.input.Mouse", device("org/lwjgl/input/Mouse",
                new String[][] {{"getEventX", "eventX", "()I"}, {"getEventY", "eventY", "()I"}}));
        classes.put("org.lwjgl.opengl.Display", device("org/lwjgl/opengl/Display",
                new String[][] {{"getPixelScaleFactor", "scale", "()F"}}));
        classes.put("org.lwjgl.LWJGLUtil", platform());
        try (InputStream input = getClass().getResourceAsStream("/org/polydevs/opusmc/forge/RetinaDisplay.class")) {
            classes.put("org.polydevs.opusmc.forge.RetinaDisplay", read(input));
        }
        ClassLoader loader = new DeviceLoader(classes);
        Class<?> screenType = loader.loadClass("axu");
        Class<?> minecraftType = loader.loadClass("ave");
        Object screen = screenType.getConstructor().newInstance();
        Object minecraft = minecraftType.getConstructor().newInstance();
        screenType.getField("j").set(screen, minecraft);
        int cases = 0;
        for (int[] window : new int[][] {{740, 446}, {960, 540}, {1050, 605}}) {
            for (int deviceScale : new int[] {1, 2}) {
                for (int guiScale : new int[] {0, 1, 2, 3, 4}) {
                    int width = window[0] * deviceScale;
                    int height = window[1] * deviceScale;
                    int factor = 1;
                    while (factor < (guiScale == 0 ? 1000 : guiScale)
                            && width / (factor + 1) >= 320 && height / (factor + 1) >= 240) factor++;
                    int guiWidth = (int) Math.ceil(width / (double) factor);
                    int guiHeight = (int) Math.ceil(height / (double) factor);
                    minecraftType.getField("d").setInt(minecraft, width);
                    minecraftType.getField("e").setInt(minecraft, height);
                    screenType.getField("l").setInt(screen, guiWidth);
                    screenType.getField("m").setInt(screen, guiHeight);
                    int left = (guiWidth - 176) / 2;
                    int top = (guiHeight - 168) / 2;
                    // Bedwars icon: second chest row, sixth column (slot 14).
                    int slotX = left + 8 + 5 * 18;
                    int slotY = top + 18 + 18;
                    Input.pixelScale = deviceScale;
                    Input.x = (int) Math.round((slotX + 9) * (double) width / guiWidth / deviceScale);
                    Input.y = (int) Math.round((guiHeight - slotY - 9 - 1) * (double) height / guiHeight / deviceScale);
                    int[] point = (int[]) screenType.getMethod("coordinates").invoke(screen);
                    assertTrue(point[0] >= slotX && point[0] < slotX + 18
                                    && point[1] >= slotY && point[1] < slotY + 18,
                            "Vanilla click missed hovered Bedwars slot: " + window[0] + "x" + window[1]
                                    + " device=" + deviceScale + " GUI=" + guiScale + " expected inside "
                                    + slotX + "," + slotY + " received " + point[0] + "," + point[1]);
                    cases++;
                }
            }
        }
        assertEquals(30, cases);
        System.out.println("PASS: real vanilla GuiScreen mouse prelude hits hovered chest slot in 30 window/device/GUI-scale configurations");
    }

    private static byte[] coordinatePrelude(byte[] screen) {
        ClassNode node = new ClassNode();
        new ClassReader(screen).accept(node, 0);
        MethodNode input = null;
        for (Object item : node.methods) {
            MethodNode method = (MethodNode) item;
            if (method.name.equals("k") && method.desc.equals("()V")) input = method;
        }
        assertNotNull(input, "Vanilla handleMouseInput prelude missing");
        ClassWriter writer = type("axu");
        writer.visitField(Opcodes.ACC_PUBLIC, "j", "Lave;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "l", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "m", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "coordinates", "()[I", null, null);
        method.visitCode();
        boolean stopped = false;
        for (AbstractInsnNode instruction = input.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).name.equals("getEventButton")) {
                stopped = true;
                break;
            }
            if (instruction.getOpcode() >= 0) instruction.clone(new HashMap<LabelNode, LabelNode>()).accept(method);
        }
        assertTrue(stopped, "Vanilla coordinate prelude boundary changed");
        method.visitInsn(Opcodes.ICONST_2);
        method.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
        for (int index = 0; index < 2; index++) {
            method.visitInsn(Opcodes.DUP);
            method.visitInsn(index == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1);
            method.visitVarInsn(Opcodes.ILOAD, index + 1);
            method.visitInsn(Opcodes.IASTORE);
        }
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] minecraftDimensions() {
        ClassWriter writer = type("ave");
        writer.visitField(Opcodes.ACC_PUBLIC, "d", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "e", "I", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter type(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        return writer;
    }

    private static byte[] device(String name, String[][] getters) {
        ClassWriter writer = type(name);
        for (String[] getter : getters) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, getter[0], getter[2], null, null);
            method.visitCode();
            method.visitMethodInsn(Opcodes.INVOKESTATIC,
                    StandaloneVanillaMouseInputTest.Input.class.getName().replace('.', '/'), getter[1], getter[2], false);
            method.visitInsn(getter[2].endsWith("F") ? Opcodes.FRETURN : Opcodes.IRETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] platform() {
        ClassWriter writer = type("org/lwjgl/LWJGLUtil");
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getPlatform", "()I", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_2);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] read(InputStream input) throws java.io.IOException {
        assertNotNull(input);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) != -1) bytes.write(buffer, 0, length);
        return bytes.toByteArray();
    }

    public static final class Input {
        static int x;
        static int y;
        static float pixelScale;
        public static int eventX() { return x; }
        public static int eventY() { return y; }
        public static float scale() { return pixelScale; }
    }

    private static final class DeviceLoader extends ClassLoader {
        private final Map<String, byte[]> classes;
        DeviceLoader(Map<String, byte[]> classes) {
            super(StandaloneVanillaMouseInputTest.class.getClassLoader());
            this.classes = classes;
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (!classes.containsKey(name)) return super.loadClass(name, resolve);
                Class<?> result = findLoadedClass(name);
                if (result == null) {
                    byte[] bytes = classes.get(name);
                    result = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) resolveClass(result);
                return result;
            }
        }
    }
}
