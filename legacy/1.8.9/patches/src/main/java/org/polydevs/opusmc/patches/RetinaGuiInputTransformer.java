package org.polydevs.opusmc.patches;

import org.polydevs.opusmc.bootstrap.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Normalizes LWJGL's absolute macOS mouse points before vanilla GUI scaling.
 * Relative camera motion, wheel input, and non-vanilla screens stay untouched.
 */
public final class RetinaGuiInputTransformer implements ClassTransformer {
    static final String VANILLA_GUI_PREFIX = "net.minecraft.client.gui.";
    static final String ENTITY_RENDERER = "bfk";
    static final String MAPPED_ENTITY_RENDERER = "net.minecraft.client.renderer.EntityRenderer";
    static final String MOUSE_OWNER = "org/lwjgl/input/Mouse";
    static final String RETINA_OWNER = "org/polydevs/opusmc/forge/RetinaDisplay";
    static final String MOUSE_COORDINATE = "mouseCoordinate";
    static final String GETTER_DESCRIPTOR = "()I";
    static final String NORMALIZER_DESCRIPTOR = "(I)I";

    @Override
    public String id() {
        return "opus.retina-gui-input";
    }

    @Override
    public int priority() {
        return 110;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytecode) {
        if (!targets(className)) {
            return originalBytecode;
        }

        ClassReader reader = new ClassReader(originalBytecode);
        int[] existing = countCalls(reader);
        if (existing[1] > 0) {
            if (existing[0] == existing[1]) {
                return originalBytecode;
            }
            throw new IllegalStateException(
                    "Retina GUI input is only partially normalized: getters="
                            + existing[0] + ", hooks=" + existing[1]);
        }
        if (existing[0] == 0) {
            return originalBytecode;
        }

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] inserted = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(
                        access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM5, delegate) {
                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String name,
                            String descriptor,
                            boolean isInterface) {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        if (isAbsoluteMouseGetter(opcode, owner, name, descriptor)) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    RETINA_OWNER,
                                    MOUSE_COORDINATE,
                                    NORMALIZER_DESCRIPTOR,
                                    false);
                            inserted[0]++;
                        }
                    }
                };
            }
        }, 0);

        if (inserted[0] != existing[0]) {
            throw new IllegalStateException(
                    "Retina GUI input anchor changed: getters="
                            + existing[0] + ", inserted=" + inserted[0]);
        }
        System.out.println("[OPUS/PATCH] applied " + id()
                + " class=" + normalize(className)
                + " hooks=" + inserted[0]);
        return writer.toByteArray();
    }

    static boolean targets(String className) {
        String normalized = normalize(className);
        return normalized.startsWith(VANILLA_GUI_PREFIX)
                || ENTITY_RENDERER.equals(normalized)
                || MAPPED_ENTITY_RENDERER.equals(normalized);
    }

    private static int[] countCalls(ClassReader reader) {
        int[] calls = {0, 0};
        reader.accept(new ClassVisitor(Opcodes.ASM5) {
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
                        if (isAbsoluteMouseGetter(opcode, owner, name, descriptor)) {
                            calls[0]++;
                        } else if (opcode == Opcodes.INVOKESTATIC
                                && RETINA_OWNER.equals(owner)
                                && MOUSE_COORDINATE.equals(name)
                                && NORMALIZER_DESCRIPTOR.equals(descriptor)) {
                            calls[1]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return calls;
    }

    private static boolean isAbsoluteMouseGetter(
            int opcode,
            String owner,
            String name,
            String descriptor) {
        if (opcode != Opcodes.INVOKESTATIC
                || !MOUSE_OWNER.equals(owner)
                || !GETTER_DESCRIPTOR.equals(descriptor)) {
            return false;
        }
        return "getX".equals(name)
                || "getY".equals(name)
                || "getEventX".equals(name)
                || "getEventY".equals(name);
    }

    private static String normalize(String className) {
        return className == null ? "" : className.replace('/', '.');
    }
}
