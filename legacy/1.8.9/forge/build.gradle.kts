import org.gradle.api.file.DuplicatesStrategy
import java.util.jar.JarFile

plugins {
    `java-library`
}

repositories {
    maven(url = "https://maven.minecraftforge.net/")
    maven(url = "https://libraries.minecraft.net/")
}

dependencies {
    implementation(project(":bootstrap"))
    implementation(project(":common"))
    implementation(project(":legacy:1.8.9:patches"))

    // This is supplied by the managed Forge runtime. It must never be packed
    // into the Opus coremod because Forge owns LaunchWrapper and ASM 5.0.3.
    compileOnly("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9:universal")
    compileOnly("org.ow2.asm:asm-all:5.0.3")
    compileOnly("net.minecraft:launchwrapper:1.12") {
        isTransitive = false
    }
    // The managed Minecraft runtime supplies LWJGL 2 at game time. Compile
    // against the exact 1.8.9 version but never pack it into the coremod.
    compileOnly("org.lwjgl.lwjgl:lwjgl:2.9.3") {
        isTransitive = false
    }

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.ow2.asm:asm-all:5.0.3")
    testImplementation("net.minecraft:launchwrapper:1.12") {
        isTransitive = false
    }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    archiveBaseName.set("opus-forge-coremod")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes[
                "FMLCorePlugin"
        ] = "org.polydevs.opusmc.forge.OpusLoadingPlugin"
        attributes[
                "FMLCorePluginContainsFMLMod"
        ] = "false"
        attributes[
                "Implementation-Title"
        ] = "Opus Forge Coremod"
        attributes[
                "Implementation-Version"
        ] = project.version
    }

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
                .filter { artifact -> !artifact.name.startsWith("asm-") }
                .map { artifact -> zipTree(artifact) }
    }) {
        // Vanilla Minecraft is the sole production UI path. Retired UI,
        // standalone-loader, and service-registration classes must never enter
        // the Core Mod, even if a stale dependency is restored later.
        exclude("org/polydevs/opusmc/core/ClientConfigUi*.class")
        exclude("org/polydevs/opusmc/core/ClientUiHooks.class")
        exclude("org/polydevs/opusmc/patches/ClientOptionsTransformer*.class")
        exclude("org/polydevs/opusmc/patches/OpusClientOptionsScreenFactory.class")
        exclude("org/polydevs/opusmc/patches/WindowTitleTransformer*.class")
        exclude("org/polydevs/opusmc/client/gui/**")
        exclude("org/polydevs/opusmc/forge/BootTerminalTransformer*.class")
        exclude("org/polydevs/opusmc/forge/LoadingTerminalTransformer*.class")
        exclude("org/polydevs/opusmc/forge/OpusBootTerminal*.class")
        exclude("org/polydevs/opusmc/bootstrap/BootstrapArguments.class")
        exclude("org/polydevs/opusmc/bootstrap/BootstrapMain.class")
        exclude("org/polydevs/opusmc/bootstrap/TransformingClassLoader.class")
        exclude("META-INF/services/org.polydevs.opusmc.bootstrap.ClassTransformer")
        exclude("META-INF/MANIFEST.MF")
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
    }
}

tasks.register("verifyCoremodArtifact") {
    group = "verification"
    description = "Verifies that the coremod contains only the approved Retina input compatibility path."
    dependsOn(tasks.jar)

    doLast {
        val artifact = tasks.jar.get().archiveFile.get().asFile
        JarFile(artifact).use { archive ->
            val requiredRetinaInputClasses = listOf(
                "org/polydevs/opusmc/forge/RetinaDisplay.class",
                "org/polydevs/opusmc/patches/RetinaGuiInputTransformer.class",
            )
            val missingRetinaInputClasses = requiredRetinaInputClasses.filter {
                archive.getEntry(it) == null
            }
            check(missingRetinaInputClasses.isEmpty()) {
                "Core Mod is missing Retina input classes: ${missingRetinaInputClasses.joinToString()}"
            }

            val forbidden = listOf(
                "org/polydevs/opusmc/core/ClientConfigUi.class",
                "org/polydevs/opusmc/core/ClientUiHooks.class",
                "org/polydevs/opusmc/patches/ClientOptionsTransformer.class",
                "org/polydevs/opusmc/patches/OpusClientOptionsScreenFactory.class",
                "org/polydevs/opusmc/patches/WindowTitleTransformer.class",
                "org/polydevs/opusmc/client/gui/OpusClientOptionsScreen.class",
                "org/polydevs/opusmc/forge/BootTerminalTransformer.class",
                "org/polydevs/opusmc/forge/LoadingTerminalTransformer.class",
                "org/polydevs/opusmc/forge/OpusBootTerminal.class",
                "org/polydevs/opusmc/bootstrap/BootstrapArguments.class",
                "org/polydevs/opusmc/bootstrap/BootstrapMain.class",
                "org/polydevs/opusmc/bootstrap/TransformingClassLoader.class",
                "META-INF/services/org.polydevs.opusmc.bootstrap.ClassTransformer",
            )
            val leaked = forbidden.filter { archive.getEntry(it) != null }.toMutableList()
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement().name
                if (entry.startsWith("org/polydevs/opusmc/forge/BootTerminalTransformer\$")
                    || entry.startsWith("org/polydevs/opusmc/forge/LoadingTerminalTransformer\$")
                    || entry.startsWith("org/polydevs/opusmc/forge/OpusBootTerminal\$")
                    || entry.startsWith("org/polydevs/opusmc/client/gui/")
                    || entry.startsWith("org/polydevs/opusmc/core/ClientConfigUi")
                    || entry.startsWith("org/polydevs/opusmc/core/ClientUiHooks")
                    || entry.startsWith("org/polydevs/opusmc/patches/ClientOptionsTransformer")
                    || entry.startsWith("org/polydevs/opusmc/patches/OpusClientOptionsScreenFactory")
                    || entry.startsWith("org/polydevs/opusmc/patches/WindowTitleTransformer")
                    || entry.startsWith("org/polydevs/opusmc/bootstrap/BootstrapArguments")
                    || entry.startsWith("org/polydevs/opusmc/bootstrap/BootstrapMain")
                    || entry.startsWith("org/polydevs/opusmc/bootstrap/TransformingClassLoader")
                ) {
                    leaked.add(entry)
                }
            }
            val forbiddenUiSymbols = listOf(
                "org/polydevs/opusmc/client/gui/",
                "org.polydevs.opusmc.client.gui.",
                "net/minecraft/client/gui/",
                "net.minecraft.client.gui.",
                "net/minecraftforge/client/event/GuiScreenEvent",
                "net/minecraftforge/fml/client/registry/ClientRegistry",
                "net/minecraft/client/settings/KeyBinding",
                "org/lwjgl/input/Keyboard",
                "org/lwjgl/input/Mouse",
                "org/cef/",
                "com/cef/",
                "opus-webview-helper",
                "opus.window.title",
                // A stale UI integration can avoid a direct GUI class
                // dependency through reflection or a string-dispatched hook.
                // Keep the production Core Mod unable to own a screen/input
                // path even through that escape hatch.
                "displayGuiScreen",
                "currentScreen",
                "onGuiOpening",
                "registerKeyBinding",
                "keyTyped",
                "mouseClicked",
                "handleMouseInput",
            )
            val approvedRetinaInputSymbols = mapOf(
                "org/polydevs/opusmc/forge/OpusForgeClassTransformer.class" to setOf(
                    "net.minecraft.client.gui.",
                ),
                "org/polydevs/opusmc/patches/RetinaGuiInputTransformer.class" to setOf(
                    "net.minecraft.client.gui.",
                    "org/lwjgl/input/Mouse",
                ),
            )
            val classEntries = archive.entries()
            while (classEntries.hasMoreElements()) {
                val entry = classEntries.nextElement()
                if (!entry.name.endsWith(".class")) {
                    continue
                }
                val symbols = archive.getInputStream(entry).use { input ->
                    String(input.readBytes(), Charsets.ISO_8859_1)
                }
                val approvedSymbols = approvedRetinaInputSymbols[entry.name].orEmpty()
                forbiddenUiSymbols
                    .filter { symbol -> symbols.contains(symbol) && symbol !in approvedSymbols }
                    .forEach { symbol -> leaked.add("${entry.name} references forbidden UI symbol $symbol") }
            }
            check(leaked.isEmpty()) {
                "Core Mod contains retired UI or standalone-loader artifacts: ${leaked.joinToString()}"
            }
        }
    }
}
