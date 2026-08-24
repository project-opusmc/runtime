package org.polydevs.opusmc.client.ui.render;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

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
    private boolean depthMask;
    private int activeTexture;
    private int textureId;
    private int texture0Id;
    private boolean texture0;
    private int blendSrc;
    private int blendDst;
    private int blendSrcAlpha;
    private int blendDstAlpha;
    private final int[] viewport = new int[4];
    private final float[] color = new float[4];
    private final int[] scissorBox = new int[4];

    void begin() {
        blend = GL11.glIsEnabled(GL11.GL_BLEND);
        depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        textureId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        blendSrc = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        blendDst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        IntBuffer viewportBuffer = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewportBuffer);
        for (int index = 0; index < viewport.length; index++) {
            viewport[index] = viewportBuffer.get(index);
        }

        // Texture binding and enable state are per texture unit. The renderer
        // always draws on unit 0, so restoring only the initially active unit
        // would leak a changed unit-0 binding into Minecraft's next pass.
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        texture0Id = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        texture0 = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        GL13.glActiveTexture(activeTexture);

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
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA,
                GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE,
                GL11.GL_ZERO);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableAlpha();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    void end() {
        GlStateManager.popMatrix();
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(texture0Id);
        if (texture0) GlStateManager.enableTexture2D(); else GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(activeTexture);
        GlStateManager.bindTexture(textureId);
        GlStateManager.color(color[0], color[1], color[2], color[3]);
        GlStateManager.tryBlendFuncSeparate(blendSrc, blendDst, blendSrcAlpha, blendDstAlpha);
        GlStateManager.depthMask(depthMask);
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

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture0Id);
        setEnabled(GL11.GL_TEXTURE_2D, texture0);
        GL13.glActiveTexture(activeTexture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL14.glBlendFuncSeparate(blendSrc, blendDst, blendSrcAlpha, blendDstAlpha);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL11.glDepthMask(depthMask);
        GL11.glColor4f(color[0], color[1], color[2], color[3]);
        setEnabled(GL11.GL_BLEND, blend);
        setEnabled(GL11.GL_DEPTH_TEST, depth);
        setEnabled(GL11.GL_ALPHA_TEST, alpha);
        setEnabled(GL11.GL_TEXTURE_2D, texture);
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability); else GL11.glDisable(capability);
    }
}
