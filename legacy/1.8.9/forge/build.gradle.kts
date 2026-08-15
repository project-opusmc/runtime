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
        // The old ASM/reflection UI remains as migration evidence only. The
        // typed Forge client mod is the sole production UI path, so none of
        // these classes or its service registration may enter the coremod.
        exclude("org/polydevs/opusmc/core/ClientUiHooks.class")
        exclude("org/polydevs/opusmc/patches/ClientOptionsTransformer*.class")
        exclude("org/polydevs/opusmc/patches/OpusClientOptionsScreenFactory.class")
        exclude("org/polydevs/opusmc/client/gui/**")
        exclude("META-INF/services/org.polydevs.opusmc.bootstrap.ClassTransformer")
        exclude("META-INF/MANIFEST.MF")
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
    }
}

tasks.register("verifyCoremodArtifact") {
    group = "verification"
    description = "Verifies that the telemetry coremod contains no legacy Opus UI path."
    dependsOn(tasks.jar)

    doLast {
        val artifact = tasks.jar.get().archiveFile.get().asFile
        JarFile(artifact).use { archive ->
            val forbidden = listOf(
                "org/polydevs/opusmc/core/ClientUiHooks.class",
                "org/polydevs/opusmc/patches/ClientOptionsTransformer.class",
                "org/polydevs/opusmc/patches/OpusClientOptionsScreenFactory.class",
                "org/polydevs/opusmc/client/gui/OpusClientOptionsScreen.class",
                "META-INF/services/org.polydevs.opusmc.bootstrap.ClassTransformer",
            )
            val leaked = forbidden.filter { archive.getEntry(it) != null }
            check(leaked.isEmpty()) {
                "Telemetry coremod contains retired UI artifacts: ${leaked.joinToString()}"
            }
        }
    }
}
