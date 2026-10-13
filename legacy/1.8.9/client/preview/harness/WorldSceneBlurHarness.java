package org.polydevs.opusmc.client;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.multiplayer.WorldClient;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.PixelFormat;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiNavigationState;
import org.polydevs.opusmc.client.ui.UiRoute;

/** Real framebuffer pixels through the production RShift screen blur boundary. */
public final class WorldSceneBlurHarness {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 400;

    public static void main(String[] args) throws Exception {
        int status;
        try { verify(args); status = 0; }
        catch (Throwable failure) { failure.printStackTrace(); status = 1; }
        // The disposable LWJGL window leaves an AWT event thread alive even
        // after destruction; always release this test JVM after verification.
        System.exit(status);
    }

    private static void verify(String[] args) throws Exception {
        OpusClientScreen screen = null;
        Minecraft previousMinecraft = null;
        boolean previousShaders = OpenGlHelper.shadersSupported;
        boolean previousFramebuffers = OpenGlHelper.framebufferSupported;
        Field singleton = Minecraft.class.getDeclaredField("theMinecraft");
        singleton.setAccessible(true);
        try {
            // Apple's Metal compatibility driver reports Pbuffer support but
            // returns empty readback for that legacy drawable. Use the same
            // window-backed context as Minecraft, owned only by this harness.
            Display.setTitle("Opus native blur verification");
            Display.setDisplayMode(new DisplayMode(WIDTH, HEIGHT));
            Display.create(new PixelFormat().withDepthBits(24));
            Display.update();
            System.out.println("GPU: " + GL11.glGetString(GL11.GL_RENDERER)
                    + "; " + GL11.glGetString(GL11.GL_VERSION));
            GL11.glViewport(0, 0, WIDTH, HEIGHT);
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
            minecraft.gameSettings = (GameSettings) unsafe.allocateInstance(GameSettings.class);
            minecraft.gameSettings.fboEnable = false;
            minecraft.theWorld = (WorldClient) unsafe.allocateInstance(WorldClient.class);
            minecraft.entityRenderer = (EntityRenderer) unsafe.allocateInstance(EntityRenderer.class);
            previousMinecraft = (Minecraft) singleton.get(null);
            singleton.set(null, minecraft);
            OpenGlHelper.shadersSupported = true;
            OpenGlHelper.framebufferSupported = true;
            require(!OpenGlHelper.isFramebufferEnabled(), "Framebuffer-off fixture is not active");
            screen = (OpusClientScreen) unsafe.allocateInstance(OpusClientScreen.class);
            set(screen, "mc", minecraft);
            set(screen, "navigation", new UiNavigationState(UiRoute.hudEditor(), UiNavigationState.EntryPoint.HOTKEY));
            set(screen, "productViewport", new UiBounds(0, 0, WIDTH / 2, HEIGHT / 2));
            set(screen, "hudEditing", true);
            Class<?> hostType = Class.forName("org.polydevs.opusmc.client.OpusClientScreen$1");
            Constructor<?> constructor = hostType.getDeclaredConstructor(OpusClientScreen.class);
            constructor.setAccessible(true);
            PauseWorldBlur.ShaderHost host = (PauseWorldBlur.ShaderHost) constructor.newInstance(screen);
            set(screen, "pauseWorldBlur", new PauseWorldBlur(host));
            try {
                Class<?> fallback = Class.forName("org.polydevs.opusmc.client.WorldSceneBlur");
                Constructor<?> fallbackConstructor = fallback.getDeclaredConstructor();
                fallbackConstructor.setAccessible(true);
                set(screen, "worldSceneBlur", fallbackConstructor.newInstance());
            } catch (ClassNotFoundException absentBeforeFix) {
                // The red phase still executes the production shader lease.
            }

            pattern();
            BufferedImage before = pixels();
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Fixture drawing produced an OpenGL error");
            require(variance(before) > 5000, "GPU fixture did not produce the sharp source image");
            drawBlur(screen);
            BufferedImage after = pixels();
            File output = new File(args.length == 0 ? "build/world-blur-proof" : args[0]);
            require(output.isDirectory() || output.mkdirs(), "Cannot create blur proof directory");
            ImageIO.write(before, "png", new File(output, "rshift-before.png"));
            ImageIO.write(after, "png", new File(output, "rshift-after.png"));
            double beforeVariance = variance(before);
            double afterVariance = variance(after);
            System.out.println("Native RShift pixel variance before=" + beforeVariance + "; after=" + afterVariance);
            require(afterVariance < beforeVariance * .35,
                    "RShift did not blur the actual framebuffer when Minecraft framebuffer rendering is disabled");
            require(Math.abs(mean(before) - mean(after)) < 16,
                    "Blur lost the game image or changed its brightness excessively");
            require(!minecraft.gameSettings.fboEnable && minecraft.entityRenderer.getShaderGroup() == null,
                    "Blur changed user framebuffer preferences or installed a world shader");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Blur produced an OpenGL error");
            verifyDrawOrder();

            pattern();
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(7, 9, 101, 107);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glColor4f(.2F, .3F, .4F, .9F);
            int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            drawBlur(screen);
            require(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE1,
                    "Blur changed the active texture unit");
            require(GL11.glGetInteger(GL11.GL_MATRIX_MODE) == matrixMode,
                    "Blur changed the active matrix mode");
            require(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) && GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
                    && GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), "Blur leaked renderer state");
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glColor4f(1, 1, 1, 1);

            // A broad scene edge must survive, and chrome drawn afterwards
            // must retain its exact pixel boundary rather than being blurred.
            sceneEdge();
            drawBlur(screen);
            BufferedImage scene = pixels();
            require(((scene.getRGB(80, 80) >> 16) & 255) < 50
                    && ((scene.getRGB(WIDTH - 80, 80) >> 16) & 255) > 180,
                    "Blur erased the broad scene structure");
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2i(300, 180); GL11.glVertex2i(320, 180);
            GL11.glVertex2i(320, 200); GL11.glVertex2i(300, 200);
            GL11.glEnd();
            BufferedImage chrome = pixels();
            require((chrome.getRGB(310, HEIGHT - 191) & 0xFFFFFF) == 0xFFFFFF
                    && (chrome.getRGB(299, HEIGHT - 191) & 0xFFFFFF) != 0xFFFFFF,
                    "Chrome drawn after scene blur is not sharp");
            ImageIO.write(chrome, "png", new File(output, "rshift-scene-and-sharp-chrome.png"));

            // Reallocate under a resized physical viewport, then restore the
            // original view. The same screen owns both texture generations.
            GL11.glViewport(0, 0, 384, 240);
            set(screen, "productViewport", new UiBounds(0, 0, 192, 120));
            drawBlur(screen);
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Resizing blur produced an OpenGL error");
            set(screen, "productViewport", new UiBounds(0, 0, WIDTH / 2, HEIGHT / 2));

            set(screen, "hudEditing", false);
            pattern();
            double gameView = variance(pixels());
            drawBlur(screen);
            require(Math.abs(variance(pixels()) - gameView) < .01,
                    "Game view still contains editor blur");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Closing editor blur produced an OpenGL error");
            System.out.println("PASS: production RShift blurs native pixels with framebuffer off, preserves scene/chrome and GL state, resizes and leaves Game view sharp");
        } finally {
            if (screen != null) {
                try {
                    Object fallback = field(screen, "worldSceneBlur");
                    if (fallback != null) {
                        Method close = fallback.getClass().getDeclaredMethod("close");
                        close.setAccessible(true);
                        close.invoke(fallback);
                    }
                } catch (NoSuchFieldException beforeFix) { }
            }
            singleton.set(null, previousMinecraft);
            OpenGlHelper.shadersSupported = previousShaders;
            OpenGlHelper.framebufferSupported = previousFramebuffers;
            if (Display.isCreated()) Display.destroy();
        }
    }

    private static void drawBlur(OpusClientScreen screen) throws Exception {
        Method method = OpusClientScreen.class.getDeclaredMethod("drawNativeWorldBlur");
        method.setAccessible(true);
        method.invoke(screen);
    }

    private static void verifyDrawOrder() throws Exception {
        final List<String> calls = new ArrayList<String>();
        new ClassReader(OpusClientScreen.class.getName()).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                if (!"drawScreen".equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                          String descriptor, boolean isInterface) {
                        calls.add(method);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        int blur = calls.indexOf("drawNativeWorldBlur");
        require(blur >= 0 && blur < calls.indexOf("drawHudEditor") && blur < calls.indexOf("drawProductSurface"),
                "The actual GuiScreen must blur before native widgets and CEF chrome");
    }

    private static void sceneEdge() {
        pattern();
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor3f(.1F, .3F, .85F);
        GL11.glVertex2i(0, 0); GL11.glVertex2i(WIDTH / 2, 0);
        GL11.glVertex2i(WIDTH / 2, HEIGHT); GL11.glVertex2i(0, HEIGHT);
        GL11.glColor3f(.85F, .4F, .1F);
        GL11.glVertex2i(WIDTH / 2, 0); GL11.glVertex2i(WIDTH, 0);
        GL11.glVertex2i(WIDTH, HEIGHT); GL11.glVertex2i(WIDTH / 2, HEIGHT);
        GL11.glEnd();
        GL11.glColor4f(1, 1, 1, 1);
    }

    private static void pattern() {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glViewport(0, 0, WIDTH, HEIGHT);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, WIDTH, 0, HEIGHT, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glClearColor(.1F, .1F, .1F, 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glBegin(GL11.GL_QUADS);
        for (int y = 0; y < HEIGHT; y += 8) {
            for (int x = 0; x < WIDTH; x += 8) {
                float value = ((x / 8 + y / 8) & 1) == 0 ? .92F : .12F;
                GL11.glColor3f(value, value, value);
                GL11.glVertex2i(x, y);
                GL11.glVertex2i(x + 8, y);
                GL11.glVertex2i(x + 8, y + 8);
                GL11.glVertex2i(x, y + 8);
            }
        }
        GL11.glEnd();
        GL11.glColor4f(1, 1, 1, 1);
    }

    private static BufferedImage pixels() {
        ByteBuffer bytes = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes);
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int at = (y * WIDTH + x) * 4;
            int colour = (bytes.get(at + 3) & 255) << 24 | (bytes.get(at) & 255) << 16
                    | (bytes.get(at + 1) & 255) << 8 | bytes.get(at + 2) & 255;
            image.setRGB(x, HEIGHT - 1 - y, colour);
        }
        return image;
    }

    private static double variance(BufferedImage image) {
        double sum = 0, squares = 0;
        int count = 0;
        for (int y = 32; y < HEIGHT - 32; y++) for (int x = 32; x < WIDTH - 32; x++) {
            int value = (image.getRGB(x, y) >> 16) & 255;
            sum += value; squares += value * value; count++;
        }
        return squares / count - (sum / count) * (sum / count);
    }

    private static double mean(BufferedImage image) {
        long total = 0;
        int count = 0;
        for (int y = 32; y < HEIGHT - 32; y++) for (int x = 32; x < WIDTH - 32; x++) {
            total += (image.getRGB(x, y) >> 16) & 255; count++;
        }
        return (double) total / count;
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException absent) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return; }
            catch (NoSuchFieldException absent) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
