package org.polydevs.opusmc.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class ForgeLifecycleTransformerTest {
    @Test
    void injectsTheRunningMarkerAtTheFrameLoopEntry() {
        byte[] transformed = new ForgeLifecycleTransformer().transform("ave", frameLoopClass());

        assertEquals(1, countRunningMarkers(transformed));
    }

    @Test
    void rejectsMinecraftClassesWithoutTheVerifiedFrameLoop() {
        assertThrows(
                IllegalStateException.class,
                () -> new ForgeLifecycleTransformer().transform("ave", emptyMinecraftClass()));
    }

    private static byte[] frameLoopClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "ave", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "av", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyMinecraftClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "ave", null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int countRunningMarkers(byte[] bytecode) {
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
                                && "org/polydevs/opusmc/forge/ForgeTelemetryLifecycle".equals(owner)
                                && "markRunning".equals(methodName)
                                && "()V".equals(methodDescriptor)
                                && !isInterface) {
                            calls.incrementAndGet();
                        }
                    }
                };
            }
        }, 0);
        return calls.get();
    }
}
