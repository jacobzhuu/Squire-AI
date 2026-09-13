# Engineer full-catalog validation — 2026-09-06

This is the earlier full-catalog release record. Subsequent fluid support and renewed
qualification are documented in [Engineer fluid construction](engineer-fluid-construction.md);
the counts and installed artifact below describe that earlier release, not the new build.

Fabric / Minecraft 1.20.1, Java 17 bytecode, built and tested with JDK 21.
KeepItLevel source commit: `74259e3da775f467275adb57b076caefdea84d99`.

## Content and growth

- 750 source blueprints; 72 player families in nine categories; one internal asset.
- 486 enabled variants: 485 native qualification passes plus the legacy fountain.
- 263 explicitly unavailable resources, with source-conversion, site, placement or
  access errors retained. Inclusion does not mean lossless conversion.
- New unlocks by Engineer level: `106, 31, 49, 39, 52, 38, 44, 43, 42, 42`.
- Permanent-material waste: `20, 18, 16, 13, 10, 8, 6, 4, 2, 0` percent.
- Full residence construction: Lv1 7,150 ticks; Lv10 2,425 ticks; ratio 2.94845.
  This includes travel, supply stages, construction, lighting and temporary cleanup.
  Each level receives its own exact bill; traits are cleared for comparison.

## Automated evidence

- 825 JUnit tests, zero failures/errors/skips.
- All 243 registered GameTests covered successfully: M30 full-library qualification
  plus a clean final run of the other 242 tests.
- M30 evaluates all 750 sources and records failures per asset; a passing M30 test
  does **not** mean all 750 structures passed qualification. The hash-bound results
  are packaged in `data/squire/blueprint_rules/keepitlevel-qualification.json`.
- M29 parses all converted native structures and tests frozen waste, atomic tall
  plants, paid-block rebuild refusal and unconfirmed quote invalidation.
- M16 builds the six legacy imports with real NPCs, verifies final geometry, tests
  repeated backpack-sized escrow deposits, high construction access and cancellation
  cleanup across snapshot recovery.
- M32 physically builds native sawmill Tier II, rail station Tier V and road Tier III.
- M20/M21/M22 cover server-driven family selection, Tier/level gates and panel actions.
- `python tools/import_keepitlevel.py --check`: 2,253 generated files, zero drift.
- Final shipping build omits `squireGameTestClass`, restoring the full Fabric
  GameTest entrypoint manifest rather than shipping a filtered test manifest.

The first full regression found a pre-existing M2 guard-fixture collision: its
18-block-wide zombie query counted entities from adjacent concurrently running
fixtures. The test now tracks its own spawned threat, runs in a separate batch and
cleans up its owner/body. Combat production logic was not changed for this failure.
The final 242-test run passed after this fixture fix and the snapshot-write failure
guard. M30 evidence was retained: neither change alters blueprint qualification.

Local logs: `build/engineer-final-regression.log` (M30 and initial full run),
`build/engineer-final-suite.log` (clean 242-test run),
`build/engineer-release-build.log` (unfiltered shipping build).

## Installed artifact

Installed to the existing Minecraft instance:
`E:/PCL-MC/PCL-MC/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/squire-0.1.0+mc1.20.1.jar`.
The previous JAR is retained alongside it as
`squire-0.1.0+mc1.20.1.jar.pre-full-catalog-20260906-043209.bak`.

The installed 9,093,258-byte JAR matches the release build:
`B1892F29198A1A870098E6CC14867AF3FB282A043B3FFA68E1BD11FF93000450` (SHA-256).
The package audit confirmed 750 native NBT files, 750 index entries, 486 enabled
variants, both license notices, all 34 GameTest entrypoint classes including M30,
and no bundled MineColonies/Structurize implementation classes.
No user world was opened or modified as part of installation. Restart Minecraft to
load the new code; make a normal world backup before first loading an older save.

## Scope and limitations

- No third-party mod code, models or textures copied; MIT notice and per-coordinate
  source/replacement reports ship with the assets.
- `.schem` is an extension point, not an implemented importer.
- Fluid execution and unsupported special resources remain blocked, including at Lv10.
- Automated server UI/packet tests are not a manual client rendering/playthrough check.
  The final JAR must be loaded by restarting the user's game after installation.
- Real-NPC samples and controlled qualification do not guarantee every terrain setup.
  Site requirements and protection/reach/path checks remain mandatory.
- Hard-crash recovery across Minecraft chunk/player files is not a distributed
  transaction. Uncertain settlement intents pause for inspection; they are never
  automatically replayed, refunded or erased. Snapshot files are retained for recovery.

See [Engineer Building Catalog](guides/engineer-building-catalog.md) for layout,
loading, level configuration, material accounting, migration and adding resources.
