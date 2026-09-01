# Roles, Abilities and Autonomy

Your squire starts as a generalist and stays useful forever. Picking a role does not
take anything away — it opens a track it can train on, and a small number of slots you
have to spend carefully.

## The short version

```
/squire profile                        # who is he right now
/squire role                           # the five roles and what they unlock
/squire role builder                   # specialise (near home, with a cooldown)
/squire ability                        # what can be equipped, and what is still locked
/squire ability equip build.fast       # spend a slot
/squire ability remove build.fast      # get it back
/squire autonomy conservative          # how much he does unasked
```

The panel's **随从** tab shows all of it, and the autonomy buttons are there too.

## Basic vs advanced

Every companion — role or not — can already **guard, build from a blueprint, excavate a
blueprint, haul, deliver and remember places**. Those are *basic* abilities. They cost no
slot and picking a role never removes them.

Everything else is *advanced*: it has to be trained to, and then **equipped into a slot**.
Advanced abilities only ever add behaviour.

| Slots | When |
|---|---|
| 2 | from the start |
| 3 | role level 3 |
| 4 | role level 5 — and the 4th only takes your current role's abilities |

**Unlocks are permanent.** A squire that trained as a Builder keeps "double work speed"
after switching to Guardian — but carrying it costs one of the shared slots. That is the
trade the whole system is built on, and it is why a second squire eventually beats a
better-equipped first one.

## The five roles

| Role | Track | Advanced abilities |
|---|---|---|
| **护卫 Guardian** | combat | 挡在前面 (Lv2) · 集火 (Lv3) · 护送 (Lv4) |
| **建筑师 Builder** | build | 双倍工速 (Lv2) · 通用建材 (Lv3) · 拆改 (Lv4) |
| **管家 Steward** | logistics | 装备维护 (Lv2) · 工地物流 (Lv4) |
| **掘进工 Excavator** | excavate | 照明 (Lv2) · 深掘 (Lv3) |
| **探险家 Scout** | explore | 引路 (Lv2) · 预警 (Lv3) |

农夫 is listed and greyed out. It is not built: farming is break + place + crop-growth
sensing on a multi-day clock, and it is a variant of wild gathering — the one direction
this mod deliberately does not go.

What the advanced ones actually do:

- **集火** — he attacks whatever you are attacking, inside the guard radius.
- **护送** — guard radius ×1.5, so he follows you further from base without dropping out.
- **双倍工速** — twice as many blocks placed per tick. Speed only; a block still costs a block.
- **通用建材** — short of oak planks, any other planks will do. The groups are narrow on
  purpose (planks ↔ planks, common stone ↔ common stone); glass, doors and chests never
  substitute, because swapping those changes what the building *is*.
- **拆改** — clears a wrong block that is in the way instead of skipping the cell. Without
  it he never touches something you put there.
- **工地物流** — short of materials, he walks to a chest near the site and fetches them
  before starting.
- **照明** — after digging, he plants torches from **his own** backpack in the hole.
- **深掘** — twice the excavation rate.
- **引路** — asking him to recall a place makes him walk you there.
- **挡在前面** — while following, he positions himself on the line between you and
  whatever is attacking you. Below his retreat threshold he drops behind you instead;
  a companion that dies holding the line is just a corpse.
- **装备维护** — back at base with nothing to do, he swaps in better armour he is
  carrying. He does not repair anything: repairing needs an anvil and experience, and he
  has neither.
- **预警** — at each patrol waypoint he stops, looks around, and tells you about hostile
  mobs, ground dark enough to spawn them, and doors left open. **Only when there is
  something to say.**

## Behaviour modes

The four modes are genuinely different jobs now, not four labels on "stand around".

**跟随** keeps a 3–6 block band instead of hugging you. When something attacks you and he
has 挡在前面, he moves onto the line between you and it; hurt badly enough (his traits set
the threshold) he falls back behind you instead.

**待命 is an area, not a nail.** He roams freely inside `stayRadius` (8 blocks by default)
and walks back when pushed out. Order him to do something *outside* the circle and the
task fails with `OUT_OF_STAY_AREA` and says so — he is not stuck, he is not allowed.
Switching to any other mode releases the area.

**巡逻 follows a route** when you give it one:

```
/squire patrol add      # records the square HE is standing on
/squire patrol          # the route, and which point is next
/squire patrol clear    # back to circling one spot
```

He walks the points in order, **stops at each one for two seconds and looks around**, then
moves on. Without points he circles his anchor exactly as before, so existing worlds are
unchanged.

**回家** — idle for ten seconds within eight blocks of home, and he starts a low-priority
routine of his own: swap in better armour (needs 装备维护), and stow leftovers in a nearby
chest (needs the 囤积 trait or 积极 autonomy). It is a normal task, so anything you ask for
preempts it immediately. On the default settings he touches nothing.

## Growth

Proficiency is credited **only when a task actually completes**. Cancelled, failed and
timed-out work pays nothing, and a task that resumes after a restart is never counted
twice.

Milestones are `40 → 160 → 500 → 1400 → 3500` on your role's track. Every milestone gives
an ability or a slot. **Nothing ever gives you a combat number** — a Guardian at level 5
does not hit harder, he behaves differently.

Four things stop you farming it:

1. a daily soft cap per track (512 blocks, 1024 items, 40 kills); past it you still earn,
   at 20%;
2. undoing a build **takes the proficiency back** — otherwise build-undo-build is free;
3. blocks that were already correct never count;
4. only hostile mobs count as kills, and only while you are online.

Changing role or moving an ability has a five-minute cooldown and has to happen **near
home**. Specialising is meant to be a decision, not a dropdown.

## Personality

Every squire rolls **1–2 traits** the first time it is summoned, and keeps them. Traits
change mechanics, never speech:

| Trait | Effect |
|---|---|
| 谨慎 Cautious | retreats at 40% health instead of 25% |
| 莽撞 Reckless | retreats at 15% |
| 勤勉 Diligent | builds and digs faster |
| 囤积 Hoarder | stows leftovers after a job |
| 轻捷 Swift | moves faster |
| 皮实 Sturdy | takes slightly less damage |

谨慎 and 莽撞 are never rolled together.

## Autonomy

| Level | What he does unasked |
|---|---|
| 保守 Conservative | only what you say |
| 标准 Standard *(default)* | defends himself, comes to rescue you, finishes what he started |
| 积极 Proactive | also tidies up, warns you, and does the finishing touches |
| 完全自动 | **not open** — it spends your materials and edits the world on its own |

A permission you explicitly unticked always wins over the autonomy level. If you took
away "break blocks", 积极 will not give it back.
