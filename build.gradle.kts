import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    base
}

allprojects {
    group = "dev.rbw"
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

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(8)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-options", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

val forgeClientModDirectory = file("client-mod")
val forgeClientModArtifact = file(
    "${forgeClientModDirectory}/build/libs/rbw-forge-client-0.0.1-preview.3.jar",
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
    dependsOn(":bootstrap:jar", ":forge:verifyCoremodArtifact", prepareForgeClientMod)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(project(":bootstrap").tasks.named("jar"))
    from(project(":forge").tasks.named("jar"))
    from(forgeClientModArtifact)
    into(layout.buildDirectory.dir("bootstrap"))
}
