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

val oneConfigProject = layout.projectDirectory.dir("third_party/oneconfig")
val oneConfigForkJar = oneConfigProject.file(
    "versions/1.8.9-forge/build/libs/oneconfig-1.8.9-forge-0.2.2-alpha228-full.jar",
)
val nativeClientProject = layout.projectDirectory.dir("legacy/1.8.9/native-client")
val nativeClientJar = nativeClientProject.file("build/libs/opus-native-ui-1.8.9-0.1.0.jar")
val nativeUiBuildCache = layout.buildDirectory.dir("native-ui-cache")
val nativeUiSourceCache = file(
    System.getenv("OPUS_NATIVE_UI_CACHE_DIR")
        ?: "${System.getProperty("user.home")}/.cache/opus-native-ui",
)
val nativeUiGradleUserHome = layout.buildDirectory.dir("native-client-gradle-user-home")
val oneConfigGradleUserHome = layout.buildDirectory.dir("oneconfig-gradle-user-home")
val oneConfigGradle = file(
    System.getenv("OPUS_ONECONFIG_GRADLE")
        ?: "/Users/zvwgvx/.cache/opus-oneconfig-stock-build/toolchains/gradle-8.7/bin/gradle",
)
val oneConfigLauncherJdk = file(
    System.getenv("OPUS_ONECONFIG_JAVA_HOME")
        ?: "/Users/zvwgvx/.cache/opus-oneconfig-stock-build/toolchains/jdk-17.0.17+10/Contents/Home",
)
val oneConfigJava8Home = file(
    System.getenv("OPUS_ONECONFIG_JAVA8_HOME")
        ?: "/Users/zvwgvx/.cache/opus-oneconfig-stock-build/toolchains/jdk8u502-b07/Contents/Home",
)

val buildOpusConfig = tasks.register<Exec>("buildOpusConfig") {
    group = "build"
    description = "Builds the vendored OneConfig fork used as the OpusConfig frontend."
    workingDir(oneConfigProject)
    inputs.files(
        fileTree(oneConfigProject) {
            exclude(".git/**", ".gradle/**", "build/**", "versions/*/build/**")
        },
    )
    outputs.file(oneConfigForkJar)
    environment("JAVA_HOME", oneConfigLauncherJdk)
    environment("GRADLE_USER_HOME", oneConfigGradleUserHome.get().asFile.absolutePath)
    environment("PATH", "${oneConfigLauncherJdk}/bin:${System.getenv("PATH") ?: ""}")
    commandLine(
        "/usr/bin/arch",
        "-x86_64",
        "/bin/bash",
        "-lc",
        "exec ${oneConfigGradle.absolutePath} --no-daemon " +
            "-Porg.gradle.java.installations.auto-detect=false " +
            "-Porg.gradle.java.installations.auto-download=false " +
            "-Porg.gradle.java.installations.paths=${oneConfigJava8Home.absolutePath} " +
            ":platform:1.8.9-forge:build",
    )
}

val stageNativeUiDependencies = tasks.register("stageNativeUiDependencies") {
    group = "build"
    description = "Stages the locked Elementa, UniversalCraft, and OpusConfig inputs for the native client build."
    dependsOn(buildOpusConfig)
    inputs.file(oneConfigForkJar)
    inputs.files(
        nativeUiSourceCache.resolve("elementa-762.jar"),
        nativeUiSourceCache.resolve("universalcraft-1.8.9-forge-516.jar"),
    )
    outputs.dir(nativeUiBuildCache)
    doLast {
        val outputDirectory = nativeUiBuildCache.get().asFile
        outputDirectory.deleteRecursively()
        outputDirectory.mkdirs()

        fun stage(source: File, destinationName: String) {
            check(source.isFile) {
                "Missing locked native UI input: $source. Set OPUS_NATIVE_UI_CACHE_DIR to the verified cache."
            }
            source.copyTo(outputDirectory.resolve(destinationName), overwrite = true)
        }

        stage(oneConfigForkJar.asFile, "opusconfig-1.8.9-forge-0.2.2-alpha228-full.jar")
        stage(nativeUiSourceCache.resolve("elementa-762.jar"), "elementa-762.jar")
        stage(
            nativeUiSourceCache.resolve("universalcraft-1.8.9-forge-516.jar"),
            "universalcraft-1.8.9-forge-516.jar",
        )
    }
}

val buildNativeUi = tasks.register<Exec>("buildNativeUi") {
    group = "build"
    description = "Builds the thin Forge mod that connects Elementa, OpusConfig, and Minecraft."
    dependsOn(stageNativeUiDependencies)
    workingDir(nativeClientProject)
    inputs.dir(nativeClientProject.dir("src"))
    inputs.file(nativeClientProject.file("build.gradle"))
    inputs.file(nativeClientProject.file("../client/native-ui-dependencies.lock.json"))
    outputs.file(nativeClientJar)
    environment("JAVA_HOME", oneConfigJava8Home)
    environment("OPUS_FORGE_UI_JAVAC", oneConfigJava8Home.resolve("bin/javac"))
    environment("OPUS_NATIVE_UI_CACHE_DIR", nativeUiBuildCache.get().asFile)
    environment("GRADLE_USER_HOME", nativeUiGradleUserHome.get().asFile.absolutePath)
    environment("PATH", "${oneConfigJava8Home}/bin:${System.getenv("PATH") ?: ""}")
    commandLine(
        "/usr/bin/arch",
        "-x86_64",
        "/bin/bash",
        "-lc",
        "cd ${nativeClientProject.asFile.absolutePath} && " +
            "exec ./gradlew --no-daemon verifyNativeClientArtifact --stacktrace",
    )
}

tasks.register<Sync>("prepareBootstrap") {
    dependsOn(
        ":bootstrap:verifyBootstrapArtifact",
        ":legacy:1.8.9:forge:verifyCoremodArtifact",
        ":legacy:1.8.9:lwjgl-macos-compat:verifyMacOsLwjglCompatibilityArtifact",
        buildNativeUi,
    )
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(project(":bootstrap").tasks.named("jar"))
    from(project(":legacy:1.8.9:forge").tasks.named("jar"))
    from(project(":legacy:1.8.9:lwjgl-macos-compat").tasks.named("jar"))
    from(nativeClientJar)
    from(nativeUiBuildCache) {
        include("opusconfig-1.8.9-forge-0.2.2-alpha228-full.jar")
        include("elementa-762.jar")
        include("universalcraft-1.8.9-forge-516.jar")
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
        role = "lwjgl-macos-compat",
        sourceName = "opus-lwjgl-macos-compat-2.9.2-nightly-20140822.jar",
        releaseName = "opus-lwjgl-macos-compat-2.9.2-nightly-20140822.jar",
    ),
    RuntimeArtifact(
        role = "native-ui",
        sourceName = "opus-native-ui-1.8.9-0.1.0.jar",
        releaseName = "opus-native-ui-1.8.9-0.1.0.jar",
    ),
    RuntimeArtifact(
        role = "opusconfig",
        sourceName = "opusconfig-1.8.9-forge-0.2.2-alpha228-full.jar",
        releaseName = "opus-oneconfig-1.8.9-forge-0.2.2-alpha228-full.jar",
    ),
    RuntimeArtifact(
        role = "elementa",
        sourceName = "elementa-762.jar",
        releaseName = "opus-elementa-762.jar",
    ),
    RuntimeArtifact(
        role = "universalcraft",
        sourceName = "universalcraft-1.8.9-forge-516.jar",
        releaseName = "opus-universalcraft-1.8.9-forge-516.jar",
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
