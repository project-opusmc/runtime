package org.polydevs.opusmc.patches;

import org.polydevs.opusmc.bootstrap.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Keeps OptiFine M5's cloud renderer inert on the macOS OpenGL compatibility
 * context. The legacy display-list allocation returns zero on current Metal
 * backed OpenGL even when the context itself is healthy; clouds are already
 * disabled in the Opus profile, so retaining the renderer has no product value.
 */
public final class OptiFineCloudRendererTransformer implements ClassTransformer {
    public static final String TARGET_CLASS = "net.optifine.render.CloudRenderer";

    @Override
    public String id() {
        return "opus.optifine-cloud-compat";
    }

    @Override
    public int priority() {
        return 130;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytecode) {
        if (!TARGET_CLASS.equals(className)) {
            return originalBytecode;
        }

        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        int[] replaced = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor replacement = super.visitMethod(
                        access, name, descriptor, signature, exceptions);
                if (!isInertMethod(name, descriptor)) {
                    return replacement;
                }
                replaced[0]++;
                // Do not delegate the original body. OptiFine's implementation
                // allocates display lists and may execute native GL calls even
                // after the compatibility anchor has been disabled.
                return new MethodVisitor(Opcodes.ASM5) {
                    private boolean bodyWritten;

                    @Override
                    public void visitCode() {
                        if (bodyWritten) {
                            return;
                        }
                        bodyWritten = true;
                        replacement.visitCode();
                        if ("<init>".equals(name)) {
                            replacement.visitVarInsn(Opcodes.ALOAD, 0);
                            replacement.visitMethodInsn(
                                    Opcodes.INVOKESPECIAL,
                                    "java/lang/Object",
                                    "<init>",
                                    "()V",
                                    false);
                            replacement.visitInsn(Opcodes.RETURN);
                        } else if ("()Z".equals(descriptor)) {
                            replacement.visitInsn(Opcodes.ICONST_0);
                            replacement.visitInsn(Opcodes.IRETURN);
                        } else {
                            replacement.visitInsn(Opcodes.RETURN);
                        }
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        if (bodyWritten) {
                            replacement.visitMaxs(
                                    "<init>".equals(name) ? 1 : 1,
                                    "<init>".equals(name) ? 2 : 1);
                        }
                    }

                    @Override
                    public void visitEnd() {
                        replacement.visitEnd();
                    }
                };
            }
        }, 0);

        if (replaced[0] != 6) {
            throw new IllegalStateException(
                    "OptiFine CloudRenderer compatibility anchor changed: " + replaced[0]);
        }
        System.out.println("[OPUS/PATCH] applied " + id());
        return writer.toByteArray();
    }

    private static boolean isInertMethod(String name, String descriptor) {
        return ("<init>".equals(name) && "(Lave;)V".equals(descriptor))
                || ("shouldUpdateGlList".equals(name) && "()Z".equals(descriptor))
                || ("startUpdateGlList".equals(name) && "()V".equals(descriptor))
                || ("endUpdateGlList".equals(name) && "()V".equals(descriptor))
                || ("renderGlList".equals(name) && "()V".equals(descriptor))
                || ("reset".equals(name) && "()V".equals(descriptor));
    }
}
