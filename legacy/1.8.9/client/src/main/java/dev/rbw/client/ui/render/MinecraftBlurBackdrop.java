package dev.rbw.client.ui.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.ShaderLinkHelper;
import net.minecraft.util.ResourceLocation;

/**
 * A real post-process backdrop blur. It is deliberately screen-owned so Opus
 * never stops a shader that was already active (for example an OptiFine
 * shader-pack path). Unsupported paths fall back to the page's normal dim.
 */
public final class MinecraftBlurBackdrop {
    private static final ResourceLocation RBW_BLUR =
            new ResourceLocation("rbwclient", "shaders/post/rbw_blur.json");

    private final Minecraft minecraft;
    private boolean ownsShader;
    private boolean unavailable;

    public MinecraftBlurBackdrop(Minecraft minecraft) {
        if (minecraft == null) {
            throw new IllegalArgumentException("minecraft is required");
        }
        this.minecraft = minecraft;
    }

    public boolean begin() {
        if (ownsShader || unavailable || !OpenGlHelper.shadersSupported || minecraft.entityRenderer == null) {
            return ownsShader;
        }
        if (minecraft.entityRenderer.isShaderActive()) {
            return false;
        }
        try {
            // Forge 1.8.9's dev launch can leave this global unset even when
            // OpenGlHelper reports shader support. We own no pre-existing
            // shader at this point, so reset it immediately before loading.
            ShaderLinkHelper.setNewStaticShaderLinkHelper();
            minecraft.entityRenderer.loadShader(RBW_BLUR);
            ownsShader = minecraft.entityRenderer.isShaderActive();
            return ownsShader;
        } catch (Exception exception) {
            unavailable = true;
            return false;
        } catch (LinkageError error) {
            unavailable = true;
            return false;
        }
    }

    public void end() {
        if (ownsShader && minecraft.entityRenderer != null) {
            minecraft.entityRenderer.stopUseShader();
        }
        ownsShader = false;
    }
}
