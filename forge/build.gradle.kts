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
    implementation(project(":core"))
    implementation(project(":patches"))

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
    archiveBaseName.set("rbw-forge-coremod")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes[
                "FMLCorePlugin"
        ] = "dev.rbw.forge.RbwLoadingPlugin"
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
        exclude("dev/rbw/core/ClientUiHooks.class")
        exclude("dev/rbw/patches/ClientOptionsTransformer*.class")
        exclude("dev/rbw/patches/RbwClientOptionsScreenFactory.class")
        exclude("rbwclient/gui/**")
        exclude("META-INF/services/dev.rbw.bootstrap.ClassTransformer")
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
                "dev/rbw/core/ClientUiHooks.class",
                "dev/rbw/patches/ClientOptionsTransformer.class",
                "dev/rbw/patches/RbwClientOptionsScreenFactory.class",
                "rbwclient/gui/RbwClientOptionsScreen.class",
                "META-INF/services/dev.rbw.bootstrap.ClassTransformer",
            )
            val leaked = forbidden.filter { archive.getEntry(it) != null }
            check(leaked.isEmpty()) {
                "Telemetry coremod contains retired UI artifacts: ${leaked.joinToString()}"
            }
        }
    }
}
