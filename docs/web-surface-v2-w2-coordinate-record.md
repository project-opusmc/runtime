# Web Surface V2 — W2 Coordinate Contract Record

Date: 2026-09-16

## Decision

Web Surface V2 has exactly two geometry spaces before HUD persistence is introduced:

1. logical pixels, identical to CEF CSS pixels;
2. framebuffer pixels, identical to the raster uploaded to the game compositor.

Minecraft `ScaledResolution` and Minecraft GUI Scale are not inputs to this transform. The legacy `MinecraftUiScale` path is historical and is not an allowed Web Surface V2 source of geometry.

On macOS the display-metrics authority is AppKit:

- logical extent: `NSView.bounds`;
- framebuffer extent: `[view convertRectToBacking:view.bounds]`;
- diagnostic scale: `NSWindow.backingScaleFactor`.

The production integration will query those values from the real LWJGL game window. W2 proves the contract independently and does not activate the web surface in Minecraft.

## Pure coordinate contract

`OpusDisplayMetrics` and `OpusDisplayMetricsTracker` are Java 8 compatible and have no Minecraft imports. The contract explicitly defines both top-origin CEF coordinates and bottom-origin LWJGL mouse coordinates.

Verified matrix:

- `800x600 -> 800x600` (`1.0`)
- `1280x720 -> 1280x720` (`1.0`)
- `1920x1080 -> 1920x1080` (`1.0`)
- `800x500 -> 1000x625` (`1.25`)
- `480x320 -> 720x480` (`1.5`)
- `1280x800 -> 2560x1600` (`2.0`)
- `1440x900 -> 2880x1800` (`2.0`)
- `1728x1117 -> 3456x2234` (`2.0`)
- `1920x1080 -> 3840x2160` (`2.0`)
- `2560x1440 -> 5120x2880` (`2.0`)

For every matrix entry the test verifies corner mappings, representative interior points, forward/reverse X mapping, top-origin Y mapping, bottom-origin LWJGL Y mapping, and generation changes. Minecraft GUI Scale values `0..4` produce no change because they are absent from the contract.

Result: `W2 COORDINATE CONTRACT: ALL PASSED`.

## AppKit metrics proof

A hidden native Cocoa window was created by the W2 probe on the current Apple Silicon host. It reported:

```text
logical=800x600 framebuffer=1600x1200 backingScale=2.00
W2 APPKIT METRICS: ALL PASSED
```

This proves the selected AppKit APIs expose the logical/backing relationship required by the contract on the current Retina environment.

## CEF pixel/input oracle

The CEF 144 OSR helper renders a deterministic HTML pattern. The harness samples the raw BGRA shared-memory frame at coordinates produced by `OpusDisplayMetrics`, verifies the four corner colors, body color, and a click target, then injects a logical CEF click and verifies the expected repaint.

Verified runtime matrix:

- `640x480 @1.0 -> 640x480`
- `800x500 @1.25 -> 1000x625`
- `480x320 @1.5 -> 720x480`
- `1920x1080 @2.0 -> 3840x2160`

The logical click and framebuffer sample resolve to the same CSS location.

Result: `W2 CEF COORDINATE ORACLE: ALL PASSED`.

## Resize-generation race found and fixed

The first complete W2 rerun exposed a nondeterministic resize race after the `3840x2160` test raster was resized back to `1000x625`. CEF could deliver one or more paints at the old raster size after the helper had already advanced to the new generation. Those stale pixels were therefore tagged with the new generation, rejected by the Java host for size mismatch, and in one run no later paint arrived.

The helper now computes the exact raster expected from the current logical extent and device scale inside `OnPaint`. A paint whose dimensions do not match that raster is never published to shared memory. It is logged as a stale raster, dropped, and a deferred `WasResized()` plus `Invalidate(PET_VIEW)` schedules a fresh paint.

Observed transitions include `3840x2160 -> 1000x625`; after the fix, stale 4K paints are dropped and the correct `1000x625` frame follows.

The complete W2 gate reruns the CEF pixel/input oracle three times. Final acceptance on 2026-09-16 was `3/3` successful runs with no timeout.

## W3 blocker discovered by W2

The legacy frame transport reserves two fixed `3840x2160x4` slots and rejects frames wider than `3840` or taller than `2160`. W2 intentionally supports larger geometry such as `2560x1440 @2.0 -> 5120x2880`, so the legacy transport cannot be the production V2 transport.

W3 must replace the fixed 4K capacity with viewport-negotiated shared-memory allocation and explicit slot metadata before 5K/large Retina viewports can be accepted. This is a frame-transport issue, not a coordinate-contract issue.

## Acceptance

W2 is accepted when all three commands pass:

```bash
./web-surface-v2/scripts/run-w2-coordinate-contract.sh
./web-surface-v2/scripts/run-w2-macos-metrics-probe.sh
./web-surface-v2/scripts/run-w2-cef-coordinate-oracle.sh
```

`run-w2.sh` executes the complete gate and additionally rejects any Minecraft/ScaledResolution dependency in the V2 coordinate source.
