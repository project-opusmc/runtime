package org.polydevs.opusmc.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

final class OpusForgeClassTransformerTest {
    @Test
    void recognizesTheLaunchWrapperMappedMinecraftName() {
        assertEquals(
                "ave",
                OpusForgeClassTransformer.targetClassName(
                        "ave", "net.minecraft.client.Minecraft"));
    }

    @Test
    void reportsTheTelemetryOnlyForgeTransformerChain() {
        assertEquals(6, OpusForgeClassTransformer.transformerCount());
    }

    @Test
    void neverTransformsTheRetiredReflectionUiPlaceholder() {
        OpusForgeClassTransformer transformer = new OpusForgeClassTransformer();
        String className = "org.polydevs.opusmc.client.gui.OpusClientOptionsScreen";
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
