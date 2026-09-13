"""Reproducible, dependency-free KeepItLevel -> vanilla structure importer.

No upstream implementation code is used. Network input is pinned and cached in
build/import-cache; generated resources are deterministic. Unknown conversions
are quarantined, never silently published. Run with --check for a drift audit.
"""
from __future__ import annotations
import argparse
import collections
import gzip
import hashlib
import io
import json
import pathlib
import re
import struct
import urllib.request
import zipfile
import fnmatch

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / 'src/main/resources/data/squire'
SHA = '74259e3da775f467275adb57b076caefdea84d99'
PROJECT = 'https://github.com/gowenrw/keepitlevel_mc_style'


class Nbt:
    def __init__(self, data):
        self.f = io.BytesIO(data)

    def number(self, fmt):
        return struct.unpack('>' + fmt, self.f.read(struct.calcsize(fmt)))[0]

    def string(self):
        return self.f.read(self.number('H')).decode('utf-8')

    def tag(self, kind, depth=0):
        if depth > 64:
            raise ValueError('NBT depth limit')
        if kind in range(1, 7):
            return self.number({1:'b', 2:'h', 3:'i', 4:'q', 5:'f', 6:'d'}[kind])
        if kind == 8:
            return self.string()
        if kind == 10:
            result = {}
            while True:
                child = self.number('B')
                if not child:
                    return result
                key = self.string()
                result[key] = self.tag(child, depth + 1)
        child = self.number('B') if kind == 9 else None
        size = self.number('i')
        if not 0 <= size <= 1048576:
            raise ValueError('NBT collection limit')
        if kind == 7:
            return self.f.read(size)
        if kind == 9:
            return [self.tag(child, depth + 1) for _ in range(size)]
        if kind in (11, 12):
            return [self.number('i' if kind == 11 else 'q') for _ in range(size)]
        raise ValueError('unknown NBT tag ' + str(kind))

    def root(self):
        kind = self.number('B')
        if kind != 10:
            raise ValueError('NBT root must be compound')
        self.string()
        return self.tag(kind)


def nbt_string(s):
    b = s.encode('utf-8')
    return struct.pack('>H', len(b)) + b


def nbt_tag(value):
    if isinstance(value, int):
        return 3, struct.pack('>i', value)
    if isinstance(value, str):
        return 8, nbt_string(value)
    if isinstance(value, dict):
        out = bytearray()
        for key, val in value.items():
            kind, payload = nbt_tag(val)
            out.extend(bytes([kind]) + nbt_string(key) + payload)
        return 10, bytes(out) + b'\0'
    if isinstance(value, list):
        items = [nbt_tag(v) for v in value]
        kind = items[0][0] if items else 10
        if any(k != kind for k, _ in items):
            raise ValueError('mixed NBT list')
        return 9, bytes([kind]) + struct.pack('>i', len(items)) + b''.join(v for _, v in items)
    raise ValueError(type(value))


def archive():
    cached = ROOT / 'build/import-cache' / (SHA + '.zip')
    if not cached.exists():
        cached.parent.mkdir(parents=True, exist_ok=True)
        for attempt in range(4):
            try:
                with urllib.request.urlopen(PROJECT.replace('github.com', 'codeload.github.com') + '/zip/' + SHA, timeout=60) as response:
                    chunks = []
                    while chunk := response.read(65536):
                        chunks.append(chunk)
                data = b''.join(chunks)
                zipfile.ZipFile(io.BytesIO(data)).testzip()
                cached.write_bytes(data)
                break
            except Exception:
                if attempt == 3:
                    raise
    return zipfile.ZipFile(cached)


def classify(path, rules):
    stem = path[:-10]
    for rule in rules['families']:
        match = re.fullmatch(rule['pattern'], stem)
        if match:
            fields = {k: v or '' for k, v in match.groupdict().items()}
            family = rule['family'].format(**fields)
            category = rules['categoryOverrides'].get(family, rule['category'])
            tier = int(fields.get('tier', '0') or 0)
            return family, category, tier, bool(fields.get('deck'))
    raise ValueError('unclassified resource: ' + path)


def convert_state(state, tile, rules):
    name = state['Name']
    props = dict(state.get('Properties', {}))
    notes, blockers = [], []
    if name.startswith('minecraft:'):
        if name in rules['blockedVanilla']:
            blockers.append('UNSUPPORTED_PLACEMENT:' + name)
        # Stored fluid is separately paid; normalize unsupported honey, compost, growth and loot.
        for key, value in rules['normalizedProperties'].items():
            if key == 'waterlogged' or key == 'level' and name in ('minecraft:water', 'minecraft:lava'):
                continue  # Preserve authored fluid goals; the runtime prices source/waterlogging operations.
            if key in props and props[key] != value:
                notes.append('normalize:' + key + '=' + value)
                props[key] = value
        if name in rules['direct']:
            target = rules['direct'][name]
            return {'Name': target['block']}, ['replace:' + name], blockers
        return {'Name': name, **({'Properties': props} if props else {})}, notes, blockers
    if name in rules['markers']:
        mode = rules['markers'][name]
        if mode not in ('keep', 'foundation', 'anchor', 'environment-fluid'):
            blockers.append('SITE_MARKER_REQUIRES_RULE:' + mode)
        return None, ['marker:' + mode], blockers
    if name in rules['direct']:
        rule = rules['direct'][name]
        kept = {k: props[k] for k in rule.get('preserve', []) if k in props}
        return {'Name': rule['block'], **({'Properties': kept} if kept else {})}, ['replace:' + name], []
    shape = rules['shapes'].get(name)
    if shape:
        textures = tile.get('textureData', {})
        source_material = next((textures[k] for k in shape['textureKeys'] if k in textures), None)
        material = rules['materials'].get(source_material)
        if not material or shape['variant'] not in material:
            return None, [], ['UNMAPPED_MATERIAL:' + str(source_material) + ':' + name]
        target = material[shape['variant']]
        kept = {k: props[k] for k in shape.get('preserve', []) if k in props}
        kept.update(shape.get('properties', {}))
        if props.get('waterlogged') == 'true':
            kept['waterlogged'] = 'true'
        if shape.get('slabFromShape'):
            kept['type'] = 'top' if props.get('shape') == 'top' else 'bottom'
        return {'Name': target, **({'Properties': kept} if kept else {})}, ['approximate:' + name], blockers
    return None, [], ['UNMAPPED_BLOCK:' + name]


def recommended(tier, category, count, dims, special, growth):
    score = sum((count >= 512, count >= 1536, max(dims[0], dims[2]) >= 17,
                 dims[1] >= 16, dims[1] >= 28, special))
    level = max(1, tier * 2 - 1) + (1 if score >= 2 else 0)
    level = max(level, growth['categoryMinimum'].get(category, 1))
    if count >= 2500 or dims[1] >= 32:
        level = max(level, 8)
    return min(10, level), score


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--qualification', type=pathlib.Path, help='Import generated GameTest evidence; never hand-author pass results')
    args = parser.parse_args()
    rules = json.loads((DATA / 'blueprint_rules/keepitlevel.json').read_text(encoding='utf-8'))
    themes = json.loads((DATA / 'blueprint_rules/keepitlevel-materials.json').read_text(encoding='utf-8'))
    rules_hash = hashlib.sha256(json_bytes({'rules': rules, 'themes': themes})).hexdigest()
    evidence_path = args.qualification or DATA / 'blueprint_rules/keepitlevel-qualification.json'
    evidence = json.loads(evidence_path.read_text(encoding='utf-8')) if evidence_path.exists() else {'schemaVersion': 1, 'results': []}
    qualified = {r['id']: r for r in evidence['results']}
    z = archive()
    variants, reports, output = [], [], {}
    aliases = rules['legacyAliases']
    prefix = z.namelist()[0] + 'blueprints/keepitlevel/'
    paths = sorted(n for n in z.namelist() if n.startswith(prefix) and n.endswith('.blueprint'))
    for file in paths:
        path = file[len(prefix):]
        raw = z.read(file)
        expanded = gzip.decompress(raw)
        if len(expanded) > 64 * 1024 * 1024:
            raise ValueError('NBT size limit: ' + path)
        source = Nbt(expanded).root()
        if source['version'] != 1 or source['mcversion'] != 3465:
            raise ValueError('unsupported source version: ' + path)
        sx, sy, sz = [source['size_' + a] for a in 'xyz']
        volume = sx * sy * sz
        if not 0 < volume <= 1048576 or len(source['blocks']) != (volume + 1) // 2:
            raise ValueError('invalid packed volume: ' + path)
        family, category, tier, deck = classify(path, rules)
        cells, changes, blockers, requirements = [], [], set(), []
        tiles = {(t['x'], t['y'], t['z']): t for t in source['tile_entities']}
        for i in range(volume):
            word = source['blocks'][i // 2]
            index = (word >> (16 if i % 2 == 0 else 0)) & 65535
            state = source['palette'][index]
            pos = (i % sx, i // (sx * sz), (i // sx) % sz)
            marker = rules['markers'].get(state['Name'])
            if marker in ('foundation', 'environment-fluid'):
                requirements.append({'pos': list(pos), 'kind': 'solid' if marker == 'foundation' else 'fluid'})
            target, notes, errors = convert_state(state, tiles.get(pos, {}), rules)
            blockers.update(errors)
            if notes or errors:
                changes.append({'pos': pos, 'source': state, 'textureData': tiles.get(pos, {}).get('textureData', {}),
                                'target': target, 'notes': notes, 'errors': errors})
            if target:
                cells.append((pos, target))
        solid = [p for p, s in cells if s['Name'] != 'minecraft:air']
        if not solid:
            raise ValueError('empty converted resource: ' + path)
        lo = [min(p[a] for p in solid) for a in range(3)]
        hi = [max(p[a] for p in solid) for a in range(3)]
        # Keep horizontal scan frames for modular alignment; trim only vertical scan padding.
        ground = []
        for tile in tiles.values():
            for tag in tile.get('blueprintDataProvider', {}).get('posTagMap', []):
                if any(n.get('tagName') == 'groundlevel' for n in tag.get('tagNameList', [])):
                    ground.append(tile['y'] + tag['tagPos']['y'])
        ground_y = min(ground) + 1 if ground else lo[1]
        for requirement in requirements:
            requirement['pos'][1] -= ground_y
        dims = [hi[a] - lo[a] + 1 for a in range(3)]
        palette, entries, indices = [], [], {}
        for pos, state in cells:
            if not lo[1] <= pos[1] <= hi[1]:
                continue
            key = json.dumps(state, sort_keys=True)
            if key not in indices:
                indices[key] = len(palette)
                palette.append(state)
            entries.append({'pos': [pos[0], pos[1] - lo[1], pos[2]], 'state': indices[key]})
        structure = {'DataVersion': 3465, 'author': 'Richard Gowen (@alt_bier); Squire vanilla conversion',
                     'size': [sx, dims[1], sz], 'palette': palette, 'blocks': entries, 'entities': []}
        _, payload = nbt_tag(structure)
        binary = gzip.compress(b'\x0a\0\0' + payload, mtime=0)
        resource = 'keepitlevel/' + path[:-10]
        canonical = 'squire:' + resource
        ident = aliases.get(path, canonical)
        level, complexity = recommended(tier, category, len(solid), dims, bool(blockers), rules['growth'])
        level = rules.get('levelOverrides', {}).get(path, level)
        label = rules['names'].get(family, family.replace('_', ' ').title())
        leaf = path.rsplit('/', 1)[-1][:-10]
        standard_family = re.fullmatch(re.escape(family) + r'(_d)?[1-5]', leaf)
        variant_label = ('Tier ' + str(tier) + (' · 带平台' if deck else ' · 标准')) if tier and standard_family else leaf
        if tier and not standard_family:
            variant_label = 'Tier ' + str(tier) + ' · ' + re.sub(r'[1-5]$', '', leaf)
        internal = category == 'internal'
        proof = qualified.get(ident, {})
        verified = (not blockers and proof.get('passed') and proof.get('validation') == 'vanilla-fluid-escrow-access-v5'
                    and proof.get('convertedSha256') == hashlib.sha256(binary).hexdigest()
                    and proof.get('conversionRulesSha256') == rules_hash)
        status = 'INTERNAL' if internal else ('ADAPTED' if path in aliases or verified else 'UNSUPPORTED')
        reason = '' if path in aliases or verified else ('上游内部测试资源' if internal else
                 '待适配：' + ', '.join(sorted(blockers)[:2]) if blockers else '已转换，待原版施工验证')
        if path not in aliases and not blockers and not verified and proof.get('errors'):
            reason = '待适配：' + '; '.join(proof['errors'][:2])
            for code, explanation in {
                'FLUID_CONTAINMENT_OPEN': '流体围护存在未验证的开口',
                'FLUID_FIRE_RISK': '岩浆附近存在可燃材料',
                'FLUID_POUR_FAILED': '原版注液未成功',
                'FLUID_CONTAINER_CHANGED': '含水容器状态不兼容',
                'PHYSICS_CHANGED': '原版物理更新后结构不稳定',
            }.items():
                reason = reason.replace(code, explanation + ' [' + code + ']')
        variant = {'id': ident, 'family': 'squire:keepitlevel/' + family, 'familyName': label,
                   'displayName': label + ' · ' + variant_label, 'category': category, 'tier': tier,
                   'variant': variant_label, 'deck': deck, 'requiredEngineerLevel': level,
                   'complexity': complexity, 'blockCount': len(solid), 'width': sx, 'height': dims[1], 'depth': sz,
                   'status': status, 'reason': reason[:240], 'conversionStatus': 'BLOCKED' if blockers else 'CONVERTED',
                   'descriptor': 'squire:building_catalog/variants/' + resource + '.json',
                   'author': 'Richard Gowen (@alt_bier)', 'license': 'MIT', 'style': 'KeepItLevel',
                   'source': PROJECT + '/blob/' + SHA + '/blueprints/keepitlevel/' + path,
                   'sourcePath': path, 'sourceSha256': hashlib.sha256(raw).hexdigest(), 'upstreamCommit': SHA,
                   'convertedSha256': hashlib.sha256(binary).hexdigest(), 'converterVersion': rules['version']}
        variant['validation'] = 'legacy-real-npc-six' if path in aliases else proof.get('validation', 'not-tested')
        variant['conversionRulesSha256'] = rules_hash
        if path in aliases:
            variant['descriptor'] = 'squire:blueprints/' + ident + '.json'
            # The six compatibility descriptors use cropped content, not the new modular scan frame.
            variant['width'], variant['height'], variant['depth'] = dims
        descriptor = {'schemaVersion': 1, 'id': ident, 'format': 'minecraft:structure_nbt',
                      'displayName': variant['displayName'], 'tier': max(1, tier), 'category': rules['legacyCategories'][category],
                      'minEngineerLevel': level, 'structure': 'squire:' + resource, 'airMode': 'clear',
                      'entityPolicy': 'strip', 'blockEntityPolicy': 'strip', 'offsetY': lo[1] - ground_y,
                      'metadata': {'author': variant['author'], 'source': variant['source'], 'license': 'MIT',
                                   'style': 'KeepItLevel', 'tags': ['keepitlevel', 'catalog', category]},
                      'sourceFrame': [sx, sy, sz], 'primaryOffset': source['optional_data'].get('structurize', {}).get('primary_offset', {}),
                      'groundY': ground_y, 'siteRequirements': requirements}
        present = {s['Name'] for _, s in cells}
        slots, bindings = [], []
        for group in themes['groups']:
            mapped = {key: block for key, block in group['blocks'].items() if block in present}
            if not mapped:
                continue
            slots.append({'id': group['id'], 'displayName': group['name'], 'type': group['type'],
                          'defaultFamily': group['family'], 'requiredVariants': sorted(mapped)})
            bindings.extend({'block': block, 'slot': group['id'], 'variant': key} for key, block in mapped.items())
        descriptor['materialSlots'] = slots
        descriptor['materialBindings'] = bindings
        descriptor['neighborComputedProperties'] = {block: keys for pattern, keys in themes['neighborComputedProperties'].items()
                                                    for block in sorted(present) if fnmatch.fnmatchcase(block, pattern)}
        output['structures/' + resource + '.nbt'] = binary
        output['building_catalog/variants/' + resource + '.json'] = json_bytes(descriptor)
        report = {'id': ident, 'sourcePath': path, 'blockers': sorted(blockers), 'changes': changes,
                  'strippedEntities': len(source['entities']), 'strippedBlockEntities': len(tiles)}
        output['blueprint_rules/reports/' + resource + '.json.gz'] = gzip.compress(json_bytes(report), mtime=0)
        variants.append(variant)
        reports.append({k: report[k] for k in ['id', 'sourcePath', 'blockers', 'strippedEntities', 'strippedBlockEntities']})
    output['building_catalog/keepitlevel.json'] = json_bytes({'schemaVersion': 1, 'upstreamCommit': SHA,
                                                           'categories': rules['categories'], 'variants': variants})
    if evidence['results']:
        output['blueprint_rules/keepitlevel-qualification.json'] = json_bytes(evidence)
    families = {v['family'] for v in variants if v['status'] != 'INTERNAL'}
    output['blueprint_rules/keepitlevel-inventory.json'] = json_bytes({'sourceCount': len(paths), 'familyCount': len(families),
            'statusCounts': dict(collections.Counter(v['status'] for v in variants)), 'resources': reports})
    if len(paths) != 750 or len(families) != 72:
        raise ValueError('unexpected upstream inventory: ' + str((len(paths), len(families))))
    drift = []
    for path, data in output.items():
        dest = DATA / path
        if not dest.exists() or dest.read_bytes() != data:
            drift.append(path)
            if not args.check:
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(data)
    print(json.dumps({'sources': len(paths), 'families': len(families), 'generatedFiles': len(output), 'changed': len(drift),
                      'statuses': dict(collections.Counter(v['status'] for v in variants))}))
    if args.check and drift:
        raise SystemExit('generated resource drift: ' + ', '.join(drift[:10]))


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


if __name__ == '__main__':
    main()
