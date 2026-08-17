package org.polydevs.opusmc.forge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class BootTerminalTransformerTest {
    @Test
    void replacesTheVerifiedSplashBodyWithTheTerminalPainter() {
        byte[] transformed = new BootTerminalTransformer().transform("ave", minecraftClass());
        assertEquals(1, renderCalls(transformed));
    }

    @Test
    void keepsTheClassBootableWhenTheSplashAnchorIsMissing() {
        byte[] original = minecraftClassWithoutSplash();
        byte[] transformed = new BootTerminalTransformer().transform("ave", original);
        assertArrayEquals(original, transformed);
    }

    private static byte[] minecraftClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "ave", null, "java/lang/Object", null);
        MethodVisitor splash = writer.visitMethod(
                Opcodes.ACC_PRIVATE, "a", "(Lbmj;)V", null, null);
        splash.visitCode();
        splash.visitInsn(Opcodes.RETURN);
        splash.visitMaxs(0, 0);
        splash.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] minecraftClassWithoutSplash() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "ave", null, "java/lang/Object", null);
        MethodVisitor other = writer.visitMethod(
                Opcodes.ACC_PRIVATE, "a", "(I)V", null, null);
        other.visitCode();
        other.visitInsn(Opcodes.RETURN);
        other.visitMaxs(0, 0);
        other.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int renderCalls(byte[] bytecode) {
        AtomicInteger calls = new AtomicInteger();
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
                            String methodName,
                            String methodDescriptor,
                            boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && "org/polydevs/opusmc/forge/OpusBootTerminal".equals(owner)
                                && "render".equals(methodName)
                                && "()V".equals(methodDescriptor)) {
                            calls.incrementAndGet();
                        }
                    }
                };
            }
        }, 0);
        return calls.get();
    }
}
