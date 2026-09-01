# Player Guide

Everything you need as a player using a squire. Server owners: see the
[Server Guide](server-guide.md) — some features are off until an operator enables them.

## Your squire

Create your first level-0 squire by building a training dummy and placing the
pumpkin head **last**. Either horizontal direction works:

```text
      carved pumpkin
wool    hay bale    wool
          fence
```

The dummy becomes your squire and gives you a bound **Recall Bell**. The bell is
not consumed: use it after death or dismissal, when the squire is far away, or
when you change dimensions. A replacement bell can be crafted from a gold nugget,
copper ingot and string in one vertical line; it binds to your existing squire
when first used.

One squire per player. It persists across restarts and remembers whose it is —
nobody else can command yours.

Right-click the squire or press **K** to open the panel. Following, staying,
patrolling, setting or returning home, dismissal, equipment, professions,
permissions and projects all have panel controls. If the squire is absent, K
shows the summon/recall hint and never creates or teleports one silently.

## Talking to it

Chat normally, or use the text field at the bottom of the panel. Simple intents ("come", "stay",
"give me bread") are handled by FastPath shortcuts without any LLM round trip.
More complex requests go to the server's configured language model; the model
only ever *proposes* actions — the server checks permissions before anything
happens.

The companion's physical behaviour is deliberately bounded: follow/stay, guard you,
rescue/heal you, **carry things, and build from a blueprint**. Item wording such as
“give me”, “gather”, “craft”, or “smelt” is still fulfilled with a bounded `/give` —
the squire never walks away to mine ore, chop trees or farm. It only ever breaks blocks
**inside a blueprint it has been given**, and every block it places really leaves its
backpack. See the [Blueprint Guide](blueprint-guide.md).

Pick a role on the **Squire** panel page and it starts training a proficiency track, unlocking
advanced abilities you equip into a small number of slots. Basic abilities are never
taken away. See the [Roles Guide](roles-guide.md).

If a server-compiled command is longer than 256 characters, Squire places a temporary
command block in a permitted air cell, runs it with the same bound target, and restores
the original cell immediately. It does not leave a command block project behind.

Chinese item names work: 面包 → bread, 两组火把 → 128 torches. Quantities like
`3个面包` or `面包x10` are understood.

## Who is in charge

**Your most recent instruction wins.** Telling him 跟随 / 待命 / 巡逻 / 回家 / 停下 —
in chat or from the panel — drops whatever he is doing right now and says how many jobs
he put down. A companion that answers "let me finish this wall first" is
indistinguishable from a broken one.

A **standing order and a job are not the same thing**, and a job never overwrites the
order. While he is building, following stands down; when the job ends he goes back to
following on his own.

If you told him 待命 and then order a job **outside** that circle, the newer order wins:
he gives up 待命 and tells you he did. 待命 still stops him wandering off on his own
(the home routine, patrol, the next step of a project you left running).

## High-risk confirmations

Dangerous operations (such as world edits) pause and
send you a **confirmation id**:

```
[侍从/Squire] Confirm required: a1b2c3…  (expires in 600s)
/squire confirm <id>
```

Confirmations are bound to *you*, single-use, and expire. If you didn't request
the operation, don't confirm — nothing happens.

## Compatibility commands

Older worlds and server scripts may continue to use `/squire summon`, `follow`,
`stay`, `home`, `status`, `dismiss` and related commands. They are compatibility
and operator surfaces, not required by the normal survival flow described above.

## Automations

```
/squire automation                      # list yours
/squire automation inspect "<name>"
/squire automation pause|resume|remove "<name>"
/squire automation fire <id>            # trigger now
```

Automations are legacy/admin graphs managed with explicit commands: a trigger (time of day,
owner online, entering region, task event), conditions, and nodes (tool call,
notify you, start a task). They are TTL-bounded, paused when disabled by admins,
and survive restarts.

## Legacy/admin CBP projects (materialized command blocks)

These explicit commands remain for administrators and existing worlds, but the
companion and model no longer plan or materialize CBP projects. They are deliberately strict:

1. **Set a workspace** — the ONLY area your projects may ever touch:
   ```
   /squire workspace set <from> <to>     # e.g. set ~5 ~ -3 ~12 ~ +8
   /squire workspace show
   /squire workspace clear
   ```
2. **Plan a project** (requires the admin to have enabled `cbp`):
   ```
   /squire cbp plan "<name>" <pos> <type> <auto> <command>
   # type: impulse (default) | chain | repeating ; auto: true|false
   ```
3. **Confirm** — placement starts only after `/squire confirm <id>`.
4. Blocks are placed gradually, stay **inert** (`powered=false`) until verified,
   and the baked command text is checked afterwards.
5. Manage: `/squire cbp list`, `inspect <id>`, `disable/enable <id>`,
   `remove <id>` — remove restores air exactly where blocks were placed.

Note: after a server restart, `remove` still deletes the registry entry but
cannot restore blocks placed before the restart; it will tell you so honestly.

## Language

Command feedback follows your client's language (English and 简体中文 ship in the
jar). Engine-internal diagnostics (tool results, refusals from subsystems) are
English-only in v1.0.
