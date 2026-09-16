# Web Surface V2 — W4 Input Contract Record

Date: 2026-09-16

## Decision

W4 defines one input transform for the future Web Surface screen:

```text
LWJGL framebuffer coordinates
(x from left, y from bottom)
    -> OpusDisplayMetrics / OpusInputMapper
    -> CSS logical coordinates
(x from left, y from top)
    -> generation-scoped OpusWebViewClient input
    -> CEF native SendMouse*/SendKey* APIs
```

Minecraft `ScaledResolution`, `GuiScreen.width/height`, and Minecraft GUI Scale are not inputs to this transform. The legacy `OpusClientScreen` still contains the old `MinecraftUiScale` path, but W4 deliberately does not migrate that screen; screen ownership is a W6 integration task.

## Pointer mapping

The pure Java input contract is verified at:

- `800x600 -> 800x600`
- `1000x625 -> 800x500`
- `720x480 -> 480x320`
- `2880x1800 -> 1440x900`
- `5120x2880 -> 2560x1440`

For each matrix entry, logical points are converted to raw LWJGL framebuffer positions and mapped back through `OpusInputMapper`. The test covers corners, interior points, clamping, and the bottom-origin to top-origin Y inversion.

Result: `W4 INPUT CONTRACT: ALL PASSED`.

## Input protocol V2

Every input message now carries the current browser generation:

```text
INPUT <generation> move <x> <y> <modifiers>
INPUT <generation> leave <x> <y> <modifiers>
INPUT <generation> down|up <x> <y> <button> <modifiers>
INPUT <generation> wheel <x> <y> <delta> <modifiers>
INPUT <generation> char <bmp-codepoint> <modifiers>
INPUT <generation> key <windows-vk> <down> <modifiers>
```

The helper posts the event onto the CEF UI thread and checks the generation again before injection. An event from an older route/viewport is dropped.

The dedicated negative test advances the viewport from generation 1 to generation 2, sends a raw generation-1 pointer event, and observes:

```text
stale input generation 1; current 2; dropping
PASS stale-generation input is dropped before CEF injection
```

## Modifiers and key mapping

Protocol modifier bits are:

- `1`: Shift
- `2`: Control
- `4`: Alt/Option
- `8`: Command/Meta

The helper maps them to CEF event flags. The pure input contract covers all four bits and combined masks.

The helper's macOS native-key mapping now covers the navigation/editing keys used by Opus plus ANSI letters A-Z and digits 0-9, which allows real shortcut key events instead of synthesizing DOM events.

CEF runtime acceptance includes both `Control+A` and `Command+A` and confirms the DOM receives the corresponding `ctrlKey` / `metaKey` semantics.

## Browser-native acceptance

A deterministic CEF OSR page is driven exclusively through CEF input APIs. Its DOM changes a solid status pixel for each accepted event, and the Java harness verifies the resulting BGRA frame.

Accepted paths:

- raw LWJGL framebuffer point -> CSS hover at DPR 2: PASS
- native mouse leave: PASS
- left mouse: PASS
- right mouse: PASS
- middle mouse: PASS
- Shift + left mouse modifier: PASS
- positive/negative wheel direction: PASS
- editable-input focus by native pointer: PASS
- Unicode BMP text `A` followed by `U+1EAF`: PASS
- Backspace: PASS
- ArrowLeft: PASS
- Escape: PASS
- Tab: PASS
- Control+A: PASS
- Command+A: PASS
- stale generation rejection: PASS

The initial text test uncovered two real issues in the proof path:

1. CEF mouse/key structs were not zero-initialized; W4 now uses zero-initialized event structs.
2. the test source encoded the Vietnamese comparison literal in decomposed form while CEF inserted precomposed `U+1EAF`; the oracle now compares code points directly rather than depending on Unicode normalization.

The browser console confirmed the inserted value as code points `41,1eaf`.

## Regression evidence

After the W4 protocol change:

- W1: `WEBVIEW HARNESS: ALL PASSED`
- W2 pure coordinate + AppKit metrics + CEF oracle 3/3: PASS
- W3 focused 5K/triple-buffer/remap-barrier harness: PASS
- W4 pure input contract: PASS
- W4 CEF input oracle final stress: 3/3 PASS

## Current boundary

W4 proves direct BMP character events and native key/pointer/wheel semantics. It does not claim full IME/composition support or non-BMP text input requiring surrogate-pair/composition handling. Those must be handled explicitly before a general-purpose text editor surface is considered complete.

W4 also does not switch Minecraft screen ownership. W6 must ensure that when `OpusWebScreen` owns input, gameplay/vanilla GUI does not receive the same physical event.

## Reproduction

Full gate:

```bash
./web-surface-v2/scripts/run-w4.sh
```

Focused tests:

```bash
./web-surface-v2/scripts/run-w4-input-contract.sh
./web-surface-v2/scripts/run-w4-cef-input-oracle.sh
```
