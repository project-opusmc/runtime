package org.polydevs.opusmc.standalone;

import org.polydevs.opusmc.bootstrap.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Injects event dispatch hooks into Minecraft 1.8.9 classes so that client UI and
 * overlay systems receive ticks, renders, GUI changes, and overlay events in standalone mode.
 */
final class StandaloneEventHookTransformer implements ClassTransformer {
    private static final String MINECRAFT_CLASS = "ave";
    private static final String GUI_SCREEN_CLASS = "axu";
    private static final String ENTITY_RENDERER_CLASS = "bfk";
    private static final String GUI_INGAME_CLASS = "avo";

    private static final String HOOKS_OWNER = "org/polydevs/opusmc/standalone/OpusEventHooks";

    @Override
    public String id() {
        return "opus.standalone-event-hooks";
    }

    @Override
    public int priority() {
        return 108;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytecode) {
        if (MINECRAFT_CLASS.equals(className)) {
            return transformMinecraft(originalBytecode);
        } else if (ENTITY_RENDERER_CLASS.equals(className)) {
            return transformRenderer(originalBytecode);
        } else if (GUI_INGAME_CLASS.equals(className)) {
            return transformGuiIngame(originalBytecode);
        } else if ("avi".equals(className)) {
            return transformLoadingScreen(originalBytecode);
        }
        return originalBytecode;
    }

    private byte[] transformLoadingScreen(byte[] originalBytecode) {
        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final int[] anchors = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"a".equals(name) || !"(I)V".equals(descriptor)) return delegate;
                return new MethodNode(Opcodes.ASM5, access, name, descriptor, signature, exceptions) {
                    @Override
                    public void visitEnd() {
                        for (AbstractInsnNode node = instructions.getFirst(); node != null; node = node.getNext()) {
                            if (node instanceof MethodInsnNode
                                    && HOOKS_OWNER.equals(((MethodInsnNode) node).owner)
                                    && "onLoadingScreen".equals(((MethodInsnNode) node).name)) {
                                anchors[0]++;
                                accept(delegate);
                                return;
                            }
                        }
                        for (AbstractInsnNode node = instructions.getFirst(); node != null; node = node.getNext()) {
                            if (node.getOpcode() == Opcodes.NEW && node instanceof TypeInsnNode
                                    && "avr".equals(((TypeInsnNode) node).desc)) {
                                // This anchor follows vanilla's running/loading-success
                                // guard and its 100 ms throttle, before any native drawing.
                                InsnList hook = new InsnList();
                                LabelNode nativeDrawing = new LabelNode();
                                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                                hook.add(new FieldInsnNode(Opcodes.GETFIELD, "avi", "b", "Lave;"));
                                hook.add(new FieldInsnNode(Opcodes.GETFIELD, "ave", "m", "Laxu;"));
                                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_OWNER,
                                        "onLoadingScreen", "(Ljava/lang/Object;)Z", false));
                                hook.add(new JumpInsnNode(Opcodes.IFEQ, nativeDrawing));
                                hook.add(new InsnNode(Opcodes.RETURN));
                                hook.add(nativeDrawing);
                                hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                                instructions.insertBefore(node, hook);
                                anchors[0]++;
                                break;
                            }
                        }
                        accept(delegate);
                    }
                };
            }
        }, 0);
        if (anchors[0] != 1) {
            throw new IllegalStateException("Minecraft 1.8.9 loading presentation anchor changed");
        }
        return writer.toByteArray();
    }

    private byte[] transformMinecraft(byte[] originalBytecode) {
        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                // runTick: s()V
                if ("s".equals(name) && "()V".equals(descriptor)) {
                    return new MethodVisitor(Opcodes.ASM5, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    HOOKS_OWNER,
                                    "onClientTickStart",
                                    "()V",
                                    false);
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        HOOKS_OWNER,
                                        "onClientTickEnd",
                                        "()V",
                                        false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                // displayGuiScreen: a(Laxu;)V
                if ("a".equals(name) && "(Laxu;)V".equals(descriptor)) {
                    final MethodVisitor outMv = delegate;
                    return new MethodNode(Opcodes.ASM5, access, name, descriptor, signature, exceptions) {
                        @Override
                        public void visitEnd() {
                            boolean injected = false;
                            for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
                                if (insn.getOpcode() == Opcodes.INSTANCEOF
                                        && ("aya".equals(((TypeInsnNode) insn).desc)
                                                || "net/minecraft/client/gui/GuiMainMenu".equals(((TypeInsnNode) insn).desc))) {
                                    AbstractInsnNode target = insn.getPrevious();
                                    if (target != null && target.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) target).var == 1) {
                                        String targetGuiScreen = "aya".equals(((TypeInsnNode) insn).desc)
                                                ? GUI_SCREEN_CLASS
                                                : "net/minecraft/client/gui/GuiScreen";
                                        InsnList hook = new InsnList();
                                        LabelNode notCancelled = new LabelNode();
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                                        hook.add(new MethodInsnNode(
                                                Opcodes.INVOKESTATIC,
                                                HOOKS_OWNER,
                                                "onGuiOpen",
                                                "(Ljava/lang/Object;)Ljava/lang/Object;",
                                                false));
                                        hook.add(new InsnNode(Opcodes.DUP));
                                        hook.add(new FieldInsnNode(
                                                Opcodes.GETSTATIC,
                                                HOOKS_OWNER,
                                                "GUI_CANCELLED",
                                                "Ljava/lang/Object;"));
                                        hook.add(new JumpInsnNode(Opcodes.IF_ACMPNE, notCancelled));
                                        hook.add(new InsnNode(Opcodes.POP));
                                        hook.add(new InsnNode(Opcodes.RETURN));
                                        hook.add(notCancelled);
                                        hook.add(new TypeInsnNode(Opcodes.CHECKCAST, targetGuiScreen));
                                        hook.add(new VarInsnNode(Opcodes.ASTORE, 1));

                                        instructions.insertBefore(target, hook);
                                        injected = true;
                                        break;
                                    }
                                }
                            }
                            if (!injected) {
                                System.err.println("[OPUS/STANDALONE] WARNING: Failed to find insertion point before instanceof aya in displayGuiScreen, injecting at start");
                                InsnList hook = new InsnList();
                                LabelNode notCancelled = new LabelNode();
                                hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                                hook.add(new MethodInsnNode(
                                        Opcodes.INVOKESTATIC,
                                        HOOKS_OWNER,
                                        "onGuiOpen",
                                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                                        false));
                                hook.add(new InsnNode(Opcodes.DUP));
                                hook.add(new FieldInsnNode(
                                        Opcodes.GETSTATIC,
                                        HOOKS_OWNER,
                                        "GUI_CANCELLED",
                                        "Ljava/lang/Object;"));
                                hook.add(new JumpInsnNode(Opcodes.IF_ACMPNE, notCancelled));
                                hook.add(new InsnNode(Opcodes.POP));
                                hook.add(new InsnNode(Opcodes.RETURN));
                                hook.add(notCancelled);
                                hook.add(new TypeInsnNode(Opcodes.CHECKCAST, GUI_SCREEN_CLASS));
                                hook.add(new VarInsnNode(Opcodes.ASTORE, 1));
                                instructions.insert(hook);
                            }
                            accept(outMv);
                        }
                    };
                }
                // startGame: am()V
                if ("am".equals(name) && "()V".equals(descriptor)) {
                    return new MethodVisitor(Opcodes.ASM5, delegate) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        HOOKS_OWNER,
                                        "onGameStarted",
                                        "()V",
                                        false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return delegate;
            }
        }, 0);
        System.out.println("[OPUS/PATCH] applied " + id() + " to " + MINECRAFT_CLASS);
        return writer.toByteArray();
    }

    private byte[] transformRenderer(byte[] originalBytecode) {
        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                // updateCameraAndRender: a(FJ)V
                if ("a".equals(name) && "(FJ)V".equals(descriptor)) {
                    return new MethodVisitor(Opcodes.ASM5, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.FLOAD, 1);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    HOOKS_OWNER,
                                    "onRenderTickStart",
                                    "(F)V",
                                    false);
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                super.visitVarInsn(Opcodes.FLOAD, 1);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        HOOKS_OWNER,
                                        "onRenderTickEnd",
                                        "(F)V",
                                        false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return delegate;
            }
        }, 0);
        System.out.println("[OPUS/PATCH] applied " + id() + " to " + ENTITY_RENDERER_CLASS);
        return writer.toByteArray();
    }

    private byte[] transformGuiIngame(byte[] originalBytecode) {
        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                // renderGameOverlay: a(F)V
                if ("a".equals(name) && "(F)V".equals(descriptor)) {
                    return new MethodVisitor(Opcodes.ASM5, delegate) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                super.visitVarInsn(Opcodes.FLOAD, 1);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        HOOKS_OWNER,
                                        "onRenderOverlay",
                                        "(F)V",
                                        false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return delegate;
            }
        }, 0);
        System.out.println("[OPUS/PATCH] applied " + id() + " to " + GUI_INGAME_CLASS);
        return writer.toByteArray();
    }
}
