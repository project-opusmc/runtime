package dev.rbw.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

final class RbwForgeClassTransformerTest {
    @Test
    void recognizesTheLaunchWrapperMappedMinecraftName() {
        assertEquals(
                "ave",
                RbwForgeClassTransformer.targetClassName(
                        "ave", "net.minecraft.client.Minecraft"));
    }

    @Test
    void reportsTheTelemetryOnlyForgeTransformerChain() {
        assertEquals(6, RbwForgeClassTransformer.transformerCount());
    }

    @Test
    void neverTransformsTheRetiredReflectionUiPlaceholder() {
        RbwForgeClassTransformer transformer = new RbwForgeClassTransformer();
        String className = "rbwclient.gui.RbwClientOptionsScreen";
        byte[] placeholder = placeholderBytecode(className.replace('.', '/'));

        byte[] transformed = transformer.transform(className, className, placeholder);

        assertEquals(6, transformer.configuredTransformerCount());
        assertEquals("java/lang/Object", new ClassReader(transformed).getSuperName());
    }

    private static byte[] placeholderBytecode(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
