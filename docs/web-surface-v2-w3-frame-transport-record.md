# Web Surface V2 — W3 Frame Transport Record

Date: 2026-09-16

## Decision

W3 replaces the legacy fixed two-slot `3840x2160` mapping with a negotiated three-slot shared-memory transport.

The control socket remains line-based loopback TCP and never transports pixels. CEF OSR pixels remain in a shared sparse file. The Java host owns capacity changes and grows the existing file before requesting a helper remap:

```text
Java file grow/map
  -> SHM_RESIZE <request> <total_bytes>
  -> helper remap on CEF UI thread
  -> SHM_READY <request> <total_bytes> <slot_bytes>
  -> Java atomically adopts the pending mapping
  -> VIEW may publish the larger viewport
```

The mapping does not shrink during the session. This avoids repeated remaps during resize oscillation and keeps a previously proven large backing store reusable.

## Capacity policy

- ownership slots: `3`
- minimum slot capacity: `1 MiB`
- slot capacities grow by powers of two
- maximum slot capacity: `128 MiB`
- maximum accepted raster dimension: `8192`
- total mapped capacity: exactly `3 * slotCapacity`

The initial `640x480 @1.0` harness viewport requires `1,228,800` BGRA bytes and therefore starts at `3 x 2 MiB`.

Observed negotiated growth:

- `640x480 @1.0 -> 640x480`: `3 x 2 MiB`
- `800x500 @1.25 -> 1000x625`: `3 x 4 MiB`
- `1920x1080 @2.0 -> 3840x2160`: `3 x 32 MiB`
- `2560x1440 @2.0 -> 5120x2880`: `3 x 64 MiB`

The 5K Retina case consumes `58,982,400` bytes per BGRA frame and therefore fits the negotiated `64 MiB` slot.

## Slot ownership contract

A helper slot is either free or owned by exactly one published frame sequence. Java increments ownership only after validating and publishing a frame object, and `Frame.release()` is idempotent through an atomic released flag.

The dedicated harness deliberately holds three frames without releasing them. It verifies:

1. the three frames occupy three distinct slots;
2. a fourth paint is not published while every slot is owned;
3. releasing one frame lets the helper resume a pending paint;
4. Java ownership accounting drains back to zero.

Result: `PASS three-slot ownership blocks the fourth paint and resumes after RELEASE`.

## Remap barrier

Shared-memory remap is forbidden while Java still owns any frame from the active mapping. Before growth, Java drops/releases its queued latest frame and requires the outstanding ownership count to be zero.

The negative-path harness intentionally keeps one frame owned and requests a growth from the initial 2 MiB slot to the 5K viewport. The client fails closed, does not adopt a new mapping, and reports:

```text
WARN Opus CEF cannot grow shared memory while 1 frame(s) are still owned
PASS growth is fail-closed while an old frame is still owned
```

This prevents a remap from changing slot offsets while the compositor is still reading an older mapped frame.

## Resize-generation compatibility

W2 already sealed the rule that stale CEF raster sizes are not published under a new viewport generation. W3 retains that rule across shared-memory growth. CEF remapping completes before the larger `VIEW` is sent, while stale geometry paints are still dropped and explicitly invalidated for repaint.

## Acceptance evidence

W1 lifecycle/navigation/input baseline after W3: `WEBVIEW HARNESS: ALL PASSED`.

Complete W2 regression after W3:

- pure coordinate contract: PASS
- AppKit Retina metrics: PASS
- CEF pixel/input oracle iteration 1/3: PASS
- iteration 2/3: PASS
- iteration 3/3: PASS

Dedicated W3 transport harness final stress:

- iteration 1/3: `W3 FRAME TRANSPORT: ALL PASSED`
- iteration 2/3: `W3 FRAME TRANSPORT: ALL PASSED`
- iteration 3/3: `W3 FRAME TRANSPORT: ALL PASSED`

Each run also validates the 5K Retina raster, downsize/re-upsize reuse, three-slot backpressure, and the fail-closed remap barrier.

## Reproduction

The complete gate is:

```bash
./web-surface-v2/scripts/run-w3.sh
```

For a focused transport run:

```bash
./web-surface-v2/scripts/run-w3-frame-transport.sh
```

## Boundary

W3 proves CPU shared-memory frame transport only. It does not yet approve the final Minecraft OpenGL upload path, PBO optimization, dirty-rectangle upload, or production Web Surface screen ownership. Those remain later integration/performance work.
