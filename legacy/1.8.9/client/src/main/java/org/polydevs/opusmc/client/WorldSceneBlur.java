package org.polydevs.opusmc.client;

import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

/**
 * Blurs the current game image without enabling Minecraft's framebuffer or
 * replacing its shader. The small working image uses the current drawable,
 * so Fast Render and framebuffer-off profiles need no FBO or GLSL program.
 */
final class WorldSceneBlur {
    private static final int DOWNSAMPLE = 4;
    private static final float LOGICAL_SIGMA = 4.0F;
    // LWJGL 2 requires sixteen slots even for the four-value viewport query.
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private int sourceTexture;
    private int workingTexture;
    private int sourceWidth;
    private int sourceHeight;
    private int workingWidth;
    private int workingHeight;
    private float[] horizontalWeights;
    private float[] verticalWeights;
    private float horizontalSigma = -1;
    private float verticalSigma = -1;

    boolean draw(int logicalWidth, int logicalHeight) {
        ContextCapabilities capabilities = GLContext.getCapabilities();
        viewport.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        int x = viewport.get(0), y = viewport.get(1);
        int width = viewport.get(2), height = viewport.get(3);
        int maximum = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        if (width < 4 || height < 4 || width > maximum || height > maximum) return false;
        int smallWidth = Math.max(1, width / DOWNSAMPLE);
        int smallHeight = Math.max(1, height / DOWNSAMPLE);
        float sigmaX = LOGICAL_SIGMA * smallWidth / Math.max(1, logicalWidth);
        float sigmaY = LOGICAL_SIGMA * smallHeight / Math.max(1, logicalHeight);
        if (horizontalWeights == null || horizontalSigma != sigmaX) {
            horizontalSigma = sigmaX;
            horizontalWeights = weights(sigmaX);
        }
        if (verticalWeights == null || verticalSigma != sigmaY) {
            verticalSigma = sigmaY;
            verticalWeights = weights(sigmaY);
        }

        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int program = capabilities.OpenGL20 ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, 1, 0, 1, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        try {
            if (capabilities.OpenGL20) GL20.glUseProgram(0);
            int units = GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
            for (int unit = 0; unit < units; unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL11.GL_TEXTURE_1D);
                GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);
                GL11.glDisable(GL11.GL_TEXTURE_GEN_S);
                GL11.glDisable(GL11.GL_TEXTURE_GEN_T);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            GL11.glDisable(GL11.GL_DITHER);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL11.glColorMask(true, true, true, true);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glColor4f(1, 1, 1, 1);

            if (sourceTexture == 0) sourceTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sourceTexture);
            textureParameters();
            if (sourceWidth != width || sourceHeight != height) {
                GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, x, y, width, height, 0);
                sourceWidth = width; sourceHeight = height;
            } else {
                GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, x, y, width, height);
            }

            // Only this temporary rectangle is changed until the completed
            // blur is expanded back over the original viewport.
            GL11.glViewport(x, y, smallWidth, smallHeight);
            GL11.glScissor(x, y, smallWidth, smallHeight);
            quad(0, 0);
            if (workingTexture == 0) workingTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, workingTexture);
            textureParameters();
            if (workingWidth != smallWidth || workingHeight != smallHeight) {
                GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, x, y, smallWidth, smallHeight, 0);
                workingWidth = smallWidth; workingHeight = smallHeight;
            } else {
                GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, x, y, smallWidth, smallHeight);
            }
            blurPass(horizontalWeights, true, x, y, smallWidth, smallHeight);
            blurPass(verticalWeights, false, x, y, smallWidth, smallHeight);

            GL11.glViewport(x, y, width, height);
            GL11.glScissor(x, y, width, height);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glColor4f(1, 1, 1, 1);
            quad(0, 0);
            return true;
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glPopMatrix();
            if (capabilities.OpenGL20) GL20.glUseProgram(program);
            GL11.glPopAttrib();
            GL13.glActiveTexture(activeTexture);
            GL11.glMatrixMode(matrixMode);
        }
    }

    private static float[] weights(float sigma) {
        sigma = Math.max(.5F, Math.min(4, sigma));
        int radius = Math.min(12, (int) Math.ceil(sigma * 3));
        float[] weights = new float[radius * 2 + 1];
        double total = 0;
        for (int at = 0; at < weights.length; at++) {
            double offset = at - radius;
            weights[at] = (float) Math.exp(-(offset * offset) / (2 * sigma * sigma));
            total += weights[at];
        }
        for (int at = 0; at < weights.length; at++) weights[at] /= total;
        return weights;
    }

    private void blurPass(float[] weights, boolean horizontal, int x, int y, int width, int height) {
        GL11.glClearColor(0, 0, 0, 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
        int radius = weights.length / 2;
        for (int at = 0; at < weights.length; at++) {
            float weight = weights[at];
            GL11.glColor4f(weight, weight, weight, 1);
            float offset = at - radius;
            quad(horizontal ? offset / width : 0, horizontal ? 0 : offset / height);
        }
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, x, y, width, height);
    }

    private static void textureParameters() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    private static void quad(float offsetX, float offsetY) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(offsetX, offsetY); GL11.glVertex2f(0, 0);
        GL11.glTexCoord2f(1 + offsetX, offsetY); GL11.glVertex2f(1, 0);
        GL11.glTexCoord2f(1 + offsetX, 1 + offsetY); GL11.glVertex2f(1, 1);
        GL11.glTexCoord2f(offsetX, 1 + offsetY); GL11.glVertex2f(0, 1);
        GL11.glEnd();
    }

    void close() {
        if (sourceTexture != 0 || workingTexture != 0) {
            boolean hasContext;
            try {
                GLContext.getCapabilities();
                hasContext = true;
            } catch (RuntimeException contextAlreadyClosed) {
                hasContext = false;
            }
            if (hasContext) {
                if (sourceTexture != 0) GL11.glDeleteTextures(sourceTexture);
                if (workingTexture != 0) GL11.glDeleteTextures(workingTexture);
            }
        }
        sourceTexture = workingTexture = 0;
        sourceWidth = sourceHeight = workingWidth = workingHeight = 0;
    }
}
