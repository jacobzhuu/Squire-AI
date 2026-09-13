# Engineer scaffolding update — 2026-09-06

Minecraft/Fabric 1.20.1; Java 17 bytecode, built using JDK 21.

## Behavior

- New access programs place vanilla scaffolding, with native distance/bottom state
  calculation, grounded climbing columns and supported platforms.
- Movement remains collision-constrained, with short-range building and no flight,
  teleport or no-clip. Land MoveControl is bypassed only inside real scaffolding,
  where its obstacle-jump behavior otherwise causes repeated ceiling collisions.
- Routes exclude downward diagonal entry through a suspended scaffold bottom lip.
  Native hazards and neighbouring fence/wall collisions remain checked.
- Preview/supply totals derive temporary materials from the saved work cells.
  Temporary materials incur no permanent-material waste. Reclaim refunds exactly
  one actual owned item; it must not strand the worker or collapse connected
  scaffolding, including unowned player bridges.
- Committed cobblestone programs remain readable and retain their original bill,
  geometry and refunds. Snapshot validation explicitly accepts both materials.
- Construction UI/activity and blocked-work messages now describe scaffolding.

## Verified

- `gradlew.bat build`: successful; final build has no GameTest-class filter.
- 826 JUnit tests, zero failures/errors/skips.
- 246 distinct GameTests passed across two runs: 32 Engineer-focused tests, then
  218 other/repeated tests (the four scaffolding tests appear in both runs).
- M16: six existing imported buildings; tall tower; cancellation after snapshot
  recovery; preserving player replacement blocks; real refunds and escrow stages.
- M32: native sawmill II, rail station V and road III, including physical cleanup.
- M33: real climbing/building/reclaim; native scheduled-tick stability; refusing
  unsupported placement; six-block cantilever limit; dangerous floors/neighbours;
  protecting an attached unowned bridge; retaining mixed legacy material identity.
- Full residence build: Lv1 7,325 ticks; Lv10 2,500 ticks (2.93×), including travel,
  material stages, permanent construction, temporary cleanup and lighting.
- Shipping JAR audited: 35 unfiltered GameTest entrypoints, scaffolding helper class
  and all 750 existing native NBT resources present.

Logs: `build/scaffolding-regression4.log`,
`build/scaffolding-other-regression.log`, `build/scaffolding-release-build.log`.
Earlier numbered scaffolding runs document failures found and fixed, not the final
result. The M30 offline all-750 qualification was not rerun in this update; its
previous content evidence is retained, not claimed as a new full-library NPC test.
Every newly requested project is still reviewed against its current actual site.

## Artifact and installation

`build/libs/squire-0.1.0+mc1.20.1.jar` (9,104,598 bytes)

SHA-256: `EF3CBBE751430E30F618CF9AA778A29974EEF4C2FC750B369044B85F6BBC0C62`.

The target Minecraft client was still running at release verification. Its installed
JAR was not overwritten and no user world was opened/edited. Installation requires
saving and exiting the client first. The existing installed JAR remains the prior
full-catalog build (`B1892F29198A1A870098E6CC14867AF3FB282A043B3FFA68E1BD11FF93000450`).
