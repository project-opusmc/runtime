package dev.rbw.patches;

import dev.rbw.bootstrap.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class WindowTitleTransformer implements ClassTransformer {
    static final String TARGET_CLASS = "ave";
    static final String VANILLA_TITLE = "Minecraft 1.8.9";
    static final String RBW_TITLE = "RBW Client";

    @Override
    public String id() {
        return "rbw.window-title";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytecode) {
        if (!TARGET_CLASS.equals(className)) {
            return originalBytecode;
        }

        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, 0);
        int[] replacements = {0};
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM5, delegate) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (VANILLA_TITLE.equals(value)) {
                            replacements[0]++;
                            super.visitLdcInsn(RBW_TITLE);
                        } else {
                            super.visitLdcInsn(value);
                        }
                    }
                };
            }
        };
        reader.accept(visitor, 0);

        if (replacements[0] != 1) {
            throw new IllegalStateException(
                    "Window title patch expected exactly one anchor but found " + replacements[0]);
        }
        System.out.println("[RBW/PATCH] applied " + id());
        return writer.toByteArray();
    }
}
