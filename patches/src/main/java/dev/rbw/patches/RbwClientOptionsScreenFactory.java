package dev.rbw.patches;

import java.lang.reflect.Method;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Creates the Opus options screen inside Minecraft's active game class loader.
 *
 * <p>The project intentionally compiles without a bundled Minecraft jar.
 * A compact placeholder class is included as a resource and transformed
 * before definition by either Opus's standalone loader or Forge's
 * LaunchClassLoader. That keeps this boundary intact while still allowing
 * the generated screen to extend the real 1.8.9 {@code axu} (GuiScreen)
 * class at runtime.</p>
 */
public final class RbwClientOptionsScreenFactory {
    static final String CLASS_NAME = "rbwclient.gui.RbwClientOptionsScreen";
    static final String INTERNAL_NAME = "rbwclient/gui/RbwClientOptionsScreen";

    private static final String GUI_SCREEN = "axu";
    private static final String MINECRAFT = "ave";
    private static final String HOOKS = "dev/rbw/core/ClientUiHooks";
    private static final byte[] SCREEN_BYTECODE = createScreenBytecode();

    private RbwClientOptionsScreenFactory() {
    }

    public static Object create(ClassLoader gameLoader, Object parentScreen)
            throws ReflectiveOperationException {
        if (gameLoader == null) {
            throw new IllegalArgumentException("Opus Client Options requires a game class loader");
        }

        Class<?> guiScreenClass = Class.forName(GUI_SCREEN, false, gameLoader);
        Class<?> screenClass = loadScreenClass(gameLoader, guiScreenClass);
        if (!guiScreenClass.isAssignableFrom(screenClass)) {
            throw new IllegalStateException("Opus Client Options placeholder was not transformed");
        }
        return screenClass.getConstructor(guiScreenClass).newInstance(parentScreen);
    }

    private static Class<?> loadScreenClass(ClassLoader gameLoader, Class<?> guiScreenClass)
            throws ReflectiveOperationException {
        try {
            Class<?> placeholder = Class.forName(CLASS_NAME, true, gameLoader);
            if (guiScreenClass.isAssignableFrom(placeholder)) {
                return placeholder;
            }
        } catch (ClassNotFoundException missingPlaceholder) {
            // The legacy bootstrap keeps Opus support jars in its parent loader.
            // It therefore cannot see the placeholder resource in its child.
        }

        Method defineGenerated = gameLoader.getClass().getMethod(
                "defineGeneratedGameClass", String.class, byte[].class);
        Object generated = defineGenerated.invoke(gameLoader, CLASS_NAME, SCREEN_BYTECODE.clone());
        if (!(generated instanceof Class<?>)) {
            throw new IllegalStateException("Opus game class loader returned an invalid generated screen");
        }
        return (Class<?>) generated;
    }

    static byte[] screenBytecode() {
        return SCREEN_BYTECODE.clone();
    }

    private static byte[] createScreenBytecode() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V1_6,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                INTERNAL_NAME,
                null,
                GUI_SCREEN,
                null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "rbwParent", "Laxu;", null, null).visitEnd();

        writeConstructor(writer);
        writeClose(writer);
        writeInitGui(writer);
        writeOnGuiClosed(writer);
        writeKeyTyped(writer);
        writeDrawScreen(writer);
        writeMouseClicked(writer);
        writeMouseDragged(writer);
        writeMouseReleased(writer);
        writeDoesNotPause(writer);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeConstructor(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "<init>",
                "(Laxu;)V",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, GUI_SCREEN, "<init>", "()V", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitFieldInsn(Opcodes.PUTFIELD, INTERNAL_NAME, "rbwParent", "Laxu;");
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeClose(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "rbwClose",
                "()V",
                null,
                null);
        method.visitCode();
        closeScreen(method);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeInitGui(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "b", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, GUI_SCREEN, "n", "Ljava/util/List;");
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "clear", "()V", true);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                HOOKS,
                "openConfigScreen",
                "(Ljava/lang/Object;)V",
                false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeOnGuiClosed(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "m",
                "()V",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                HOOKS,
                "closeConfigScreen",
                "(Ljava/lang/Object;)V",
                false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, GUI_SCREEN, "m", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeKeyTyped(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PROTECTED,
                "a",
                "(CI)V",
                null,
                null);
        method.visitCode();
        Label delegate = new Label();
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitJumpInsn(Opcodes.IF_ICMPEQ, delegate);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitIntInsn(Opcodes.BIPUSH, 54);
        Label input = new Label();
        method.visitJumpInsn(Opcodes.IF_ICMPNE, input);
        method.visitLabel(delegate);
        closeScreen(method);
        method.visitLabel(input);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                HOOKS,
                "configKeyTyped",
                "(Ljava/lang/Object;CI)V",
                false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void closeScreen(MethodVisitor method) {
        Label restoreParent = new Label();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, INTERNAL_NAME, "rbwParent", "Laxu;");
        method.visitJumpInsn(Opcodes.IFNONNULL, restoreParent);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, GUI_SCREEN, "j", "Lave;");
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MINECRAFT, "a", "(Laxu;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(restoreParent);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, GUI_SCREEN, "j", "Lave;");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, INTERNAL_NAME, "rbwParent", "Laxu;");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MINECRAFT, "a", "(Laxu;)V", false);
        method.visitInsn(Opcodes.RETURN);
    }

    private static void writeDrawScreen(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "a",
                "(IIF)V",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitVarInsn(Opcodes.FLOAD, 3);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                HOOKS,
                "renderConfigScreen",
                "(Ljava/lang/Object;IIF)V",
                false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeMouseClicked(ClassWriter writer) {
        writeMouseHook(
                writer,
                "a",
                "(III)V",
                "configMouseClicked",
                "(Ljava/lang/Object;III)V");
    }

    private static void writeMouseDragged(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PROTECTED,
                "a",
                "(IIIJ)V",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitVarInsn(Opcodes.ILOAD, 3);
        method.visitVarInsn(Opcodes.LLOAD, 4);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                HOOKS,
                "configMouseDragged",
                "(Ljava/lang/Object;IIIJ)V",
                false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeMouseReleased(ClassWriter writer) {
        writeMouseHook(
                writer,
                "b",
                "(III)V",
                "configMouseReleased",
                "(Ljava/lang/Object;III)V");
    }

    private static void writeMouseHook(
            ClassWriter writer,
            String name,
            String descriptor,
            String hook,
            String hookDescriptor) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PROTECTED,
                name,
                descriptor,
                null,
                null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitVarInsn(Opcodes.ILOAD, 3);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, hook, hookDescriptor, false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeDoesNotPause(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "d", "()Z", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

}
