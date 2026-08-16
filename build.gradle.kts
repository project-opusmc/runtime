import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.compile.JavaCompile
import java.security.MessageDigest

plugins {
    base
}

allprojects {
    group = "org.polydevs.opusmc"
    version = "0.0.1"

    repositories {
        mavenCentral()
    }

    dependencyLocking {
        lockAllConfigurations()
    }
}

subprojects {
    apply(plugin = "java-library")

    // Pin the compiler toolchain so Runtime artifact bytes are reproducible
    // regardless of the JDK that launches Gradle. Without this, the same
    // source compiled by JDK 21 on CI and JDK 25 locally produced different
    // bootstrap/coremod bytes and broke the release-lock SHA-256 contract.
    extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(8)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-options", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

val forgeClientModDirectory = file("legacy/1.8.9/client")
val forgeClientModArtifact = file(
    "${forgeClientModDirectory}/build/libs/opus-forge-client-0.0.1-preview.3.jar",
)

val prepareForgeClientMod = tasks.register<Exec>("prepareForgeClientMod") {
    group = "build"
    description = "Builds and verifies the isolated typed Forge client-mod artifact."
    workingDir(forgeClientModDirectory)
    commandLine(forgeClientModDirectory.resolve("gradlew").absolutePath, "verifyClientArtifact")
    inputs.files(fileTree(forgeClientModDirectory) {
        exclude("build/**")
        exclude(".gradle/**")
    })
    outputs.file(forgeClientModArtifact)
}

tasks.register<Sync>("prepareBootstrap") {
    dependsOn(
        ":bootstrap:jar",
        ":legacy:1.8.9:forge:verifyCoremodArtifact",
        prepareForgeClientMod,
    )
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(project(":bootstrap").tasks.named("jar"))
    from(project(":legacy:1.8.9:forge").tasks.named("jar"))
    from(forgeClientModArtifact)
    into(layout.buildDirectory.dir("bootstrap"))
}

data class RuntimeArtifact(
    val role: String,
    val sourceName: String,
    val releaseName: String,
)

val runtimeArtifacts = listOf(
    RuntimeArtifact(
        role = "bootstrap",
        sourceName = "bootstrap-${project.version}.jar",
        releaseName = "opus-bootstrap-${project.version}.jar",
    ),
    RuntimeArtifact(
        role = "runtime-legacy-1.8.9",
        sourceName = "opus-forge-coremod-${project.version}.jar",
        releaseName = "opus-runtime-legacy-1.8.9-${project.version}.jar",
    ),
    RuntimeArtifact(
        role = "client-legacy-1.8.9",
        sourceName = "opus-forge-client-0.0.1-preview.3.jar",
        releaseName = "opus-client-legacy-1.8.9-${project.version}.jar",
    ),
)
val runtimeVersion = project.version.toString()

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) {
                break
            }
            digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

val prepareRuntime = tasks.register("prepareRuntime") {
    group = "build"
    description = "Builds versioned OPUS Runtime artifacts and integrity manifests."
    dependsOn("prepareBootstrap")

    val bootstrapDirectory = layout.buildDirectory.dir("bootstrap")
    val runtimeDirectory = layout.buildDirectory.dir("runtime")
    inputs.dir(bootstrapDirectory)
    outputs.dir(runtimeDirectory)

    doLast {
        val outputDirectory = runtimeDirectory.get().asFile
        val artifactDirectory = outputDirectory.resolve("artifacts")
        outputDirectory.deleteRecursively()
        artifactDirectory.mkdirs()

        val records = runtimeArtifacts.map { artifact ->
            val source = bootstrapDirectory.get().file(artifact.sourceName).asFile
            check(source.isFile) { "Missing built Runtime artifact: $source" }

            val destination = artifactDirectory.resolve(artifact.releaseName)
            source.copyTo(destination, overwrite = true)
            linkedMapOf<String, Any>(
                "role" to artifact.role,
                "file" to artifact.releaseName,
                "size" to destination.length(),
                "sha256" to sha256(destination),
            )
        }

        val manifest = linkedMapOf<String, Any>(
            "schemaVersion" to 1,
            "runtimeVersion" to runtimeVersion,
            "protocolVersion" to 1,
            "minecraftVersion" to "1.8.9",
            "artifacts" to records,
        )
        outputDirectory.resolve("runtime-manifest.json").writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n",
        )

        val checksums = linkedMapOf<String, Any>(
            "schemaVersion" to 1,
            "algorithm" to "SHA-256",
            "files" to records.associate { record ->
                record.getValue("file") as String to record.getValue("sha256") as String
            }.toSortedMap(),
        )
        outputDirectory.resolve("runtime-checksums.json").writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(checksums)) + "\n",
        )
    }
}

tasks.register("verifyRuntimeArtifacts") {
    group = "verification"
    description = "Verifies Runtime manifest sizes and SHA-256 checksums."
    dependsOn(prepareRuntime)

    doLast {
        val outputDirectory = layout.buildDirectory.dir("runtime").get().asFile
        val manifest = JsonSlurper().parse(
            outputDirectory.resolve("runtime-manifest.json"),
        ) as Map<*, *>
        val checksums = JsonSlurper().parse(
            outputDirectory.resolve("runtime-checksums.json"),
        ) as Map<*, *>
        val checksumFiles = checksums["files"] as Map<*, *>
        val records = manifest["artifacts"] as List<*>

        check(records.size == runtimeArtifacts.size) {
            "Runtime manifest does not contain the expected artifact set."
        }
        records.forEach { rawRecord ->
            val record = rawRecord as Map<*, *>
            val fileName = record["file"] as String
            val artifact = outputDirectory.resolve("artifacts").resolve(fileName)
            check(artifact.isFile) { "Runtime artifact is missing: $artifact" }
            check(artifact.length() == (record["size"] as Number).toLong()) {
                "Runtime artifact size mismatch: $fileName"
            }
            val actualSha256 = sha256(artifact)
            check(actualSha256 == record["sha256"]) {
                "Runtime manifest SHA-256 mismatch: $fileName"
            }
            check(actualSha256 == checksumFiles[fileName]) {
                "Runtime checksum file mismatch: $fileName"
            }
        }
    }
}
