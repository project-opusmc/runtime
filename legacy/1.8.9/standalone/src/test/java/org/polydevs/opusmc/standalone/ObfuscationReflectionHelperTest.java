package org.polydevs.opusmc.standalone;

import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

public class ObfuscationReflectionHelperTest {
    @Test
    public void resolvesConnectingReturnParentOnNotchOnlyScreen() throws Exception {
        Class<Object> screen = notchScreen("awz", "i");
        Object instance = screen.newInstance();
        Object parent = new Object();
        java.lang.reflect.Field field = screen.getDeclaredField("i");
        field.setAccessible(true);
        field.set(instance, parent);
        assertSame(parent, ObfuscationReflectionHelper.getPrivateValue(
                screen, instance, "previousGuiScreen", "field_146374_i"));
    }

    @Test
    public void preservesDirectFieldsAndRejectsUnmappedNames() throws Exception {
        Class<Object> screen = notchScreen("UnrelatedFixture", "i");
        Object instance = screen.newInstance();
        assertNull(ObfuscationReflectionHelper.getPrivateValue(screen, instance, "i"));
        assertThrows(RuntimeException.class, () -> ObfuscationReflectionHelper.getPrivateValue(
                screen, instance, "field_146374_i"));
    }

    @SuppressWarnings("unchecked")
    private static Class<Object> notchScreen(String owner, String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, name, "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        return (Class<Object>) new ClassLoader(ObfuscationReflectionHelperTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(owner, bytes, 0, bytes.length); }
        }.define();
    }
}
