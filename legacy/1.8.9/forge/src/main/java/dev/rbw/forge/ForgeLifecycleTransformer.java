package dev.rbw.forge;

import dev.rbw.bootstrap.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Injects a one-time lifecycle marker at Minecraft's verified first-frame path. */
final class ForgeLifecycleTransformer implements ClassTransformer {
    private static final String MINECRAFT_CLASS = "ave";
    private static final String FRAME_LOOP_METHOD = "av";
    private static final String VOID_DESCRIPTOR = "()V";
    private static final String LIFECYCLE_OWNER = "dev/rbw/forge/ForgeTelemetryLifecycle";

    @Override
    public String id() {
        return "rbw.forge-lifecycle";
    }

    @Override
    public int priority() {
        return 105;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytecode) {
        if (!MINECRAFT_CLASS.equals(className)) {
            return originalBytecode;
        }

        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] matches = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!FRAME_LOOP_METHOD.equals(name) || !VOID_DESCRIPTOR.equals(descriptor)) {
                    return delegate;
                }
                matches[0]++;
                return new MethodVisitor(Opcodes.ASM5, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                LIFECYCLE_OWNER,
                                "markRunning",
                                VOID_DESCRIPTOR,
                                false);
                    }
                };
            }
        }, 0);
        if (matches[0] != 1) {
            throw new IllegalStateException("Forge lifecycle frame anchor changed: " + matches[0]);
        }
        System.out.println("[OPUS/PATCH] applied " + id());
        return writer.toByteArray();
    }
}
