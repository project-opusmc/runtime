# Opus Web Surface V2

This directory owns the isolated Web Surface V2 research lane. W0 freezes the current native UI as the rollback baseline. W1 proves CEF OSR independently of the production Minecraft renderer.

The W1 baseline is pinned to CEF `144.0.35+g12f1636+chromium-144.0.7559.262` for macOS ARM64. The archive size and SHA-1 are locked in `cef-lock.json`.

Run:

```bash
./web-surface-v2/scripts/bootstrap-cef-macos-arm64.sh
./web-surface-v2/scripts/build-w1-helper.sh
./web-surface-v2/scripts/run-w1-harness.sh
```

W1 is accepted only when the standalone harness passes browser paint, mouse hover, keyboard activation, pointer activation, route changes, DPR-aware resize, clean stop/reopen, temporary-resource cleanup, and the Keychain safety gate.

W1 does not enable CEF in the production Runtime artifact and does not modify Minecraft screen ownership.

## W2 — coordinate contract

W2 removes Minecraft GUI Scale from the web compositor geometry. AppKit content-view points are the macOS logical/CSS space and backing pixels are the framebuffer space. The pure Java contract also defines the LWJGL bottom-origin mouse-Y conversion.

Run the complete W2 gate:

```bash
./web-surface-v2/scripts/run-w2.sh
```

The CEF oracle validates real BGRA pixels through DPR 2.0 at a 3840x2160 raster. The coordinate contract itself also covers 5120x2880; W3 must replace the legacy fixed-4K shared-memory capacity before that larger raster can be transported.
