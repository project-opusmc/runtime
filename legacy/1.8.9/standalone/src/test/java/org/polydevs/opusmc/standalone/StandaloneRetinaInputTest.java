package org.polydevs.opusmc.standalone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class StandaloneRetinaInputTest {
    @Test
    void rawNotchGuiScreenReceivesTheSameRetinaClickPatchAsMappedScreens() {
        byte[] original = inputClass("axu");
        byte[] patched = new OpusStandaloneClassTransformer().transform("axu", "axu", original);
        assertEquals(Arrays.asList("getEventX", "mouseCoordinate", "getEventY", "mouseCoordinate"),
                inputCalls(patched), "Standalone raw GuiScreen must normalize both click coordinates");
    }

    @Test
    void rawNotchGuiSlotReceivesRetinaNormalization() {
        byte[] patched = new OpusStandaloneClassTransformer().transform("awi", "awi", inputClass("awi"));
        assertEquals(Arrays.asList("getEventX", "mouseCoordinate", "getEventY", "mouseCoordinate"), inputCalls(patched));
    }

    @Test
    void preservesUnrelatedAndCefInputBytecode() {
        for (String name : Arrays.asList("avh", "org.lwjgl.input.Mouse",
                "org.polydevs.opusmc.client.OpusClientScreen")) {
            byte[] original = inputClass(name.replace('.', '/'));
            assertSame(original, new OpusStandaloneClassTransformer().transform(name, name, original));
        }
    }

    @Test
    void rawGuiScreenTransformationRemainsIdempotent() {
        OpusStandaloneClassTransformer transformer = new OpusStandaloneClassTransformer();
        byte[] once = transformer.transform("axu", "axu", inputClass("axu"));
        assertArrayEquals(once, transformer.transform("axu", "axu", once));
    }

    private static byte[] inputClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "input", "()V", null, null);
        method.visitCode();
        for (String getter : Arrays.asList("getEventX", "getEventY")) {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/input/Mouse", getter, "()I", false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> inputCalls(byte[] data) {
        ClassNode node = new ClassNode();
        new ClassReader(data).accept(node, 0);
        List<String> calls = new ArrayList<String>();
        for (Object item : node.methods) {
            MethodNode method = (MethodNode) item;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.owner.equals("org/lwjgl/input/Mouse")
                            || call.owner.equals("org/polydevs/opusmc/forge/RetinaDisplay")) calls.add(call.name);
                }
            }
        }
        return calls;
    }
}
