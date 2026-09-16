# Web Surface V2 — W6 Production Migration Record

Date: 2026-09-16

## Decision

W6 promotes `ui-v2/` React + TypeScript + Vite from the isolated W5 product lane to the production Forge client screen. The legacy Svelte `ui/` remains a rollback build only.

The production screen is still `OpusClientScreen`; W6 does not create a second competing `GuiScreen`. CEF remains the sole product compositor while Minecraft owns lifecycle, route authority and the live HUD canvas boundary.

## Production coordinate and input contract

`OpusClientScreen` no longer uses `MinecraftUiScale` for the browser viewport or web input. It derives logical size from the physical framebuffer and `Display.getPixelScaleFactor()`, creates an `OpusDisplayMetrics` snapshot and routes raw LWJGL coordinates through `OpusInputMapper`.

Mouse press/release/drag use `Mouse.getEventX/Y()` from the event being dispatched; hover uses the current framebuffer pointer. Shift, Control, Alt and Command modifier state is forwarded with browser input. Minecraft GUI Scale is not part of the Web Surface geometry.

## Canonical React routes

The production React bundle accepts the canonical Java route IDs:

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

Host-mode navigation goes through the existing revision-scoped loopback bridge. Singleplayer, multiplayer, pause actions and the HUD editor native input region use the existing Java bridge endpoints rather than browser-only mock state.

## Resource migration

`buildUiDist` now packages `ui-v2/dist` into `/opusui`. The former Svelte package can still be built explicitly through `buildLegacyUiDist`, but it is no longer the production resource source.

## Acceptance

W6 requires:

- React typecheck + production build
- Forge production client compile
- loopback bridge harness
- source gate proving the product screen has no `MinecraftUiScale` dependency
- raw LWJGL event/DPR source gates
- W4 native CEF input regression
- real React production bundle through CEF OSR for three consecutive runs

The React oracle is inset from the framebuffer edge and checks a bounded oracle region so compositor-edge sampling cannot create a false failure. W2 remains the exact pixel-coordinate oracle.
