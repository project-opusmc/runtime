# Forge client-mod UI migration plan

Status: **historical migration evidence; the active product-UI architecture and
acceptance gates are in [overlay-ui-framework-adoption.md](overlay-ui-framework-adoption.md)**

Date: 2026-08-11

## Decision

Move every OPUS in-game UI concern from the current reflection/ASM UI path into
a conventional **client-only Forge mod**. Keep the Forge coremod only for
telemetry or a narrowly justified patch that cannot be implemented through a
Forge API.

This is a migration, not a patch to the existing `ClientUiHooks` /
`ClientConfigUi` path.

## Why this is necessary

The installed Forge + OptiFine session proves that Forge, OptiFine, the OPUS
coremod, and the bytecode transformers can load. It also proves the current UI
architecture is invalid under that runtime:

- Forge applies runtime deobfuscation after OPUS's raw-name patches.
- `ClientUiHooks` and `ClientConfigUi` reflect obfuscated fields/methods such as
  `m`, `a`, `j`, and `k`.
- A real Right Shift press reached the hook but failed with
  `NoSuchFieldException`, so no screen opened.
- The shared one-time failure reporter hides subsequent HUD-binding failures.

Changing individual string names would only create another fragile reflection
layer. A normal Forge client mod instead compiles against typed Minecraft/Forge
classes and is reobfuscated as a build artifact.

## Non-negotiable constraints

1. **No mock UI or data.** A surface, module, action, number, toggle, or setting
   stays absent until it is both observed and functional. See invariant 49 in
   [invariants.md](invariants.md).
2. **No launcher proof.** Only an installed Minecraft 1.8.9 Forge + OptiFine
   session and a real screenshot count as UI validation.
3. **No raw Minecraft reflection for UI.** New UI code must not use string field
   names such as `"m"` or `"a"`, generated `GuiScreen` bytecode, or a UI ASM
   hook into `ave`, `axp`, or `avo`.
4. **One source of module truth.** Catalog card, live HUD widget, HUD-editor
   gear, and module-detail page all resolve one `moduleId`, one option schema,
   and one persisted state.
5. **Fresh profiles stay quiet.** No HUD widget, example value, FPS, CPS, or
   diagnostic text appears until an actual user action enables a real module.
6. **Do not overwrite the current test app during migration.** Every migration
   test uses an isolated preview bundle and isolated QA data root until all
   acceptance gates pass.
7. **OptiFine remains local-import only.** The existing locked `HD U M5` import
   contract stays in force.

## Target architecture

```text
Forge + OptiFine runtime
│
├─ OPUS coremod (kept temporarily)
│  └─ telemetry / only Forge-unavailable patches
│
└─ OPUS Forge client mod (new, typed, client-only)
   ├─ @Mod lifecycle
   ├─ KeyBinding + ClientTickEvent       → Right Shift
   ├─ GuiScreenEvent                     → Client Options in pause menu
   ├─ RenderGameOverlayEvent.Post        → real enabled HUD widgets
   ├─ GuiScreen subclasses               → hub, catalog, detail, HUD editor
   ├─ Minecraft/FontRenderer/Gui direct types
   ├─ module registry + shared options schema
   └─ versioned settings repository
```

The client mod is a separate, explicitly allowed Forge artifact at first. It
is safer than combining an FML mod and a coremod in the same JAR while the new
rendering path is being proved.

## What is known enough to implement

The screenshot study establishes these surfaces and routes:

| Surface | Evidence | Migration action |
| --- | --- | --- |
| Right Shift HUD editor | L2 + user report | Implement after one real HUD module exists |
| HUD editor → Mods | L2/L3 + user report | Implement |
| Pause menu → Client Options → Mods catalog | user report/L9 | Implement route; exact pause-menu placement waits for capture |
| Mods catalog | L3/L5–L9 | Implement with only real OPUS modules |
| Module detail | L4 | Implement from the shared option schema |
| HUD editor | L10 + user report | Implement after real widget layout exists |
| Widget resize / settings / remove controls | L10 + user report | Implement against a real widget only |

The following surfaces must remain absent until new evidence and real OPUS
behavior exist:

- Quick hub's remaining non-HUD side button.
- `SETTINGS` and `WAYPOINTS` tabs.
- Lunar-style Discover, Store, account, currency, cosmetics, ads, and other
  game-main-menu services.
- Any module copied only because it appears in Lunar's catalog.
- Exact pause-menu button position/style, until the requested pause capture is
  supplied.

## Migration phases and gates

### Phase 0 — freeze the broken UI path and preserve evidence

**Goal:** prevent a second misleading install.

- Record the currently installed QA bundle/version and the failing session ID.
- Preserve the old coremod source for comparison, but mark its UI path legacy.
- The isolated UI Preview passes `-Dopus.legacy.ui.disabled=true`, so the
  known-broken reflection UI/HUD path is absent while its typed Forge client
  module is being proved. This switch does not disable telemetry or the Forge
  bootstrap, and it does not alter the existing Demo bundle.
- Do not change QA utility settings or make any new widget enabled by default.
- Do not replace `/Applications/Opus Client Demo.app`.

**Exit gate:** the exact Forge runtime failure and the source locations using
raw reflection are recorded. This gate is already met.

### Phase 1 — build-toolchain proof, with no visible product UI

**Goal:** establish a supported development/reobfuscation lane for a normal
Forge 1.8.9 client mod.

The current `game/forge` project uses Gradle 9 and `compileOnly` Forge
universal JARs. It produces an `FMLCorePlugin`, not a typed Forge mod
development environment. It cannot safely compile direct MCP-named classes
such as `Minecraft`, `GuiScreen`, and `FontRenderer`.

Create a **separate compatibility build lane** for the Forge client mod:

1. Pin the legacy ForgeGradle/MCP toolchain required by Forge 1.8.9; do not
   downgrade the root Gradle 9 build.
2. Pin the Java runtime used by that lane, including its exact version and
   checksum/source policy.
3. Prove one source class annotated with `@Mod` compiles, reobfuscates, and
   produces a normal Forge mod JAR.
4. Add no visible screen, button, HUD text, or placeholder at this stage. A
   startup log marker is sufficient proof of loading.
5. Package the resulting client-mod JAR as a distinct launcher resource and
   extend the managed-mod allowlist/integrity contract to stage exactly three
   artifacts: OptiFine, OPUS coremod, OPUS client mod.

**Exit gate:** a clean isolated QA preview launches Forge + OptiFine and logs
the OPUS FML mod lifecycle once. The old UI transformer is still untouched, but
no new UI is exposed.

**Implementation evidence (2026-08-11):** passed. The separately installed
`Opus Client UI Preview` opened a real Forge + OptiFine session and
logged `OPUS Forge client module loaded; no overlay surface is enabled.` The
Preview supplied `-Dopus.legacy.ui.disabled=true`, so no reflection UI ran.

**Stop rule:** if ForgeGradle 1.8.9 cannot run reproducibly on the host,
evaluate a dedicated pinned legacy build worker/CI lane before writing UI code.
Do not fall back to obfuscated reflection.

### Phase 2 — typed client foundation and coremod UI cutover

**Goal:** give the client mod all UI entry points without bytecode UI patches.

Implement, in the typed Forge client mod only:

- a registered `KeyBinding` for Right Shift;
- a client tick handler that opens the quick hub only in gameplay;
- `GuiScreenEvent.InitGuiEvent.Post` and
  `GuiScreenEvent.ActionPerformedEvent.Post` for the pause-menu Client Options
  route;
- `RenderGameOverlayEvent.Text` for HUD rendering;
- a typed base `GuiScreen` with `doesGuiPauseGame() == false` where the
  live-game behavior requires it.

After the Forge event route has passed a real-game gate, remove
`ClientOptionsTransformer` from the active coremod transformer chain. Do not
leave both routes active: duplicate inputs, menus, or HUD passes are failures.
The legacy source can remain uncompiled/not registered until final deletion is
separately approved.

**Current implementation:** the normal client mod now registers Right Shift,
adds `Client Options` in the real 1.8.9 pause-menu row, and uses typed Forge
events to route both to `OpusClientScreen`. It is packaged as
`opus-forge-client-0.0.1-preview.3.jar`. This has compiled/reobfuscated and
passed artifact checks; it has not yet passed the live screen acceptance gate.

**Exit gate:** Right Shift input and pause-menu Client Options are recorded by
positive Forge-mod telemetry, not only a transformer log.

### Phase 3 — module registry, settings, and one real vertical slice

**Goal:** prove the data model before multiplying UI cards.

Create a typed module contract:

```text
moduleId
name + known category
enabled state
HUD renderer (if applicable)
one option schema
one settings serializer/validator
one detail renderer
```

Use the existing versioned utility-settings file as the contract only after
validating migration behavior. Reads must tolerate old data; writes must be
atomic. Launcher and game writes must not silently overwrite one another.
Pre-renderer settings are migrated to schema version 1 with every utility
disabled, so a historic default cannot create an unsolicited HUD item.

The first UI slice contains only one actual client-side module: an **FPS
Overlay** whose value is read live from `Minecraft.getDebugFPS()` and which is
disabled on every fresh profile. It has a real enable action, HUD renderer,
anchor, scale, opacity, and atomic persistence. Reset and HUD-editor controls
remain absent until their real behavior is implemented.

No CPS, sample stats, Armor Status, or other card appears merely to fill a
grid. Those are separate vertical slices after their data/rendering contracts
are implemented and validated.

**Exit gate:** fresh QA profile → no widget; enable through catalog → live data
appears; disable → it disappears; restart → state is retained exactly.

### Phase 4 — verified Lunar-derived UI surfaces

**Goal:** build the known interaction model with real behavior only.

Build in this order:

1. **Quick hub:** OPUS transparent wordmark, functional Mods action, functional
   HUD Edit action only when there is an editable live widget. Omit unknown
   actions.
2. **Mods catalog:** stable outer shell, profile rail only if actual profiles
   exist, functional category filters/search only if their data exists, cards
   generated from the real module registry.
3. **Module detail:** same shell, same `moduleId`, shared option controls.
4. **HUD Editor:** live widgets on the real game view; hover-only bounds,
   resize control, settings control, and remove action. Widget gear must call
   exactly the same module detail route as catalog Options.

Use actual OPUS PNG assets through Forge resources. Do not load wordmarks with
`ImageIO` and direct texture uploads. Do not use raw `GL11` texture binds that
bypass Minecraft's cached `GlStateManager` state.

**Exit gate:** every visible action in these four screens is functional in a
real session. The catalog, widget gear, and HUD state agree after each change.

### Phase 5 — blur and renderer safety

**Goal:** fulfill the live-game blurred background requirement without corrupting
Minecraft or OptiFine rendering.

1. Use typed Minecraft framebuffer/entity-renderer APIs or a controlled
   framebuffer pass; all state changes go through Minecraft-aware wrappers and
   are restored before returning.
2. Prove the behavior with OptiFine shaders disabled first. Shader-pack support
   is not implied and must remain unsupported until tested.
3. Test opening/closing screens repeatedly, using HUD editor, switching worlds,
   and returning to vanilla screens.
4. Fail the gate on any unreadable text, missing textures, leaked blend/color/
   matrix state, or frame-buffer failure.

**Exit gate:** actual screenshot shows sharp text over a visibly blurred but
recognizable live game, with no GL corruption after repeated open/close cycles.

### Phase 6 — expand only through real module slices

Add one module at a time, each with:

- real Minecraft data source;
- documented fair-play boundary;
- renderer and settings schema;
- catalog/detail/HUD-editor integration;
- fresh-default behavior;
- persistence and restart test;
- actual in-game screenshot.

Armor Status is a likely early candidate because its Lunar detail behavior is
well documented in L4, but it remains absent until its inventory/durability
data and all visible options are actually implemented.

### Phase 7 — preview, acceptance, and installation

**Goal:** make testing safe and handoff evidence unambiguous.

- Build a separately named preview bundle with its own bundle ID and QA root.
- Install it beside—not over—the current Demo app.
- Use a fresh OptiFine-imported preview QA root for acceptance so no previous
  smoke-test state can create HUD output.
- Only replace the normal Demo app after all gates below pass.

## Required acceptance matrix

| Area | Required proof |
| --- | --- |
| Forge path | Forge + exact OptiFine + OPUS client mod all load in one real session |
| No old UI path | no `ClientOptionsTransformer` active in the client-mod build |
| Right Shift | opens the real quick hub from gameplay |
| Pause menu | Client Options opens the same catalog directly |
| Catalog | every visible card/filter/search control operates on real registry data |
| Detail | catalog Options and HUD-widget gear open the same module/settings |
| HUD editor | hover, resize, remove, and settings controls work on a real widget |
| Defaults | fresh root shows no widgets and no diagnostics |
| Persistence | enable, configure, restart, and verify exact retained behavior |
| Blur | visible live-world blur; sharp text; no OpenGL state corruption |
| Resolution | test at the user's normal GUI scale and one alternate scale |
| Packaging | preview bundle contains only the locked OptiFine, coremod, and client-mod artifacts |

Each row requires a captured real game result. Build output, log lines, and a
launcher screenshot are supplementary evidence only.

## Rollback and stop conditions

- Preserve the currently installed app; never replace it automatically.
- A failed preview is removed only after its exact path has been reviewed; no
  broad cleanup command is permitted.
- If any visible UI control is non-functional, remove it from the preview rather
  than present it as disabled/mock.
- If blur conflicts with OptiFine, pause visual expansion and resolve the
  renderer path before installation.
- If the typed Forge toolchain is not reproducible, stop at Phase 1 and report
  the toolchain blocker instead of reverting to the reflection implementation.

## Information still needed from the user

The implementation can begin through Phase 3 with current evidence. Before
the relevant UI surface becomes visible, collect:

1. a full pause-menu screenshot showing Lunar Options placement/style;
2. a full HUD-editor screenshot beyond the current widget crop, if it has any
   global toolbar, safe-area marker, or exit control;
3. behavior of the remaining Quick Hub side button;
4. Settings and Waypoints captures only if those OPUS features are requested.

No implementation may fill these gaps by imitation.
