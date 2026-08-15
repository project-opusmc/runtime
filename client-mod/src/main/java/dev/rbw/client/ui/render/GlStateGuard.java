package dev.rbw.client.ui.render;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Captures mutable GL state at the UI boundary and restores it via
 * GlStateManager-compatible calls. Components never own GL state.
 */
final class GlStateGuard {
    private boolean blend;
    private boolean depth;
    private boolean alpha;
    private boolean texture;
    private boolean scissor;
    private int textureId;
    private final float[] color = new float[4];
    private final int[] scissorBox = new int[4];

    void begin() {
        blend = GL11.glIsEnabled(GL11.GL_BLEND);
        depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        textureId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        // LWJGL 2 validates glGetFloat buffers against the largest legal
        // result (a 4x4 matrix), even for GL_CURRENT_COLOR's four values.
        FloatBuffer colorBuffer = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, colorBuffer);
        for (int index = 0; index < color.length; index++) {
            color[index] = colorBuffer.get(index);
        }
        // See the FloatBuffer note above: this LWJGL binding validates the
        // generic glGetInteger call against a 4x4 result as well.
        IntBuffer scissorBuffer = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scissorBuffer);
        for (int index = 0; index < scissorBox.length; index++) {
            scissorBox[index] = scissorBuffer.get(index);
        }

        GlStateManager.pushMatrix();
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA,
                GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE,
                GL11.GL_ZERO);
        GlStateManager.disableDepth();
        GlStateManager.enableAlpha();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    void end() {
        GlStateManager.popMatrix();
        GlStateManager.bindTexture(textureId);
        GlStateManager.color(color[0], color[1], color[2], color[3]);
        if (blend) {
            GlStateManager.enableBlend();
        } else {
            GlStateManager.disableBlend();
        }
        if (depth) {
            GlStateManager.enableDepth();
        } else {
            GlStateManager.disableDepth();
        }
        if (alpha) {
            GlStateManager.enableAlpha();
        } else {
            GlStateManager.disableAlpha();
        }
        if (texture) {
            GlStateManager.enableTexture2D();
        } else {
            GlStateManager.disableTexture2D();
        }
        if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }
}
