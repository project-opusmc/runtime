import org.gradle.api.file.DuplicatesStrategy
import java.util.jar.JarFile

plugins {
    `java-library`
}

repositories {
    maven(url = "https://libraries.minecraft.net/")
    mavenCentral()
}

dependencies {
    implementation(project(":bootstrap"))
    implementation(project(":common"))
    implementation(project(":legacy:1.8.9:patches"))

    compileOnly("org.ow2.asm:asm-all:5.0.3")
    compileOnly("net.minecraft:launchwrapper:1.12") {
        isTransitive = false
    }
    compileOnly("org.lwjgl.lwjgl:lwjgl:2.9.3") {
        isTransitive = false
    }
    compileOnly("org.apache.logging.log4j:log4j-api:2.0-beta9")
    compileOnly("org.apache.logging.log4j:log4j-core:2.0-beta9")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.ow2.asm:asm-all:5.0.3")
    testImplementation("net.minecraft:launchwrapper:1.12") {
        isTransitive = false
    }
    testRuntimeOnly("net.sf.jopt-simple:jopt-simple:4.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    archiveBaseName.set("opus-standalone-loader")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes[
                "Implementation-Title"
        ] = "Opus Standalone Loader"
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
        exclude("org/polydevs/opusmc/core/ClientConfigUi*.class")
        exclude("org/polydevs/opusmc/core/ClientUiHooks.class")
        exclude("org/polydevs/opusmc/patches/ClientOptionsTransformer*.class")
        exclude("org/polydevs/opusmc/patches/OpusClientOptionsScreenFactory.class")
        exclude("org/polydevs/opusmc/patches/WindowTitleTransformer*.class")
        exclude("org/polydevs/opusmc/client/gui/**")
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

tasks.register("verifyStandaloneArtifact") {
    group = "verification"
    description = "Verifies that the standalone loader contains only verified standalone components."
    dependsOn(tasks.jar)

    doLast {
        val artifact = tasks.jar.get().archiveFile.get().asFile
        JarFile(artifact).use { archive ->
            val requiredEntries = listOf(
                "notch-srg.srg",
                "org/polydevs/opusmc/standalone/OpusTweaker.class",
                "org/polydevs/opusmc/standalone/OpusStandaloneClassTransformer.class",
                "org/polydevs/opusmc/standalone/StandaloneLifecycleTransformer.class",
                "org/polydevs/opusmc/standalone/StandaloneTelemetryLifecycle.class",
                "net/minecraftforge/common/MinecraftForge.class",
                "org/polydevs/opusmc/forge/RetinaDisplay.class",
                "net/minecraftforge/fml/common/eventhandler/EventBus.class",
                "net/minecraftforge/fml/common/eventhandler/Cancelable.class",
            )
            val missing = requiredEntries.filter { archive.getEntry(it) == null }
            check(missing.isEmpty()) {
                "Standalone Loader is missing required entries: ${missing.joinToString()}"
            }

            val forbidden = listOf(
                "org/polydevs/opusmc/bootstrap/BootstrapArguments.class",
                "org/polydevs/opusmc/bootstrap/BootstrapMain.class",
                "org/polydevs/opusmc/bootstrap/TransformingClassLoader.class",
                "META-INF/services/org.polydevs.opusmc.bootstrap.ClassTransformer",
            )
            val leaked = forbidden.filter { archive.getEntry(it) != null }.toMutableList()
            check(leaked.isEmpty()) {
                "Standalone Loader contains leaked legacy classes: ${leaked.joinToString()}"
            }
        }
    }
}
