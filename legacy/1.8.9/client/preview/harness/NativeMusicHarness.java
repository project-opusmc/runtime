package org.polydevs.opusmc.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.logging.log4j.LogManager;

/** Exercises the production Forge listener without a window or audio device. */
public final class NativeMusicHarness {
    public static void main(String[] args) throws Exception {
        Path fixture = Files.createTempDirectory("opus-native-music-harness-");
        String previous = System.getProperty("opus.utility.settings.file");
        System.setProperty("opus.utility.settings.file", fixture.resolve("utilities.json").toString());
        ClientOverlayController controller = new ClientOverlayController(LogManager.getLogger("NativeMusicHarness"));
        try {
            for (SoundCategory category : SoundCategory.values()) {
                ISound sound = PositionedSoundRecord.create(new ResourceLocation("harness", "sound"));
                PlaySoundEvent event = new PlaySoundEvent(null, sound, category);
                // Exercise the actual public Forge subscriber boundary. A
                // standalone JavaExec has no LaunchClassLoader/Loader, so it
                // cannot construct Forge's global mod-container registry.
                for (Method method : controller.getClass().getMethods()) {
                    if (method.getAnnotation(SubscribeEvent.class) != null
                            && method.getParameterTypes().length == 1
                            && method.getParameterTypes()[0] == PlaySoundEvent.class) {
                        method.invoke(controller, event);
                    }
                }
                if (category == SoundCategory.MUSIC) {
                    require(event.result == null, "Music playback was not vetoed independently of the volume slider");
                } else {
                    require(event.result == sound, "Non-music audio was altered: " + category.name());
                }
            }
            System.out.println("NATIVE MUSIC: MUSIC vetoed; all other categories unchanged; no audio device opened");
        } finally {
            if (previous == null) System.clearProperty("opus.utility.settings.file");
            else System.setProperty("opus.utility.settings.file", previous);
            Files.delete(fixture);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
