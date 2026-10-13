package org.polydevs.opusmc.standalone;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;

public class OpusUiRemappingTransformer implements IClassTransformer {
    private SimpleRemapper remapper;
    private Map<String, String> guiScreenMethodMappings;
    private Map<String, String> guiScreenFieldMappings;
    private Map<String, String> guiMethodMappings;
    private final Map<String, String> superclassNames = new HashMap<String, String>();

    public OpusUiRemappingTransformer() {
        try {
            Map<String, String> map = new HashMap<String, String>();
            Map<String, String> guiScreenMethods = new HashMap<String, String>();
            Map<String, String> guiScreenFields = new HashMap<String, String>();
            Map<String, String> guiBaseMethods = new HashMap<String, String>();
            InputStream is = getClass().getResourceAsStream("/notch-srg.srg");
            if (is == null) {
                System.err.println("[OPUS/STANDALONE] notch-srg.srg not found in classpath!");
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("CL: ")) {
                    String[] parts = line.substring(4).split(" ");
                    map.put(parts[1], parts[0]);
                } else if (line.startsWith("FD: ")) {
                    String[] parts = line.substring(4).split(" ");
                    String srg = parts[1];
                    String notch = parts[0];
                    int srgLastSlash = srg.lastIndexOf('/');
                    int notchLastSlash = notch.lastIndexOf('/');
                    String srgOwner = srg.substring(0, srgLastSlash);
                    String srgName = srg.substring(srgLastSlash + 1);
                    String notchName = notch.substring(notchLastSlash + 1);
                    map.put(srgOwner + "." + srgName, notchName);
                    if ("net/minecraft/client/gui/GuiScreen".equals(srgOwner)) {
                        guiScreenFields.put(srgName, notchName);
                    }
                } else if (line.startsWith("MD: ")) {
                    String[] parts = line.substring(4).split(" ");
                    String notch = parts[0];
                    String notchDesc = parts[1];
                    String srg = parts[2];
                    String srgDesc = parts[3];
                    int srgLastSlash = srg.lastIndexOf('/');
                    int notchLastSlash = notch.lastIndexOf('/');
                    String srgOwner = srg.substring(0, srgLastSlash);
                    String srgName = srg.substring(srgLastSlash + 1);
                    String notchName = notch.substring(notchLastSlash + 1);
                    String key = srgOwner + "." + srgName + srgDesc;
                    map.put(key, notchName);
                    if ("net/minecraft/client/gui/GuiScreen".equals(srgOwner)) {
                        guiScreenMethods.put(srgName + srgDesc, notchName);
                    } else if ("net/minecraft/client/gui/Gui".equals(srgOwner)) {
                        guiBaseMethods.put(srgName + srgDesc, notchName);
                        map.put("net/minecraft/client/gui/GuiScreen." + srgName + srgDesc, notchName);
                    }
                }
            }
            reader.close();
            this.remapper = new SimpleRemapper(map);
            this.guiScreenMethodMappings = guiScreenMethods;
            this.guiScreenFieldMappings = guiScreenFields;
            this.guiMethodMappings = guiBaseMethods;
            System.out.println("[OPUS/STANDALONE] Loaded " + map.size() + " SRG->Notch mappings for UI.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || remapper == null) return basicClass;
        if (name.startsWith("org.polydevs.opusmc.client.") || name.startsWith("org.polydevs.opusmc.websurface.") || name.startsWith("net.minecraftforge.")) {
            try {
                ClassReader cr = new ClassReader(basicClass);
                superclassNames.put(cr.getClassName(), cr.getSuperName());
                ClassWriter cw = new ClassWriter(0);
                RemappingClassAdapter adapter = new RemappingClassAdapter(
                        cw,
                        remapperForClass());
                cr.accept(adapter, ClassReader.EXPAND_FRAMES);
                return cw.toByteArray();
            } catch (Exception e) {
                System.err.println("[OPUS/STANDALONE] Failed to remap UI class: " + name);
                e.printStackTrace();
            }
        }
        return basicClass;
    }

    private Remapper remapperForClass() {
        boolean hasGuiSuperclassMappings = (guiScreenMethodMappings != null && !guiScreenMethodMappings.isEmpty())
                || (guiScreenFieldMappings != null && !guiScreenFieldMappings.isEmpty())
                || (guiMethodMappings != null && !guiMethodMappings.isEmpty());
        if (!hasGuiSuperclassMappings) {
            return remapper;
        }
        return new GuiSubclassRemapper(
                remapper,
                guiScreenMethodMappings,
                guiScreenFieldMappings,
                guiMethodMappings);
    }

    private static boolean isGuiScreenSubclass(String superName) {
        return "net/minecraft/client/gui/GuiScreen".equals(superName)
                || "net/minecraft/client/gui/GuiIngameMenu".equals(superName)
                || "net/minecraft/client/gui/Gui".equals(superName)
                || "avp".equals(superName)
                || "axu".equals(superName)
                || "avo".equals(superName);
    }

    private boolean isGuiOwner(String owner) {
        for (int depth = 0; owner != null && depth < 32; depth++) {
            if (isGuiScreenSubclass(owner)) return true;
            if (!owner.startsWith("org/polydevs/opusmc/client/")
                    && !owner.startsWith("org/polydevs/opusmc/websurface/")) return false;
            if (!superclassNames.containsKey(owner)) {
                // A helper may be transformed before the screen it references.
                // Read metadata without loading/initializing that game class.
                try (InputStream input = guiMetadata(owner)) {
                    if (input == null) return false;
                    superclassNames.put(owner, new ClassReader(input).getSuperName());
                } catch (java.io.IOException unavailable) {
                    return false;
                }
            }
            owner = superclassNames.get(owner);
        }
        return false;
    }

    private InputStream guiMetadata(String owner) {
        String resource = owner + ".class";
        // The transformer is parent-loaded, while client classes can be added
        // to LaunchClassLoader only. Native threads may have an unrelated TCCL.
        InputStream input = Launch.classLoader == null ? null : Launch.classLoader.getResourceAsStream(resource);
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (input == null && context != null) input = context.getResourceAsStream(resource);
        if (input == null) input = getClass().getResourceAsStream("/" + resource);
        return input;
    }

    private final class GuiSubclassRemapper extends Remapper {
        private final SimpleRemapper delegate;
        private final Map<String, String> inheritedMethods;
        private final Map<String, String> inheritedFields;
        private final Map<String, String> inheritedGuiMethods;

        GuiSubclassRemapper(
                SimpleRemapper delegate,
                Map<String, String> inheritedMethods,
                Map<String, String> inheritedFields,
                Map<String, String> inheritedGuiMethods) {
            this.delegate = delegate;
            this.inheritedMethods = inheritedMethods;
            this.inheritedFields = inheritedFields;
            this.inheritedGuiMethods = inheritedGuiMethods;
        }

        @Override
        public String map(String internalName) {
            return delegate.map(internalName);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String mapped = delegate.mapFieldName(owner, name, descriptor);
            if (!name.equals(mapped)) {
                return mapped;
            }
            String inherited = inheritedFields.get(name);
            if (inherited != null && isGuiOwner(owner)) {
                return inherited;
            }
            return name;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            String mapped = delegate.mapMethodName(owner, name, descriptor);
            if (!name.equals(mapped)) {
                return mapped;
            }
            String inherited = inheritedMethods.get(name + descriptor);
            if (inherited == null) {
                inherited = inheritedGuiMethods.get(name + descriptor);
            }
            if (inherited != null && isGuiOwner(owner)) {
                return inherited;
            }
            return name;
        }
    }
}
