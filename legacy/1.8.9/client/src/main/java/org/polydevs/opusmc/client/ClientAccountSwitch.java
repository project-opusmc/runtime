package org.polydevs.opusmc.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

final class ClientAccountSwitch {
    /** Runs on an HTTP worker, never on the rendering/client thread. */
    static Session resolve(String id, String username, String kind) throws Exception {
        String resolver = System.getProperty("opus.account.resolver", "");
        if (resolver.isEmpty()) {
            if (!"offline".equals(kind)) throw new IllegalStateException("Account requires reconnect in Opus Launcher");
            return new Session(username, UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""), "0", "legacy");
        }
        File binary = new File(resolver);
        File catalog = new File(System.getProperty("opus.accounts.file", ""));
        if (!binary.isAbsolute() || !binary.isFile() || !binary.canExecute() || catalog.getParentFile() == null) throw new IllegalStateException("Account resolver is unavailable");
        Process process = new ProcessBuilder(binary.getAbsolutePath(), "--resolve-client-account").start();
        Thread discard = new Thread(new Runnable() { public void run() { try { InputStream stream = process.getErrorStream(); byte[] buffer = new byte[1024]; while (stream.read(buffer) >= 0) {} } catch (Exception ignored) {} } }, "opus-account-resolver-stderr");
        discard.setDaemon(true); discard.start();
        try {
            JsonObject request = new JsonObject(); request.addProperty("root", catalog.getParentFile().getAbsolutePath()); request.addProperty("id", id);
            process.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8)); process.getOutputStream().close();
            if (!process.waitFor(90, TimeUnit.SECONDS)) throw new IllegalStateException("Account resolver timed out");
            if (process.exitValue() != 0) throw new IllegalStateException("Account requires reconnect in Opus Launcher");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int count;
            InputStream stream = process.getInputStream();
            while ((count = stream.read(buffer)) >= 0) { if (bytes.size() + count > 16384) throw new IllegalStateException("Invalid resolver response"); bytes.write(buffer, 0, count); }
            JsonObject data = new JsonParser().parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
            String name = data.get("username").getAsString(), uuid = data.get("uuid").getAsString(), token = data.get("accessToken").getAsString(), type = data.get("userType").getAsString();
            if (!name.matches("[A-Za-z0-9_]{1,16}") || !uuid.matches("[a-fA-F0-9]{32}") || token.length() > 4096 || ("microsoft".equals(kind) ? !"msa".equals(type) || token.length() < 8 : !"legacy".equals(type) || !"0".equals(token))) throw new IllegalStateException("Invalid resolver identity");
            return new Session(name, uuid, token, "msa".equals(type) ? "mojang" : type);
        } finally { process.destroy(); }
    }

    /** Runs on Minecraft thread, rechecking that no connection/world has started. */
    static void apply(Minecraft mc, Session next) throws Exception {
        if (mc.theWorld != null) throw new IllegalStateException("Disconnect before changing account");
        Field session = ReflectionHelper.findField(Minecraft.class, "session", "field_71449_j");
        Field properties = ReflectionHelper.findField(Minecraft.class, "profileProperties", "field_181038_N");
        Object oldProperties = properties.get(mc);
        // Resolve every mapped field before committing. The session service itself
        // is account independent; subsequent joins use next's UUID and access token.
        session.set(mc, next);
        if (oldProperties instanceof com.mojang.authlib.properties.PropertyMap) ((com.mojang.authlib.properties.PropertyMap) oldProperties).clear();
        if (mc.getSession() != next) throw new IllegalStateException("Session switch was not committed");
    }
}
