package org.polydevs.opusmc.client;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Validates shipped settings calls against vanilla classes without starting a game. */
public final class MinecraftSettingsLinkageHarness {
    public static void main(String[] args) throws Exception {
        try (JarFile client = new JarFile(args[0]); JarFile minecraft = new JarFile(args[2]);
             URLClassLoader loader = new URLClassLoader(new URL[] {new File(args[1]).toURI().toURL()}, MinecraftSettingsLinkageHarness.class.getClassLoader())) {
            Class<?> type = loader.loadClass("org.polydevs.opusmc.standalone.OpusUiRemappingTransformer");
            Object transformer = type.newInstance();
            Map<String,ClassNode> metadata = new HashMap<String,ClassNode>();
            List<String> missing = new ArrayList<String>();
            int checked = 0;
            for (String name : new String[] {"ClientOverlayController", "MinecraftSettingCollections"}) {
                String full = "org/polydevs/opusmc/client/" + name;
                byte[] input = bytes(client.getInputStream(client.getJarEntry(full + ".class")));
                byte[] output = (byte[]) type.getMethod("transform", String.class, String.class, byte[].class)
                        .invoke(transformer, full.replace('/', '.'), full.replace('/', '.'), input);
                ClassNode node = new ClassNode(); new ClassReader(output).accept(node, 0);
                for (Object item : node.methods) {
                    MethodNode method = (MethodNode) item;
                    if (name.equals("ClientOverlayController") && !method.name.equals("gameOptions")
                            && !method.name.equals("adjustGameOption") && !method.name.equals("setGameOptionFloat")) continue;
                    for (Object instruction : method.instructions.toArray()) {
                        if (!(instruction instanceof MethodInsnNode)) continue;
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if (minecraft.getJarEntry(call.owner + ".class") == null) continue;
                        checked++;
                        if (!resolves(minecraft, metadata, call.owner, call.name, call.desc, 0))
                            missing.add(name + "." + method.name + " -> " + call.owner + "." + call.name + call.desc);
                    }
                }
            }
            if (checked < 30) throw new AssertionError("Settings artifact was not inspected: " + checked);
            if (!missing.isEmpty()) throw new AssertionError("Unresolved shipped Minecraft settings calls: " + missing);
            System.out.println("PASS: " + checked + " shipped settings read/write and resource-collection calls resolve through the real standalone remapper, including in-world difficulty");
        }
    }

    private static boolean resolves(JarFile minecraft, Map<String,ClassNode> metadata,
            String owner, String name, String descriptor, int depth) throws Exception {
        if (owner == null || depth > 32) return false;
        ClassNode node = metadata.get(owner);
        if (node == null) {
            InputStream input = minecraft.getJarEntry(owner + ".class") == null
                    ? MinecraftSettingsLinkageHarness.class.getClassLoader().getResourceAsStream(owner + ".class")
                    : minecraft.getInputStream(minecraft.getJarEntry(owner + ".class"));
            if (input == null) return false;
            node = new ClassNode(); new ClassReader(bytes(input)).accept(node, ClassReader.SKIP_CODE);
            metadata.put(owner, node);
        }
        for (Object item : node.methods) {
            MethodNode method = (MethodNode) item;
            if (method.name.equals(name) && method.desc.equals(descriptor)) return true;
        }
        if (name.equals("<init>")) return false;
        if (resolves(minecraft, metadata, node.superName, name, descriptor, depth + 1)) return true;
        for (Object iface : node.interfaces) if (resolves(minecraft, metadata, String.valueOf(iface), name, descriptor, depth + 1)) return true;
        return false;
    }

    private static byte[] bytes(InputStream input) throws Exception {
        try (InputStream stream = input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
