package org.polydevs.opusmc.client;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.WorldInfo;
import org.apache.logging.log4j.Logger;

/** Uses real GameSettings and temporary options.txt, never a user profile. */
public final class MinecraftSettingsPersistenceHarness {
    public static void main(String[] args) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        Minecraft mc = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        Field singleton = Minecraft.class.getDeclaredField("theMinecraft"); singleton.setAccessible(true);
        Object previous = singleton.get(null);
        String previousSettings = System.getProperty("opus.utility.settings.file");
        Field translations = null;
        for (Field candidate : net.minecraft.client.resources.I18n.class.getDeclaredFields())
            if (candidate.getType() == net.minecraft.client.resources.Locale.class) translations = candidate;
        if (translations == null) throw new AssertionError("Minecraft translation holder is unavailable");
        translations.setAccessible(true);
        Object previousTranslations = translations.get(null);
        Object forge = net.minecraftforge.fml.client.FMLClientHandler.instance();
        Field loading = forge.getClass().getDeclaredField("loading"); loading.setAccessible(true);
        boolean previousLoading = loading.getBoolean(forge);
        File folder = Files.createTempDirectory("opus-minecraft-settings-").toFile();
        try {
            // Forge suppresses options.txt writes until startup has completed.
            loading.setBoolean(forge,false);
            translations.set(null, new net.minecraft.client.resources.Locale());
            singleton.set(null, mc);
            mc.gameSettings = new GameSettings(mc, folder);
            net.minecraft.client.audio.SoundHandler sounds = new net.minecraft.client.audio.SoundHandler(null, mc.gameSettings);
            for (Field candidate : Minecraft.class.getDeclaredFields()) {
                if (candidate.getType() == net.minecraft.client.audio.SoundHandler.class) {
                    candidate.setAccessible(true); candidate.set(mc,sounds);
                }
            }
            Logger log = (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[]{Logger.class},
                    (proxy,method,arguments)->method.getReturnType()==boolean.class?Boolean.FALSE:null);
            System.setProperty("opus.utility.settings.file",new File(folder,"utility.json").getAbsolutePath());
            ClientOverlayController controller = new ClientOverlayController(log);
            List<String[]> title = controller.gameOptions();
            require(keys(title).contains("FOV") && keys(title).contains("SOUND:master")
                    && keys(title).contains("KEY:key.jump") && keys(title).contains("SKIN:HAT"), "Native title settings are incomplete");
            mc.theWorld = (WorldClient) unsafe.allocateInstance(WorldClient.class);
            Field worldInfo = World.class.getDeclaredField("worldInfo"); worldInfo.setAccessible(true);
            worldInfo.set(mc.theWorld, new WorldInfo(new WorldSettings(1L, WorldSettings.GameType.SURVIVAL,
                    true, false, WorldType.DEFAULT), "settings-fixture"));
            List<String[]> inWorld = controller.gameOptions();
            require(inWorld.size() == title.size() + 2 && keys(inWorld).contains("WORLD:DIFFICULTY"), "In-world settings lost the native option rows");
            controller.setGameOptionFloat("SOUND:music", .27F);
            controller.onClientTick(new net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent(
                    net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END));
            require(Math.abs(mc.gameSettings.getSoundLevel(SoundCategory.MUSIC)-.27F)<.001F, "Music setting was overwritten by the client tick");
            controller.setGameOptionFloat("SOUND:master", .37F);
            require(Math.abs(mc.gameSettings.getSoundLevel(SoundCategory.MASTER)-.37F)<.001F, "Sound did not change");
            controller.setGameOptionFloat("FOV", .65F);
            require(Math.abs(GameSettings.Options.FOV.normalizeValue(mc.gameSettings.getOptionFloatValue(GameSettings.Options.FOV))-.65F)<.02F, "FOV did not change");
            boolean chat = mc.gameSettings.getOptionOrdinalValue(GameSettings.Options.CHAT_COLOR);
            controller.adjustGameOption("CHAT_COLOR", 1);
            require(mc.gameSettings.getOptionOrdinalValue(GameSettings.Options.CHAT_COLOR)!=chat, "Chat toggle did not change");
            boolean telemetry = mc.gameSettings.getOptionOrdinalValue(GameSettings.Options.SNOOPER_ENABLED);
            controller.adjustGameOption("SNOOPER_ENABLED",1);
            require(mc.gameSettings.getOptionOrdinalValue(GameSettings.Options.SNOOPER_ENABLED)!=telemetry, "Privacy toggle did not change");
            controller.setGameOptionFloat("KEY:key.jump", 34);
            require(mc.gameSettings.keyBindJump.getKeyCode()==34, "Key binding did not change");
            boolean hat = mc.gameSettings.getModelParts().contains(EnumPlayerModelParts.HAT);
            controller.adjustGameOption("SKIN:HAT",1);
            require(mc.gameSettings.getModelParts().contains(EnumPlayerModelParts.HAT)!=hat, "Skin toggle did not change");
            GameSettings reloaded = new GameSettings(mc, folder);
            System.out.println("Isolated reload: master=" + reloaded.getSoundLevel(SoundCategory.MASTER)
                    + " jump=" + reloaded.keyBindJump.getKeyCode() + " chat="
                    + reloaded.getOptionOrdinalValue(GameSettings.Options.CHAT_COLOR) + " initialChat=" + chat
                    + " hat=" + reloaded.getModelParts().contains(EnumPlayerModelParts.HAT) + " initialHat=" + hat);
            require(Math.abs(reloaded.getSoundLevel(SoundCategory.MASTER)-.37F)<.001F
                    && Math.abs(reloaded.getSoundLevel(SoundCategory.MUSIC)-.27F)<.001F
                    && Math.abs(GameSettings.Options.FOV.normalizeValue(reloaded.getOptionFloatValue(GameSettings.Options.FOV))-.65F)<.02F
                    && reloaded.keyBindJump.getKeyCode()==34
                    && reloaded.getOptionOrdinalValue(GameSettings.Options.CHAT_COLOR)!=chat
                    && reloaded.getOptionOrdinalValue(GameSettings.Options.SNOOPER_ENABLED)!=telemetry
                    && reloaded.getModelParts().contains(EnumPlayerModelParts.HAT)!=hat, "Native settings did not survive options.txt reload");
            String saved = new String(Files.readAllBytes(new File(folder,"options.txt").toPath()),StandardCharsets.UTF_8);
            require(saved.contains("soundCategory_master:0.37") && saved.contains("key_key.jump:34"), "Minecraft options.txt did not persist writes");
            System.out.println("PASS: real Minecraft settings expose " + title.size() + " title rows and " + inWorld.size()
                    + " in-world rows; FOV, audio, chat, skin and key edits save and reload from isolated options.txt");
        } finally {
            loading.setBoolean(forge,previousLoading);
            if(previousSettings==null)System.clearProperty("opus.utility.settings.file");else System.setProperty("opus.utility.settings.file",previousSettings);
            translations.set(null,previousTranslations);
            singleton.set(null,previous);
            File[] files = folder.listFiles(); if(files!=null)for(File file:files)file.delete(); folder.delete();
        }
    }
    private static Set<String> keys(List<String[]> rows){Set<String> keys=new HashSet<String>();for(String[] row:rows)keys.add(row[0]);return keys;}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
