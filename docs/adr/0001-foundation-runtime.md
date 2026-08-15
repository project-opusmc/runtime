# ADR 0001: Minecraft 1.8.9 foundation runtime

Status: partially superseded by [ADR 0003](0003-forge-optifine-runtime.md)

## Decision

- Minecraft is locked to version `1.8.9` and resolved from Mojang metadata.
- Game-side bytecode remains Java 8 compatible.
- The launcher and resolver are Rust.
- macOS ARM64 runs Mojang's x86_64 `jre-legacy` and x86_64 LWJGL 2 natives
  under Rosetta. Host and game architectures are modeled separately.
- The game directory is isolated from `.minecraft`.
- The original standalone child-first OPUS game classloader decision has been
  superseded. The supported profile now enters Forge's LaunchWrapper, which
  owns game classloading and transformer ordering; see ADR 0003.
- Authenticated game arguments are transferred through a bounded,
  length-prefixed stdin protocol to the Forge bootstrap. They are never placed
  in the OS command line.
- Launch is cache-first. A versioned local install state is accepted only after
  the pinned Minecraft version JSON, pinned Java runtime index and every
  transitive artifact have passed integrity verification.

## Verification gates

An artifact is usable only after its declared size and SHA-1 match. Downloads
are written to a sibling temporary file and atomically renamed after
verification. Library rules are evaluated using the game architecture, not the
launcher host architecture.

The verified Mojang logging configuration must contain its deny filter for
`${...}` lookups. OPUS fails closed when that anchor is absent and derives a
session-local configuration that also suppresses legacy session-token logging.

The first transformer changes exactly one verified `Minecraft 1.8.9` window
title constant in runtime class `ave`. Zero or multiple anchors fail the class
load instead of silently applying an uncertain patch. Under the active profile,
that patch is registered through the OPUS Forge coremod rather than a standalone
classloader.
