package org.polydevs.opusmc.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class LoadingTerminalTransformerTest {
    @Test
    void replacesTheVerifiedLoadingProgressBody() {
        byte[] transformed = new LoadingTerminalTransformer().transform("avi", loadingClass());
        assertEquals(1, renderCalls(transformed));
    }

    private static byte[] loadingClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "avi", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "a", "Ljava/lang/String;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "c", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "a", "(I)V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
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
                                && "renderLoading".equals(methodName)
                                && "(Ljava/lang/String;Ljava/lang/String;I)V".equals(methodDescriptor)) {
                            calls.incrementAndGet();
                        }
                    }
                };
            }
        }, 0);
        return calls.get();
    }
}
