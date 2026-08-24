# Native UI source and stock-build baseline

Status: **verified technical baseline — 2026-08-23**

This document records the immutable inputs and first untouched-source build for
the OneConfig + Elementa native UI program. It is evidence for Workstream A of
the accepted Opus implementation plan; it does not resolve the open public
distribution and attribution gates.

## Frozen inputs

The machine-readable source, artifact, license, compatibility, and build-input
contract is
`legacy/1.8.9/client/native-ui-dependencies.lock.json`. The regular Runtime
check invokes `scripts/verify-native-ui-lock.mjs`, which rejects mutable
coordinates, changed commits, changed license files, changed artifact bytes,
or changed source-build inputs.

The stock OneConfig build lane is intentionally x86_64 on macOS. This allows
Gradle 8.7 to run under Java 17 while OneConfig's Java toolchain resolver uses
the required Java 8 compiler on an Apple Silicon host.

| Input | Version | Archive SHA-256 | Size |
| --- | --- | --- | ---: |
| Gradle | 8.7 | `544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d` | 134,184,980 |
| Eclipse Temurin launcher JDK | 17.0.17+10, macOS x86_64 | `a2a7bfd3a767fcaf35a2e96cc562e6a63cd695e08c1a896222303c4e978da3d6` | 180,324,092 |
| Eclipse Temurin compiler JDK | 8u502-b07, macOS x86_64 | `66565d468f4a48e9defa7420bbe522bf811a00609f618da1a85fc4ff149683b1` | 108,024,763 |

## Repository-owned build command

Run from the Runtime repository:

```sh
node scripts/build-stock-oneconfig.mjs \
  --source /absolute/path/to/OneConfig
```

The command performs the complete proof rather than trusting a pre-existing
build directory:

1. verifies the checkout is the locked commit and has no tracked edits;
2. verifies the locked OneConfig license file;
3. downloads and SHA-256-verifies all three build archives in a private cache;
4. runs Gradle as x86_64 with Java auto-detection and auto-download disabled;
5. builds exactly `:platform:1.8.9-forge:build`;
6. verifies the published OneConfig baseline JAR against the dependency lock;
7. compares every extracted path and byte between the source-built and
   published regular JARs; and
8. writes generated evidence to
   `build/evidence/oneconfig-stock-build.json` by default.

`--no-build` may be used only to re-check an already-built artifact. It does
not count as a fresh source-build proof.

The equivalent raw Gradle invocation proven on the original audited checkout
was:

```sh
arch -x86_64 /bin/bash -lc '
export JAVA_HOME=/Users/zvwgvx/.cache/opus-oneconfig-toolchain/jdk-17.0.17+10/Contents/Home
export GRADLE_USER_HOME=/Users/zvwgvx/.cache/opus-oneconfig-gradle-x86
./gradlew --no-daemon \
  -Porg.gradle.java.installations.auto-detect=false \
  -Porg.gradle.java.installations.auto-download=false \
  -Porg.gradle.java.installations.paths=/Users/zvwgvx/.cache/opus-forge-ui/jdk8u502-b07/Contents/Home \
  :platform:1.8.9-forge:build --stacktrace
'
```

Those Java installation settings are Gradle project properties (`-P`). Using
system properties (`-D`) does not constrain OneConfig's toolchain discovery in
this build.

## First successful untouched build

Source:

```text
repository: https://github.com/Polyfrost/OneConfig.git
commit:     233452661e6d273f130230bb8e1813fb0a28b80a
target:     1.8.9-forge
version:    0.2.2-alpha228
```

Observed result on 2026-08-23:

```text
BUILD SUCCESSFUL in 11m 38s
21 actionable tasks
```

Artifact comparison:

| Property | Source-built regular JAR | Locked published regular JAR |
| --- | --- | --- |
| Size | 8,736,779 bytes | 8,736,779 bytes |
| SHA-256 | `a1e759bd56489b4ae7531a5c016d5246518cec3bf52aa66a58b22d51a49c0bb7` | `bf44d346a6a53df3f1f9813de9c6f028cc93adf9c8cb1276c694863a7e1136de` |
| Extracted files | 1,550 | 1,550 |
| Extracted names and bytes | identical | identical |

The ZIP byte streams differ because the upstream build writes current entry
timestamps. Therefore the correct baseline claim is **content-equivalent**, not
byte-for-byte reproducible. The repository command enforces complete extracted
content equivalence and records the observed source-build hash for each run.

## Source import decision

The accepted destination is a history-preserving OneConfig subtree under
`runtime/third_party/oneconfig`. The first import must be the frozen commit with
no Opus product changes. Because ancestry is represented by the integration
commit rather than by copied files, the subtree operation belongs in a clean,
dedicated integration commit. It must not be simulated by copying source into
the currently dirty Runtime worktree.

Until that integration commit is made, the repository-owned command requires
an explicit audited checkout path. Product styling and fork modifications must
not begin against an unverified or floating checkout.

## Open gates

- import the frozen source with preserved ancestry;
- capture the complete dependency graph and duplicate-class report;
- prove stock OneConfig inside the exact managed Forge + OptiFine runtime;
- select Elementa's UniversalCraft candidate from runtime evidence;
- resolve OneConfig attribution and the transitive notice inventory before a
  public or commercial artifact is distributed.
