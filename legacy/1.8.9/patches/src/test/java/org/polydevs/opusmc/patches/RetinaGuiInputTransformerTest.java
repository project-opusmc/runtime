package org.polydevs.opusmc.patches;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class RetinaGuiInputTransformerTest {
    @Test
    void wrapsOnlyAbsoluteMouseCoordinatesInVanillaGuiBytecode() {
        byte[] transformed = transform(
                "net.minecraft.client.gui.GuiScreen",
                "axu",
                "getX", "getY", "getEventX", "getEventY",
                "getDX", "getDY", "getEventDWheel");

        assertEquals(Arrays.asList(
                mouse("getX"), retina(),
                mouse("getY"), retina(),
                mouse("getEventX"), retina(),
                mouse("getEventY"), retina(),
                mouse("getDX"),
                mouse("getDY"),
                mouse("getEventDWheel")), relevantCalls(transformed));
    }

    @Test
    void wrapsDirectGuiSlotReadsWhenForgeSuppliesTheMappedClassName() {
        byte[] transformed = transform(
                "net.minecraft.client.gui.GuiSlot",
                "awi",
                "getX", "getEventY", "getEventDWheel");

        assertEquals(Arrays.asList(
                mouse("getX"), retina(),
                mouse("getEventY"), retina(),
                mouse("getEventDWheel")), relevantCalls(transformed));
    }

    @Test
    void wrapsEntityRendererBfkHoverCoordinates() {
        byte[] transformed = transform("bfk", "bfk", "getX", "getY");

        assertEquals(Arrays.asList(
                mouse("getX"), retina(),
                mouse("getY"), retina()), relevantCalls(transformed));
    }

    @Test
    void leavesCefRawMouseContractUntouched() {
        byte[] original = classWithMouseCalls(
                "org/polydevs/opusmc/client/OpusClientScreen",
                "getX", "getY", "getEventX", "getEventY");

        byte[] transformed = new RetinaGuiInputTransformer().transform(
                "org.polydevs.opusmc.client.OpusClientScreen", original);

        assertSame(original, transformed);
    }

    @Test
    void leavesVanillaGuiClassesWithoutAbsoluteReadsByteForByteUnchanged() {
        byte[] original = classWithMouseCalls("awi", "getEventDWheel");

        byte[] transformed = new RetinaGuiInputTransformer().transform(
                "net.minecraft.client.gui.GuiSlot", original);

        assertSame(original, transformed);
    }

    @Test
    void repeatedTransformationDoesNotScaleCoordinatesTwice() {
        RetinaGuiInputTransformer transformer = new RetinaGuiInputTransformer();
        byte[] original = classWithMouseCalls("axu", "getEventX", "getEventY");

        byte[] once = transformer.transform("net.minecraft.client.gui.GuiScreen", original);
        byte[] twice = transformer.transform("net.minecraft.client.gui.GuiScreen", once);

        assertArrayEquals(once, twice);
        assertEquals(2, countRetinaCalls(twice));
    }

    private static byte[] transform(
            String className,
            String internalName,
            String... mouseMethods) {
        return new RetinaGuiInputTransformer().transform(
                className, classWithMouseCalls(internalName, mouseMethods));
    }

    private static byte[] classWithMouseCalls(String internalName, String... mouseMethods) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "mouseInput",
                "()V",
                null,
                null);
        method.visitCode();
        for (String mouseMethod : mouseMethods) {
            method.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/input/Mouse",
                    mouseMethod,
                    "()I",
                    false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> relevantCalls(byte[] bytecode) {
        List<String> calls = new ArrayList<String>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String name,
                            String descriptor,
                            boolean isInterface) {
                        if ("org/lwjgl/input/Mouse".equals(owner)
                                || "org/polydevs/opusmc/forge/RetinaDisplay".equals(owner)) {
                            calls.add(owner + "." + name + descriptor);
                        }
                    }
                };
            }
        }, 0);
        return calls;
    }

    private static int countRetinaCalls(byte[] bytecode) {
        int count = 0;
        for (String call : relevantCalls(bytecode)) {
            if (retina().equals(call)) {
                count++;
            }
        }
        return count;
    }

    private static String mouse(String method) {
        return "org/lwjgl/input/Mouse." + method + "()I";
    }

    private static String retina() {
        return "org/polydevs/opusmc/forge/RetinaDisplay.mouseCoordinate(I)I";
    }
}
