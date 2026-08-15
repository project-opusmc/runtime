package dev.rbw.patches;

import dev.rbw.bootstrap.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Adds timing and local input hooks to the verified 1.8.9 Minecraft loop. */
public final class MinecraftTelemetryTransformer implements ClassTransformer {
    static final String TARGET_CLASS = "ave";
    static final String FRAME_LOOP_METHOD = "av";
    static final String TICK_METHOD = "s";
    static final String LEFT_CLICK_METHOD = "aw";
    static final String VOID_DESCRIPTOR = "()V";
    static final String TELEMETRY_OWNER = "dev/rbw/core/ClientTelemetry";

    @Override
    public String id() {
        return "rbw.minecraft-telemetry";
    }

    @Override
    public int priority() {
        return 110;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytecode) {
        if (!TARGET_CLASS.equals(className)) {
            return originalBytecode;
        }

        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] hooks = {0, 0, 0, 0, 0};
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (FRAME_LOOP_METHOD.equals(name) && VOID_DESCRIPTOR.equals(descriptor)) {
                    hooks[0]++;
                    return timed(delegate, "frameStarted", "frameFinished", hooks, 1);
                }
                if (TICK_METHOD.equals(name) && VOID_DESCRIPTOR.equals(descriptor)) {
                    hooks[2]++;
                    return timed(delegate, "tickStarted", "tickFinished", hooks, 3);
                }
                if (LEFT_CLICK_METHOD.equals(name) && VOID_DESCRIPTOR.equals(descriptor)) {
                    hooks[4]++;
                    return entryHook(delegate, "attackInput");
                }
                return delegate;
            }
        };
        reader.accept(visitor, 0);

        if (hooks[0] != 1 || hooks[1] == 0 || hooks[2] != 1 || hooks[3] == 0 || hooks[4] != 1) {
            throw new IllegalStateException(
                    "Minecraft telemetry anchors changed: frame=" + hooks[0] + "/" + hooks[1]
                            + ", tick=" + hooks[2] + "/" + hooks[3]
                            + ", leftClick=" + hooks[4]);
        }
        System.out.println("[RBW/PATCH] applied " + id());
        return writer.toByteArray();
    }

    private static MethodVisitor timed(
            MethodVisitor delegate,
            String startedHook,
            String finishedHook,
            int[] hooks,
            int returnCountIndex) {
        return new MethodVisitor(Opcodes.ASM5, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();
                invoke(startedHook);
            }

            @Override
            public void visitInsn(int opcode) {
                if (opcode == Opcodes.RETURN) {
                    hooks[returnCountIndex]++;
                    invoke(finishedHook);
                }
                super.visitInsn(opcode);
            }

            private void invoke(String hook) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, TELEMETRY_OWNER, hook, VOID_DESCRIPTOR, false);
            }
        };
    }

    private static MethodVisitor entryHook(MethodVisitor delegate, String hook) {
        return new MethodVisitor(Opcodes.ASM5, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();
                super.visitMethodInsn(Opcodes.INVOKESTATIC, TELEMETRY_OWNER, hook, VOID_DESCRIPTOR, false);
            }
        };
    }
}
