package org.polydevs.opusmc.patches;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class OptiFineCloudRendererTransformerTest {
    @Test
    void replacesAllCloudAnchorsWithoutDelegatingOriginalBodies() throws Exception {
        byte[] original = cloudRendererFixture();
        byte[] transformed = new OptiFineCloudRendererTransformer()
                .transform(OptiFineCloudRendererTransformer.TARGET_CLASS, original);

        DefiningLoader loader = new DefiningLoader();
        Class<?> argumentType = loader.define("ave", emptyClass("ave"));
        Class<?> cloudRenderer = loader.define(
                OptiFineCloudRendererTransformer.TARGET_CLASS,
                transformed);

        Constructor<?> constructor = cloudRenderer.getConstructor(argumentType);
        Object instance = constructor.newInstance(argumentType.getDeclaredConstructor().newInstance());
        Method shouldUpdate = cloudRenderer.getMethod("shouldUpdateGlList");
        assertFalse((Boolean) shouldUpdate.invoke(instance));

        for (String methodName : new String[] {
            "startUpdateGlList", "endUpdateGlList", "renderGlList", "reset"
        }) {
            assertDoesNotThrow(() -> cloudRenderer.getMethod(methodName).invoke(instance));
        }
    }

    @Test
    void leavesUnrelatedClassesUntouched() {
        byte[] original = cloudRendererFixture();
        byte[] transformed = new OptiFineCloudRendererTransformer().transform("other.Type", original);

        assertSame(original, transformed);
    }

    private static byte[] cloudRendererFixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                "net/optifine/render/CloudRenderer",
                null,
                "java/lang/Object",
                null);
        writeThrowingConstructor(writer);
        writeThrowingMethod(writer, "shouldUpdateGlList", "()Z");
        writeThrowingMethod(writer, "startUpdateGlList", "()V");
        writeThrowingMethod(writer, "endUpdateGlList", "()V");
        writeThrowingMethod(writer, "renderGlList", "()V");
        writeThrowingMethod(writer, "reset", "()V");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeThrowingConstructor(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "(Lave;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        throwRuntimeException(method);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeThrowingMethod(ClassWriter writer, String name, String descriptor) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        throwRuntimeException(method);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void throwRuntimeException(MethodVisitor method) {
        method.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/RuntimeException",
                "<init>",
                "()V",
                false);
        method.visitInsn(Opcodes.ATHROW);
    }

    private static byte[] emptyClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class DefiningLoader extends ClassLoader {
        Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
