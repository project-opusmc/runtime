package org.polydevs.opusmc.patches;

import org.polydevs.opusmc.bootstrap.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Makes Minecraft 1.8.9 treat LWJGL's Retina backing store as pixels instead
 * of Cocoa points. LWJGL 2 keeps Display.getWidth()/getHeight() in logical
 * window units while getPixelScaleFactor() reports the backing ratio. Vanilla
 * Minecraft is not HiDPI-aware and otherwise allocates/renderers only the
 * lower-left fraction of the drawable when HighDPI is enabled.
 */
public final class RetinaBackbufferTransformer implements ClassTransformer {
    static final String TARGET_CLASS = "ave";
    static final String STARTUP_METHOD = "am";
    static final String RESIZE_METHOD = "i";
    static final String VOID_DESCRIPTOR = "()V";
    static final String DISPLAY_OWNER = "org/lwjgl/opengl/Display";
    static final String RETINA_OWNER = "org/polydevs/opusmc/forge/RetinaDisplay";
    static final String BACKING_WIDTH = "backingWidth";
    static final String BACKING_HEIGHT = "backingHeight";
    static final String DIMENSION_DESCRIPTOR = "()I";
    static final String CREATE_DISPLAY_METHOD = "ap";
    static final String DISPLAY_WIDTH_FIELD = "d";
    static final String DISPLAY_HEIGHT_FIELD = "e";

    @Override
    public String id() {
        return "opus.retina-backbuffer";
    }

    @Override
    public int priority() {
        return 105;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytecode) {
        if (!TARGET_CLASS.equals(className)) {
            return originalBytecode;
        }

        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] anchors = {0, 0, 0};
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (STARTUP_METHOD.equals(name) && VOID_DESCRIPTOR.equals(descriptor)) {
                    return startup(delegate, anchors);
                }
                if (RESIZE_METHOD.equals(name) && VOID_DESCRIPTOR.equals(descriptor)) {
                    return resize(delegate, anchors);
                }
                return delegate;
            }
        };
        reader.accept(visitor, 0);

        if (anchors[0] != 1 || anchors[1] != 1 || anchors[2] != 1) {
            throw new IllegalStateException(
                    "Retina backbuffer anchors changed: startup=" + anchors[0]
                            + ", width=" + anchors[1]
                            + ", height=" + anchors[2]);
        }
        System.out.println("[OPUS/PATCH] applied " + id());
        return writer.toByteArray();
    }

    private static MethodVisitor startup(MethodVisitor delegate, int[] anchors) {
        return new MethodVisitor(Opcodes.ASM5, delegate) {
            @Override
            public void visitMethodInsn(
                    int opcode,
                    String owner,
                    String name,
                    String descriptor,
                    boolean isInterface) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                if (opcode == Opcodes.INVOKESPECIAL
                        && TARGET_CLASS.equals(owner)
                        && CREATE_DISPLAY_METHOD.equals(name)
                        && VOID_DESCRIPTOR.equals(descriptor)) {
                    anchors[0]++;
                    syncDimension(DISPLAY_WIDTH_FIELD, BACKING_WIDTH);
                    syncDimension(DISPLAY_HEIGHT_FIELD, BACKING_HEIGHT);
                }
            }

            private void syncDimension(String field, String method) {
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        RETINA_OWNER,
                        method,
                        DIMENSION_DESCRIPTOR,
                        false);
                super.visitFieldInsn(Opcodes.PUTFIELD, TARGET_CLASS, field, "I");
            }
        };
    }

    private static MethodVisitor resize(MethodVisitor delegate, int[] anchors) {
        return new MethodVisitor(Opcodes.ASM5, delegate) {
            @Override
            public void visitMethodInsn(
                    int opcode,
                    String owner,
                    String name,
                    String descriptor,
                    boolean isInterface) {
                if (opcode == Opcodes.INVOKESTATIC
                        && DISPLAY_OWNER.equals(owner)
                        && DIMENSION_DESCRIPTOR.equals(descriptor)) {
                    if ("getWidth".equals(name)) {
                        anchors[1]++;
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                RETINA_OWNER,
                                BACKING_WIDTH,
                                DIMENSION_DESCRIPTOR,
                                false);
                        return;
                    }
                    if ("getHeight".equals(name)) {
                        anchors[2]++;
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                RETINA_OWNER,
                                BACKING_HEIGHT,
                                DIMENSION_DESCRIPTOR,
                                false);
                        return;
                    }
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        };
    }
}
