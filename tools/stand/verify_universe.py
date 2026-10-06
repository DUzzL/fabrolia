"""Compare a player's saved advancements with every advancement loaded from enabled packs.

Missing ids are explained only when hidden (no display) AND some enabled pack function revokes them.
"""
import io, json, re, sys, zipfile
from pathlib import Path

import autosave_check

RX = re.compile(r'^data/([^/]+)/advancement/(.+)\.json$')


def analyze(server, name):
    server = Path(server)
    world = server / 'world-native-burst'
    enabled = autosave_check.read_nbt(world / 'level.dat')['Data']['DataPacks']['Enabled']
    ids, functions = {}, []

    def add_zip(z, origin):
        for n in z.namelist():
            m = RX.match(n)
            if m:
                ids[f'{m.group(1)}:{m.group(2)}'] = (origin, z, n)
            elif n.endswith('.mcfunction'):
                functions.append(z.read(n).decode('utf-8', 'replace'))
            elif n.endswith('.jar') and n.startswith('META-INF/'):
                try:
                    add_zip(zipfile.ZipFile(io.BytesIO(z.read(n))), origin + '!' + n)
                except Exception:
                    pass
    add_zip(zipfile.ZipFile(next(server.rglob('server-26.3.jar'))), 'vanilla')
    for jar in sorted((server / 'mods').glob('*.jar')):
        add_zip(zipfile.ZipFile(jar), jar.name)
    for dp in sorted((world / 'datapacks').iterdir()):
        if 'file/' + dp.name not in enabled:
            continue
        if dp.suffix == '.zip':
            add_zip(zipfile.ZipFile(dp), dp.name)
        elif dp.is_dir():
            for f in dp.rglob('*'):
                rel = str(f.relative_to(dp))
                m = RX.match(rel)
                if m:
                    ids[f'{m.group(1)}:{m.group(2)}'] = (dp.name, None, f)
                elif rel.endswith('.mcfunction'):
                    functions.append(f.read_text(errors='replace'))
    revokes = [l.strip() for t in functions for l in t.splitlines() if 'advancement revoke' in l]
    cache = {e['name']: e['uuid'] for e in json.loads((server / 'usercache.json').read_text())}
    u = cache.get(name) or autosave_check.offline_uuid(name)
    adv = json.loads((world / 'players' / 'advancements' / (u + '.json')).read_text(encoding='utf-8'))
    done = {k for k, v in adv.items() if isinstance(v, dict) and v.get('done')}
    missing, unexplained = [], []
    for k in sorted(set(ids) - done):
        origin, z, n = ids[k]
        d = json.loads(z.read(n) if z else Path(n).read_text(encoding='utf-8'))
        rev = next((l for l in revokes if re.search(r'only ' + re.escape(k) + r'\b', l)), None)
        entry = {'id': k, 'display': 'display' in d, 'reward': (d.get('rewards') or {}).get('function'),
                 'origin': origin, 'revoked_by': rev}
        missing.append(entry)
        if entry['display'] or not rev:
            unexplained.append(entry)
    return {'universe': len(ids), 'done': len(done), 'done_not_in_universe': sorted(done - set(ids))[:20],
            'missing_count': len(missing), 'missing': missing, 'unexplained': unexplained}


if __name__ == '__main__':
    print(json.dumps(analyze(Path(sys.argv[1]) / 'server', sys.argv[2] if len(sys.argv) > 2 else 'Town03'), indent=1))
