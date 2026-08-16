package org.polydevs.opusmc.patches;

import org.polydevs.opusmc.bootstrap.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class WindowTitleTransformer implements ClassTransformer {
    static final String TARGET_CLASS = "ave";
    static final String VANILLA_TITLE = "Minecraft 1.8.9";
    static final String DEFAULT_TITLE = "Opus Client";
    static final String TITLE_PROPERTY = "opus.window.title";
    static final int MAX_TITLE_LENGTH = 120;

    private final String title;

    public WindowTitleTransformer() {
        this(resolveConfiguredTitle());
    }

    WindowTitleTransformer(String title) {
        this.title = sanitize(title);
    }

    /**
     * Resolve the per-instance window title from a JVM system property so each
     * concurrently launched instance can carry its own identity, for example
     * "Opus Client - [OFFICIAL] zvwgvx". A missing or blank value falls back to
     * the shared default so a plain launch still reads "Opus Client".
     */
    static String resolveConfiguredTitle() {
        return sanitize(System.getProperty(TITLE_PROPERTY));
    }

    /**
     * Keep the injected string a single, printable, length-bounded line. The
     * value becomes a decompiled string constant in a live UI, so control
     * characters and unbounded input must never reach the game.
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return DEFAULT_TITLE;
        }
        // Collapse any control character or whitespace run into a single space
        // so the injected constant is one printable line, then bound its length.
        // This mirrors the launcher-side normalization for defense in depth.
        StringBuilder builder = new StringBuilder(Math.min(raw.length(), MAX_TITLE_LENGTH));
        boolean pendingSpace = false;
        for (int index = 0; index < raw.length() && builder.length() < MAX_TITLE_LENGTH; index++) {
            char character = raw.charAt(index);
            boolean isSpace = character <= 0x20 || character == 0x7f;
            if (isSpace) {
                pendingSpace = builder.length() > 0;
                continue;
            }
            if (pendingSpace && builder.length() < MAX_TITLE_LENGTH) {
                builder.append(' ');
                pendingSpace = false;
            }
            builder.append(character);
        }
        String cleaned = builder.toString();
        return cleaned.isEmpty() ? DEFAULT_TITLE : cleaned;
    }

    @Override
    public String id() {
        return "opus.window-title";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytecode) {
        if (!TARGET_CLASS.equals(className)) {
            return originalBytecode;
        }

        ClassReader reader = new ClassReader(originalBytecode);
        ClassWriter writer = new ClassWriter(reader, 0);
        int[] replacements = {0};
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM5, delegate) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (VANILLA_TITLE.equals(value)) {
                            replacements[0]++;
                            super.visitLdcInsn(title);
                        } else {
                            super.visitLdcInsn(value);
                        }
                    }
                };
            }
        };
        reader.accept(visitor, 0);

        if (replacements[0] != 1) {
            throw new IllegalStateException(
                    "Window title patch expected exactly one anchor but found " + replacements[0]);
        }
        System.out.println("[OPUS/PATCH] applied " + id());
        return writer.toByteArray();
    }
}
