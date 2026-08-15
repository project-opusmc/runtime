package org.polydevs.opusmc.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;

public final class TransformingClassLoader extends URLClassLoader {
    private static final String GENERATED_GAME_PREFIX = "org.polydevs.opusmc.client.gui.";
    private static final String[] PARENT_FIRST_PREFIXES = {
            "java.",
            "javax.",
            "sun.",
            "com.sun.",
            "jdk.",
            "apple.",
            "org.w3c.",
            "org.xml.",
            "org.ietf.",
            "org.polydevs.opusmc.",
            "org.objectweb.asm."
    };

    private final TransformerChain transformerChain;

    public TransformingClassLoader(URL[] urls, ClassLoader parent, TransformerChain transformerChain) {
        super(urls, parent);
        this.transformerChain = transformerChain;
    }

    /**
     * Defines a small Opus-owned class in the game class loader.
     *
     * <p>The bootstrap and core jars deliberately load parent-first, while
     * Minecraft's obfuscated classes load in this child loader. A generated
     * UI class must therefore be defined here so that its {@code GuiScreen}
     * superclass resolves against the same Minecraft runtime.</p>
     */
    public Class<?> defineGeneratedGameClass(String className, byte[] bytecode) {
        if (className == null || !className.startsWith(GENERATED_GAME_PREFIX)) {
            throw new IllegalArgumentException("Generated game class has an invalid name: " + className);
        }
        if (bytecode == null || bytecode.length == 0) {
            throw new IllegalArgumentException("Generated game class bytecode cannot be empty");
        }

        synchronized (getClassLoadingLock(className)) {
            Class<?> loaded = findLoadedClass(className);
            if (loaded != null) {
                return loaded;
            }
            definePackageIfNeeded(className);
            return defineClass(className, bytecode, 0, bytecode.length);
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (isParentFirst(name)) {
                    loaded = super.loadClass(name, false);
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException notFoundLocally) {
                        loaded = super.loadClass(name, false);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String resourceName = name.replace('.', '/') + ".class";
        URL resource = findResource(resourceName);
        if (resource == null) {
            throw new ClassNotFoundException(name);
        }

        try {
            byte[] original = readAll(resource);
            byte[] transformed = transformerChain.transform(name, original);
            definePackageIfNeeded(name);
            return defineClass(name, transformed, 0, transformed.length);
        } catch (Exception exception) {
            throw new ClassNotFoundException("Failed to transform " + name, exception);
        }
    }

    private void definePackageIfNeeded(String className) {
        int separator = className.lastIndexOf('.');
        if (separator < 0) {
            return;
        }
        String packageName = className.substring(0, separator);
        if (getPackage(packageName) == null) {
            try {
                definePackage(packageName, null, null, null, null, null, null, null);
            } catch (IllegalArgumentException alreadyDefinedByAnotherThread) {
                if (getPackage(packageName) == null) {
                    throw alreadyDefinedByAnotherThread;
                }
            }
        }
    }

    private static boolean isParentFirst(String className) {
        for (String prefix : PARENT_FIRST_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readAll(URL resource) throws IOException {
        try (InputStream input = resource.openStream();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
