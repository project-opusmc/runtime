package dev.rbw.patches;

import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Small bytecode fixtures and assertions shared by telemetry transformer tests. */
final class TelemetryTransformerTestSupport {
    static final String TELEMETRY_OWNER = "dev/rbw/core/ClientTelemetry";

    private TelemetryTransformerTestSupport() {}

    static MethodSpec method(String name, String descriptor) {
        return new MethodSpec(name, descriptor, false);
    }

    static MethodSpec methodWithTwoReturns(String name, String descriptor) {
        return new MethodSpec(name, descriptor, true);
    }

    static byte[] classWithMethods(String internalName, MethodSpec... methods) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        for (MethodSpec spec : methods) {
            if (spec != null) {
                writeMethod(writer, spec);
            }
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    static int countTelemetryCalls(byte[] bytecode, String hook, String descriptor) {
        return countStaticCalls(bytecode, TELEMETRY_OWNER, hook, descriptor);
    }

    static int countStaticCalls(byte[] bytecode, String expectedOwner, String hook, String descriptor) {
        AtomicInteger calls = new AtomicInteger();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String methodDescriptor,
                    String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String invocationOwner,
                            String name,
                            String invocationDescriptor,
                            boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && expectedOwner.equals(invocationOwner)
                                && hook.equals(name)
                                && descriptor.equals(invocationDescriptor)) {
                            calls.incrementAndGet();
                        }
                    }
                };
            }
        }, 0);
        return calls.get();
    }

    static int countMethodCalls(byte[] bytecode, int expectedOpcode, String owner, String name, String descriptor) {
        AtomicInteger calls = new AtomicInteger();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String methodName,
                    String methodDescriptor,
                    String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String invocationOwner,
                            String invocationName,
                            String invocationDescriptor,
                            boolean isInterface) {
                        if (opcode == expectedOpcode
                                && owner.equals(invocationOwner)
                                && name.equals(invocationName)
                                && descriptor.equals(invocationDescriptor)) {
                            calls.incrementAndGet();
                        }
                    }
                };
            }
        }, 0);
        return calls.get();
    }

    static int countTelemetryObjectCallsWithAload(
            byte[] bytecode, String hook, int expectedArgumentIndex) {
        AtomicInteger calls = new AtomicInteger();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String methodDescriptor,
                    String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    private int lastOpcode = -1;
                    private int lastVariable = -1;

                    @Override
                    public void visitVarInsn(int opcode, int variable) {
                        lastOpcode = opcode;
                        lastVariable = variable;
                    }

                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String name,
                            String descriptor,
                            boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && TELEMETRY_OWNER.equals(owner)
                                && hook.equals(name)
                                && "(Ljava/lang/Object;)V".equals(descriptor)
                                && lastOpcode == Opcodes.ALOAD
                                && lastVariable == expectedArgumentIndex) {
                            calls.incrementAndGet();
                        }
                        lastOpcode = -1;
                        lastVariable = -1;
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        lastOpcode = -1;
                        lastVariable = -1;
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        lastOpcode = -1;
                        lastVariable = -1;
                    }

                    @Override
                    public void visitLabel(Label label) {
                        lastOpcode = -1;
                        lastVariable = -1;
                    }
                };
            }
        }, 0);
        return calls.get();
    }

    private static void writeMethod(ClassWriter writer, MethodSpec spec) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC, spec.name, spec.descriptor, null, null);
        method.visitCode();
        if (spec.hasTwoReturns) {
            Label secondReturn = new Label();
            method.visitInsn(Opcodes.ICONST_0);
            method.visitJumpInsn(Opcodes.IFEQ, secondReturn);
            method.visitInsn(Opcodes.RETURN);
            method.visitLabel(secondReturn);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    static final class MethodSpec {
        private final String name;
        private final String descriptor;
        private final boolean hasTwoReturns;

        private MethodSpec(String name, String descriptor, boolean hasTwoReturns) {
            this.name = name;
            this.descriptor = descriptor;
            this.hasTwoReturns = hasTwoReturns;
        }
    }
}
