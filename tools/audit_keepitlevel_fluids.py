"""Read-only audit of converted fluid content and catalog qualification.

Run from the repository root after import_keepitlevel.py. No upstream code required.
"""
import collections
import gzip
import json
from pathlib import Path

from import_keepitlevel import Nbt


def main():
    root = Path(__file__).resolve().parents[1] / 'src/main/resources/data/squire'
    variants = json.loads((root / 'building_catalog/keepitlevel.json').read_text(encoding='utf-8'))['variants']
    rows = []
    for v in variants:
        resource = v['sourcePath'].removesuffix('.blueprint')
        nbt = Nbt(gzip.decompress((root / 'structures/keepitlevel' / (resource + '.nbt')).read_bytes())).root()
        palette = nbt['palette']
        kinds = set()
        for state in palette:
            if state['Name'] in ('minecraft:water', 'minecraft:lava'):
                kinds.add(state['Name'].split(':')[1])
            if state.get('Properties', {}).get('waterlogged') == 'true':
                kinds.add('waterlogged')
        desc = json.loads((root / 'building_catalog/variants/keepitlevel' / (resource + '.json')).read_text(encoding='utf-8'))
        if any(q['kind'] == 'fluid' for q in desc.get('siteRequirements', [])):
            kinds.add('environment-water')
        if kinds:
            rows.append({'id': v['id'], 'tier': v['tier'], 'family': v['family'],
                         'status': v['status'], 'kinds': sorted(kinds), 'reason': v['reason']})
    # The six compatibility IDs still use their separately pinned legacy geometry.
    legacy = {v['id'] for v in variants if v.get('validation') == 'legacy-real-npc-six'}
    enabled = [r for r in rows if r['status'] in ('READY', 'ADAPTED') and r['id'] not in legacy]
    print(json.dumps({'catalogStatuses': dict(collections.Counter(v['status'] for v in variants)),
                      'fluidVariants': len(rows), 'enabledFluidVariants': len(enabled),
                      'enabledFluidTierVariants': sum(r['tier'] > 0 for r in enabled),
                      'enabledFluidFamilies': sorted(set(r['family'] for r in enabled)),
                      'enabledFluidKinds': dict(collections.Counter(k for r in enabled for k in r['kinds'])),
                      'enabled': enabled}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
