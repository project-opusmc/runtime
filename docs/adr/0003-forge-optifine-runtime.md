# ADR 0003: Pinned Forge + OptiFine runtime

Status: accepted

Supersedes the standalone child-first classloader portion of
[ADR 0001](0001-foundation-runtime.md). The 1.8.9, Java 8, isolated-data, and
artifact-verification decisions in ADR 0001 remain in force.

## Context

RBW needs a reproducible Minecraft 1.8.9 runtime that can use OptiFine and
leave a controlled path for future reviewed Forge integrations. The former RBW
bootstrap owned its own `TransformingClassLoader`; that conflicts with Forge's
LaunchWrapper ownership of tweakers and transformed Minecraft classes.

## Decision

- The supported runtime is Forge `1.8.9-11.15.1.2318-1.8.9` over Minecraft
  `1.8.9`, entered through Forge LaunchWrapper and `FMLTweaker`.
- The checked-in Forge runtime lock pins the profile, library coordinates,
  HTTPS source URLs, sizes, and SHA-1 values. RBW does not run a Forge
  installer or adopt mutable third-party profile metadata.
- OptiFine `1.8.9 HD U M5` is user-provided. RBW verifies a local JAR against
  the lock and makes an isolated copy for its managed runtime; it never
  downloads, bundles, redistributes, modifies, uploads, or removes the source
  JAR.
- `ForgeBootstrapMain` retains the secret-free stdin argument boundary, then
  invokes Forge's LaunchWrapper. Forge owns game classloading and transform
  ordering.
- RBW game integration is a verified Forge coremod. Its `IFMLLoadingPlugin`
  registers the RBW transformer adapter with Forge rather than creating a
  competing Minecraft classloader.
- QA allows only the verified RBW coremod and the exact locally imported
  OptiFine artifact. Any future managed mod must be explicitly pinned,
  integrity-checked, ordering-reviewed, and covered by the directory
  validation policy before QA supports it.

## Consequences

- The legacy standalone `BootstrapMain` / `TransformingClassLoader` route is
  not a supported Premium or QA launch path.
- Installer, desktop, CLI, package assets, tests, and documentation must agree
  on the Forge profile and on the two-artifact QA mod set.
- A missing, altered, or unsupported OptiFine JAR is a launch blocker, not a
  fallback to vanilla.
- Future mods are a deliberate compatibility/release decision; users cannot
  extend the QA runtime by dropping arbitrary JARs into its managed data.
