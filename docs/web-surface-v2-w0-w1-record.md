# Web Surface V2 — W0/W1 Record

Date: 2026-09-16

## W0

The verified native UI/launcher state was checkpointed before starting this lane. The superproject branch is `feature/web-surface-v2`; Runtime uses the matching branch. Production packaging remains on the existing native UI path during W1.

## W1 environment

- Host: Apple Silicon macOS
- CEF channel: stable
- CEF: `144.0.35+g12f1636+chromium-144.0.7559.262`
- CEF platform: `macosarm64`
- Archive size: `265016044`
- Archive SHA-1: `a948048e1c5638652ac8d06278a6f66cc78e9c6e`
- Renderer mode: CEF OSR
- Frame transport under test: shared memory with generation-scoped frame/release protocol
- Game JVM: not involved in browser execution; the standalone Java 8 harness is only the host/control client

## W1 result

The CEF 144 standard distribution configured successfully with CMake `Unix Makefiles` on the current Command Line Tools installation. `libcef_dll_wrapper` built successfully for ARM64. The existing isolated CEF helper compiled and bundled successfully against CEF 144.

The first harness run proved OSR paint/input/resize/reopen but failed the final safety gate because an ARM64-only `opus-keychain-guard.dylib` was inherited by an x86_64 subprocess. The guard build was changed to a universal `arm64+x86_64` dylib. The second run passed the complete harness.

Accepted evidence:

- title SPA frame and navigation ACK: PASS
- native CEF mouse hover: PASS
- keyboard activation: PASS
- pointer activation: PASS
- route navigation changes the rendered frame: PASS
- canonical module identity through pointer input: PASS
- HUD input-region ownership: PASS
- DPR-aware resize `800x500 @ 1.25 -> 1000x625`: PASS
- clean stop/reopen `480x320 @ 1.5 -> 720x480`: PASS
- temporary-resource cleanup: PASS
- Keychain safety gate: PASS
- final harness status: `WEBVIEW HARNESS: ALL PASSED`

## Boundary

W1 proves the browser subsystem independently. It does not approve direct integration with Minecraft. W2 must define one display-metrics source of truth and prove framebuffer/logical/CSS coordinate conversion across Retina, resize, fullscreen, and Minecraft GUI-scale changes before the browser surface can become a game screen.
