package dev.rbw.patches;

import dev.rbw.bootstrap.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Measures the verified 1.8.9 renderer invocation separately from frame time. */
public final class RenderTelemetryTransformer implements ClassTransformer {
    static final String TARGET_CLASS = "bfk";
    static final String RENDER_METHOD = "a";
    static final String RENDER_DESCRIPTOR = "(FJ)V";
    static final String TELEMETRY_OWNER = "dev/rbw/core/ClientTelemetry";

    @Override
    public String id() {
        return "rbw.render-telemetry";
    }

    @Override
    public int priority() {
        return 115;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytecode) {
        if (!TARGET_CLASS.equals(className)) {
            return originalBytecode;
        }

        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] hooks = {0, 0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!RENDER_METHOD.equals(name) || !RENDER_DESCRIPTOR.equals(descriptor)) {
                    return delegate;
                }
                hooks[0]++;
                return new MethodVisitor(Opcodes.ASM5, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        invoke("renderStarted");
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            hooks[1]++;
                            invoke("renderFinished");
                        }
                        super.visitInsn(opcode);
                    }

                    private void invoke(String hook) {
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                TELEMETRY_OWNER,
                                hook,
                                "()V",
                                false);
                    }
                };
            }
        }, 0);
        if (hooks[0] != 1 || hooks[1] == 0) {
            throw new IllegalStateException("Render telemetry anchor changed: " + hooks[0] + "/" + hooks[1]);
        }
        System.out.println("[RBW/PATCH] applied " + id());
        return writer.toByteArray();
    }
}
