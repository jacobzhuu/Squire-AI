# Tool API Guide

How third-party mods extend Squire with tools, sensors and task types.

## Entry point

Add the `squire` entrypoint key to **your** `fabric.mod.json`:

```json
"entrypoints": {
  "squire": ["com.example.mymod.MySquireExtension"]
}
```

Implement any combination of:

```java
public interface SquireToolProvider   { void registerTools(ToolRegistrar registrar); }
public interface SquireSensorProvider { java.util.List<SquireSensor> createSensors(); }
public interface SquireTaskProvider   { void registerTaskTypes(TaskTypeRegistrar registrar); }
```

Your classes are instantiated by the core at server start. Import failures are
contained: a broken provider is reported to the log and skipped — it can never
take the server down (§58).

## Registering a tool

```java
public final class MySquireExtension implements SquireToolProvider {
    @Override
    public void registerTools(ToolRegistrar registrar) {
        registrar.register(ExternalTool.builder("mymod:bless_player", MySquireExtension::bless)
            .description("Applies a short regeneration effect to the caller's owner.")
            .tag("healing")
            .alias("治疗玩家")
            .arg(ArgSpec.requiredString("player", "target player name"))
            .readOnly(false)
            .risk("MEDIUM")
            .exposure("owner")
            .precondition("caller has an owner online")
            .postcondition("target has regeneration for 30s")
            .build());
    }

    private static ExternalToolResult bless(ToolInvocation inv) {
        // ... do the work, return typed data ...
        return ExternalToolResult.ok(inv.callId(), Map.of("applied", true));
    }
}
```

### What every tool must provide (§58 checklist)

| Item | Where |
|---|---|
| stable id | namespaced (`mymod:…`); duplicates/reserved namespaces are refused |
| schema | `ArgSpec` list — types + descriptions |
| retrieval metadata | optional `tag(...)` / `alias(...)`; name, description and schema are also scored automatically |
| validator | args validated against schema before your handler runs |
| permission | `permissionNode(...)`; defaults are conservative |
| risk | `risk("LOW"/"MEDIUM"/"HIGH")` — HIGH requires owner confirmation |
| exposure | who may call it |
| pre/postconditions | documented strings consumed by planning/verification |
| unit test | yours to ship |
| GameTest | required if the tool writes the world |
| docs | changelog/readme entry |

## Trust model — read this before designing your tool

Annotations (`readOnly`, `destructive`) are **hints that can only make things more
restrictive**. The per-server trust store decides what actually runs:

- `TRUSTED_MOD` / `ADMIN_APPROVED_LOCAL` / `ADMIN_APPROVED_REMOTE` → allowed within policy.
- `UNTRUSTED` (default!) → write-shaped or high-risk use is denied even if you claim readOnly.

Admins manage trust via `config/squire/mcp-trust.json` and in-game commands.
Nothing your jar ships can raise its own trust — trust is server-owner data.

## Sensors

Read-only, bounded, budgeted observers that feed perception lines to agents.
Return them from `SquireSensorProvider.createSensors()`:

```java
public class WeatherSensor implements SquireSensor {
    @Override public SensorDefinition definition() { /* id, cadence, bounds */ }
    @Override public SensorResult observe(SensorContext context) { /* bounded text */ }
}
```

Sensor output is appended to perception as context — it cannot execute actions.

## Task types

Custom goal-driven activities integrate with the scheduler (priority, retry,
timeout, verification) through `SquireTaskProvider`. Your executor reports
`CONTINUE` / `WORK_DONE` / `FAILED`; the server-side GoalVerifier judges completion
— your code never declares victory on its own.

## MCP servers (data-only)

To expose external *data* rather than in-process logic, run an MCP server and have
the admin connect it — see [mcp-guide.md](mcp-guide.md). MCP results are data;
they re-enter the same validation pipeline as everything else.

## Testing your extension

- Unit-test handlers pure-JVM against `ToolInvocation`.
- Ship a GameTest for world writes.
- The core's own suite proves the contract: see `M4ExtensionGameTests`
  (untrusted destructive denial, import refusal reporting) in this repository as a
  reference.
