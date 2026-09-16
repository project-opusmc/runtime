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
