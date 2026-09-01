# Professions: Guard and Engineer

A profession is the **long game**. Roles (see `roles-guide.md`) decide which abilities your
companion can slot in; a profession decides how much of its own trade it has actually
learned. The two are independent — taking a profession never removes anything, and a
companion that never takes one behaves exactly as it did before.

> **Levels give behaviour. You give resources.**

Across all ten Guard levels the numbers move by five hearts and at most +1.5 melee
damage. Everything else a level buys is a *decision* it did not know how to make before:
which target to hit, when to raise a shield, when to stop chasing, when something is too
big to fight.

## The short version

```
/squire profession              # profession, level, XP bar, what's next
/squire profession list         # what Guard and Engineer each learn, level by level
/squire profession guard        # take a profession (starts at Lv1)
/squire profession promote      # spend the materials and take the level
/squire profession forget       # step down (level and XP reset; boss tallies do not)

/squire stance defensive        # Guard Lv8: how far he chases, when he pulls back
/squire supplies                # Guard Lv5: what he's carrying

/squire design house            # Engineer: open a parametric site
/squire design floors 2         # every level unlocks another parameter
/squire design preset save 生存屋
```

The companion page in the panel shows profession, level, the XP bar, and
**READY FOR PROMOTION** when the bar is full.

## Levelling

Each level has its **own** bar — you never watch a five-digit lifetime total crawl.

| Level | XP to the next | Promotion material |
|---:|---:|---|
| 1 | 100 | Iron Ingot ×8 |
| 2 | 200 | Gold Ingot ×8 |
| 3 | 350 | Lapis Lazuli ×16 |
| 4 | 550 | Diamond ×2 |
| 5 | 800 | Emerald ×12 |
| 6 | 1100 | Diamond ×4 |
| 7 | 1500 | Netherite Scrap ×4 |
| 8 | 2000 | Diamond ×8 |
| 9 | 2800 | Nether Star (Guard) / Beacon (Engineer) |

Three things must all be true to take a level: **the bar is full**, **you hand over the
materials**, and **you ask for it**. Nothing levels up on its own.

If the bar fills before you have the materials, the extra is not wasted — it is held as
*overflow*, up to a quarter of the next level's requirement, and moves into the new bar on
promotion. The cap is there so you cannot bank three levels and take them all at once.

**Dying costs nothing.** Death, lost gear and downed states never roll a profession back.

## Guard

Solves entity threats. Its growth is "gets better at fighting", not "turns into a boss".

| Lv | What actually changes |
|---:|---|
| 1 | Approaches and attacks. Eats and drinks to heal, as before. |
| 2 | **Equipment awareness** — swaps out a weapon that is about to break. |
| 3 | **Threat evaluation** — scores targets instead of hitting whatever is nearest, and stops chasing a low-value mob away from you. |
| 4 | **Bow proficiency** — distant and airborne targets get shot at. Arrows and durability are consumed normally. |
| 5 | **Weapon switching** by what it is facing, and **supply awareness** (`/squire supplies`). |
| 6 | **Shield** against incoming ranged fire, and healing timing: food first, potions only below 35%, with a cooldown. |
| 7 | **Intercept** — puts itself between an attacker and you — and **owner emergency**: when you are in trouble it pulls in tight. |
| 8 | **Combat stance** — you pick Defensive / Balanced / Aggressive. Personality never overrides your choice. |
| 9 | **High-threat awareness** — recognises a Warden and does *not* engage. A high level is better judgement, not more recklessness. |
| 10 | **Guardian protocol** — while you are in danger, every target that is not threatening you is dropped. |

Max health goes 20 → 30 across those ten levels. That is the entire stat curve.

### Earning Guard XP

A kill counts only if the companion **landed the killing blow** or **dealt at least 20% of
the target's maximum health**. Standing next to you while you kill things earns nothing.

| Tier | Examples | XP |
|---|---|---:|
| 1 | Zombie, Spider, Drowned | 2 |
| 2 | Skeleton, Creeper, Witch, Pillager | 4 |
| 3 | Blaze, Ghast, Wither Skeleton, Evoker | 8 |
| 4 | Ravager, Elder Guardian | 20 |
| Boss | Warden 120 / Wither 180 / Ender Dragon 250 | — |

Targets that were threatening **you** are worth ×1.25.

Mob farms do not work: within a five-minute window the same mob type pays 100% for the
first ten, 50% to the 25th, 20% to the 50th, and 5% after that — at which point a Creeper
rounds to zero. The same boss pays half the second time and a quarter thereafter, for the
lifetime of that companion; that tally survives restarts and stepping down from the
profession.

Modded mobs are not guessed at. They fall to tier 1 unless a server owner lists them.

## Engineer

Solves template buildings and blueprint work. Its growth is "gets better at using the
blueprint system" — **not** "leans harder on the model". Natural language only ever picks
parameters; the shape itself is generated by ordinary deterministic code.

| Lv | What actually changes |
|---:|---|
| 1 | Basic blueprint: place, preview in particles, nudge, confirm. Footprint up to 9×9. |
| 2 | **Template library I** — shed, small storage, small house. Footprint to 13. |
| 3 | **Rotation** — 0° / 90° / 180° / 270°. |
| 4 | **Material regions** — foundation, wall, floor, roof, window and trim chosen separately. Footprint to 17. |
| 5 | **Structural variants** — roof (flat/gable/hip), foundation, window and entrance styles. Footprint to 21. |
| 6 | **Multi-floor** — two and three storeys, with floors and stairs generated for you. Large house, warehouse, watchtower. |
| 7 | **Mirror** on X or Z, and **one optional module** (porch, chimney, storage wing, tower, balcony). |
| 8 | **Modular blueprint** — combine modules freely. Footprint to 32. |
| 9 | **Compound blueprint** — an outpost with a house, warehouse, watchtower, fence and gate in one preview — and **saved presets**. |
| 10 | The full library, footprint to 48. |

### Earning Engineer XP

XP is paid **once, after the construction verifies**. A cancelled preview, a cancelled
build, a failed command or an interrupted project pays nothing at all. Blocks placed are
never counted individually — that would reward paving a floor over and over.

```
Final = BaseProjectXP × Size × StructuralVariant × Floors × Modules × RepeatPenalty
```

Base is 20 / 40 / 70 / 110 / 180 by project tier; size multiplies by 1.00 to 1.75; each
module adds 10% up to +40%; two floors ×1.15, three ×1.25.

Repeats decay to 75% / 50% / 25% / 10% within the window. **Material colour is not part of
a project's identity** — an oak house and a spruce house of the same shape are the same
project, so re-skinning is not a way to farm.

## For server owners

Every number on this page lives in `config/squire/profession.json` and none of them is
hardcoded in behaviour. The file is optional; without it the tables above apply.

One default deliberately departs from the design document: `foodHealThreshold` ships at
**0.84**, the same trigger every companion already used, rather than the document's 0.65.
A Lv6 Guard that starts eating *later* than an unprofessional companion would be growth
turned into a downgrade. Set it to 0.65 if you want the leaner curve.

```json
{
  "general": {
    "xpRequiredPerLevel": [100, 200, 350, 550, 800, 1100, 1500, 2000, 2800],
    "overflowXpRatio": 0.25,
    "promotionItems": {
      "2": [ { "item": "minecraft:iron_ingot", "count": 8 } ]
    },
    "masterPromotionItems": {
      "guard":    [ { "item": "minecraft:nether_star", "count": 1 } ],
      "engineer": [ { "item": "minecraft:beacon", "count": 1 } ]
    }
  },
  "guard": {
    "hpPerLevel": [20, 20, 22, 22, 24, 24, 26, 26, 28, 30],
    "bonusDamagePerLevel": [0, 0, 0, 0, 0.5, 0.5, 0.5, 1.0, 1.0, 1.5],
    "tierBaseXp": [2, 4, 8, 20],
    "mobThreatTier": { "somemod:dire_wolf": 3 },
    "bossXp": { "somemod:lich": 200 },
    "highThreatMobs": ["minecraft:warden", "minecraft:wither", "minecraft:ender_dragon"],
    "repeatKillWindowTicks": 6000,
    "repeatKillThresholds": [10, 25, 50],
    "repeatKillMultipliers": [1.0, 0.5, 0.2, 0.05],
    "bossRepeatMultipliers": [1.0, 0.5, 0.25],
    "protectionXpBonus": 1.25,
    "effectiveDamageFraction": 0.2,
    "foodHealThreshold": 0.84,
    "potionHealThreshold": 0.35,
    "retreatThreshold": 0.2,
    "consumableUseCooldown": 60,
    "chaseDistanceByStance": { "defensive": 0.75, "balanced": 1.5, "aggressive": 2.5 }
  },
  "engineer": {
    "projectBaseXp": [20, 40, 70, 110, 180],
    "sizeMultiplier": { "small": 1.0, "medium": 1.25, "large": 1.5, "very_large": 1.75 },
    "floorMultiplier": { "1": 1.0, "2": 1.15, "3": 1.25 },
    "moduleMultiplier": 0.1,
    "maxModuleBonus": 0.4,
    "repeatProjectPenalty": [1.0, 0.75, 0.5, 0.25, 0.1],
    "repeatWindowTicks": 72000,
    "maxBlueprintSizeByLevel": [9, 13, 13, 17, 21, 21, 21, 32, 32, 48],
    "templateMinLevel": { "shed": 2, "large_house": 6, "outpost": 9 },
    "templateTier": { "shed": 1, "house": 2, "large_house": 3, "outpost": 5 }
  }
}
```

Only the keys you write are overridden; anything you leave out keeps its default. A single
malformed entry falls back on its own and logs a line — the file never loads halfway.

## What this version deliberately does not do

Multi-companion coordination, personality, long-term memory, secondary professions,
ability slots for professions, Knight/Ranger and Architect/Mechanist branches, autonomous
road planning, village finding, Create machinery design, redstone design, multi-turn agent
loops, self-reflection after failure, autonomous world exploration, survival material
consumption for construction, and a downed/revive system are all out of scope here and
planned separately.
