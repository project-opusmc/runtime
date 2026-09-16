import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipFile

plugins {
    `java-library`
}

val lwjglVersion = "2.9.2-nightly-20140822"
val lwjglCoordinate = "org.lwjgl.lwjgl:lwjgl:$lwjglVersion"
val lwjglSha1 = "7707204c9ffa5d91662de95f0a224e2f721b22af"
// Keep the Java and native ABI on the same proven 2.9.2 nightly. The Java
// compatibility classes below bypass the obsolete display-mode path, pin a
// viable 32-bit pixel format, and avoid the unsafe off-main-thread AppKit
// style-mask mutation while retaining user-driven native window resizing.
// The native build step below also disables LWJGL's AppKit-side
// NSOpenGLContext.update() callback; resize notification is observed by the
// Java render thread instead.
val lwjglMacOsNativeVersion = lwjglVersion
val lwjglMacOsNativeCoordinate =
    "org.lwjgl.lwjgl:lwjgl-platform:$lwjglMacOsNativeVersion:natives-osx@jar"
val lwjglMacOsNativeArchiveSha1 = "79f5ce2fea02e77fe47a3c745219167a542121d7"
val lwjglMacOsNativeArchiveSize = 468_116L
val replacementNative = "liblwjgl.dylib"
val replacementNativeSha1 = "9ade823779dae4be29556dc39ed28b42ba5508e1"
val replacementNativeSize = 838_892L
val resizeGuardNativeName = "libopus_lwjgl_resize_guard.dylib"
val resizeGuardNativeSha1 = "b80da68bc3de6c8823bcfdee42d660a90246a09f"
val resizeGuardNativeSize = 15_696L
val replacementRootClass = "org/lwjgl/MacOSXSysImplementation.class"
val patchedOpenGlClasses = setOf(
    "org/lwjgl/opengl/MacOSXDisplay.class",
    "org/lwjgl/opengl/MacOSXCanvasPeerInfo.class",
    "org/lwjgl/opengl/MacOSXCanvasPeerInfo\$1.class",
)

repositories {
    maven(url = "https://libraries.minecraft.net/")
}

val lwjglOriginal by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val lwjglMacOsNativeOriginal by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val patchedNative = layout.buildDirectory.file("patched-native/$replacementNative")
val resizeGuardSource = layout.projectDirectory.file(
    "src/main/native/opus_lwjgl_resize_guard.m",
)
val resizeGuardNative = layout.buildDirectory.file("patched-native/$resizeGuardNativeName")

val patchNativeResize = tasks.register("patchNativeResize") {
    group = "build"
    description = "Removes LWJGL's unsafe AppKit-side GL context update during live resize."
    inputs.files(lwjglMacOsNativeOriginal)
    outputs.file(patchedNative)

    doLast {
        val sourceArchive = lwjglMacOsNativeOriginal.singleFile
        val sourceBytes = ZipFile(sourceArchive).use { archive ->
            val entry = archive.getEntry(replacementNative)
                ?: error("Missing $replacementNative in $sourceArchive")
            archive.getInputStream(entry).use { it.readBytes() }
        }
        val bytes = sourceBytes.copyOf()

        fun findUnique(needle: ByteArray, label: String): Int {
            val matches = mutableListOf<Int>()
            for (start in 0..(bytes.size - needle.size)) {
                var equal = true
                for (index in needle.indices) {
                    if (bytes[start + index] != needle[index]) {
                        equal = false
                        break
                    }
                }
                if (equal) matches += start
            }
            check(matches.size == 1) {
                "Expected one $label machine-code site, found ${matches.size}"
            }
            return matches.single()
        }

        // x86_64: exact function body of
        // -[MacOSXOpenGLView _surfaceNeedsUpdate:] in the locked LWJGL
        // native binary. A short prologue is not specific enough because
        // many Objective-C methods share it.
        val x86_64Site = findUnique(
            byteArrayOf(
                0x55, 0x48, 0x89.toByte(), 0xe5.toByte(),
                0x48, 0x8b.toByte(), 0x05, 0xe5.toByte(), 0x3e, 0x03, 0x00,
                0x48, 0x8d.toByte(), 0x35, 0xde.toByte(), 0x3e, 0x03, 0x00,
                0x5d, 0xff.toByte(), 0xe0.toByte(),
            ),
            "x86_64 AppKit resize callback",
        )
        // i386: exact function body of the same callback in the 32-bit
        // slice. Keep both architecture patches because the artifact is a
        // universal binary even though current Java 8 runs x86_64.
        val i386Site = findUnique(
            byteArrayOf(
                0x55, 0x89.toByte(), 0xe5.toByte(), 0x83.toByte(), 0xec.toByte(), 0x08,
                0xe8.toByte(), 0x00, 0x00, 0x00, 0x00, 0x58, 0x8b.toByte(), 0x4d,
                0x08, 0x8b.toByte(), 0x80.toByte(), 0x01, 0xe0.toByte(), 0x01, 0x00,
                0x89.toByte(), 0x44, 0x24, 0x04, 0x89.toByte(), 0x0c, 0x24,
                0xe8.toByte(), 0xfd.toByte(), 0x8f.toByte(), 0x01, 0x00,
                0x83.toByte(), 0xc4.toByte(), 0x08, 0x5d, 0xc3.toByte(),
            ),
            "i386 AppKit resize callback",
        )
        check(bytes[x86_64Site] == 0x55.toByte())
        check(bytes[i386Site] == 0x55.toByte())
        bytes[x86_64Site] = 0xc3.toByte()
        bytes[i386Site] = 0xc3.toByte()

        val destination = patchedNative.get().asFile
        destination.parentFile.mkdirs()
        destination.writeBytes(bytes)
    }
}

val compileResizeGuardNative = tasks.register<Exec>("compileResizeGuardNative") {
    group = "build"
    description = "Builds the Cocoa/render-thread live-resize handshake for LWJGL 2."
    inputs.file(resizeGuardSource)
    outputs.file(resizeGuardNative)

    doFirst {
        val javaHome = File(System.getProperty("java.home"))
        val includeRoot = javaHome.resolve("include")
        val output = resizeGuardNative.get().asFile
        check(includeRoot.resolve("jni.h").isFile) {
            "JNI headers are missing from $javaHome"
        }
        output.parentFile.mkdirs()
        commandLine(
            "xcrun",
            "clang",
            "-arch",
            "x86_64",
            "-dynamiclib",
            "-O2",
            "-fno-ident",
            "-fvisibility=hidden",
            "-mmacosx-version-min=10.13",
            "-Wl,-install_name,@rpath/$resizeGuardNativeName",
            "-I${includeRoot.absolutePath}",
            "-I${includeRoot.resolve("darwin").absolutePath}",
            "-framework",
            "Cocoa",
            "-o",
            output.absolutePath,
            resizeGuardSource.asFile.absolutePath,
        )
    }
}

dependencies {
    compileOnly(lwjglCoordinate) {
        isTransitive = false
    }
    lwjglOriginal(lwjglCoordinate) {
        isTransitive = false
    }
    lwjglMacOsNativeOriginal(lwjglMacOsNativeCoordinate) {
        isTransitive = false
    }
}

tasks.jar {
    dependsOn(patchNativeResize, compileResizeGuardNative)
    archiveFileName.set("opus-lwjgl-macos-compat-$lwjglVersion.jar")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    manifest {
        attributes[
            "Implementation-Title"
        ] = "Opus LWJGL 2 macOS compatibility root package"
        attributes["Implementation-Version"] = lwjglVersion
        // Mojang's LWJGL 2 JAR seals every package. The compatibility JAR is
        // therefore a complete Java replacement for the LWJGL Java artifact,
        // so LaunchWrapper never has to define a sealed package from two JARs.
        attributes["Sealed"] = "true"
    }

    from({ zipTree(lwjglOriginal.singleFile) }) {
        include("org/lwjgl/**/*.class")
        exclude(replacementRootClass)
        exclude(*patchedOpenGlClasses.toTypedArray())
    }
    from(patchedNative)
    from(resizeGuardNative)
}

tasks.register("verifyMacOsLwjglCompatibilityArtifact") {
    group = "verification"
    description = "Verifies the complete sealed LWJGL Java replacement and macOS compatibility classes."
    dependsOn(tasks.jar)

    doLast {
        val source = lwjglOriginal.singleFile
        val nativeSource = lwjglMacOsNativeOriginal.singleFile
        val artifact = tasks.jar.get().archiveFile.get().asFile
        check(source.isFile) { "Missing locked LWJGL input: $source" }
        check(nativeSource.isFile) { "Missing locked LWJGL macOS native input: $nativeSource" }
        check(artifact.isFile) { "Missing LWJGL compatibility artifact: $artifact" }

        val sourceSha1 = MessageDigest.getInstance("SHA-1").digest(source.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        check(sourceSha1 == lwjglSha1) {
            "Unexpected LWJGL input SHA-1: $sourceSha1"
        }
        check(nativeSource.length() == lwjglMacOsNativeArchiveSize) {
            "Unexpected LWJGL macOS native input size: ${nativeSource.length()}"
        }
        val nativeSourceSha1 = MessageDigest.getInstance("SHA-1").digest(nativeSource.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        check(nativeSourceSha1 == lwjglMacOsNativeArchiveSha1) {
            "Unexpected LWJGL macOS native input SHA-1: $nativeSourceSha1"
        }

        JarFile(source).use { original ->
            JarFile(artifact).use { compatibility ->
                check(compatibility.manifest.mainAttributes.getValue("Sealed") == "true") {
                    "LWJGL compatibility root package must remain sealed"
                }

                val managedClassPattern = Regex("^org/lwjgl/.+\\.class$")
                val originalManagedClasses = original.entries().asSequence()
                    .map { entry -> entry.name }
                    .filter { name -> managedClassPattern.matches(name) }
                    .toSortedSet()
                val compatibilityClasses = compatibility.entries().asSequence()
                    .map { entry -> entry.name }
                    .filter { name -> name.endsWith(".class") }
                    .toSortedSet()
                check(compatibilityClasses == originalManagedClasses) {
                    "Compatibility JAR must contain the complete org.lwjgl Java class set"
                }

                val replacementClasses = patchedOpenGlClasses + replacementRootClass
                originalManagedClasses.filter { name -> name !in replacementClasses }.forEach { name ->
                    val originalBytes = original.getInputStream(original.getJarEntry(name)).use { it.readBytes() }
                    val compatibilityBytes = compatibility
                        .getInputStream(compatibility.getJarEntry(name))
                        .use { it.readBytes() }
                    check(originalBytes.contentEquals(compatibilityBytes)) {
                        "Compatibility JAR changed an unrelated LWJGL class: $name"
                    }
                }

                val replacement = compatibility
                    .getInputStream(compatibility.getJarEntry(replacementRootClass))
                    .use { it.readBytes() }
                val symbols = String(replacement, Charsets.ISO_8859_1)
                check(!symbols.contains("java/awt/Toolkit")) {
                    "Compatibility class still eagerly initializes AWT"
                }
                check(symbols.contains("com.apple.eio.FileManager")) {
                    "Compatibility class no longer preserves LWJGL's URL-opening behavior"
                }

                val displaySymbols = compatibility
                    .getInputStream(compatibility.getJarEntry("org/lwjgl/opengl/MacOSXDisplay.class"))
                    .use { String(it.readBytes(), Charsets.ISO_8859_1) }
                check(displaySymbols.contains("OPUS_MODERN_MACOS_DISPLAY_COMPAT_V1")) {
                    "Compatibility display class does not carry the modern macOS startup patch"
                }
                check(displaySymbols.contains("OPUS_COCOA_LIVE_RESIZE_HANDSHAKE_V10_APPKIT_DRAWABLE_UPDATE")) {
                    "Compatibility display class does not carry the Cocoa live-resize handshake"
                }
                check(displaySymbols.contains("opus.lwjgl.macos.liveResizeGuard")) {
                    "Compatibility display class cannot isolate the live-resize guard for M3 preview"
                }
                val peerInfoSymbols = compatibility
                    .getInputStream(compatibility.getJarEntry("org/lwjgl/opengl/MacOSXCanvasPeerInfo.class"))
                    .use { String(it.readBytes(), Charsets.ISO_8859_1) }
                check(peerInfoSymbols.contains("OPUS_MODERN_MACOS_PIXEL_FORMAT_COMPAT_V2")) {
                    "Compatibility peer-info class does not carry the pass-through pixel-format patch"
                }
                val nativeEntry = compatibility.getJarEntry(replacementNative)
                    ?: error("Compatibility artifact is missing $replacementNative")
                check(nativeEntry.size == replacementNativeSize) {
                    "Unexpected replacement native size: ${nativeEntry.size}"
                }
                val nativeBytes = compatibility.getInputStream(nativeEntry).use { it.readBytes() }
                val nativeSha1 = MessageDigest.getInstance("SHA-1").digest(nativeBytes)
                    .joinToString("") { byte -> "%02x".format(byte) }
                check(nativeSha1 == replacementNativeSha1) {
                    "Unexpected replacement native SHA-1: $nativeSha1"
                }
                val magic = nativeBytes.take(4)
                check(
                    magic == listOf(
                        0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(),
                    ) || magic == listOf(
                        0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte(),
                    ),
                ) {
                    "Replacement native is not a Mach-O dylib"
                }
                val guardEntry = compatibility.getJarEntry(resizeGuardNativeName)
                    ?: error("Compatibility artifact is missing $resizeGuardNativeName")
                check(guardEntry.size == resizeGuardNativeSize) {
                    "Unexpected resize guard size: ${guardEntry.size}"
                }
                val guardBytes = compatibility.getInputStream(guardEntry).use { it.readBytes() }
                val guardSha1 = MessageDigest.getInstance("SHA-1").digest(guardBytes)
                    .joinToString("") { byte -> "%02x".format(byte) }
                check(guardSha1 == resizeGuardNativeSha1) {
                    "Unexpected resize guard SHA-1: $guardSha1"
                }
                check(
                    guardBytes.take(4) == listOf(
                        0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte(),
                    ),
                ) {
                    "Resize guard is not a 64-bit Mach-O dylib"
                }
                check(
                    String(guardBytes, Charsets.ISO_8859_1)
                        .contains("OPUS_COCOA_LIVE_RESIZE_GUARD_V10_MAIN_QUEUE_INSTALL"),
                ) {
                    "Resize guard marker is missing"
                }
                val guardLoadCommands = providers.exec {
                    commandLine("otool", "-l", resizeGuardNative.get().asFile.absolutePath)
                }.standardOutput.asText.get()
                check(guardLoadCommands.lineSequence().any { it.trim() == "cmd LC_UUID" }) {
                    "Resize guard is missing the LC_UUID load command required by modern macOS"
                }
            }
        }

        val artifactUrl = artifact.toURI().toURL()
        URLClassLoader(arrayOf(artifactUrl, source.toURI().toURL()), null).use { loader ->
            val implementation = Class.forName(
                "org.lwjgl.MacOSXSysImplementation",
                true,
                loader,
            )
            val parent = implementation.superclass
            check(implementation.protectionDomain.codeSource.location == artifactUrl) {
                "Compatibility implementation did not win classpath precedence"
            }
            check(parent.protectionDomain.codeSource.location == artifactUrl) {
                "Sealed org.lwjgl parent class was loaded from the wrong JAR"
            }
            check(implementation.`package`.isSealed(artifactUrl)) {
                "Compatibility org.lwjgl package is not sealed to its own artifact"
            }

            val display = Class.forName("org.lwjgl.opengl.MacOSXDisplay", true, loader)
            val constructor = display.getDeclaredConstructor().apply { isAccessible = true }
            val instance = constructor.newInstance()
            display.getDeclaredMethod("setResizable", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(instance, true)
            val init = display.getDeclaredMethod("init").apply { isAccessible = true }
            val initialMode = init.invoke(instance)
            val modeClass = initialMode.javaClass
            check(modeClass.getMethod("getWidth").invoke(initialMode) == 1280)
            check(modeClass.getMethod("getHeight").invoke(initialMode) == 720)
            check(modeClass.getMethod("getBitsPerPixel").invoke(initialMode) == 32)
            check(modeClass.getMethod("getFrequency").invoke(initialMode) == 60)
            val availableModes = display.getDeclaredMethod("getAvailableDisplayModes")
                .apply { isAccessible = true }
                .invoke(instance) as Array<*>
            val availableMode = availableModes.single()
            check(
                modeClass.getMethod("getWidth").invoke(availableMode) == 1280 &&
                    modeClass.getMethod("getHeight").invoke(availableMode) == 720 &&
                    modeClass.getMethod("getBitsPerPixel").invoke(availableMode) == 32 &&
                    modeClass.getMethod("getFrequency").invoke(availableMode) == 60
            ) {
                "Compatibility display class did not bypass legacy display-mode enumeration"
            }
            check(display.protectionDomain.codeSource.location == artifactUrl) {
                "Compatibility display implementation did not win classpath precedence"
            }
            val peerInfo = Class.forName(
                "org.lwjgl.opengl.MacOSXCanvasPeerInfo",
                false,
                loader,
            )
            check(peerInfo.protectionDomain.codeSource.location == artifactUrl) {
                "Compatibility peer-info implementation did not win classpath precedence"
            }
            check(display.`package`.isSealed(artifactUrl)) {
                "Compatibility org.lwjgl.opengl package is not sealed to its own artifact"
            }
        }
    }
}

tasks.named("check") {
    dependsOn("verifyMacOsLwjglCompatibilityArtifact")
}
