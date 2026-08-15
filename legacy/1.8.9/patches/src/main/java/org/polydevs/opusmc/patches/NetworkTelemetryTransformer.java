package org.polydevs.opusmc.patches;

import org.polydevs.opusmc.bootstrap.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Adds payload-free connection and packet-flow counters to NetworkManager. */
public final class NetworkTelemetryTransformer implements ClassTransformer {
    static final String TARGET_CLASS = "ek";
    static final String TELEMETRY_OWNER = "org/polydevs/opusmc/core/ClientTelemetry";
    private static final String CHANNEL_CONTEXT = "Lio/netty/channel/ChannelHandlerContext;";
    private static final String PACKET = "Lff;";
    private static final String CHANNEL_LIFECYCLE_DESCRIPTOR = "(" + CHANNEL_CONTEXT + ")V";
    private static final String OUTBOUND_PACKET_DESCRIPTOR = "(" + PACKET + ")V";
    private static final String INBOUND_PACKET_DESCRIPTOR = "(" + CHANNEL_CONTEXT + PACKET + ")V";

    @Override
    public String id() {
        return "opus.network-telemetry";
    }

    @Override
    public int priority() {
        return 130;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytecode) {
        if (!TARGET_CLASS.equals(className)) {
            return originalBytecode;
        }

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
                if ("channelActive".equals(name) && CHANNEL_LIFECYCLE_DESCRIPTOR.equals(descriptor)) {
                    matches[0]++;
                    return entryHook(delegate, "connectionOpened", -1);
                }
                if ("channelInactive".equals(name) && CHANNEL_LIFECYCLE_DESCRIPTOR.equals(descriptor)) {
                    matches[1]++;
                    return entryHook(delegate, "connectionClosed", -1);
                }
                if ("a".equals(name) && OUTBOUND_PACKET_DESCRIPTOR.equals(descriptor)) {
                    matches[2]++;
                    return entryHook(delegate, "outboundPacket", 1);
                }
                if ("a".equals(name) && INBOUND_PACKET_DESCRIPTOR.equals(descriptor)) {
                    matches[3]++;
                    return entryHook(delegate, "inboundPacket", 2);
                }
                return delegate;
            }
        }, 0);
        for (int match : matches) {
            if (match != 1) {
                throw new IllegalStateException("Network telemetry anchor changed");
            }
        }
        System.out.println("[OPUS/PATCH] applied " + id());
        return writer.toByteArray();
    }

    private static MethodVisitor entryHook(MethodVisitor delegate, String hook, int argumentIndex) {
        return new MethodVisitor(Opcodes.ASM5, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();
                if (argumentIndex < 0) {
                    super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            TELEMETRY_OWNER,
                            hook,
                            "()V",
                            false);
                } else {
                    super.visitVarInsn(Opcodes.ALOAD, argumentIndex);
                    super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            TELEMETRY_OWNER,
                            hook,
                            "(Ljava/lang/Object;)V",
                            false);
                }
            }
        };
    }
}
