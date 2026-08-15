# Real-game UI Preview Mode

## Purpose

This is RBW's replacement for HTML mockups, desktop-window screenshots, and
manual Computer Use while iterating on Minecraft UI. The Forge client mod
renders the normal RBW page tree, then captures the **same OpenGL framebuffer**
that a player sees. There is no second renderer.

Preview Mode is deliberately opt-in. It exists only when the development JVM
receives explicit local file paths. A packaged Opus Launcher or Opus Launcher QA
does not watch arbitrary theme/control files.

## Run it

From `game/client-mod`:

```sh
./gradlew runUiPreview
```

The task launches a 1440 x 900 Forge development client and enables:

- `preview/rbw-ui-preview.json` — revisioned route/input/capture command;
- `preview/rbw-ui-theme.json` — visual tokens and selected layout ratios.
- `preview/rbw-utility-settings.json` — local preferences for the currently
  implemented live utilities. It begins empty; preview fixtures never inject
  player equipment or sample telemetry to make a module look populated.

The client creates or opens a local creative fixture world only when the
control file requests `"fixture": "world"` or `"fixture": "pause-menu"`.
The latter opens Minecraft's actual `GuiIngameMenu` before dispatching the
production Client Options route; it is evidence for the pause-menu flow, not
a second renderer or a mock menu. The FPS HUD always reads the running
client's actual frame rate and no fixture manufactures module data. Captures
are written to:

`game/client-mod/run/screenshots/<capture.file>`

The adjacent status file is written by the real client:

`game/client-mod/preview/rbw-ui-preview.json.status.json`

## Fast iteration loop

1. Keep the preview client open once.
2. Save `rbw-ui-theme.json`; its valid visual token changes reload on the next
   client tick and relayout the real open page.
3. Edit `rbw-ui-preview.json`, increment `revision`, select a route and a
   capture filename.
4. The game opens the real route, waits for it to render, then writes its
   framebuffer PNG. Inspect that PNG; do not judge a separate mockup.

Java behavior/layout changes still require a mod rebuild/restart. The next
phase is to move page geometry into a validated declarative layout document;
until then only theme tokens and the listed layout ratios are hot-reloaded.

## Delivery gates

1. **Foundation — complete:** use the same in-game renderer, real fixture
   world, route driver, hover/click/drag dispatch, framebuffer capture and
   status protocol.
2. **Visual iteration — started:** theme colors and primary frame/menu ratios
   hot-reload. The next layout work must move screen geometry from Java into a
   validated document before visual implementation resumes.
3. **Interaction proof:** each new route must have a revisioned control-file
   scenario that opens it, invokes each real action, captures the result and
   reports its route/status.
4. **Release proof:** rebuild the exact client-mod JAR, stage those exact
   bytes in each product bundle, then rerun the capture suite from that JAR.

### Armor Status release gate

Armor Status may be catalogued only because it reads the local player's actual
equipped `armorInventory` and real item durability. A preview world starts
without armor, so an enabled Armor Status widget must remain absent there.
Do not fill the inventory, produce rows, or manufacture durability simply to
make a capture. Before its bytes are staged in a product bundle, capture the
HUD editor and normal HUD while a player has equipped real armor, then verify:

1. the rendered rows match the worn slots and item durability;
2. the editor gear resolves `ModuleDetail(armor-status)`;
3. an enable/configure/restart cycle retains only persisted settings.

## Control file contract

Every command must increase `revision`. A repeated revision is intentionally
ignored, so the client cannot accidentally repeat a click or drag every tick.

```json
{
  "schemaVersion": 1,
  "revision": 2,
  "fixture": "world",
  "route": "mods",
  "pointer": { "x": 320, "y": 140 },
  "input": { "type": "click", "x": 320, "y": 140, "button": 0 },
  "capture": { "file": "rbw-mods-hover.png", "afterFrames": 10 }
}
```

Supported routes: `main`, `hud`, `mods`, `detail`, `armor`.

Supported fixtures: `current` (do not launch a world), `world` (the local
creative fixture world), and `pause-menu` (the fixture world followed by the
real vanilla pause menu and the production Client Options destination), and
`right-shift` (the fixture world followed by the exact production route method
used by the Right Shift key binding).

`pointer` makes the real UI render its actual hover state. `input` is optional
and is dispatched to the existing `UiRuntime`; only a real control can handle
it. Supported types are `click` and `drag`. Coordinates are RBW logical UI
coordinates, not desktop pixels.

`capture.file` must be a simple `.png` filename. It is saved by Minecraft's
`ScreenShotHelper` from the active framebuffer.

## Invariants

- The preview calls the same `RbwClientScreen`, `UiRuntime`, pages, assets,
  font renderer, blur path and framebuffer as the shipped client.
- A visible route/control requires real production behavior; Preview Mode does
  not manufacture module state or telemetry.
- The control protocol is local, revisioned and fail-closed.
- A final product screenshot must be recaptured from the exact packaged JAR;
  development captures are iteration evidence, not release proof.
