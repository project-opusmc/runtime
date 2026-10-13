package org.polydevs.opusmc.standalone;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OpusUiRemappingTransformerTest {

    @Test
    public void remapsGuiScreenOverridesDeclaredOnUiSubclass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                "org/polydevs/opusmc/client/OpusClientScreen",
                null,
                "net/minecraft/client/gui/GuiScreen",
                null);
        addMethod(writer, "func_73866_w_", "()V");
        addMethod(writer, "func_73863_a", "(IIF)V");
        addMethod(writer, "func_73876_c", "()V");
        MethodVisitor drawRect = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "drawRectThroughGui", "()V", null, null);
        drawRect.visitCode();
        drawRect.visitIntInsn(Opcodes.BIPUSH, 1);
        drawRect.visitIntInsn(Opcodes.BIPUSH, 2);
        drawRect.visitIntInsn(Opcodes.BIPUSH, 3);
        drawRect.visitIntInsn(Opcodes.BIPUSH, 4);
        drawRect.visitLdcInsn(0xFF000000);
        drawRect.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/polydevs/opusmc/client/OpusClientScreen",
                "func_73734_a",
                "(IIIII)V",
                false);
        drawRect.visitInsn(Opcodes.RETURN);
        drawRect.visitMaxs(5, 1);
        drawRect.visitEnd();
        writer.visitEnd();

        OpusUiRemappingTransformer transformer = new OpusUiRemappingTransformer();
        byte[] remapped = transformer.transform(
                "org.polydevs.opusmc.client.OpusClientScreen",
                "org.polydevs.opusmc.client.OpusClientScreen",
                writer.toByteArray());
        assertNotNull(remapped);

        ClassReader reader = new ClassReader(remapped);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        assertTrue(hasMethod(node, "b", "()V"), "initGui must remap to b()V");
        assertTrue(hasMethod(node, "a", "(IIF)V"), "drawScreen must remap to a(IIF)V");
        assertTrue(hasMethod(node, "e", "()V"), "updateScreen must remap to e()V");
        assertTrue(
                hasMethodInvocation(node, "a", "(IIIII)V", "org/polydevs/opusmc/client/OpusClientScreen"),
                "drawRect invocation must remap to a(IIIII)V");
    }

    @Test
    public void remapsGuiScreenFieldsReferencedByUiSubclass() {
        String owner = "org/polydevs/opusmc/client/OpusClientScreen";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                owner,
                null,
                "net/minecraft/client/gui/GuiScreen",
                null);

        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "readInheritedFields", "()V", null, null);
        method.visitCode();
        addFieldRead(method, owner, "field_146297_k", "Lnet/minecraft/client/Minecraft;");
        addFieldRead(method, owner, "field_146294_l", "I");
        addFieldRead(method, owner, "field_146295_m", "I");
        addFieldRead(method, owner, "field_999999_x", "I");
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        writer.visitEnd();

        OpusUiRemappingTransformer transformer = new OpusUiRemappingTransformer();
        byte[] remapped = transformer.transform(
                "org.polydevs.opusmc.client.OpusClientScreen",
                "org.polydevs.opusmc.client.OpusClientScreen",
                writer.toByteArray());
        assertNotNull(remapped);

        ClassNode node = new ClassNode();
        new ClassReader(remapped).accept(node, 0);
        assertTrue(hasFieldAccess(node, "j", owner), "Minecraft field must remap to j");
        assertTrue(hasFieldAccess(node, "l", owner), "GuiScreen width must remap to l");
        assertTrue(hasFieldAccess(node, "m", owner), "GuiScreen height must remap to m");
        assertTrue(
                hasFieldAccess(node, "field_999999_x", owner),
                "unknown fields must remain unchanged");
        assertFalse(hasFieldAccess(node, "field_146297_k", owner), "mc must not retain its SRG name");
        assertFalse(hasFieldAccess(node, "field_146294_l", owner), "width must not retain its SRG name");
        assertFalse(hasFieldAccess(node, "field_146295_m", owner), "height must not retain its SRG name");
    }

    @Test
    public void remapsInheritedGuiFieldsFromAnonymousShaderHost() {
        String screen = "org/polydevs/opusmc/client/OpusClientScreen";
        ClassWriter outer = new ClassWriter(0);
        outer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, screen, null,
                "net/minecraft/client/gui/GuiScreen", null);
        outer.visitEnd();
        OpusUiRemappingTransformer transformer = new OpusUiRemappingTransformer();
        transformer.transform(screen.replace('/', '.'), screen.replace('/', '.'), outer.toByteArray());

        String host = screen + "$1";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, host, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "currentMinecraft", "(L" + screen + ";)Lnet/minecraft/client/Minecraft;", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, screen, "field_146297_k", "Lnet/minecraft/client/Minecraft;");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transform(host.replace('/', '.'), host.replace('/', '.'),
                writer.toByteArray())).accept(node, 0);
        assertTrue(hasFieldAccess(node, "j", screen),
                "Pause shader host must resolve the outer screen's inherited Minecraft field");
        assertFalse(hasFieldAccess(node, "field_146297_k", screen),
                "Anonymous host must not leave a field that fails only when pause opens");
    }

    @Test
    public void remapsHelperBeforeOwnerWithUnrelatedContextClassLoader() {
        String screen = "org/polydevs/opusmc/client/RemapFixtureScreen";
        String helper = screen + "$Shader";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, helper, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "currentMinecraft", "(L" + screen + ";)Lnet/minecraft/client/Minecraft;", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, screen, "field_146297_k", "Lnet/minecraft/client/Minecraft;");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(new ClassLoader(null) { });
            ClassNode node = new ClassNode();
            byte[] remapped = new OpusUiRemappingTransformer().transform(
                    helper.replace('/', '.'), helper.replace('/', '.'), writer.toByteArray());
            new ClassReader(remapped).accept(node, 0);
            assertTrue(hasFieldAccess(node, "j", screen),
                    "A context-loader miss must fall back to the defining loader's screen metadata");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    public void doesNotRemapGuiScreenFieldsOnUnrelatedSubclass() {
        String owner = "org/polydevs/opusmc/client/UnrelatedScreen";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "readField", "()V", null, null);
        method.visitCode();
        addFieldRead(method, owner, "field_146297_k", "I");
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        writer.visitEnd();

        OpusUiRemappingTransformer transformer = new OpusUiRemappingTransformer();
        byte[] remapped = transformer.transform(
                "org.polydevs.opusmc.client.UnrelatedScreen",
                "org.polydevs.opusmc.client.UnrelatedScreen",
                writer.toByteArray());
        assertNotNull(remapped);

        ClassNode node = new ClassNode();
        new ClassReader(remapped).accept(node, 0);
        assertTrue(
                hasFieldAccess(node, "field_146297_k", owner),
                "unrelated subclass fields must not use GuiScreen mappings");
        assertFalse(hasFieldAccess(node, "j", owner), "unrelated subclass fields must not be remapped");
    }

    private static void addFieldRead(MethodVisitor method, String owner, String name, String descriptor) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, name, descriptor);
        method.visitInsn(Opcodes.POP);
    }

    private static void addMethod(ClassWriter writer, String name, String descriptor) {
        writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null).visitEnd();
    }

    private static boolean hasFieldAccess(ClassNode node, String name, String owner) {
        for (Object value : node.methods) {
            MethodNode method = (MethodNode) value;
            for (Object instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    if (owner.equals(field.owner) && name.equals(field.name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasMethodInvocation(
            ClassNode node, String name, String descriptor, String owner) {
        for (Object value : node.methods) {
            MethodNode method = (MethodNode) value;
            for (Object instruction : method.instructions.toArray()) {
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode) {
                    org.objectweb.asm.tree.MethodInsnNode invocation =
                            (org.objectweb.asm.tree.MethodInsnNode) instruction;
                    if (owner.equals(invocation.owner)
                            && name.equals(invocation.name)
                            && descriptor.equals(invocation.desc)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasMethod(ClassNode node, String name, String descriptor) {
        for (Object value : node.methods) {
            MethodNode method = (MethodNode) value;
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                return true;
            }
        }
        return false;
    }
}
