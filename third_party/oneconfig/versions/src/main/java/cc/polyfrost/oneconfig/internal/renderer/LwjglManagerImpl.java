package cc.polyfrost.oneconfig.internal.renderer;

import cc.polyfrost.oneconfig.libs.deencapsulation.Deencapsulation;
import cc.polyfrost.oneconfig.renderer.LwjglManager;
import cc.polyfrost.oneconfig.renderer.NanoVGHelper;
import cc.polyfrost.oneconfig.renderer.TinyFD;
import cc.polyfrost.oneconfig.renderer.asset.AssetHelper;
import cc.polyfrost.oneconfig.renderer.font.FontHelper;
import cc.polyfrost.oneconfig.renderer.scissor.ScissorHelper;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;
//#if FORGE==1 && MC<=11202
import org.objectweb.asm.commons.RemappingClassAdapter;
//#else
//$$ import org.objectweb.asm.commons.ClassRemapper;
//#endif
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

@SuppressWarnings("deprecation")
public class LwjglManagerImpl
        extends URLClassLoader
        implements LwjglManager {

    private static final Logger LOGGER = LogManager.getLogger("OneConfig LWJGL Manager");
    private static final boolean isPojav = checkPojav();

    private static final Object unsafeInstance;
    private static final Method defineClassMethod;
    private static final Map<String, String> remappingMap;

    private static final String LWJGL_FUNCTION_PROVIDER =
            "cc.polyfrost.oneconfig.internal.plugin.hooks.Lwjgl2FunctionProvider";

    /**
     * LWJGL 2 and LWJGL 3 both call their core native library "lwjgl". They
     * are loaded by the same JVM, so allowing LWJGL 3 to claim that basename
     * makes macOS resolve Minecraft's later LWJGL 2 load to the wrong image.
     * Keep the two native runtimes addressable independently.
     */
    private static final String LWJGL3_LIBRARY_NAME = "opus_lwjgl3";

    private final Set<String> classLoaderInclude = new CopyOnWriteArraySet<>();
    private final Set<String> locallyDefinedPackages = new CopyOnWriteArraySet<>();
    private final Map<String, Class<?>> classCache = new HashMap<>();

    private static final String JAR_NAME = "oneconfig-lwjgl3.jar";
    private static final URL jarFile = getJarFile();

    private final AssetHelper assetHelper;
    private final NanoVGHelper nanoVGHelper;
    private final ScissorHelper scissorHelper;
    private final FontHelper fontHelper;
    private final TinyFD tinyFD;

    public LwjglManagerImpl() throws ReflectiveOperationException {
        super(new URL[]{jarFile}, LwjglManager.class.getClassLoader());

        ClassLoader classLoader = isPojav ? getClass().getClassLoader() : this;
        if (!isPojav) {
            // Internal accessors
            classLoaderInclude.add("cc.polyfrost.oneconfig.internal.renderer.FontHelperImpl");
            classLoaderInclude.add("cc.polyfrost.oneconfig.internal.renderer.ScissorHelperImpl");
            classLoaderInclude.add("cc.polyfrost.oneconfig.internal.renderer.NanoVGHelperImpl");
            classLoaderInclude.add("cc.polyfrost.oneconfig.internal.renderer.AssetHelperImpl");
            classLoaderInclude.add("cc.polyfrost.oneconfig.internal.renderer.TinyFDImpl");
            // Provider
            classLoaderInclude.add(LWJGL_FUNCTION_PROVIDER);
            // Lwjgl
            Arrays.asList("nanovg", "actually3"
                    //#if MC<=11202
                    , "stb", "util.tinyfd", "system"
                    //#endif
            ).forEach(it -> classLoaderInclude.add("org.lwjgl." + it + "."));
            classLoaderInclude.add("org.lwjgl.Version"); // won't work when remapped

            //#if MC<=11202
            // Keep the path somewhere for LWJGL2 after initializing LWJGL3
            // (this is read in the Lwjgl2FunctionProvider class)
            String libraryPath = System.getProperty("org.lwjgl.librarypath", "");
            if (!libraryPath.isEmpty()) {
                System.setProperty("oneconfig.lwjgl2.librarypath", libraryPath);
            }

            // Setup LW3 config
            Class<?> configClass = Class.forName("org.lwjgl.system.Configuration", true, classLoader);
            Method setMethod = configClass.getMethod("set", Object.class);

            // The embedded LWJGL3 runtime is repacked below with this core
            // native basename. Set both the configuration object and the
            // system property because Configuration reads the property during
            // class initialization in some LWJGL3 builds.
            Object libraryNameField = configClass.getField("LIBRARY_NAME").get(null);
            setMethod.invoke(libraryNameField, LWJGL3_LIBRARY_NAME);
            System.setProperty("org.lwjgl.libname", LWJGL3_LIBRARY_NAME);

            Object extractDirField = configClass.getField("SHARED_LIBRARY_EXTRACT_DIRECTORY").get(null);
            setMethod.invoke(extractDirField, new File("./OneConfig/temp").getAbsolutePath());

            // stop trying to Class.forName("true") ffs
            Object debugStreamField = configClass.getField("DEBUG_STREAM").get(null);
            setMethod.invoke(debugStreamField, System.err);
            //#endif
        }

        // Initialize helper instances
        try {
            nanoVGHelper = (NanoVGHelper) Class.forName("cc.polyfrost.oneconfig.internal.renderer.NanoVGHelperImpl", true, classLoader).getConstructor().newInstance();
            scissorHelper = (ScissorHelper) Class.forName("cc.polyfrost.oneconfig.internal.renderer.ScissorHelperImpl", true, classLoader).getConstructor().newInstance();
            assetHelper = (AssetHelper) Class.forName("cc.polyfrost.oneconfig.internal.renderer.AssetHelperImpl", true, classLoader).getConstructor().newInstance();
            fontHelper = (FontHelper) Class.forName("cc.polyfrost.oneconfig.internal.renderer.FontHelperImpl", true, classLoader).getConstructor().newInstance();
            tinyFD = (TinyFD) Class.forName("cc.polyfrost.oneconfig.internal.renderer.TinyFDImpl", true, classLoader).getConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            exception.printStackTrace();
            throw new RuntimeException("fuck", exception);
        }
    }

    private boolean canBeSharedWithMc(String name) {
        for (String implClass : classLoaderInclude) {
            if (name.startsWith(implClass)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!canBeSharedWithMc(name) && !isPojav) {
            synchronized (getClassLoadingLock(name)) {
                Class<?> cls = findLoadedClass(name);
                if (cls == null) {
                    cls = findClass(name);
                }
                if (resolve) {
                    resolveClass(cls);
                }
                return cls;
            }
        }
        return getParent().loadClass(name);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (isPojav) {
            return getParent().loadClass(name);
        }
        String remappedName = remappingMap.getOrDefault(
                name.replace('.', '/'),
                name
        ).replace('/', '.');
        String unmappedName = remappingMap.keySet().stream()
                .filter(it -> remappingMap.get(it).equalsIgnoreCase(remappedName.replace('.', '/')))
                .findFirst()
                .orElse(name)
                .replace('/', '.');

        // if it exists in the remapping map
        if (!remappedName.equals(unmappedName)) {
            // if we're being requested class.A and see that it has class.B as
            // a remapped value, load it through the parent classloader
            if (name.equals(unmappedName)) {
                return getParent().loadClass(name);
            }
            // Otherwise, if we get class.B and see that it's class.A remapped,
            // then we need to transform and load class.B ourselves
        }
        if (classCache.containsKey(name)) {
            // ayo cache :D
            return classCache.get(name);
        }

        if (canBeSharedWithMc(remappedName)) {
            // Delegate share-able requests to the parent classloader
            return getParent().loadClass(remappedName);
        }

        try {
            String path = unmappedName.replace('.', '/').concat(".class");
            URL classUrl = null;
            // First check the lwjgl jar
            Enumeration<URL> urls = getResources(path);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();

                // check that the URL is provided from the custom lwjgl jar
                if (!url.toString().contains(JAR_NAME)) continue;

                classUrl = url;
                break;
            }

            if (classUrl == null) {
                classUrl = getParent().getResource(path);
                if (classUrl == null) {
                    throw new ClassNotFoundException(name);
                }
            }

            byte[] classBuffer = IOUtils.toByteArray(classUrl);

            // define class through Unsafe to bypass package seal checking
            Class<?> clazz = defineClassBypass(unmappedName, classBuffer);
            classCache.put(remappedName, clazz);
            return clazz;
        } catch (IOException ignored) {
        }
        throw new ClassNotFoundException(name);
    }

    private Class<?> defineClassBypass(String name, byte[] b) {
        name = remappingMap.getOrDefault(name.replace('.', '/'), name)
                .replace('/', '.');

        ClassReader classReader = new ClassReader(b);
        Remapper remapper = new Remapper() {
            @Override
            public String map(String desc) {
                if (remappingMap.containsKey(desc)) {
                    return remappingMap.get(desc);
                }
                return desc;
            }
        };
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS);
        //#if FORGE==1 && MC<=11202
        RemappingClassAdapter classRemapper = new RemappingClassAdapter(classWriter, remapper);
        //#else
        //$$ ClassRemapper classRemapper = new ClassRemapper(classWriter, remapper);
        //#endif
        classReader.accept(classRemapper, ClassReader.EXPAND_FRAMES);
        b = classWriter.toByteArray();

        //#if MC<=11202
        if (name.equalsIgnoreCase("org.lwjgl.nanovg.NanoVGGLConfig")) {
            ClassNode node = new ClassNode();
            classReader = new ClassReader(b);
            classReader.accept(node, ClassReader.EXPAND_FRAMES);

            transform(node);

            classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS);
            node.accept(classWriter);
            b = classWriter.toByteArray();
        }
        //#endif

        ensurePackageDefined(name);

        try {
            return (Class<?>) defineClassMethod.invoke(unsafeInstance, name, b, 0, b.length, /*classLoader = */this, null);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("whoops...", e);
        }
    }

    /**
     * Unsafe#defineClass does not create package metadata for this isolated
     * loader. LWJGL reads that metadata during Version initialization, so a
     * missing local Package object causes Class#getPackage() to return null.
     * Track packages ourselves because getPackage() may return an inherited
     * LWJGL 2 package from Minecraft's parent classloader.
     */
    private void ensurePackageDefined(String className) {
        int separator = className.lastIndexOf('.');
        if (separator <= 0) {
            return;
        }

        String packageName = className.substring(0, separator);
        synchronized (locallyDefinedPackages) {
            if (!locallyDefinedPackages.add(packageName)) {
                return;
            }

            try {
                definePackage(packageName, null, null, null, null, null, null, null);
            } catch (IllegalArgumentException ignored) {
                // This loader already defined the package concurrently.
            }
        }
    }

    //#if MC<=11202
    private void transform(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.name.equals("configGL")) {
                InsnList list = new InsnList();

                list.add(new VarInsnNode(Opcodes.LLOAD, 0));
                list.add(new TypeInsnNode(Opcodes.NEW, "cc/polyfrost/oneconfig/internal/plugin/hooks/Lwjgl2FunctionProvider"));
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "cc/polyfrost/oneconfig/internal/plugin/hooks/Lwjgl2FunctionProvider",
                        "<init>",
                        "()V",
                        false
                ));
                list.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/nanovg/NanoVGGLConfig",
                        "config",
                        "(JLorg/lwjgl/system/FunctionProvider;)V",
                        false
                ));
                list.add(new InsnNode(Opcodes.RETURN));

                method.instructions.clear();
                method.instructions.insert(list);
            }
        }
    }
    //#endif

    static {
        registerAsParallelCapable();

        if (!isPojav) {
            remappingMap = new HashMap<>();
            //#if MC<=11202
            remappingMap.put("org/lwjgl/BufferUtils", "org/lwjgl/actually3/BufferUtils");
            remappingMap.put("org/lwjgl/PointerBuffer", "org/lwjgl/actually3/PointerBuffer");
            remappingMap.put("org/lwjgl/CLongBuffer", "org/lwjgl/actually3/CLongBuffer");
            //#endif

            Class<?> unsafeClass;
            try {
                unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
            } catch (Throwable throwable) {
                try {
                    unsafeClass = Class.forName("sun.misc.Unsafe");
                } catch (Throwable throwable1) {
                    throw new RuntimeException("Could not find Unsafe class", throwable);
                }
            }

            try {
                try {
                    Deencapsulation.deencapsulate(Object.class);
                    Deencapsulation.deencapsulate(unsafeClass);
                } catch (Throwable ignored) {
                }

                Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                unsafeInstance = unsafeField.get(null);

                defineClassMethod = unsafeClass.getDeclaredMethod(
                        "defineClass",
                        String.class,
                        byte[].class,
                        int.class,
                        int.class,
                        ClassLoader.class,
                        ProtectionDomain.class
                );
                defineClassMethod.setAccessible(true);
            } catch (ReflectiveOperationException exception) {
                throw new RuntimeException("Error while fetching Unsafe instance.", exception);
            }
        } else {
            remappingMap = null;
            unsafeInstance = null;
            defineClassMethod = null;
        }
    }

    private static synchronized URL getJarFile() {
        if (isPojav) return null;
        final File tempDirectory = new File("./OneConfig/temp");
        if (!tempDirectory.exists() && !tempDirectory.mkdirs()) {
            throw new RuntimeException("Could not create OneConfig native runtime directory: " + tempDirectory);
        }
        final File tempJar = new File(tempDirectory, JAR_NAME);
        tempJar.deleteOnExit();

        // Make the name available before the custom LWJGL3 class loader can
        // initialize org.lwjgl.system.Configuration.
        System.setProperty("org.lwjgl.libname", LWJGL3_LIBRARY_NAME);
        String jarName =
                //#if MC<=11202
                "lwjgl-legacy"
                //#elseif MC<=11900
                //$$ "lwjgl-pre-1.19"
                //#else
                //$$ "lwjgl-post-1.19"
                //#endif
        ;
        //#if MC>11202 && MC<=11900
        //$$ String osArch = System.getProperty("os.arch");
        //$$ jarName = jarName + "-" + ((osArch.startsWith("arm") || osArch.startsWith("aarch64") || !osArch.contains("64")) ? "arm" : "noarm");
        //#endif
        try (InputStream in = LwjglManagerImpl.class.getResourceAsStream("/" + jarName + ".jar")) {
            if (in == null) {
                throw new IOException("Missing embedded LWJGL3 runtime: " + jarName + ".jar");
            }

            // The dependency is supplied as a nested jar. Repack only its
            // native entry names; Java classes remain byte-for-byte intact.
            // The .sha1 sidecars are renamed with their binaries so LWJGL's
            // native verification continues to work.
            try (JarInputStream input = new JarInputStream(in);
                 JarOutputStream output = new JarOutputStream(new FileOutputStream(tempJar))) {
                JarEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = input.getNextJarEntry()) != null) {
                    JarEntry copy = new JarEntry(renameCoreNativeEntry(entry));
                    if (entry.getTime() >= 0) {
                        copy.setTime(entry.getTime());
                    }
                    output.putNextEntry(copy);
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                    output.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not prepare isolated LWJGL3 runtime", e);
        }
        try {
            return tempJar.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private static String renameCoreNativeEntry(JarEntry entry) {
        String name = entry.getName();
        int separator = name.lastIndexOf('/');
        String directory = separator < 0 ? "" : name.substring(0, separator + 1);
        String fileName = separator < 0 ? name : name.substring(separator + 1);

        String replacement;
        switch (fileName) {
            case "liblwjgl.dylib":
                replacement = "lib" + LWJGL3_LIBRARY_NAME + ".dylib";
                break;
            case "liblwjgl.dylib.sha1":
                replacement = "lib" + LWJGL3_LIBRARY_NAME + ".dylib.sha1";
                break;
            case "liblwjgl.dylib.git":
                replacement = "lib" + LWJGL3_LIBRARY_NAME + ".dylib.git";
                break;
            case "liblwjgl.so":
                replacement = "lib" + LWJGL3_LIBRARY_NAME + ".so";
                break;
            case "liblwjgl.so.sha1":
                replacement = "lib" + LWJGL3_LIBRARY_NAME + ".so.sha1";
                break;
            case "liblwjgl.so.git":
                replacement = "lib" + LWJGL3_LIBRARY_NAME + ".so.git";
                break;
            case "lwjgl.dll":
                replacement = LWJGL3_LIBRARY_NAME + ".dll";
                break;
            case "lwjgl.dll.sha1":
                replacement = LWJGL3_LIBRARY_NAME + ".dll.sha1";
                break;
            case "lwjgl.dll.git":
                replacement = LWJGL3_LIBRARY_NAME + ".dll.git";
                break;
            default:
                return name;
        }
        return directory + replacement;
    }

    private static boolean checkPojav() {
        try {
            Class.forName("org.lwjgl.glfw.CallbackBridge");
            LOGGER.warn("Pojav detected, letting Pojav handle LWJGL.");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public NanoVGHelper getNanoVGHelper() {
        return nanoVGHelper;
    }

    @Override
    public ScissorHelper getScissorHelper() {
        return scissorHelper;
    }

    @Override
    public AssetHelper getAssetHelper() {
        return assetHelper;
    }

    @Override
    public FontHelper getFontHelper() {
        return fontHelper;
    }

    @Override
    public TinyFD getTinyFD() {
        return tinyFD;
    }
}
