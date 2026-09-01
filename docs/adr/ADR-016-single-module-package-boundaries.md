# ADR-016: Single Gradle Module With Enforced Package Boundaries

## Status
Accepted (M0 decision; refines spec §5 "recommended structure")

## Context
Spec §5 recommends Gradle modules common/api/server/client/mcp/gametest/test-fixtures.
A multi-project Fabric Loom build multiplies toolchain risk (per-subproject loom wiring,
remapping across source sets) for an architecture whose real goal is boundary enforcement,
not separate artifacts.

## Decision
One Gradle project produces one mod jar. The module boundaries from spec §5 are realized as
top-level packages with hard rules:

```
dev.squire.common.*   -> MUST NOT import net.minecraft.* or net.fabricmc.*
dev.squire.api.*      -> third-party facing interfaces only (Tool/Sensor/Task API)
dev.squire.server.*   -> all authority: runtime, gateway, tasks, world, persistence
dev.squire.client.*   -> renderer/GUI/HUD only
dev.squire.mcp.*      -> optional MCP client bridge (default off)
dev.squire.gametest.* -> GameTests (inert without -Dfabric-api.gametest)
```

Enforcement: `CommonModuleBoundaryTest` scans sources of `src/main/java/dev/squire/common`
at test time and fails the build on any forbidden import. CI runs it on every build.
GameTests live in the main source set so the gametest run inherits them; they are excluded
from the released jar by package filter at release time (M6).

If a future milestone genuinely needs separate artifacts (e.g., a standalone api jar), split
then — the package layout maps 1:1 onto future modules.

## Consequences
- Architecture intent (boundary enforcement, no MC types in common) is preserved and machine-
  checked.
- Build stays a single loom configuration — fewer moving parts on the locked 1.20.1 stack.
