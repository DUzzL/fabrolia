"""Validate player files written by the server: gzip NBT .dat, advancements/stats JSON."""
import gzip, hashlib, io, json, re, struct, uuid
from pathlib import Path


def offline_uuid(name):
    d = bytearray(hashlib.md5(('OfflinePlayer:' + name).encode()).digest())
    d[6] = (d[6] & 0x0f) | 0x30
    d[8] = (d[8] & 0x3f) | 0x80
    return str(uuid.UUID(bytes=bytes(d)))


def _read_tag(t, f):
    if t == 1: return struct.unpack('>b', f.read(1))[0]
    if t == 2: return struct.unpack('>h', f.read(2))[0]
    if t == 3: return struct.unpack('>i', f.read(4))[0]
    if t == 4: return struct.unpack('>q', f.read(8))[0]
    if t == 5: return struct.unpack('>f', f.read(4))[0]
    if t == 6: return struct.unpack('>d', f.read(8))[0]
    if t == 7:
        n = struct.unpack('>i', f.read(4))[0]; return f.read(n)
    if t == 8:
        n = struct.unpack('>H', f.read(2))[0]; return f.read(n).decode('utf-8', 'replace')
    if t == 9:
        et = f.read(1)[0]; n = struct.unpack('>i', f.read(4))[0]
        return [_read_tag(et, f) for _ in range(n)]
    if t == 10:
        out = {}
        while True:
            ct = f.read(1)[0]
            if ct == 0: return out
            n = struct.unpack('>H', f.read(2))[0]; key = f.read(n).decode('utf-8', 'replace')
            out[key] = _read_tag(ct, f)
    if t == 11:
        n = struct.unpack('>i', f.read(4))[0]; return list(struct.unpack('>%di' % n, f.read(4 * n)))
    if t == 12:
        n = struct.unpack('>i', f.read(4))[0]; return list(struct.unpack('>%dq' % n, f.read(8 * n)))
    raise ValueError('bad tag %d' % t)


def read_nbt(path):
    f = io.BytesIO(gzip.decompress(Path(path).read_bytes()))
    t = f.read(1)[0]
    n = struct.unpack('>H', f.read(2))[0]; f.read(n)
    return _read_tag(t, f)


def check_world(server_dir, names, adv_id, probe_pos_lines):
    server_dir = Path(server_dir)
    props = dict(l.split('=', 1) for l in (server_dir / 'server.properties').read_text().splitlines()
                 if '=' in l and not l.startswith('#'))
    world = server_dir / props.get('level-name', 'world')
    players = world / 'players'
    result = {'players': len(names), 'dat_ok': 0, 'adv_ok': 0, 'stats_ok': 0, 'errors': [],
              'leftover_tmp': sorted(p.name for p in (players / 'data').glob('*-*.dat')
                                     if not re.fullmatch(r'[0-9a-f-]{36}\.dat', p.name))}
    cache = {}
    try:
        cache = {e['name']: e['uuid'] for e in json.loads((server_dir / 'usercache.json').read_text())}
    except Exception:
        pass
    for name in names:
        u = cache.get(name) or offline_uuid(name)
        try:
            tag = read_nbt(players / 'data' / (u + '.dat'))
            if 'Pos' not in tag:
                raise ValueError('no Pos')
            result['dat_ok'] += 1
            if probe_pos_lines and name == names[0]:
                m = None
                for line in probe_pos_lines:
                    m = re.search(r'\[(-?[\d.E-]+)d, (-?[\d.E-]+)d, (-?[\d.E-]+)d\]', line) or m
                if m:
                    want = [float(m.group(i)) for i in (1, 2, 3)]
                    result['probe_pos_cmd'] = want
                    result['probe_pos_file'] = tag['Pos']
                    result['probe_pos_match'] = all(abs(a - b) < 1e-6 for a, b in zip(want, tag['Pos']))
        except Exception as error:
            result['errors'].append(f'{name} dat: {error!r}')
        try:
            adv = json.loads((players / 'advancements' / (u + '.json')).read_text(encoding='utf-8'))
            result['adv_ok'] += 1
            if adv_id and name == names[0]:
                result['probe_adv_done'] = bool(adv.get(adv_id, {}).get('done'))
        except Exception as error:
            result['errors'].append(f'{name} adv: {error!r}')
        try:
            json.loads((players / 'stats' / (u + '.json')).read_text(encoding='utf-8'))
            result['stats_ok'] += 1
        except Exception as error:
            result['errors'].append(f'{name} stats: {error!r}')
    try:
        read_nbt(world / 'level.dat')
        result['level_dat_ok'] = True
    except Exception as error:
        result['errors'].append(f'level.dat: {error!r}')
    return result
