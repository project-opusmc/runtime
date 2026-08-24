# Opus native UI client

This is the native Forge 1.8.9 client mod for the OpusConfig and Elementa
integration. It is intentionally separate from the older
`legacy/1.8.9/client` CEF/Svelte research lane.

The native UI JAR is thin and keeps OneConfig, Elementa, and UniversalCraft as
separate managed dependencies. OneConfig `0.2.2-alpha228-full`, Elementa `762`, and
UniversalCraft `516` remain separate checksum-verified JARs. `runClient` adds
them to ForgeGradle's launch-time `runtime` configuration so LaunchWrapper can
load the OneConfig tweaker, and also stages the same immutable JARs in this
lane's isolated `run/mods` directory so Forge can discover them. They are never
shaded into the Opus native UI mod. Optional user-provided OptiFine is staged beside
them only after its locked size and SHA-1 are verified.

## Verify and build

```bash
./gradlew clean check
```

The wrapper fetches the frozen UI artifacts into
`$OPUS_NATIVE_UI_CACHE_DIR` (default `~/.cache/opus-native-ui`) and validates
them against `../client/native-ui-dependencies.lock.json` before Gradle runs.

Static evidence is written to:

```text
build/reports/native-ui/static-compatibility-report.json
build/reports/native-ui/opus-design-report.json
```

`verifyOpusDesignContract` validates the renderer-neutral Java 8 design
contract through both the OneConfig and Elementa adapters. The current schema-2
report covers 32 canonical component/state descriptions, 16 colors, 11
typography styles, 3 icon metrics, 3 elevation tokens, 3 accessibility metrics,
and 20 framebuffer-rounding samples. The Elementa proof lab consumes the same
contract for semantic colors, shared spacing and sizing, radii, typography, and
motion. This is static contract/parity evidence; it does not replace actual
font/rendering review or the exact Forge/OptiFine game run.

## Safety gates

The isolated wrapper checks the process list, estimated available memory, and
free disk before it creates cache directories, fetches artifacts, starts
Gradle, or launches Minecraft. It never terminates a process or deletes a
file. It also prints the same read-only report after a completed Gradle
invocation.

- Static tasks require no matching Minecraft/native-client process, at least
  2 GiB estimated available memory, and at least 2 GiB free disk.
- `runClient` requires no matching process, at least 6 GiB estimated available
  memory, and at least 8 GiB free disk. The current thresholds can only be
  changed deliberately with the documented `OPUS_NATIVE_UI_MIN_*_MIB`
  environment variables.
- `clean` is disabled unless `OPUS_NATIVE_UI_ALLOW_CLEAN=1` is set explicitly.
  It affects only this isolated client lane, but must still be approved against
  its exact targets before use.

The native UI JVM defaults to `-Xms256M -Xmx1536M`; `-PopusNativeUiHeapMib=...` is
limited to 768 through 2048 MiB. Verbose Forge logging and Mixin class export
are opt-in through `-PopusForgeConsoleDebug=true` and
`-PopusMixinDebugExport=true` so routine proof runs do not create avoidable
logs or exported classes.

## Run

For a dependency-only smoke run:

```bash
./gradlew runClient
```

For the exact supported runtime, point at the user's locally obtained locked
OptiFine artifact and make it mandatory:

```bash
OPUS_OPTIFINE_JAR=/absolute/path/OptiFine_1.8.9_HD_U_M5.jar \
  ./gradlew runClient -PopusRequireOptifine=true
```

Development controls:

- `Right Shift`: open or close OpusConfig.
- The Elementa shell owns the main menu and pause menu routes.

Runtime evidence is updated at `run/native-ui-runtime-report.json`. Exercise
four openings in this order, closing each screen before opening the next:
OneConfig, Elementa, Elementa, OneConfig. This proves both transition
directions. In OneConfig, change representative automatic controls and press
the `Record callback` / `Run` button. In Elementa, exercise text focus and
selection, clipboard shortcuts and the copy button, outer and nested scrolling,
the floating overlay, hover animation, and Escape/back. Change GUI scale and
fullscreen while the proof screens are visible, and confirm Minecraft renders
normally after every close.

The report is schema 2 and includes a fresh run-session nonce. The verifier
also requires both Elementa inner draw events and zero GL errors immediately
before and after Elementa's inner renderer. `run/native-ui-runtime-session.txt`
binds the report to the current launch; an older report is rejected.

After the interactive run, verify the captured evidence with:

```bash
./gradlew verifyNativeUiRuntimeReport
```

The verifier intentionally fails until both actual opening orders, outer and
inner draw/close events, interaction callbacks, display states, zero outer and
inner GL errors, and the locked OptiFine HD U M5 runtime are present. A
schema-1 report left by an earlier run is not valid proof.2 evidence.

This lane does not close the public-distribution legal gates recorded in the
superproject's third-party license audit.
