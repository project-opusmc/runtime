package org.polydevs.opusmc.standalone;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.launchwrapper.IClassTransformer;
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
                ClassWriter cw = new ClassWriter(0);
                RemappingClassAdapter adapter = new RemappingClassAdapter(
                        cw,
                        remapperForClass(name, cr));
                cr.accept(adapter, ClassReader.EXPAND_FRAMES);
                return cw.toByteArray();
            } catch (Exception e) {
                System.err.println("[OPUS/STANDALONE] Failed to remap UI class: " + name);
                e.printStackTrace();
            }
        }
        return basicClass;
    }

    private Remapper remapperForClass(String className, ClassReader reader) {
        boolean hasGuiSuperclassMappings = (guiScreenMethodMappings != null && !guiScreenMethodMappings.isEmpty())
                || (guiScreenFieldMappings != null && !guiScreenFieldMappings.isEmpty())
                || (guiMethodMappings != null && !guiMethodMappings.isEmpty());
        if (!hasGuiSuperclassMappings || !isGuiScreenSubclass(reader.getSuperName())) {
            return remapper;
        }
        return new GuiSubclassRemapper(
                remapper,
                className.replace('.', '/'),
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

    private static final class GuiSubclassRemapper extends Remapper {
        private final SimpleRemapper delegate;
        private final String subclassOwner;
        private final Map<String, String> inheritedMethods;
        private final Map<String, String> inheritedFields;
        private final Map<String, String> inheritedGuiMethods;

        GuiSubclassRemapper(
                SimpleRemapper delegate,
                String subclassOwner,
                Map<String, String> inheritedMethods,
                Map<String, String> inheritedFields,
                Map<String, String> inheritedGuiMethods) {
            this.delegate = delegate;
            this.subclassOwner = subclassOwner;
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
            if (subclassOwner.equals(owner)) {
                String inherited = inheritedFields.get(name);
                if (inherited != null) {
                    return inherited;
                }
            }
            return name;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            String mapped = delegate.mapMethodName(owner, name, descriptor);
            if (!name.equals(mapped)) {
                return mapped;
            }
            if (subclassOwner.equals(owner)) {
                String inherited = inheritedMethods.get(name + descriptor);
                if (inherited == null) {
                    inherited = inheritedGuiMethods.get(name + descriptor);
                }
                if (inherited != null) {
                    return inherited;
                }
            }
            return name;
        }
    }
}
