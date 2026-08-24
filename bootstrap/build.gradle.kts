import java.util.jar.JarFile

plugins {
    `java-library`
}

dependencies {
    implementation(project(":common"))
    compileOnly("net.minecraft:launchwrapper:1.12") {
        isTransitive = false
    }

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

repositories {
    maven(url = "https://libraries.minecraft.net/")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.polydevs.opusmc.bootstrap.ForgeBootstrapMain"
    }

    // These sources remain available only as historical compatibility evidence.
    // They must never enter an artifact that a production launcher can execute:
    // Forge owns the game class loader and ForgeBootstrapMain is the sole entry.
    exclude("org/polydevs/opusmc/bootstrap/BootstrapArguments.class")
    exclude("org/polydevs/opusmc/bootstrap/BootstrapMain.class")
    exclude("org/polydevs/opusmc/bootstrap/TransformingClassLoader.class")
}

tasks.register("verifyBootstrapArtifact") {
    group = "verification"
    description = "Verifies that the production bootstrap exposes only the Forge entry path."
    dependsOn(tasks.jar)

    doLast {
        val artifact = tasks.jar.get().archiveFile.get().asFile
        JarFile(artifact).use { archive ->
            val mainClass = archive.manifest?.mainAttributes?.getValue("Main-Class")
            check(mainClass == "org.polydevs.opusmc.bootstrap.ForgeBootstrapMain") {
                "Production bootstrap must use ForgeBootstrapMain, got: $mainClass"
            }

            val retiredPrefixes = listOf(
                "org/polydevs/opusmc/bootstrap/BootstrapArguments",
                "org/polydevs/opusmc/bootstrap/BootstrapMain",
                "org/polydevs/opusmc/bootstrap/TransformingClassLoader",
            )
            val leaked = mutableListOf<String>()
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement().name
                if (retiredPrefixes.any { prefix -> entry.startsWith(prefix) }) {
                    leaked.add(entry)
                }
            }
            check(leaked.isEmpty()) {
                "Production bootstrap contains retired standalone loader entries: ${leaked.joinToString()}"
            }
        }
    }
}
