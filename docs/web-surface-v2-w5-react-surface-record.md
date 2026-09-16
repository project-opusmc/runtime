# Web Surface V2 — W5 React Surface Record

Date: 2026-09-16

## Decision

W5 introduces `ui-v2/` as the new presentation lane: React 19 + TypeScript + Vite, with Inter Variable bundled locally. The existing Svelte `ui/` remains unchanged and remains the production resource target until W6.

The new surface is intentionally developed and verified as a normal web application first. Minecraft GUI Scale does not participate in its layout, and W5 forbids root CSS `zoom` and `transform: scale(...)` as substitutes for a correct framebuffer/DPR contract.

## Product surface

The initial React V2 product shell contains:

- Home / Command Center
- Modules library and selected-module detail
- Settings / presentation controls
- typed standalone/runtime bridge facade
- responsive rail navigation
- locally bundled Inter variable font, including Vietnamese subset

The UI uses ordinary CSS layout, gradients, blur, borders, typography and responsive grids. It does not depend on Minecraft drawing primitives.

## Typed bridge

`ui-v2/src/bridge/types.ts` defines the React-facing contract. Standalone mode uses deterministic local data. Integrated mode targets the existing loopback REST surface and remains deliberately limited until W6 wires navigation/actions to the canonical game bridge.

No pixel data travels through HTTP or WebSocket. CEF OSR pixels continue to use the W3 shared-memory transport.

## Deterministic CEF oracle

The production React bundle contains a visually negligible fixed 4x4 oracle pixel at the bottom-right. Its color is a deterministic function of React product state:

- Home: `#2A4CFF`
- Modules, selected module on: `#19B87A`
- Modules, selected module off: `#E14E66`
- Settings default: `#8A5CFF`
- Settings changed: `#E99B39`

The oracle exists only to prove that CEF rendered the actual React application state. Input still goes through the native CEF input path sealed by W4.

## Static bundle serving

CEF module scripts/styles do not load reliably from `file://` because Chromium gives the document a null origin and rejects module/CSS loads. The W5 harness therefore starts an in-process Java loopback static server bound to `127.0.0.1` on an ephemeral port and serves only `ui-v2/dist`.

This server carries static application assets only. It is not a pixel transport and is independent of the shared-memory frame path.

## Acceptance sequence

The CEF harness runs the real Vite production bundle at `1280x720 @1.5x` and verifies:

1. React production bundle reaches Home oracle.
2. Native pointer click routes Home -> Modules.
3. Native pointer at the measured module toggle changes the selected module state.
4. Native keyboard `S` routes Modules -> Settings.
5. Native pointer changes a Settings visual-language control.
6. Native keyboard `H` routes Settings -> Home.
7. Native keyboard `M` returns to Modules with state preserved.
8. Viewport is resized to `900x600 @2x`; current-generation React output remains correct.

The first `file://` attempt failed closed with Chromium CORS diagnostics and was replaced by the loopback static server. A temporary bounded native-pointer scan identified the real module-toggle hit point as `(1180,170)`; the final harness uses the fixed coordinate and contains no scan.

## W2 compositor-readiness hardening

The first comprehensive W5 regression exposed a pre-existing W2 harness flake: Chromium could publish a current-geometry paint after the document background existed but before the deterministic button target had reached the compositor. The harness previously treated the first current-generation frame as fully rasterized and failed on the target pixel.

No helper, transport, coordinate, or product code was relaxed. The W2 CEF oracle now waits for the known target pixel before accepting a matrix frame, while continuing to release partial current-generation frames. The hardened oracle then passed 5/5 consecutive runs.

## Build acceptance

`ui-v2` passes:

```bash
npm run check
npm run build
```

The production bundle contains local JS/CSS/font assets and the source scan confirms no CSS `zoom` or `transform: scale(...)` workaround.

## Boundary

W5 does **not** switch Minecraft's production UI resources or screen ownership. `buildUiDist` still builds legacy Svelte `ui/`, and the existing `OpusClientScreen` remains the game screen. That migration is W6.

W5 also does not claim production GL-upload performance. PBO/dirty-region work remains W7.

## Reproduction

Focused React/CEF oracle:

```bash
./web-surface-v2/scripts/run-w5-react-cef-oracle.sh
```

Full W5 regression and 3-run React stress:

```bash
./web-surface-v2/scripts/run-w5.sh
```
