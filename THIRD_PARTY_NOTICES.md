# Third-party building assets

## KeepItLevel Minecraft Style

Squire includes conversions of all 750 `.blueprint` files in the pinned KeepItLevel
style pack by Richard Gowen (`gowenrw`, `@alt_bier`). The initial six legacy imports
retain their original geometry and stable IDs for saved construction projects:

- `residence1.blueprint`
- `builder1.blueprint`
- `warehouse1.blueprint`
- `guardtower1.blueprint`
- `library1.blueprint`
- `fountain_small.blueprint`

Source: <https://github.com/gowenrw/keepitlevel_mc_style>

Pinned source revision: `74259e3da775f467275adb57b076caefdea84d99`

License: MIT

Copyright (c) 2025 @alt_bier

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

### Squire adaptations

The complete source-to-conversion inventory is packaged at
`data/squire/building_catalog/keepitlevel.json`. It records the upstream source
path, author, license, commit, source SHA-256, converted SHA-256, rules SHA-256,
category, family, Tier, and explicit availability/validation status for every file.
The generated native structures are at `data/squire/structures/keepitlevel/`.
Per-coordinate replacement reports (including source BlockState, Domum Ornamentum
texture references, replacement BlockState and unresolved errors) are packaged as
gzip JSON at `data/squire/blueprint_rules/reports/keepitlevel/`.

Conversion rules are independent Squire data. They approximate Domum Ornamentum
shapes/materials with vanilla blocks, replace MineColonies work blocks with vanilla
workstations, strip entities, inventories and block-entity payloads, normalize
stored growth/honey and preserve site requirements. Vanilla computes neighbour
connections for stairs, walls, panes and fences. Unsupported fluids, special gates,
unplaceable states and unsafe assemblies remain explicitly unavailable; inclusion
in the content inventory is not a claim of lossless or playable conversion.

Only this MIT repository's blueprint content is included. No MineColonies,
Structurize or Domum Ornamentum Java, models, textures, or separately licensed style
packs/datapacks are copied. Original namespace strings and texture identifiers in
conversion reports are provenance data, not runtime mod dependencies.

### Initial six compatibility assets

The original Structurize v1 block arrays are shipped unchanged as Base64-wrapped
gzip resources. During import, Squire strips entities and block-entity payloads,
ignores Structurize substitution markers, crops scan padding, and maps the few
MineColonies functional blocks to vanilla Minecraft equivalents. Fountain water
is represented by blue stained glass because the Engineer build executor consumes
placeable block items rather than fluid buckets.

Squire's descriptors also bind explicit timber/board/roof/masonry regions to the
material palette. Dirt paths are adapted to coarse dirt so temporary construction
supports cannot turn the authored path into a different block during construction.
These adaptations are metadata-only; the bundled original block arrays remain unchanged.

Structurize source code is not bundled or copied. Squire contains an independent,
minimal reader for the v1 NBT layout and has no MineColonies or Structurize runtime
dependency.
