package org.polydevs.opusmc.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
    void reportsTheTelemetryAndOptiFineForgeTransformerChain() {
        assertEquals(7, OpusForgeClassTransformer.transformerCount());
    }

    @Test
    void neverTransformsTheRetiredReflectionUiPlaceholder() {
        OpusForgeClassTransformer transformer = new OpusForgeClassTransformer();
        String className = "org.polydevs.opusmc.client.gui.OpusClientOptionsScreen";
        byte[] placeholder = placeholderBytecode(className.replace('.', '/'));

        byte[] transformed = transformer.transform(className, className, placeholder);

        assertEquals(7, transformer.configuredTransformerCount());
        assertEquals("java/lang/Object", new ClassReader(transformed).getSuperName());
    }

    @Test
    void neverTargetsVanillaGuiOrInputClasses() {
        OpusForgeClassTransformer transformer = new OpusForgeClassTransformer();
        String[] vanillaOwnedClasses = {
                "net.minecraft.client.gui.GuiScreen",
                "net.minecraft.client.gui.GuiMainMenu",
                "net.minecraft.client.gui.GuiIngameMenu",
                "net.minecraft.client.gui.GuiOptions",
                "net.minecraft.client.gui.GuiSelectWorld",
                "net.minecraft.client.gui.GuiMultiplayer",
                "net.minecraft.client.settings.KeyBinding",
                "org.lwjgl.input.Keyboard",
                "org.lwjgl.input.Mouse",
        };

        for (String className : vanillaOwnedClasses) {
            byte[] original = placeholderBytecode(className.replace('.', '/'));
            assertEquals(className, OpusForgeClassTransformer.targetClassName(className, className));
            assertArrayEquals(original, transformer.transform(className, className, original));
        }
    }

    private static byte[] placeholderBytecode(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
