# RBW Forge client-mod build lane

This directory is deliberately separate from `/game`'s Gradle 9 coremod build.
Minecraft Forge 1.8.9 requires ForgeGradle 2.1 and Gradle 2.14.1; running that
toolchain inside the modern root build would break both lanes.

The Phase 1 source contains a normal, client-only `@Mod` lifecycle class and
no visible feature. It is a reobfuscation and runtime-loading proof only.

## Build contract

`toolchain.lock` pins the Gradle distribution SHA-256, ForgeGradle release,
Forge version, MCP mapping set, and a build-only Temurin Java 8 JDK.
`./gradlew` verifies the Gradle and JDK archives before it runs them. It caches
the JDK privately under `~/.cache/rbw-forge-ui/` on macOS; it does not change
the JDK used by the game or install a system-wide JDK.

The helper also gives this lane its own Gradle dependency cache. The Phase 1
verification rejects a changed ForgeGradle JAR or its historic Fernflower
snapshot dependency instead of accepting new bytes silently.

Run the Phase 1 proof with:

```sh
./game/client-mod/gradlew verifyClientArtifact
```

To use a pre-provisioned Java 8 JDK instead, set
`RBW_FORGE_UI_JAVA_HOME=/path/to/java-8-home`. On non-macOS hosts that
variable is required. The caller must not use the repository's Gradle 9 wrapper
for this directory.

The successful artifact is a normal FML mod JAR under `build/libs/`. It must
not have an `FMLCorePlugin` manifest entry and must not be staged, installed,
or exposed in the normal Demo app until the launcher allowlist and isolated
preview gates have passed.
