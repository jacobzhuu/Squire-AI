# Contributing to Squire AI

Thanks for helping! This project has one hard rule above all others: **the
architecture is not negotiable**. The prohibitions in `README.md` (no raw command
execution, no gateway bypass, no blocking server-thread I/O, no LLM-judged
completion, no MCP-as-permission-system, no skipped Permission/Policy/Capability/
Validation) are enforced by tests. A PR that weakens them will be declined even if
it ships a nice feature.

## Getting set up

```
git clone <repo> && cd Squire-AI
./gradlew test           # unit tests (JUnit 5)
./gradlew runGametest    # deterministic in-server GameTests
./gradlew build          # full jar
```

Toolchain: Minecraft 1.20.1, Fabric Loader 0.19.x, Java 17 target (Gradle daemon
may run on JDK 21 — see docs/version-matrix). Windows tip used during development:
`export JAVA_HOME='C:\Program Files\Java\jdk-21'`.

## Ground rules

1. **Tests required.** New behavior needs unit tests; world-touching behavior also
   needs a GameTest in `src/main/java/dev/squire/gametest/`. Bug fixes need a
   regression test that fails without the fix.
2. **Spec is source of truth.** `docs/IMPLEMENTATION_SPEC.md` governs. Where the
   spec and the real 1.20.1 API conflict, the API wins — but keep the architectural
   intent and write an ADR describing the deviation.
3. **ADR for decisions.** Any new dependency, security-relevant change, or
   spec-vs-API resolution gets `docs/adr/ADR-0NN-<slug>.md`.
4. **Module boundaries.** Code lives in its milestone package
   (`dev.squire.server.<area>`); cross-package calls go through the seams that
   already exist, not new static singletons.
5. **Honest docs.** If something is limited (in-memory undo, unimplemented fuzzy
   matching), document it as limited rather than papering over it.

## Commit & PR style

- Small PRs; one concern each.
- Commit message: imperative summary line + body explaining *why*.
- CI must be green (`ci.yml`: unit tests, GameTests, secret scan, license check).

## Adding a tool (third-party mods)

See [docs/guides/tool-api-guide.md](docs/guides/tool-api-guide.md). Every new tool
must ship stable id, schema, validator, permission, risk, exposure,
pre/postconditions, unit tests, a GameTest if it writes the world, and docs.

## Reporting bugs

Please attach debug replay files when relevant (`/squire admin replay on`,
reproduce, `/squire admin replay off`, then share the JSONL under
`config/squire/replay/`). They are redacted before writing — but skim them anyway
before sharing.
