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

val webClientProject = layout.projectDirectory.dir("legacy/1.8.9/client")
val webClientJar = webClientProject.file("build/libs/opus-forge-client-0.0.1-preview.3.jar")
val webClientGradleUserHome = layout.buildDirectory.dir("web-client-gradle-user-home")
val webClientJavaHome = file(
    System.getenv("OPUS_FORGE_UI_JAVA_HOME")
        ?: "${System.getProperty("user.home")}/.cache/opus-forge-ui/jdk8u502-b07/Contents/Home",
)

val buildWebClient = tasks.register<Exec>("buildWebClient") {
    group = "build"
    description = "Builds the React/CEF Web Surface Forge client shipped as the active product UI."
    workingDir(webClientProject)
    inputs.dir(webClientProject.dir("src"))
    inputs.dir(webClientProject.dir("native/opus-cef-helper/src"))
    inputs.dir(layout.projectDirectory.dir("../ui-v2"))
    inputs.dir(layout.projectDirectory.dir("web-surface-v2"))
    inputs.file(webClientProject.file("build.gradle"))
    inputs.file(webClientProject.file("native/opus-cef-helper/build.sh"))
    inputs.file(webClientProject.file("toolchain.lock"))
    inputs.file(webClientProject.file("gradlew"))
    outputs.file(webClientJar)
    environment("JAVA_HOME", webClientJavaHome)
    environment("OPUS_FORGE_UI_JAVA_HOME", webClientJavaHome.absolutePath)
    environment("OPUS_FORGE_UI_JAVAC", System.getenv("OPUS_FORGE_UI_JAVAC") ?: webClientJavaHome.resolve("bin/javac").absolutePath)
    environment("GRADLE_USER_HOME", webClientGradleUserHome.get().asFile.absolutePath)
    environment("PATH", "${webClientJavaHome.resolve("bin").absolutePath}:${System.getenv("PATH") ?: ""}")
    environment("OPUS_CEF_V2_CACHE", System.getenv("OPUS_CEF_V2_CACHE") ?: "${System.getProperty("user.home")}/.cache/opus-cef-v2")
    commandLine("./gradlew", "clean", "verifyClientArtifact")
}

tasks.register<Sync>("prepareBootstrap") {
    dependsOn(
        ":bootstrap:verifyBootstrapArtifact",
        ":legacy:1.8.9:forge:verifyCoremodArtifact",
        ":legacy:1.8.9:standalone:verifyStandaloneArtifact",
        ":legacy:1.8.9:lwjgl-macos-compat:verifyMacOsLwjglCompatibilityArtifact",
        buildWebClient,
    )
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(project(":bootstrap").tasks.named("jar"))
    from(project(":legacy:1.8.9:forge").tasks.named("jar"))
    from(project(":legacy:1.8.9:standalone").tasks.named("jar"))
    from(project(":legacy:1.8.9:lwjgl-macos-compat").tasks.named("jar"))
    from(webClientJar) {
        rename { "opus-native-ui-1.8.9-0.1.0.jar" }
    }
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
        role = "standalone-loader",
        sourceName = "opus-standalone-loader-${project.version}.jar",
        releaseName = "opus-standalone-loader-${project.version}.jar",
    ),
    RuntimeArtifact(
        role = "lwjgl-macos-compat",
        sourceName = "opus-lwjgl-macos-compat-2.9.2-nightly-20140822.jar",
        releaseName = "opus-lwjgl-macos-compat-2.9.2-nightly-20140822.jar",
    ),
    RuntimeArtifact(
        role = "native-ui",
        sourceName = "opus-native-ui-1.8.9-0.1.0.jar",
        releaseName = "opus-native-ui-1.8.9-0.1.0.jar",
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
