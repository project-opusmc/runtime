# Web Surface V2 — W7 Hardening Record

Date: 2026-09-17

## PBO staging

The production GL upload path no longer calls `glFinish()` after every CEF paint. A three-entry Pixel Buffer Object ring stages each mmap-backed BGRA frame into GL-owned storage with `glBufferData(..., GL_STREAM_DRAW)`, then uploads the texture from PBO offset zero.

This preserves the W3 ownership rule: after `upload()` returns, the CEF shared-memory slot can be released because the GPU no longer depends on the helper-owned mmap bytes. PBO orphaning allows an earlier transfer to retain its old GL storage while a later paint replaces the ring entry's data store.

A client-memory fallback remains for contexts without PBO support. GL pixel-unpack-buffer binding is included in the compositor state snapshot and restored after each operation.

## Lifecycle hardening

`W7LifecycleHarness` proves:

- initial React surface startup
- twelve consecutive resize/DPR transitions
- forced helper process death enters the fail-closed state
- a fresh helper instance can recover and render again

The native failure shell remains the only fallback when Chromium is unavailable.


## Generation paint watchdog

Repeated W7 resize/DPR stress exposed an intermittent CEF OSR race where a new viewport generation could be accepted by the helper but Chromium produced no corresponding `OnPaint`. The helper remained alive, the control socket stayed healthy, and transport ownership was correct, but Java would wait indefinitely for the new generation.

`OpusWebViewClient` now owns a generation-level paint watchdog. `consumeFrame()` remains the polling boundary used by the game render loop. If the current generation has not published a frame for 150 ms, the client resends the same idempotent `VIEW <generation>` command. This does not advance generation, allocate a new transport mapping, or create a background thread; it simply reissues the existing resize/invalidate contract until CEF paints the current generation. Publication of any frame for that generation suppresses further retries.

The regression that previously timed out under repeated DPR/viewport cycling subsequently passed three complete W7 stress iterations in a row, including all twelve resize transitions, forced helper death, and fresh-helper recovery in each iteration.

## Runtime cutover

The top-level runtime now builds `legacy/1.8.9/client` as the active Forge product client. For compatibility with the existing launcher contract, its output is temporarily staged under the historical runtime role/file name `native-ui` even though the bytes now contain `OpusClientMod`, `OpusClientScreen`, React `/opusui`, and the CEF client classes. The retired `OpusNativeUiMod` is not present in the active artifact.

The macOS launcher bundle stages `opus-webview-helper.app` beside the managed runtime artifacts and passes its exact path through `-Dopus.webview.helper`. Production also opts into the Web Surface with `-Dopus.client.ui.enabled=true`.



## Active runtime dependency cleanup

The Web Surface Forge client has no source or build dependency on OneConfig, Elementa, or UniversalCraft. W7 therefore removes those three JARs from the active runtime manifest, desktop bootstrap bundle, launch contract, and managed mods allow-list. Their source/build tasks remain available only as a rollback lane; production does not build, stage, or load them.

The launcher also treats the exact former managed filenames as bounded migration artifacts. On the first launch after this cutover it removes `opus-oneconfig-1.8.9-forge-0.2.2-alpha228-full.jar`, `opus-elementa-762.jar`, and `opus-universalcraft-1.8.9-forge-516.jar` from the isolated mods directory before validating the new managed set. Tests seed all three retired files and prove that only Forge/OptiFine, the Opus coremod, and the Web Surface client remain managed.

The historical runtime role/file name `native-ui` / `opus-native-ui-1.8.9-0.1.0.jar` is temporarily retained as a compatibility label only; its bytes are the reobfuscated Web Surface V2 client and contain no `OpusNativeUiMod`.

## Production reobfuscation gate

The first live launcher cutover exposed a packaging-only failure before CEF could render: the active Web Surface JAR had been produced by the ForgeGradle `jar` task and still referenced MCP/deobfuscated method names such as `Minecraft.getMinecraft()`. The obfuscated 1.8.9 runtime therefore failed with `NoSuchMethodError` while constructing `OpusClientScreen`.

The top-level runtime now builds the active Web Surface client through `clean verifyClientArtifact`; that task depends on ForgeGradle `reobfJar`. The staged production JAR was inspected directly and now references the SRG method `func_71410_x`. `verifyRuntimeArtifacts` and the launcher artifact hash contract use those reobfuscated bytes.

## macOS standalone CEF staging

A second live cutover proved that CEF must not execute while its helper app is nested under `Opus Launcher.app/Contents/Resources`. The nested helper reached shared-memory setup and `CefInitialize`, but never opened its loopback control listener. Java correctly timed out and entered the fail-closed shell.

This was isolated with an A/B test: the helper inside the launcher bundle and the source helper were byte-identical, had the same ad-hoc signature, Info.plist and Chromium framework. Copying those exact packaged bytes to a standalone temporary `.app` made the full W7 lifecycle harness pass. The failure therefore followed bundle location, not helper contents or the Java version.

The launcher now treats the bundled helper as an immutable source artifact only. During launch-plan preparation on macOS it:

1. fingerprints the helper executable plus Chromium Embedded Framework,
2. verifies the source app with `codesign --verify --deep --strict`,
3. stages it with `ditto` to `~/.opus-launcher/runtime/cef/helper-<fingerprint>/opus-webview-helper.app`,
4. verifies the staged fingerprint and code signature,
5. reuses that immutable directory on later launches, and
6. passes only the standalone path through `-Dopus.webview.helper`.

The managed-runtime staged helper itself passed `W7LifecycleHarness`, including resize/DPR stress, forced helper death and fresh-helper recovery.

## Remaining live proof

The renderer, transport, input, React surface, reobfuscated runtime artifact and standalone helper path are headless-gated. The final interactive Minecraft proof is intentionally still open because macOS locked the console while the unattended test was running; the launcher correctly refused to activate a game window rather than bypassing the lock screen. The next unlocked launch must confirm the final sequence `OpusClientMod -> CEF listener/READY -> React paint -> PBO composition` in the real game window.
