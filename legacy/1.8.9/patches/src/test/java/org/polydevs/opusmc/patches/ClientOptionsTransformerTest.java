package org.polydevs.opusmc.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class ClientOptionsTransformerTest {
    private static final String HOOKS = "org/polydevs/opusmc/core/ClientUiHooks";

    @Test
    void injectsTheKeyboardHookAtTheSecondKeyBindingDispatch() {
        byte[] transformed = new ClientOptionsTransformer().transform(
                ClientOptionsTransformer.MINECRAFT_CLASS, minecraftTickClass());

        assertEquals(2, TelemetryTransformerTestSupport.countMethodCalls(
                transformed,
                Opcodes.INVOKESTATIC,
                ClientOptionsTransformer.KEY_BINDING_OWNER,
                "a",
                ClientOptionsTransformer.KEY_BINDING_EVENT_DESCRIPTOR));
        assertEquals(1, TelemetryTransformerTestSupport.countStaticCalls(
                transformed,
                HOOKS,
                "onKeyboardEvent",
                "(IZLjava/lang/Object;)V"));
    }

    @Test
    void rejectsMinecraftInputWhenTheTwoDispatchesAreNotPresent() {
        byte[] incomplete = TelemetryTransformerTestSupport.classWithMethods(
                ClientOptionsTransformer.MINECRAFT_CLASS,
                TelemetryTransformerTestSupport.method(
                        ClientOptionsTransformer.MINECRAFT_TICK_METHOD,
                        ClientOptionsTransformer.VOID_DESCRIPTOR));

        assertThrows(
                IllegalStateException.class,
                () -> new ClientOptionsTransformer().transform(
                        ClientOptionsTransformer.MINECRAFT_CLASS, incomplete));
    }

    @Test
    void injectsThePauseButtonAndVoidClickBridge() {
        byte[] original = TelemetryTransformerTestSupport.classWithMethods(
                ClientOptionsTransformer.PAUSE_MENU_CLASS,
                TelemetryTransformerTestSupport.method(
                        ClientOptionsTransformer.PAUSE_MENU_INIT_METHOD,
                        ClientOptionsTransformer.VOID_DESCRIPTOR),
                TelemetryTransformerTestSupport.method(
                        ClientOptionsTransformer.PAUSE_MENU_ACTION_METHOD,
                        ClientOptionsTransformer.PAUSE_MENU_ACTION_DESCRIPTOR));

        byte[] transformed = new ClientOptionsTransformer().transform(
                ClientOptionsTransformer.PAUSE_MENU_CLASS, original);

        assertEquals(1, TelemetryTransformerTestSupport.countMethodCalls(
                transformed,
                Opcodes.INVOKESPECIAL,
                "avs",
                "<init>",
                "(IIILjava/lang/String;)V"));
        assertEquals(1, TelemetryTransformerTestSupport.countStaticCalls(
                transformed,
                HOOKS,
                "onPauseMenuButton",
                "(Ljava/lang/Object;Ljava/lang/Object;)V"));
        assertTrue(containsIntInstruction(transformed, Opcodes.BIPUSH, 56));
    }

    @Test
    void rejectsPauseMenuWhenTheActionAnchorIsMissing() {
        byte[] incomplete = TelemetryTransformerTestSupport.classWithMethods(
                ClientOptionsTransformer.PAUSE_MENU_CLASS,
                TelemetryTransformerTestSupport.method(
                        ClientOptionsTransformer.PAUSE_MENU_INIT_METHOD,
                        ClientOptionsTransformer.VOID_DESCRIPTOR));

        assertThrows(
                IllegalStateException.class,
                () -> new ClientOptionsTransformer().transform(
                        ClientOptionsTransformer.PAUSE_MENU_CLASS, incomplete));
    }

    @Test
    void injectsTheHudRenderHook() {
        byte[] original = TelemetryTransformerTestSupport.classWithMethods(
                ClientOptionsTransformer.GUI_INGAME_CLASS,
                TelemetryTransformerTestSupport.method(
                        ClientOptionsTransformer.GUI_INGAME_RENDER_METHOD,
                        ClientOptionsTransformer.GUI_INGAME_RENDER_DESCRIPTOR));

        byte[] transformed = new ClientOptionsTransformer().transform(
                ClientOptionsTransformer.GUI_INGAME_CLASS, original);

        assertEquals(1, TelemetryTransformerTestSupport.countStaticCalls(
                transformed, HOOKS, "renderHud", "(Ljava/lang/Object;)V"));
    }

    @Test
    void generatesAChildLoadedGuiScreenWithTheExpectedHooks() {
        byte[] bytecode = OpusClientOptionsScreenFactory.screenBytecode();
        ClassReader reader = new ClassReader(bytecode);

        assertEquals("axu", reader.getSuperName());
        assertEquals(1, TelemetryTransformerTestSupport.countStaticCalls(
                bytecode, HOOKS, "openConfigScreen", "(Ljava/lang/Object;)V"));
        assertEquals(1, TelemetryTransformerTestSupport.countStaticCalls(
                bytecode, HOOKS, "closeConfigScreen", "(Ljava/lang/Object;)V"));
        assertEquals(1, TelemetryTransformerTestSupport.countStaticCalls(
                bytecode, HOOKS, "renderConfigScreen", "(Ljava/lang/Object;IIF)V"));
        assertEquals(1, TelemetryTransformerTestSupport.countStaticCalls(
                bytecode, HOOKS, "configMouseClicked", "(Ljava/lang/Object;III)V"));
        assertEquals(1, TelemetryTransformerTestSupport.countStaticCalls(
                bytecode, HOOKS, "configMouseDragged", "(Ljava/lang/Object;IIIJ)V"));
        assertEquals(1, TelemetryTransformerTestSupport.countStaticCalls(
                bytecode, HOOKS, "configMouseReleased", "(Ljava/lang/Object;III)V"));
        assertEquals(1, TelemetryTransformerTestSupport.countStaticCalls(
                bytecode, HOOKS, "configKeyTyped", "(Ljava/lang/Object;CI)V"));
        assertEquals(0, TelemetryTransformerTestSupport.countMethodCalls(
                bytecode, Opcodes.INVOKESPECIAL, "avs", "<init>", "(IIILjava/lang/String;)V"));
        assertTrue(hasMethod(bytecode, "opusClose", "()V"));
    }

    @Test
    void replacesTheLaunchClassLoaderPlaceholderWithTheGuiScreen() {
        byte[] placeholder = TelemetryTransformerTestSupport.classWithMethods(
                "org/polydevs/opusmc/client/gui/OpusClientOptionsScreen");

        byte[] transformed = new ClientOptionsTransformer().transform(
                OpusClientOptionsScreenFactory.CLASS_NAME, placeholder);

        assertEquals("axu", new ClassReader(transformed).getSuperName());
    }

    private static byte[] minecraftTickClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                ClientOptionsTransformer.MINECRAFT_CLASS,
                null,
                "java/lang/Object",
                null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                ClientOptionsTransformer.MINECRAFT_TICK_METHOD,
                ClientOptionsTransformer.VOID_DESCRIPTOR,
                null,
                null);
        method.visitCode();
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                ClientOptionsTransformer.KEYBOARD_OWNER,
                ClientOptionsTransformer.KEYBOARD_NEXT_METHOD,
                ClientOptionsTransformer.KEYBOARD_NEXT_DESCRIPTOR,
                false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                ClientOptionsTransformer.KEY_BINDING_OWNER,
                "a",
                ClientOptionsTransformer.KEY_BINDING_EVENT_DESCRIPTOR,
                false);
        method.visitIntInsn(Opcodes.BIPUSH, 54);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                ClientOptionsTransformer.KEY_BINDING_OWNER,
                "a",
                ClientOptionsTransformer.KEY_BINDING_EVENT_DESCRIPTOR,
                false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static boolean containsIntInstruction(byte[] bytecode, int expectedOpcode, int expectedOperand) {
        AtomicBoolean found = new AtomicBoolean();
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
                    public void visitIntInsn(int opcode, int operand) {
                        if (opcode == expectedOpcode && operand == expectedOperand) {
                            found.set(true);
                        }
                    }
                };
            }
        }, 0);
        return found.get();
    }

    private static boolean hasMethod(byte[] bytecode, String expectedName, String expectedDescriptor) {
        AtomicBoolean found = new AtomicBoolean();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                if (expectedName.equals(name) && expectedDescriptor.equals(descriptor)) {
                    found.set(true);
                }
                return null;
            }
        }, 0);
        return found.get();
    }
}
