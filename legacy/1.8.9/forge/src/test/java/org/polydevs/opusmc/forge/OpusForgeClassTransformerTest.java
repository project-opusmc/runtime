package org.polydevs.opusmc.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class OpusForgeClassTransformerTest {
    @Test
    void recognizesTheLaunchWrapperMappedMinecraftName() {
        assertEquals(
                "ave",
                OpusForgeClassTransformer.targetClassName(
                        "ave", "net.minecraft.client.Minecraft"));
    }

    @Test
    void reportsTheTelemetryAndOptiFineForgeTransformerChain() {
        assertEquals(8, OpusForgeClassTransformer.transformerCount());
    }

    @Test
    void neverTransformsTheRetiredReflectionUiPlaceholder() {
        OpusForgeClassTransformer transformer = new OpusForgeClassTransformer();
        String className = "org.polydevs.opusmc.client.gui.OpusClientOptionsScreen";
        byte[] placeholder = placeholderBytecode(className.replace('.', '/'));

        byte[] transformed = transformer.transform(className, className, placeholder);

        assertEquals(8, transformer.configuredTransformerCount());
        assertEquals("java/lang/Object", new ClassReader(transformed).getSuperName());
    }

    @Test
    void routesObfuscatedGuiScreenThroughItsMappedNameAndNormalizesClicks() {
        OpusForgeClassTransformer transformer = new OpusForgeClassTransformer();
        String mappedName = "net.minecraft.client.gui.GuiScreen";
        byte[] transformed = transformer.transform(
                "axu", mappedName, mouseCoordinateBytecode("axu", "getEventX", "getEventY"));

        assertEquals(mappedName, OpusForgeClassTransformer.targetClassName("axu", mappedName));
        assertEquals(2, countMouseNormalizers(transformed));
    }

    @Test
    void routesObfuscatedGuiSlotThroughItsMappedNameAndNormalizesRawReads() {
        OpusForgeClassTransformer transformer = new OpusForgeClassTransformer();
        String mappedName = "net.minecraft.client.gui.GuiSlot";
        byte[] transformed = transformer.transform(
                "awi", mappedName, mouseCoordinateBytecode("awi", "getX", "getEventY"));

        assertEquals(mappedName, OpusForgeClassTransformer.targetClassName("awi", mappedName));
        assertEquals(2, countMouseNormalizers(transformed));
    }

    @Test
    void keepsEntityRendererOnItsVerifiedBfkTarget() {
        assertEquals(
                "bfk",
                OpusForgeClassTransformer.targetClassName(
                        "bfk", "net.minecraft.client.renderer.EntityRenderer"));
    }

    @Test
    void neverTargetsNonGuiSettingsOrLwjglInputClasses() {
        OpusForgeClassTransformer transformer = new OpusForgeClassTransformer();
        String[] vanillaOwnedClasses = {
                "net.minecraft.client.settings.KeyBinding",
                "org.lwjgl.input.Keyboard",
                "org.lwjgl.input.Mouse",
        };

        for (String className : vanillaOwnedClasses) {
            byte[] original = placeholderBytecode(className.replace('.', '/'));
            assertEquals(className, OpusForgeClassTransformer.targetClassName(className, className));
            assertArrayEquals(original, transformer.transform(className, className, original));
        }
    }

    private static byte[] mouseCoordinateBytecode(
            String internalName,
            String... mouseMethods) {
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

    private static int countMouseNormalizers(byte[] bytecode) {
        int[] count = {0};
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
                        if ("org/polydevs/opusmc/forge/RetinaDisplay".equals(owner)
                                && "mouseCoordinate".equals(name)
                                && "(I)I".equals(descriptor)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, 0);
        return count[0];
    }

    private static byte[] placeholderBytecode(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
