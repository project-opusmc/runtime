package org.polydevs.opusmc.forge;

import org.polydevs.opusmc.bootstrap.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Replaces the vanilla Mojang splash draw with the Opus terminal boot screen.
 *
 * <p>In verified 1.8.9 bytecode, {@code Minecraft.drawSplashScreen} is
 * {@code ave.a(Lbmj;)V}: it blits {@code textures/gui/title/mojang.png} once
 * during start-up. This transformer discards that method body and calls
 * {@link OpusBootTerminal#render()} instead, so the first thing a player sees
 * is an Opus TUI frame rather than the Mojang logo. The rest of the class is
 * copied verbatim, so Forge and OptiFine keep every other hook they installed
 * on {@code ave}.</p>
 *
 * <p>Unlike the verified telemetry patches, this transformer is intentionally
 * fail-soft. OptiFine rewrites large parts of {@code ave}; if a future
 * OptiFine build renames or inlines the splash method, we return the class
 * unchanged and let the game boot normally instead of aborting the launch.</p>
 */
final class BootTerminalTransformer implements ClassTransformer {
    private static final String MINECRAFT_CLASS = "ave";
    private static final String SPLASH_METHOD = "a";
    private static final String SPLASH_DESCRIPTOR = "(Lbmj;)V";
    private static final String BOOT_OWNER = "org/polydevs/opusmc/forge/OpusBootTerminal";

    @Override
    public String id() {
        return "opus.boot-terminal";
    }

    @Override
    public int priority() {
        // Run last so it replaces the final splash body OptiFine leaves in place.
        return 130;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytecode) {
        if (!MINECRAFT_CLASS.equals(className) || originalBytecode == null) {
            return originalBytecode;
        }

        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final int[] replacements = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                if (!SPLASH_METHOD.equals(name) || !SPLASH_DESCRIPTOR.equals(descriptor)) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }
                replacements[0]++;
                MethodVisitor rewritten =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                // Emit a fresh, self-contained body: call the Opus terminal
                // boot painter and return. The original instructions are
                // dropped by returning a detached, no-op MethodVisitor below.
                rewritten.visitCode();
                rewritten.visitMethodInsn(
                        Opcodes.INVOKESTATIC, BOOT_OWNER, "render", "()V", false);
                rewritten.visitInsn(Opcodes.RETURN);
                rewritten.visitMaxs(0, 0);
                rewritten.visitEnd();
                return new MethodVisitor(Opcodes.ASM5) {
                    // Detached from the writer: discards the vanilla body.
                };
            }
        }, 0);

        if (replacements[0] != 1) {
            // Fail soft: keep the game bootable even if the anchor moved.
            System.out.println(
                    "[OPUS/PATCH] skipped " + id() + " (splash anchor matches=" + replacements[0] + ")");
            return originalBytecode;
        }
        System.out.println("[OPUS/PATCH] applied " + id());
        return writer.toByteArray();
    }
}
