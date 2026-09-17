package org.polydevs.opusmc.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class RetinaBackbufferTransformerTest {
    @Test
    void patchesStartupAndResizeDimensionsAtVerifiedAnchors() {
        byte[] transformed = new RetinaBackbufferTransformer().transform(
                RetinaBackbufferTransformer.TARGET_CLASS,
                minecraftClass(true, true));

        assertEquals(2, countCalls(transformed, RetinaBackbufferTransformer.BACKING_WIDTH));
        assertEquals(2, countCalls(transformed, RetinaBackbufferTransformer.BACKING_HEIGHT));
        assertEquals(0, countDisplayDimensionCalls(transformed));
    }

    @Test
    void failsClosedWhenStartupAnchorMoves() {
        assertThrows(
                IllegalStateException.class,
                () -> new RetinaBackbufferTransformer().transform(
                        RetinaBackbufferTransformer.TARGET_CLASS,
                        minecraftClass(false, true)));
    }

    @Test
    void failsClosedWhenResizeAnchorMoves() {
        assertThrows(
                IllegalStateException.class,
                () -> new RetinaBackbufferTransformer().transform(
                        RetinaBackbufferTransformer.TARGET_CLASS,
                        minecraftClass(true, false)));
    }

    private static byte[] minecraftClass(boolean startupAnchor, boolean resizeAnchors) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, RetinaBackbufferTransformer.TARGET_CLASS,
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, RetinaBackbufferTransformer.DISPLAY_WIDTH_FIELD,
                "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, RetinaBackbufferTransformer.DISPLAY_HEIGHT_FIELD,
                "I", null, null).visitEnd();

        MethodVisitor create = writer.visitMethod(
                Opcodes.ACC_PRIVATE,
                RetinaBackbufferTransformer.CREATE_DISPLAY_METHOD,
                RetinaBackbufferTransformer.VOID_DESCRIPTOR,
                null,
                null);
        create.visitCode();
        create.visitInsn(Opcodes.RETURN);
        create.visitMaxs(0, 1);
        create.visitEnd();

        MethodVisitor startup = writer.visitMethod(
                Opcodes.ACC_PRIVATE,
                RetinaBackbufferTransformer.STARTUP_METHOD,
                RetinaBackbufferTransformer.VOID_DESCRIPTOR,
                null,
                null);
        startup.visitCode();
        if (startupAnchor) {
            startup.visitVarInsn(Opcodes.ALOAD, 0);
            startup.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    RetinaBackbufferTransformer.TARGET_CLASS,
                    RetinaBackbufferTransformer.CREATE_DISPLAY_METHOD,
                    RetinaBackbufferTransformer.VOID_DESCRIPTOR,
                    false);
        }
        startup.visitInsn(Opcodes.RETURN);
        startup.visitMaxs(1, 1);
        startup.visitEnd();

        MethodVisitor resize = writer.visitMethod(
                Opcodes.ACC_PROTECTED,
                RetinaBackbufferTransformer.RESIZE_METHOD,
                RetinaBackbufferTransformer.VOID_DESCRIPTOR,
                null,
                null);
        resize.visitCode();
        if (resizeAnchors) {
            resize.visitVarInsn(Opcodes.ALOAD, 0);
            resize.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    RetinaBackbufferTransformer.DISPLAY_OWNER,
                    "getWidth",
                    RetinaBackbufferTransformer.DIMENSION_DESCRIPTOR,
                    false);
            resize.visitFieldInsn(
                    Opcodes.PUTFIELD,
                    RetinaBackbufferTransformer.TARGET_CLASS,
                    RetinaBackbufferTransformer.DISPLAY_WIDTH_FIELD,
                    "I");
            resize.visitVarInsn(Opcodes.ALOAD, 0);
            resize.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    RetinaBackbufferTransformer.DISPLAY_OWNER,
                    "getHeight",
                    RetinaBackbufferTransformer.DIMENSION_DESCRIPTOR,
                    false);
            resize.visitFieldInsn(
                    Opcodes.PUTFIELD,
                    RetinaBackbufferTransformer.TARGET_CLASS,
                    RetinaBackbufferTransformer.DISPLAY_HEIGHT_FIELD,
                    "I");
        }
        resize.visitInsn(Opcodes.RETURN);
        resize.visitMaxs(1, 1);
        resize.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int countCalls(byte[] bytecode, String method) {
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
                        if (RetinaBackbufferTransformer.RETINA_OWNER.equals(owner)
                                && method.equals(name)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, 0);
        return count[0];
    }

    private static int countDisplayDimensionCalls(byte[] bytecode) {
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
                        if (RetinaBackbufferTransformer.DISPLAY_OWNER.equals(owner)
                                && ("getWidth".equals(name) || "getHeight".equals(name))) {
                            count[0]++;
                        }
                    }
                };
            }
        }, 0);
        return count[0];
    }
}
