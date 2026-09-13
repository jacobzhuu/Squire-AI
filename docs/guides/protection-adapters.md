# Third-party protection adapters

Squire's core checks vanilla dedicated-server spawn protection and exposes a server-side
extension point for claim, faction and locked-container mods. Installing a protection
mod by itself does not register an adapter, so Squire must not be described as claim-safe
until the matching integration is installed and tested.

An integration add-on registers a `ProtectionAdapterProvider` with Fabric's
`squire_protection` entrypoint and declares Squire as a dependency:

```json
{
  "depends": { "squire": "*" },
  "entrypoints": {
    "squire_protection": ["example.claims.SquireClaimsProvider"]
  }
}
```

The provider creates one adapter for the running server:

```java
public final class SquireClaimsProvider implements ProtectionAdapterProvider {
    @Override
    public ProtectionAdapter create(MinecraftServer server) {
        return new ClaimsAdapter(server);
    }
}
```

Implement `canBreak`, `canPlace`, and `canInteract` with the protection mod's
authoritative permission query. The actor UUID is the player who owns the companion;
the companion entity itself must not be used as the actor. `canEditRegion` must deny if
any cell in the requested region is protected for that actor. Container logistics checks
both halves of a double chest, and structure-consuming actions check every block before
changing the world.

Squire combines registered adapters with vanilla protection using an all-must-allow
rule. A null decision or a runtime exception denies the attempted mutation. If a provider
cannot initialize, its failed state also blocks world mutations, and the server log names
the failing integration. Adapters should return a clear denial reason and should not
cache permission results across ownership changes.

The entrypoint is an integration seam, not a compatibility claim for every protection
mod. Test the actual server versions with two players: deny one player access to another
player's claim, locked container, each half of a cross-boundary double chest, and a
construction area. Also verify allowed owner and trusted-member actions. Keep world
editing and logistics disabled until those checks pass for the selected protection mod.
