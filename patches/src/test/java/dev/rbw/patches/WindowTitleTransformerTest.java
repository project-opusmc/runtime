package dev.rbw.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class WindowTitleTransformerTest {
    @Test
    void replacesExactlyOneVerifiedTitleAnchor() {
        byte[] original = classWithString(WindowTitleTransformer.VANILLA_TITLE);
        byte[] transformed = new WindowTitleTransformer().transform("ave", original);

        assertEquals(0, countString(transformed, WindowTitleTransformer.VANILLA_TITLE));
        assertEquals(1, countString(transformed, WindowTitleTransformer.RBW_TITLE));
    }

    @Test
    void failsClosedWhenAnchorIsMissing() {
        byte[] unrelated = classWithString("not the expected title");
        assertThrows(
                IllegalStateException.class,
                () -> new WindowTitleTransformer().transform("ave", unrelated));
    }

    private static byte[] classWithString(String value) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "ave", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        method.visitCode();
        method.visitLdcInsn(value);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int countString(byte[] bytecode, String expected) {
        AtomicInteger count = new AtomicInteger();
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
                    public void visitLdcInsn(Object value) {
                        if (expected.equals(value)) {
                            count.incrementAndGet();
                        }
                    }
                };
            }
        }, 0);
        return count.get();
    }
}

