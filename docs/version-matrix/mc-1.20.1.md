# Version Matrix — Minecraft 1.20.1

> Locked per spec Section 3 ("禁止使用浮动版本"). All versions verified against
> `maven.fabricmc.net` on 2026-08-26 during M0 (see docs/spikes/M0-01-project-baseline.md).

## Runtime / Build stack

| Component | Version | Source of truth |
|---|---|---|
| Minecraft | **1.20.1** | Spec §3 |
| Java (compile target) | **17** (`--release 17`) | Spec §3 |
| Gradle daemon JVM | **21** (`C:\Program Files\Java\jdk-21`) | Empirical: Fabric Loom 1.11.x refuses to run on a Java 17 Gradle daemon |
| Yarn mappings | **1.20.1+build.10** | Latest 1.20.1 build on maven.fabricmc.net |
| Fabric Loader | **0.19.3** | Latest stable; matches the user's existing local instance |
| Fabric API | **0.92.11+1.20.1** | Spec candidate confirmed to exist; latest 1.20.1 line |
| Fabric Loom | **1.11.8** | Stable line chosen over 1.17.x to limit toolchain risk; requires JDK 21 daemon + Gradle ≥8.13 |
| Gradle (wrapper) | **8.14.3** | Compatible with Loom 1.11.x |

## Test dependencies

| Component | Version | Notes |
|---|---|---|
| JUnit BOM | **5.10.2** | Pure-Java unit tests for `dev.squire.common` |
| Gson | provided by Minecraft | Not bundled — MC ships Gson at runtime; avoid duplicate classes (spec §97) |

## Deliberate exclusions

| Dependency | Decision | Reason |
|---|---|---|
| MCP Java SDK | not used | Self-built minimal JSON-RPC adapter instead (spec §84 M0-07 fallback clause); avoids pulling reactive/transitive deps into the mod jar. See ADR-015. |
| Any HTTP client SDK | not used | `java.net.http.HttpClient` (JDK built-in), fully async, satisfies thread rules (spec §7). |

## Update procedure

1. Verify candidate version exists on maven.fabricmc.net.
2. Bump in `gradle.properties`.
3. Re-run full test suite + gametest suite.
4. Record change in CHANGELOG.md and, if load-bearing, a new ADR.
