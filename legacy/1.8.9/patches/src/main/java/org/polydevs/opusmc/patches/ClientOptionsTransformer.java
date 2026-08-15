package org.polydevs.opusmc.patches;

import org.polydevs.opusmc.bootstrap.ClassTransformer;
import org.polydevs.opusmc.core.ClientUiHooks;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Adds the Opus Client Options entry point to the verified 1.8.9 UI paths.
 *
 * <p>Every injected hook is stack-neutral and branch-free. That matters for
 * the original Java 8 classes because their existing StackMapTable remains
 * valid after a {@link ClassWriter#COMPUTE_MAXS} rewrite.</p>
 */
public final class ClientOptionsTransformer implements ClassTransformer {
    static final String MINECRAFT_CLASS = "ave";
    static final String MINECRAFT_TICK_METHOD = "s";
    static final String PAUSE_MENU_CLASS = "axp";
    static final String GUI_INGAME_CLASS = "avo";
    static final String PAUSE_MENU_INIT_METHOD = "b";
    static final String PAUSE_MENU_ACTION_METHOD = "a";
    static final String GUI_INGAME_RENDER_METHOD = "a";
    static final String VOID_DESCRIPTOR = "()V";
    static final String PAUSE_MENU_ACTION_DESCRIPTOR = "(Lavs;)V";
    static final String GUI_INGAME_RENDER_DESCRIPTOR = "(F)V";
    static final String KEY_BINDING_OWNER = "avb";
    static final String KEY_BINDING_EVENT_DESCRIPTOR = "(IZ)V";
    static final String KEYBOARD_OWNER = "org/lwjgl/input/Keyboard";
    static final String KEYBOARD_NEXT_METHOD = "next";
    static final String KEYBOARD_NEXT_DESCRIPTOR = "()Z";
    static final String HOOKS_OWNER = "org/polydevs/opusmc/core/ClientUiHooks";

    @Override
    public String id() {
        return "opus.client-options";
    }

    @Override
    public int priority() {
        return 112;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytecode) {
        if (OpusClientOptionsScreenFactory.CLASS_NAME.equals(className)) {
            return OpusClientOptionsScreenFactory.screenBytecode();
        }
        if (MINECRAFT_CLASS.equals(className)) {
            return transformMinecraftInput(originalBytecode);
        }
        if (PAUSE_MENU_CLASS.equals(className)) {
            return transformPauseMenu(originalBytecode);
        }
        if (GUI_INGAME_CLASS.equals(className)) {
            return transformHudRender(originalBytecode);
        }
        return originalBytecode;
    }

    private static byte[] transformMinecraftInput(byte[] originalBytecode) {
        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] matches = {0, 0, 0, 0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!MINECRAFT_TICK_METHOD.equals(name) || !VOID_DESCRIPTOR.equals(descriptor)) {
                    return delegate;
                }
                matches[0]++;
                return new MethodVisitor(Opcodes.ASM5, delegate) {
                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String methodName,
                            String methodDescriptor,
                            boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && KEYBOARD_OWNER.equals(owner)
                                && KEYBOARD_NEXT_METHOD.equals(methodName)
                                && KEYBOARD_NEXT_DESCRIPTOR.equals(methodDescriptor)) {
                            matches[1]++;
                        }
                        if (opcode == Opcodes.INVOKESTATIC
                                && KEY_BINDING_OWNER.equals(owner)
                                && "a".equals(methodName)
                                && KEY_BINDING_EVENT_DESCRIPTOR.equals(methodDescriptor)) {
                            matches[2]++;
                            if (matches[2] == 2) {
                                matches[3]++;
                                super.visitInsn(Opcodes.DUP2);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        HOOKS_OWNER,
                                        "onKeyboardEvent",
                                        "(IZLjava/lang/Object;)V",
                                        false);
                            }
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                    }
                };
            }
        }, 0);
        if (matches[0] != 1 || matches[1] != 1 || matches[2] != 2 || matches[3] != 1) {
            throw new IllegalStateException(
                    "Client Options keyboard anchor changed: tick=" + matches[0]
                            + ", keyboardNext=" + matches[1]
                            + ", keyEvents=" + matches[2]
                            + ", hooks=" + matches[3]);
        }
        System.out.println("[OPUS/PATCH] applied " + "opus.client-options input=ave");
        return writer.toByteArray();
    }

    private static byte[] transformPauseMenu(byte[] originalBytecode) {
        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] matches = {0, 0, 0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (PAUSE_MENU_INIT_METHOD.equals(name) && VOID_DESCRIPTOR.equals(descriptor)) {
                    matches[0]++;
                    return new MethodVisitor(Opcodes.ASM5, delegate) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                matches[1]++;
                                addPauseMenuButton(this);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                if (PAUSE_MENU_ACTION_METHOD.equals(name)
                        && PAUSE_MENU_ACTION_DESCRIPTOR.equals(descriptor)) {
                    matches[2]++;
                    return new MethodVisitor(Opcodes.ASM5, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    HOOKS_OWNER,
                                    "onPauseMenuButton",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V",
                                    false);
                        }
                    };
                }
                return delegate;
            }
        }, 0);
        if (matches[0] != 1 || matches[1] != 1 || matches[2] != 1) {
            throw new IllegalStateException(
                    "Client Options pause-menu anchor changed: init=" + matches[0]
                            + "/" + matches[1] + ", action=" + matches[2]);
        }
        System.out.println("[OPUS/PATCH] applied opus.client-options pauseMenu=axp");
        return writer.toByteArray();
    }

    private static byte[] transformHudRender(byte[] originalBytecode) {
        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] matches = {0, 0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!GUI_INGAME_RENDER_METHOD.equals(name)
                        || !GUI_INGAME_RENDER_DESCRIPTOR.equals(descriptor)) {
                    return delegate;
                }
                matches[0]++;
                return new MethodVisitor(Opcodes.ASM5, delegate) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            matches[1]++;
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    HOOKS_OWNER,
                                    "renderHud",
                                    "(Ljava/lang/Object;)V",
                                    false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        if (matches[0] != 1 || matches[1] != 1) {
            throw new IllegalStateException(
                    "Client Options HUD anchor changed: render=" + matches[0]
                            + "/" + matches[1]);
        }
        System.out.println("[OPUS/PATCH] applied opus.client-options hud=avo");
        return writer.toByteArray();
    }

    private static void addPauseMenuButton(MethodVisitor method) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, "axu", "n", "Ljava/util/List;");
        method.visitTypeInsn(Opcodes.NEW, "avs");
        method.visitInsn(Opcodes.DUP);
        method.visitIntInsn(Opcodes.SIPUSH, ClientUiHooks.PAUSE_MENU_BUTTON_ID);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, "axu", "l", "I");
        method.visitInsn(Opcodes.ICONST_2);
        method.visitInsn(Opcodes.IDIV);
        method.visitIntInsn(Opcodes.BIPUSH, 100);
        method.visitInsn(Opcodes.ISUB);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, "axu", "m", "I");
        method.visitInsn(Opcodes.ICONST_4);
        method.visitInsn(Opcodes.IDIV);
        method.visitIntInsn(Opcodes.BIPUSH, 56);
        method.visitInsn(Opcodes.IADD);
        method.visitLdcInsn("Client Options");
        method.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "avs",
                "<init>",
                "(IIILjava/lang/String;)V",
                false);
        method.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "add",
                "(Ljava/lang/Object;)Z",
                true);
        method.visitInsn(Opcodes.POP);
    }
}
