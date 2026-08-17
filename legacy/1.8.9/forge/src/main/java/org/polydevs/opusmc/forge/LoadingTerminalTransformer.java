package org.polydevs.opusmc.forge;

import org.polydevs.opusmc.bootstrap.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Replaces the dirt-texture LoadingScreenRenderer with Opus terminal output. */
final class LoadingTerminalTransformer implements ClassTransformer {
    private static final String LOADING_CLASS = "avi";
    private static final String PROGRESS_METHOD = "a";
    private static final String PROGRESS_DESCRIPTOR = "(I)V";
    private static final String OPUS_OWNER = "org/polydevs/opusmc/forge/OpusBootTerminal";

    @Override
    public String id() {
        return "opus.loading-terminal";
    }

    @Override
    public int priority() {
        return 131;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytecode) {
        if (!LOADING_CLASS.equals(className) || originalBytecode == null) {
            return originalBytecode;
        }

        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final int[] replacements = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                if (!PROGRESS_METHOD.equals(name) || !PROGRESS_DESCRIPTOR.equals(descriptor)) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }
                replacements[0]++;
                MethodVisitor rewritten =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                rewritten.visitCode();
                rewritten.visitVarInsn(Opcodes.ALOAD, 0);
                rewritten.visitFieldInsn(
                        Opcodes.GETFIELD, LOADING_CLASS, "c", "Ljava/lang/String;");
                rewritten.visitVarInsn(Opcodes.ALOAD, 0);
                rewritten.visitFieldInsn(
                        Opcodes.GETFIELD, LOADING_CLASS, "a", "Ljava/lang/String;");
                rewritten.visitVarInsn(Opcodes.ILOAD, 1);
                rewritten.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        OPUS_OWNER,
                        "renderLoading",
                        "(Ljava/lang/String;Ljava/lang/String;I)V",
                        false);
                rewritten.visitInsn(Opcodes.RETURN);
                rewritten.visitMaxs(0, 0);
                rewritten.visitEnd();
                return new MethodVisitor(Opcodes.ASM5) {
                    // Detached visitor discards the vanilla dirt/progress body.
                };
            }
        }, 0);

        if (replacements[0] != 1) {
            System.out.println(
                    "[OPUS/PATCH] skipped " + id()
                            + " (progress anchor matches=" + replacements[0] + ")");
            return originalBytecode;
        }
        System.out.println("[OPUS/PATCH] applied " + id());
        return writer.toByteArray();
    }
}
