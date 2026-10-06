package net.minecraftforge.fml.common;

import java.lang.reflect.Field;
import java.util.Arrays;

public final class ObfuscationReflectionHelper {
    private ObfuscationReflectionHelper() {
    }

    @SuppressWarnings("unchecked")
    public static <T, E> T getPrivateValue(Class<? super E> classToAccess, E instance, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                Field field = classToAccess.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (T) field.get(instance);
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Could not find field " + Arrays.toString(fieldNames) + " in " + classToAccess);
    }
}
