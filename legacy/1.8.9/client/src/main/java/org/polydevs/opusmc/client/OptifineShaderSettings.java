package org.polydevs.opusmc.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Optional, narrowly mapped OptiFine adapter; no shader fixture is shipped as native data. */
final class OptifineShaderSettings {
    private static final String[] KEYS = {"ANTIALIASING", "NORMAL_MAP", "SPECULAR_MAP", "RENDER_RES_MUL", "SHADOW_RES_MUL", "HAND_DEPTH_MUL", "OLD_HAND_LIGHT", "OLD_LIGHTING"};
    private static final String[] LABELS = {"Antialiasing", "Normal map", "Specular map", "Render quality", "Shadow quality", "Hand depth", "Old hand light", "Old lighting"};
    private static Class<?> shaders() throws ReflectiveOperationException { return Class.forName("net.optifine.shaders.Shaders"); }
    private static void call(String method) throws ReflectiveOperationException { shaders().getMethod(method).invoke(null); }
    private static void loaded() throws ReflectiveOperationException {
        if (shaders().getField("shadersConfig").get(null) == null) call("loadConfig");
    }
    static List<String[]> packs() {
        List<String[]> rows = new ArrayList<String[]>();
        try {
            loaded();
            String current = String.valueOf(shaders().getMethod("getShaderPackName").invoke(null));
            for (Object name : (Iterable<?>) shaders().getMethod("listOfShaders").invoke(null)) {
                String id = String.valueOf(name);
                rows.add(MinecraftSettingCollections.row(id, "OFF".equals(id) ? "Off" : "(internal)".equals(id) ? "Internal" : id, "", id.equals(current), -1, true));
            }
        } catch (ReflectiveOperationException unavailable) { /* Host without OptiFine shaders. */ }
        return rows;
    }
    static void selectPack(String id) {
        boolean found = false;
        for (String[] row : packs()) if (row[0].equals(id)) found = true;
        if (!found) throw new IllegalArgumentException("Shader pack is unavailable");
        try {
            if (!"OFF".equals(id)) {
                Object settings = net.minecraft.client.Minecraft.getMinecraft().gameSettings;
                if (settings.getClass().getField("ofFastRender").getBoolean(settings)
                        || settings.getClass().getField("ofAaLevel").getInt(settings) > 0) {
                    throw new IllegalArgumentException("Turn off Fast Render and video antialiasing before enabling shaders");
                }
            }
            shaders().getMethod("setShaderPack", String.class).invoke(null, id);
            call("storeConfig");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Shader selection did not complete", failure); }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void appendOptions(List<String[]> rows) {
        try {
            loaded();
            Class<?> option = Class.forName("net.optifine.shaders.config.EnumShaderOption");
            for (int i = 0; i < KEYS.length; i++) {
                Object key = Enum.valueOf((Class) option, KEYS[i]);
                String value = String.valueOf(shaders().getMethod("getEnumShaderOption", option).invoke(null, key));
                boolean bool = i == 1 || i == 2;
                rows.add(new String[] {"SHADER:" + KEYS[i], LABELS[i], bool ? "boolean" : "enum", bool && "true".equals(value) ? "1" : "0", "0", "1", "1", value});
            }
            Object[] options = (Object[]) shaders().getMethod("getShaderPackOptions").invoke(null);
            if (options != null) for (Object entry : options) {
                Class<?> type = entry.getClass();
                if (!(Boolean) type.getMethod("isVisible").invoke(entry)) continue;
                String name = String.valueOf(type.getMethod("getName").invoke(entry));
                String value = String.valueOf(type.getMethod("getValue").invoke(entry));
                rows.add(new String[] {"SHADER_PACK:" + name, String.valueOf(type.getMethod("getNameText").invoke(entry)), "enum", "0", "0", "1", "1", String.valueOf(type.getMethod("getValueText", String.class).invoke(entry, value)), String.valueOf(type.getMethod("isEnabled").invoke(entry))});
            }
        } catch (ReflectiveOperationException unavailable) { /* Vanilla host exposes no shader controls. */ }
    }
    static void adjust(String key) {
        try {
            loaded();
            Class<?> type = shaders();
            if (key.startsWith("SHADER_PACK:")) {
                Object[] options = (Object[]) type.getMethod("getShaderPackOptions").invoke(null);
                Object found = null;
                if (options != null) for (Object option : options) if (key.substring(12).equals(String.valueOf(option.getClass().getMethod("getName").invoke(option)))) found = option;
                if (found == null || !(Boolean) found.getClass().getMethod("isEnabled").invoke(found)) throw new IllegalArgumentException("Shader option is unavailable");
                found.getClass().getMethod("nextValue").invoke(found);
                call("saveShaderPackOptions");
            } else {
                String name = key.substring(7);
                if ("ANTIALIASING".equals(name)) type.getMethod("nextAntialiasingLevel", boolean.class).invoke(null, false);
                else if ("NORMAL_MAP".equals(name) || "SPECULAR_MAP".equals(name)) {
                    Field field = type.getField("NORMAL_MAP".equals(name) ? "configNormalMap" : "configSpecularMap");
                    field.setBoolean(null, !field.getBoolean(null));
                } else if ("OLD_HAND_LIGHT".equals(name) || "OLD_LIGHTING".equals(name)) {
                    Object property = type.getField("OLD_HAND_LIGHT".equals(name) ? "configOldHandLight" : "configOldLighting").get(null);
                    property.getClass().getMethod("nextValue").invoke(property);
                } else {
                    String fieldName;
                    float[] values;
                    if ("RENDER_RES_MUL".equals(name) || "SHADOW_RES_MUL".equals(name)) {
                        fieldName = "RENDER_RES_MUL".equals(name) ? "configRenderResMul" : "configShadowResMul";
                        values = new float[] {.5F, .70710677F, 1F, 1.4142135F, 2F};
                    } else if ("HAND_DEPTH_MUL".equals(name)) { fieldName = "configHandDepthMul"; values = new float[] {.0625F, .125F, .25F}; }
                    else throw new IllegalArgumentException("Unknown shader setting");
                    Field field = type.getField(fieldName);
                    float current = field.getFloat(null);
                    int index = 0;
                    for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - current) < .0001F) index = i;
                    field.setFloat(null, values[(index + 1) % values.length]);
                    call("SHADOW_RES_MUL".equals(name) ? "scheduleResizeShadow" : "scheduleResize");
                }
            }
            call("uninit");
            call("storeConfig");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Shader setting did not complete", failure); }
    }
}
