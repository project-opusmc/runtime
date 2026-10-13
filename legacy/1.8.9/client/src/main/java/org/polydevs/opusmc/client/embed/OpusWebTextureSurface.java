package org.polydevs.opusmc.client.embed;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GLContext;

/**
 * Uploads the helper's BGRA frames to an OpenGL texture and draws them
 * full-screen over the game. All GL state is saved and restored so the rest of
 * the renderer is untouched; this class owns nothing beyond its own texture.
 */
public final class OpusWebTextureSurface {
    private static final int PBO_COUNT = 3;

    private int textureId = -1;
    private int textureWidth;
    private int textureHeight;
    private final int[] pixelUploadBuffers = new int[PBO_COUNT];
    private int pixelUploadIndex;
    private boolean pixelUploadBuffersInitialized;

    /**
     * Uploads a frame; allocates/reallocates the texture as the size changes.
     *
     * <p>The frame's buffer is a direct window onto the helper's shared mapping,
     * so it is handed to GL as-is: no intermediate copy, and the ~33 MB a 4K
     * frame would otherwise cost per paint never moves through the JVM heap.</p>
     */
    public void upload(OpusWebViewClient.Frame frame) {
        if (frame == null || frame.pixels == null || frame.width <= 0 || frame.height <= 0) {
            return;
        }
        int expected = frame.width * frame.height * 4;
        ByteBuffer pixels = frame.pixels;
        if (pixels.remaining() < expected) {
            return;
        }

        GlStateSnapshot state = GlStateSnapshot.capture();
        try {
            if (textureId < 0) {
                textureId = GL11.glGenTextures();
                // Force a full glTexImage2D below: a fresh name has no storage yet.
                textureWidth = 0;
                textureHeight = 0;
            }

            GlStateManager.bindTexture(textureId);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

            boolean resized = frame.width != textureWidth || frame.height != textureHeight;
            if (resized) {
                textureWidth = frame.width;
                textureHeight = frame.height;
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            }

            if (supportsPixelUploadBuffers()) {
                ensurePixelUploadBuffers();
                int pbo = pixelUploadBuffers[pixelUploadIndex];
                pixelUploadIndex = (pixelUploadIndex + 1) % PBO_COUNT;
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
                // glBufferData replaces the PBO's data store. The driver may keep
                // the previous store alive for an outstanding texture transfer,
                // while this call synchronously copies the current mmap bytes into
                // independent GL-owned storage. The CEF slot can therefore be
                // RELEASEd immediately after upload() returns without glFinish().
                GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, pixels, GL15.GL_STREAM_DRAW);
                if (resized) {
                    GL11.glTexImage2D(
                            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                            textureWidth, textureHeight, 0,
                            GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, 0L);
                } else {
                    GL11.glTexSubImage2D(
                            GL11.GL_TEXTURE_2D, 0, 0, 0,
                            textureWidth, textureHeight,
                            GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, 0L);
                }
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            } else {
                // Client-memory pixel uploads must be consumed before the call
                // returns by the OpenGL contract. This fallback exists for old
                // non-PBO contexts; production macOS 1.8.9 uses the PBO path.
                if (resized) {
                    GL11.glTexImage2D(
                            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                            textureWidth, textureHeight, 0,
                            GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, pixels);
                } else {
                    GL11.glTexSubImage2D(
                            GL11.GL_TEXTURE_2D, 0, 0, 0,
                            textureWidth, textureHeight,
                            GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, pixels);
                }
            }
        } finally {
            state.restore();
        }
    }

    private static boolean supportsPixelUploadBuffers() {
        return false; // Disabled to prevent AppleMetalOpenGLRenderer crashes on Mac
    }

    private void ensurePixelUploadBuffers() {
        if (pixelUploadBuffersInitialized) {
            return;
        }
        for (int i = 0; i < pixelUploadBuffers.length; i++) {
            pixelUploadBuffers[i] = GL15.glGenBuffers();
        }
        pixelUploadBuffersInitialized = true;
    }

    public boolean hasTexture() {
        return textureId >= 0 && textureWidth > 0 && textureHeight > 0;
    }

    public long retainedBytes() { return (long) textureWidth * textureHeight * 4L; }

    /**
     * Draws the current texture over the whole UI viewport (0,0..width,height in
     * Opus product units). GL state is fully saved and restored.
     */
    public void draw(int viewportWidth, int viewportHeight) {
        if (!hasTexture()) {
            return;
        }

        GlStateSnapshot state = GlStateSnapshot.capture();
        try {
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.enableTexture2D();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableAlpha();
            GlStateManager.enableBlend();
            // CEF's OnPaint buffer is premultiplied BGRA.
            GlStateManager.tryBlendFuncSeparate(
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.bindTexture(textureId);

            int x0 = 0;
            int y0 = 0;
            int x1 = viewportWidth;
            int y1 = viewportHeight;

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0.0F, 0.0F);
            GL11.glVertex2f(x0, y0);
            GL11.glTexCoord2f(0.0F, 1.0F);
            GL11.glVertex2f(x0, y1);
            GL11.glTexCoord2f(1.0F, 1.0F);
            GL11.glVertex2f(x1, y1);
            GL11.glTexCoord2f(1.0F, 0.0F);
            GL11.glVertex2f(x1, y0);
            GL11.glEnd();
        } finally {
            state.restore();
        }
    }

    public void dispose() {
        if (textureId >= 0) {
            GlStateManager.deleteTexture(textureId);
            textureId = -1;
        }
        textureWidth = 0;
        textureHeight = 0;
        if (pixelUploadBuffersInitialized) {
            int previous = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int i = 0; i < pixelUploadBuffers.length; i++) {
                if (pixelUploadBuffers[i] != 0) {
                    GL15.glDeleteBuffers(pixelUploadBuffers[i]);
                    pixelUploadBuffers[i] = 0;
                }
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, previous);
            pixelUploadBuffersInitialized = false;
            pixelUploadIndex = 0;
        }
    }

    /** Captures both the raw GL boundary and values mirrored by GlStateManager. */
    private static final class GlStateSnapshot {
        private final boolean blend;
        private final boolean depth;
        private final boolean depthMask;
        private final boolean alpha;
        private final boolean texture2d;
        private final int activeTexture;
        private final int textureBinding;
        private final boolean texture0Enabled;
        private final int texture0Binding;
        private final int unpackAlignment;
        private final int blendSrc;
        private final int blendDst;
        private final int blendSrcAlpha;
        private final int blendDstAlpha;
        private final int viewportX;
        private final int viewportY;
        private final int viewportWidth;
        private final int viewportHeight;
        private final float red;
        private final float green;
        private final float blue;
        private final float alphaValue;

        private GlStateSnapshot() {
            blend = GL11.glIsEnabled(GL11.GL_BLEND);
            depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            texture2d = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            textureBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            if (activeTexture == GL13.GL_TEXTURE0) {
                texture0Enabled = texture2d;
                texture0Binding = textureBinding;
            } else {
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                texture0Enabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
                texture0Binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                GL13.glActiveTexture(activeTexture);
            }
            unpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);

            blendSrc = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            blendDst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);

            // LWJGL 2 validates generic glGetInteger/glGetFloat calls against
            // the largest legal result (a 4x4 matrix), even for four-value
            // state such as GL_VIEWPORT and GL_CURRENT_COLOR.
            IntBuffer viewport = BufferUtils.createIntBuffer(16);
            GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
            viewportX = viewport.get(0);
            viewportY = viewport.get(1);
            viewportWidth = viewport.get(2);
            viewportHeight = viewport.get(3);

            FloatBuffer color = BufferUtils.createFloatBuffer(16);
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
            red = color.get(0);
            green = color.get(1);
            blue = color.get(2);
            alphaValue = color.get(3);
        }

        static GlStateSnapshot capture() {
            return new GlStateSnapshot();
        }

        void restore() {
            // All mutations in this class go through GlStateManager, so restore
            // its cache and the driver together first. Raw calls below then
            // enforce the exact captured boundary without leaving the cache at
            // a different value.
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(texture0Binding);
            if (texture0Enabled) GlStateManager.enableTexture2D(); else GlStateManager.disableTexture2D();
            GlStateManager.setActiveTexture(activeTexture);
            GlStateManager.bindTexture(textureBinding);
            GlStateManager.tryBlendFuncSeparate(blendSrc, blendDst, blendSrcAlpha, blendDstAlpha);
            GlStateManager.color(red, green, blue, alphaValue);
            GlStateManager.depthMask(depthMask);
            if (blend) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
            if (depth) GlStateManager.enableDepth(); else GlStateManager.disableDepth();
            if (alpha) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha();
            if (texture2d) GlStateManager.enableTexture2D(); else GlStateManager.disableTexture2D();

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture0Binding);
            setEnabled(GL11.GL_TEXTURE_2D, texture0Enabled);
            GL13.glActiveTexture(activeTexture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureBinding);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpackAlignment);
            GL14.glBlendFuncSeparate(blendSrc, blendDst, blendSrcAlpha, blendDstAlpha);
            GL11.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
            GL11.glColor4f(red, green, blue, alphaValue);
            GL11.glDepthMask(depthMask);
            setEnabled(GL11.GL_BLEND, blend);
            setEnabled(GL11.GL_DEPTH_TEST, depth);
            setEnabled(GL11.GL_ALPHA_TEST, alpha);
            setEnabled(GL11.GL_TEXTURE_2D, texture2d);
        }

        private static void setEnabled(int capability, boolean enabled) {
            if (enabled) GL11.glEnable(capability); else GL11.glDisable(capability);
        }
    }
}
