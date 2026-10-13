package net.minecraftforge.fml.common;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class ObfuscationReflectionHelper {
    private static final Map<String, String> NOTCH_FIELDS = loadNotchFields();

    private ObfuscationReflectionHelper() {
    }

    @SuppressWarnings("unchecked")
    public static <T, E> T getPrivateValue(Class<? super E> classToAccess, E instance, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                String mapped = NOTCH_FIELDS.get(classToAccess.getName().replace('.', '/') + "." + fieldName);
                Field field;
                try {
                    field = classToAccess.getDeclaredField(fieldName);
                } catch (NoSuchFieldException missing) {
                    if (mapped == null) continue;
                    field = classToAccess.getDeclaredField(mapped);
                }
                field.setAccessible(true);
                return (T) field.get(instance);
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Could not find field " + Arrays.toString(fieldNames) + " in " + classToAccess);
    }

    private static Map<String, String> loadNotchFields() {
        Map<String, String> fields = new HashMap<String, String>();
        InputStream input = ObfuscationReflectionHelper.class.getResourceAsStream("/notch-srg.srg");
        if (input == null) return fields;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("FD: ")) continue;
                String[] names = line.substring(4).split(" ");
                int notchSlash = names[0].lastIndexOf('/');
                int srgSlash = names[1].lastIndexOf('/');
                fields.put(names[0].substring(0, notchSlash) + "." + names[1].substring(srgSlash + 1),
                        names[0].substring(notchSlash + 1));
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not read standalone field mappings", failure);
        }
        return fields;
    }
}
