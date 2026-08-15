package dev.rbw.patches;

import dev.rbw.bootstrap.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Observes the 1.8.9 attack packet path and related inbound status signal. */
public final class CombatTelemetryTransformer implements ClassTransformer {
    static final String PLAYER_CONTROLLER_CLASS = "bda";
    static final String ATTACK_METHOD = "a";
    static final String ATTACK_DESCRIPTOR = "(Lwn;Lpk;)V";
    static final String NET_HANDLER_CLASS = "bcy";
    static final String ENTITY_STATUS_DESCRIPTOR = "(Lgi;)V";
    static final String TELEMETRY_OWNER = "dev/rbw/core/ClientTelemetry";

    @Override
    public String id() {
        return "rbw.combat-telemetry";
    }

    @Override
    public int priority() {
        return 120;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytecode) {
        if (PLAYER_CONTROLLER_CLASS.equals(className)) {
            return transformAttackController(originalBytecode);
        }
        if (NET_HANDLER_CLASS.equals(className)) {
            return transformNetHandler(originalBytecode);
        }
        return originalBytecode;
    }

    private static byte[] transformAttackController(byte[] originalBytecode) {
        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] matches = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!ATTACK_METHOD.equals(name) || !ATTACK_DESCRIPTOR.equals(descriptor)) {
                    return delegate;
                }
                matches[0]++;
                return new MethodVisitor(Opcodes.ASM5, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                TELEMETRY_OWNER,
                                "attackPacketQueued",
                                "()V",
                                false);
                    }
                };
            }
        }, 0);
        if (matches[0] != 1) {
            throw new IllegalStateException("Attack packet anchor changed: " + matches[0]);
        }
        System.out.println("[RBW/PATCH] applied rbw.combat-telemetry class=bda");
        return writer.toByteArray();
    }

    private static byte[] transformNetHandler(byte[] originalBytecode) {
        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] matches = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!ATTACK_METHOD.equals(name) || !ENTITY_STATUS_DESCRIPTOR.equals(descriptor)) {
                    return delegate;
                }
                matches[0]++;
                return new MethodVisitor(Opcodes.ASM5, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(
                                Opcodes.INVOKEVIRTUAL,
                                "gi",
                                "a",
                                "()B",
                                false);
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                TELEMETRY_OWNER,
                                "entityStatus",
                                "(I)V",
                                false);
                    }
                };
            }
        }, 0);
        if (matches[0] != 1) {
            throw new IllegalStateException("Entity status anchor changed: " + matches[0]);
        }
        System.out.println("[RBW/PATCH] applied rbw.combat-telemetry class=bcy");
        return writer.toByteArray();
    }
}
