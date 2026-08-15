# In-game overlay validation incident — 2026-08-10

Status: **historical release-blocker incident; implementation resumed only
after the user's explicit approval on 2026-08-11**

Severity: **release blocker**

Affected builds:

- `/Applications/OPUS Client.app` installed at 2026-08-10 17:12 local time
- `/Applications/Opus Client Demo.app` installed at 2026-08-10 17:13 local time
- Reproduced in QA session `1786362439772-85114`

## User-visible failures

1. The in-game Utilities screen is unreadable. Almost every Minecraft-rendered label appears as a solid rectangular glyph, and the layout does not visually match the approved launcher mock.
2. Right Shift does not open the Utilities screen from gameplay.
3. FPS and CPS appear at the top-left without an explicit user opt-in.
4. The installed result was reported as validated even though the actual LWJGL screen had not been visually exercised. Transformer-load success and launcher-browser screenshots were incorrectly treated as proof of in-game behavior.

Reference captures supplied with the report:

- `codex-clipboard-8766bbf3-24ad-4d75-a47f-0172064290dd.png`
- `codex-clipboard-b82b6179-9316-4c9c-9948-f2add54587cd.png`

## Confirmed findings

### INC-OVERLAY-001 — Font and render-state corruption

The custom renderer mixes direct `org.lwjgl.opengl.GL11` state mutations with Minecraft 1.8.9's cached `GlStateManager` (`bfl`) state.

`ClientConfigUi.GlBindings` directly binds the OPUS PNG texture and finally binds texture `0`. Minecraft's font renderer binds its glyph texture through `bfl.i(int)`, which skips the OpenGL call when its cached texture id already matches. Because the direct OPUS call bypasses that cache, the cache can say that the font atlas is bound while OpenGL actually has texture `0` or another atlas bound. Font quads are then rendered as solid blocks.

The same class also changes texture, blend, enable/disable, and color state directly, without a complete Minecraft-aware state restore. This makes both the config screen and HUD renderer unsafe.

Evidence:

- `game/core/src/main/java/org/polydevs/opusmc/core/ClientConfigUi.java`: `GlBindings.uploadTexture`, `GlBindings.texture`, and related direct GL methods
- Minecraft 1.8.9 bytecode: `avn` delegates glyph texture binding to `bmj.a(jy)`; `bmj` delegates to `bfl.i(int)`; `bfl.i(int)` conditionally skips `GL11.glBindTexture` based on its cached id
- The PNG wordmark renders correctly, while all labels rendered immediately after it become solid glyph blocks

### INC-OVERLAY-002 — Utility state was enabled without user opt-in

The current QA settings file records both widgets as enabled:

```json
"fps": { "enabled": true }
"cps": { "enabled": true }
```

Two separate causes are confirmed:

- FPS is explicitly shipped as enabled by default in both the Rust settings model and the React fallback defaults.
- CPS was enabled during an automated smoke test and was not restored afterward. The test mutated the persistent QA profile at `~/.opus-client-qa/utility-settings-v1.json`.

This is a product-default defect and a test-isolation failure. No HUD element should become visible merely because a test ran or because a fresh profile was created, unless that default has been explicitly approved.

### INC-OVERLAY-003 — FPS/CPS provenance

The values are not hard-coded mock numbers:

- FPS reads Minecraft's live `ave.C()` value.
- CPS counts timestamps recorded at the start of Minecraft's left-click method `ave.aw()` over the preceding one-second window.

However, the display must be treated as invalid in this build because its renderer is corrupt and the widgets were enabled without valid user consent.

### INC-OVERLAY-004 — Right Shift runtime failure

The transformer is loaded and reports that it patched `ave.s()`, but the user's live reproduction shows that Right Shift does not open the screen.

The current implementation has no positive telemetry for raw key code, key state, guard result, or successful screen open. Therefore the existing log line `applied opus.client-options input=ave` proves only bytecode insertion, not working input behavior.

The current QA stderr contains this runtime clue immediately after the reproduction:

```text
Unknown modifier with keycode: 0
```

Static inspection confirms that LWJGL 2 defines `KEY_RSHIFT = 54`, and the hook tests only key code `54`. The exact runtime failure is not yet proven: the event may be delivered as another/unknown code on this macOS/LWJGL path, may be dropped, or may return through the existing-screen guard. No instrumentation may be added while the freeze is active.

The pause-menu button opening the custom screen confirms that screen construction itself is reachable; the unresolved failure is specific to the keyboard entry path.

### INC-OVERLAY-005 — Mock/runtime parity and validation failure

The launcher mock is a React/Tauri view, while the game screen is a separate reflective Java/OpenGL renderer. Visual verification was performed only against the launcher at a large CSS viewport. The game implementation was validated with compilation, ASM fixture tests, transformer load logs, and process startup, but not with a captured real in-game screen at Minecraft's scaled GUI resolution.

This allowed all of the following to pass unnoticed:

- unreadable text caused by OpenGL state corruption;
- a different responsive layout at Minecraft's scaled resolution;
- a non-working Right Shift path;
- persistent test-created HUD state;
- lack of proof that blur and live controls behave as designed in the actual game.

## Current evidence snapshot

- Installed QA process is launching bootstrap/core/mappings/patches from `/Applications/Opus Client Demo.app/Contents/Resources/bootstrap`.
- Current session reports six transformers loaded and applies the UI input, pause-menu, and HUD hooks.
- No `VerifyError` is present. This does **not** imply functional correctness.
- QA settings currently have FPS and CPS enabled.
- One real attack input was recorded in diagnostics, supporting the live CPS source path.
- The supplied screenshots are the authoritative visual result for this build.

## Freeze boundary

This was the freeze boundary before the user explicitly resumed implementation:

- do not change renderer, input, utility defaults, or persistence code;
- do not build or install another app/game bundle;
- do not mutate QA or Premium utility settings;
- do not claim the overlay is functional based on compilation, unit tests, transformer logs, or launcher screenshots.

## Required acceptance evidence before a future handoff

1. A captured real Minecraft screen at the user's actual window size showing readable text and the approved central layout.
2. Right Shift opens the same screen from gameplay; Client Options opens it from the pause menu; both paths are recorded as successful.
3. A fresh profile and a migrated profile show no HUD widget unless the user explicitly enables it.
4. Enabling and disabling every shipped widget changes the live HUD and persists across a full game restart.
5. FPS and CPS are checked against independent live observations, not merely code-path assertions.
6. The config background blur is visibly confirmed in the real game, with UI text remaining sharp.
7. Rendering leaves Minecraft texture, blend, color, matrix, and shader state intact after opening, closing, and repeatedly toggling the overlay.
8. The installed artifact hash is recorded only after the above runtime checks pass.
