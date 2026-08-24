# Real-game UI Preview Mode — historical CEF evidence

> **Superseded on 2026-08-22.** This document records the CEF preview route and
> its test evidence. It is not part of the production launch contract. The
> active real-game acceptance path is
> [Forge + OptiFine + OPUS Core Mod + vanilla Minecraft UI](../../docs/coremod-first-goal-and-plan.md).

## Purpose

Preview Mode drives the production in-game UI and captures Minecraft's real
OpenGL framebuffer. It does not render a mock, open an external browser, or
restore the retired Java T-UI.

The ownership boundary is the same as production:

```text
Svelte/CEF OSR  -> product compositor
Java/Core       -> route, state, lifecycle and input authority
Native Java     -> live HUD and HUD-editor hit testing only
```

A browser preview remains useful for fast visual iteration, but only this mode
can provide automated evidence from the integrated compositor. Final acceptance
still requires a person to run Minecraft and verify resize, fullscreen, Retina,
Escape, helper failure/reopen and HUD persistence.

The CEF helper is also non-interactive with respect to macOS credentials. Opus
does not use Chromium for password/cookie storage; the browser process starts
with `use-mock-keychain` and `password-store=basic`, and the same switches are
applied by the renderer/GPU/plugin subprocess entry point. Microsoft refresh
tokens belong to the separate Opus local vault. A CEF test must fail if its log
reports an OS Keychain lookup or unavailable browser encryption.

## Stale-install guard

Existing launcher data roots can contain an older OPUS coremod/client pair and
their logs. Those sessions are not evidence for the current UI. Before a real
acceptance run, verify that the staged artifacts are the current pair:

```text
coremod  SHA-1 31c36fc84aec040cbfc30bd86f54b7be27ec1bb8
client   SHA-1 74873f2c4b996f49365f9b38a4c4b2c6d9cc71a3
```

The current 2026-08-21 Runtime release manifest also records these SHA-256
values: client `ea979a0b6a65e32a9eb10c535d5bc7bcecdcb91511cf5a45e49b5aeccfc59ccb`
and runtime/coremod `cf6d72e2c63d368cd6f445b0f2c28773a6f66c0a77d388d28d722e0085115c49`.
Do not treat a preview status file or screenshot with another artifact hash as
evidence for this release.

If the session log still reports `Opus virtual screen`, `ClientConfigUi`, or
`opus.client-options`, it is using a retired artifact and must not be used to
judge the former CEF UI.

## Run It

From `runtime/legacy/1.8.9/client`:

```sh
./gradlew runUiPreview
```

This is an explicit, one-shot launch. The task reads:

- `preview/opus-ui-preview.json` for the revisioned route/input/capture command;
- `preview/opus-utility-settings.json` for real locally persisted utility state.

There is no native UI theme file. Product visuals are owned by the Svelte bundle
under `/Users/zvwgvx/Project/Opus/ui` and are packaged into the client artifact.

Captures are written below the Forge run directory:

```text
runtime/legacy/1.8.9/client/run/screenshots/<capture.file>
```

The adjacent status file is:

```text
runtime/legacy/1.8.9/client/preview/opus-ui-preview.json.status.json
```

After a capture or terminal preview error, the preview client shuts down so the
Gradle process and Minecraft files are released deterministically.

## Readiness Contract

Preview Mode does not trust a fixed number of ticks as proof that UI exists. Its
state machine is:

```text
open production route
  -> wait for CEF READY and a current-viewport texture
  -> allow the route to settle
  -> dispatch input through the production owner
  -> wait for a post-input CEF frame or an intentional close
  -> capture the Minecraft framebuffer
```

`starting` and `failed` surfaces cannot be captured. A `closed` surface is valid
only after an action such as Escape intentionally closes the product UI and the
requested evidence is the resulting game frame.

The status document records the compositor and geometry explicitly:

```json
{
  "schemaVersion": 2,
  "state": "rendering",
  "message": "CEF product frame ready",
  "revision": 181,
  "productCompositor": "cef",
  "surfaceState": "ready",
  "viewport": { "width": 960, "height": 600 },
  "route": "quick_hub"
}
```

## Control Contract

Every command must increase `revision`; repeating a revision never repeats its
click, drag or key event.

Schema version 2 uses the same canonical route IDs as the integrated bridge:

```json
{
  "schemaVersion": 2,
  "revision": 181,
  "fixture": "right-shift",
  "route": { "id": "quick_hub" },
  "input": { "type": "key", "key": "escape" },
  "capture": { "file": "opus-quick-hub-escape.png", "afterFrames": 14 }
}
```

Canonical route IDs are:

- `title`
- `singleplayer`
- `multiplayer`
- `settings`
- `accounts`
- `game_menu`
- `quick_hub`
- `mods_catalog`
- `module_detail`
- `hud_editor`

Module detail carries its real module ID in route parameters:

```json
{
  "id": "module_detail",
  "params": { "moduleId": "fps" }
}
```

Routes remain subject to production context rules. For example, `quick_hub`,
`game_menu` and `hud_editor` require a live world, while `title`, world lists and
accounts require no live world.

Supported fixtures are:

- `current`: keep the current Minecraft world context;
- `world`: open the deterministic local creative fixture world if necessary;
- `pause-menu`: open the real FML pause-menu lifecycle, then enter the canonical
  CEF pause route and navigate to `mods_catalog`;
- `right-shift`: invoke the same production hotkey flow that opens `quick_hub`.

Input types are:

```json
{ "type": "none" }
```

Use `none` when the route itself is the scenario under review and no
post-route input should be dispatched. Other input types are:

```json
{ "type": "click", "x": 320, "y": 140, "button": 0 }
```

```json
{
  "type": "drag",
  "x": 320,
  "y": 140,
  "toX": 520,
  "toY": 260,
  "button": 0
}
```

```json
{ "type": "key", "key": "escape" }
```

`retry` is also accepted as a key command for the minimal CEF failure shell.
Pointer coordinates are OPUS logical UI coordinates and must be inside the
current product viewport. HUD-editor canvas ownership remains revision-scoped;
one event is never dispatched to both CEF and the native editor.

The responsive Minecraft acceptance run at revision `201` used a `480 x 300`
logical viewport (`1440 x 900` framebuffer at device scale `3`). It confirmed
that the native FPS widget remains visible while the CEF HUD Editor sidebar
keeps its settings and close controls inside the product surface.

## Invariants

1. CEF is the only product compositor used by Preview Mode.
2. A missing or failed helper shows only the minimal lifecycle shell.
3. Preview input uses the same Java-owned Escape/history and CEF/native ownership
   rules as a player input event.
4. Preview data never invents worlds, servers, accounts, modules, ping, equipment
   or telemetry.
5. A development capture is evidence for iteration, not proof that the packaged
   release works on the target Minecraft installation.
