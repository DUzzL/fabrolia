#!/usr/bin/env python3
"""Functional verification of the opt-max jars on the production-like 26.3 perf stand.

Same load as perf-driver.py (40 real clients, town/spread population, walkers,
production datapacks incl. BACAP, MCFC). After `warm` it runs scripted checks and
records raw console output + PASS/FAIL into RUN/verify-report.json.

python3 verify-driver.py RUN [WARM_SECONDS]
"""
import importlib.util, json, os, re, subprocess, sys, threading, time
from pathlib import Path

import standenv

Y = -60
TOWN = [(x, z) for x in (-96, -48, 0, 48, 96) for z in (-72, -24, 24, 72)]
SPREAD = [(2000 + 600 * i, 2000 + 600 * j) for i in range(5) for j in range(4)]
WALKERS = 5
PORTAL_PLAYERS = ['Far10', 'Far11']  # static Far players (walkers are Far00-04)
PORTALS = [SPREAD[10], SPREAD[11]]
NAMES = [f'Town{i:02d}' for i in range(len(TOWN))] + [f'Far{i:02d}' for i in range(len(SPREAD))]
PATTERN = re.compile(r'IllegalState|Fatal|fail-stop|STALLED|stuck|BARRIER-LEAK|ownership|'
                     r'Failed to execute function|lacks exact')
BENIGN = ('anti_ender_dragon_grief', 'Block-attached entity at invalid position',
          'restored saved player-tick ownership preference')
ADV_TOWN02 = 'blazeandcave:mining/amethyst_miner'
ADV_TOWN04 = 'blazeandcave:mining/diamond_miner'

run = Path(sys.argv[1]).resolve()
WARM_SECONDS = int(sys.argv[2]) if len(sys.argv) > 2 else 90
if os.environ.get('FABROLIA_NATIVE_BURST_ISOLATED') != 'loopback-only':
    raise RuntimeError('Requires loopback-only isolation')
spec = importlib.util.spec_from_file_location('native_transport', run / 'native-transport.py')
transport = importlib.util.module_from_spec(spec); spec.loader.exec_module(transport)
selected = json.loads((run / 'selected.json').read_text())
sys.path.insert(0, str(run))
import autosave_check, verify_universe
if transport.inventory(run / 'server') != selected['input_sha256']:
    raise RuntimeError('Fresh input manifest does not match')


def make_verify_pack():
    root = run / 'server' / 'world-native-burst' / 'datapacks' / 'verifystand'
    fn = root / 'data' / 'verifystand' / 'function'
    fn.mkdir(parents=True, exist_ok=True)
    (root / 'pack.mcmeta').write_text(json.dumps(
        {'pack': {'description': 'verify stand', 'min_format': [121, 0], 'max_format': [121, 0]}}))
    (fn / 'portal.mcfunction').write_text(
        'fill ~8 ~ ~8 ~11 ~4 ~8 minecraft:obsidian\n'
        'fill ~9 ~1 ~8 ~10 ~3 ~8 minecraft:nether_portal[axis=x]\n')
    (fn / 'feed.mcfunction').write_text(''.join(
        f'summon item ~{9.3 + 0.4 * i} ~1.2 ~8.5 {{Age:-32768s,PickupDelay:32767s,'
        f'Item:{{id:"minecraft:cobblestone",count:1}}}}\n' for i in range(2)))
    (fn / 'feedloop.mcfunction').write_text(''.join(
        f'execute positioned {x} {Y} {z} run function verifystand:feed\n' for x, z in PORTALS)
        + 'schedule function verifystand:feedloop 20t\n')


make_verify_pack()
recipe = json.loads((run / 'commands.json').read_text())
campaign = transport.Campaign(run, recipe)
report = {'status': 'RUNNING', 'phases': {}, 'checks': {}, 'raw': {}}
clients = {}
stop_walk = threading.Event()


def save_report():
    (run / 'verify-report.json').write_text(json.dumps(report, indent=2, sort_keys=True, default=str) + '\n')


def ex(command, timeout=None):
    return [l for l in campaign.exchange(command, timeout=timeout, optional=True) if 'NATIVE_BURST_ACK' not in l]


def raw(key, command, timeout=None):
    t0 = time.monotonic()
    out = ex(command, timeout)
    rtt = round((time.monotonic() - t0) * 1000, 1)
    report['raw'].setdefault(key, []).append({'cmd': command, 'rtt_ms': rtt, 'out': out})
    return out


def test(key, command):
    out = raw(key, command)
    return any('Test passed' in l for l in out), out


def check(name, ok, **evidence):
    report['checks'][name] = {'result': 'PASS' if ok else 'FAIL', **evidence}
    print('VERIFY_CHECK', name, 'PASS' if ok else 'FAIL', flush=True)
    save_report()


def phase(name):
    report['phases'][name] = time.time()
    print('PERF_PHASE', name, flush=True)


def cursor():
    return campaign.serial


def lines_since(start, pred=lambda l: True):
    with campaign.condition:
        return [line for serial, line in campaign.lines if serial > start and pred(line)]


def skin_fetch_noise(lines):
    """Exception lines that belong to a SkinRestorer skin fetch. Unsandboxed stands reach the
    real Mojang API, which answers the fake client names with 404/429 ('not found', 'too many
    requests'): network noise, not a server fault."""
    return {i for i in range(1, len(lines)) if 'SkinRestorer-Fetch' in lines[i - 1]}


def spark_tps(key):
    start = cursor()
    ex('spark tps')
    time.sleep(4)
    report['raw'].setdefault(key, []).append(
        {'cmd': 'spark tps', 'out': lines_since(start, lambda l: 'spark-worker' in l and '[⚡]' in l)})


def start_client(name):
    log = open(run / f'wire-{name}.jsonl', 'w')
    argv = [sys.executable, str(run / 'perf-wire-client.py'),
            '--name', name, '--max-seconds', '3000', '--idle-timeout', '600']
    proc = subprocess.Popen(standenv.pinned(standenv.CLIENT_CPUS, argv),
                            stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True, bufsize=1)
    clients[name] = proc


def send(name, command):
    proc = clients[name]
    proc.stdin.write(json.dumps(command) + '\n')
    proc.stdin.flush()


def walk(names_positions):
    pos = {n: [float(x) + 0.5, float(Y), float(z) + 0.5] for n, (x, z) in names_positions}
    while not stop_walk.wait(0.25):
        for n, p in pos.items():
            p[0] += 1.0
            try:
                send(n, {'cmd': 'move', 'kind': 'PosRot', 'x': p[0], 'y': p[1], 'z': p[2], 'yaw': -90.0,
                         'pitch': 0.0, 'onGround': True, 'horizontalCollision': False})
            except Exception:
                pass


def parse_pos(lines):
    m = None
    for line in lines:
        m = re.search(r'\[(-?[\d.E-]+)d, (-?[\d.E-]+)d, (-?[\d.E-]+)d\]', line) or m
    return [float(m.group(i)) for i in (1, 2, 3)] if m else None


def in_dim(key, name, dim):
    return test(key, f'execute at {name} if dimension minecraft:{dim}')[0]


def wait_dim(key, name, dim, seconds, nudge_pos=None):
    end = time.time() + seconds
    nudged = False
    while time.time() < end:
        if in_dim(key, name, dim):
            return True, nudged
        if nudge_pos is not None and time.time() > end - seconds / 2:
            nudged = True
            try:
                send(name, {'cmd': 'move', 'kind': 'Pos', 'x': nudge_pos[0], 'y': nudge_pos[1], 'z': nudge_pos[2],
                            'onGround': True, 'horizontalCollision': False})
            except Exception as error:
                report['raw'].setdefault(key, []).append({'nudge_error': repr(error)})
        time.sleep(0.5)
    return False, nudged


def check_functions():
    plan = raw('functions_plan', 'fabrolia functions plan')
    head = [l for l in plan if 'System chat' not in l and 'PARTITIONED' in l]
    want = {'blazeandcave:tick_timer': '#minecraft:tick blazeandcave:tick_timer: PARTITIONED',
            'track_stats:tick': '1t loop track_stats:tick: PARTITIONED',
            'armor_statues:tick': '1t loop armor_statues:tick: PARTITIONED'}
    plan_ok = {k: any(v in l for l in plan) for k, v in want.items()}
    raw('functions_fences_reset', 'fabrolia functions fences reset')
    time.sleep(60)
    fences = raw('functions_fences', 'fabrolia functions fences')
    plain = [l for l in fences if 'System chat' not in l]

    def counter(label):
        for l in plain:
            m = re.search(re.escape(label) + r'[^:]*: (\d+) \(([\d.]+)/t\)', l)
            if m:
                return int(m.group(1)), float(m.group(2))
        return None
    head_fence = counter('tick-head fence')
    per_fn = counter('per-function fences')
    ok = all(plan_ok.values()) and head_fence == (0, 0.0) and per_fn == (0, 0.0)
    check('1_function_plan_fences', ok, plan_partitioned=plan_ok, plan_heads=head,
          tick_head_fence=head_fence, per_function_fences=per_fn, fences=plain)


def check_carpet_spawn_tracking():
    raw('carpet_spawn_command', 'carpet commandSpawn true')
    commands = ('spawn tracking start', 'spawn tracking start -64 -64 -64 64 128 64',
                'spawn test', 'spawn test 10', 'spawn test 10 white')
    refusals = {command: raw('carpet_spawn_tracking', command) for command in commands}
    disabled = raw('carpet_spawn_tracking', 'spawn tracking')
    mobcaps = raw('carpet_spawn_mobcaps', 'spawn mobcaps')
    stopped = raw('carpet_spawn_stop', 'spawn tracking stop')
    check('0_carpet_spawn_tracking_guard',
          all(any('Carpet spawn tracking and /spawn test are disabled' in line for line in out)
              for out in refusals.values())
          and any('Spawn tracking is disabled' in line for line in disabled)
          and not any('Unknown or incomplete command' in line for line in mobcaps)
          and any('Spawning tracking stopped.' in line for line in stopped),
          refusals=refusals, disabled=disabled, mobcaps=mobcaps, stopped=stopped)


def check_advancements():
    raw('advstats_before', 'fabrolia regions debug advstats')
    # a) plain command grant
    g0 = raw('adv_town00', 'advancement grant Town00 only minecraft:story/root')
    t0, _ = test('adv_town00', 'execute if entity @a[name=Town00,advancements={minecraft:story/root=true}]')
    check('2a_grant_story_root_Town00', t0 and any('Granted' in l for l in g0), grant=g0, has=t0)

    # b) gameplay trigger: inventory_changed
    for c in ('scoreboard players get blazeandcave:mining/diamond_miner bac_obtained',
              'scoreboard players get Town01 bac_advancements'):
        raw('adv_town01', c)
    give = raw('adv_town01', 'give Town01 minecraft:diamond 64')
    time.sleep(2)
    has_dia, _ = test('adv_town01', 'execute if entity @a[name=Town01,advancements={minecraft:story/mine_diamond=true}]')
    has_dm, _ = test('adv_town01', 'execute if entity @a[name=Town01,advancements={blazeandcave:mining/diamond_miner=true}]')
    obt = raw('adv_town01', 'scoreboard players get blazeandcave:mining/diamond_miner bac_obtained')
    cnt = raw('adv_town01', 'scoreboard players get Town01 bac_advancements')
    clr = raw('adv_town01', 'clear Town01 minecraft:diamond')
    time.sleep(2)
    left = raw('adv_town01', 'clear Town01 minecraft:diamond 0')
    still, _ = test('adv_town01', 'execute if entity @a[name=Town01,advancements={minecraft:story/mine_diamond=true}]')
    check('2b_inventory_changed_Town01', has_dia and has_dm and still
          and any(re.search(r'Removed \d+ item', l) for l in clr),
          give=give, mine_diamond=has_dia, diamond_miner=has_dm, bac_obtained_after=obt, bac_advancements_after=cnt,
          clear=clr, clear_probe_after=left, mine_diamond_still_after_clear=still)

    # c) BACAP advancement with reward function + chat announcement
    settings = {k: raw('adv_town02', f'scoreboard players get {k} bac_settings')
                for k in ('challenge', 'reward', 'exp', 'trophy')}
    before = {'xp': raw('adv_town02', 'xp query Town02 points'),
              'levels': raw('adv_town02', 'xp query Town02 levels'),
              'bac_adv': raw('adv_town02', 'scoreboard players get Town02 bac_advancements'),
              'obtained': raw('adv_town02', f'scoreboard players get {ADV_TOWN02} bac_obtained')}
    online = raw('adv_town02', 'list')
    report['town02_online'] = sorted(n for l in online for n in re.findall(r'\b(?:Town|Far)\d\d\b', l)
                                     if 'players online' in l)
    start = cursor()
    report['town02_grant_unix_ns'] = time.time_ns()
    grant = raw('adv_town02', f'advancement grant Town02 only {ADV_TOWN02}')
    time.sleep(2)
    has, _ = test('adv_town02', f'execute if entity @a[name=Town02,advancements={{{ADV_TOWN02}=true}}]')
    after = {'xp': raw('adv_town02', 'xp query Town02 points'),
             'levels': raw('adv_town02', 'xp query Town02 levels'),
             'bac_adv': raw('adv_town02', 'scoreboard players get Town02 bac_advancements'),
             'obtained': raw('adv_town02', f'scoreboard players get {ADV_TOWN02} bac_obtained'),
             'amethyst_block': raw('adv_town02', 'clear Town02 minecraft:amethyst_block 0'),
             'trophy_cluster': raw('adv_town02', 'clear Town02 minecraft:amethyst_cluster 0')}
    log_town02 = lines_since(start, lambda l: 'Town02' in l or 'Amethyst' in l)
    obtained_ok = any(re.search(r'has 1 \[bac_obtained\]', l) for l in after['obtained'])
    items_ok = any(re.search(r'Found 8 matching', l) for l in after['amethyst_block'])
    check('2c_bacap_reward_Town02', has and obtained_ok and items_ok, advancement=ADV_TOWN02, settings=settings,
          before=before, grant=grant, has=has, after=after, log=log_town02,
          note='announcement (tellraw @a) delivery verified post-run from wire chat_probe events')

    # d) everything
    raw('advstats_mid', 'fabrolia regions debug advstats')
    start = cursor()
    t = time.monotonic()
    everything = raw('adv_town03', 'advancement grant Town03 everything', timeout=900)
    report['everything_rtt_ms'] = round((time.monotonic() - t) * 1000, 1)
    lst = raw('adv_town03', 'list')
    time.sleep(1)
    samples = {}
    for a in ('minecraft:story/root', 'minecraft:end/kill_dragon', 'blazeandcave:challenges/stack_all_the_items',
              'blazeandcave:bacap/advancement_legend', 'blazeandcave:mining/amethyst_miner'):
        samples[a] = test('adv_town03', f'execute if entity @a[name=Town03,advancements={{{a}=true}}]')[0]
    score = raw('adv_town03', 'scoreboard players get Town03 bac_advancements')
    raw('advstats_after', 'fabrolia regions debug advstats')
    spark_tps('tps_after_everything')
    alive = any('players online' in l for l in lst)
    granted = [l for l in everything if 'Granted' in l]
    report['everything_window_errors'] = lines_since(start, lambda l: PATTERN.search(l) and not any(b in l for b in BENIGN))
    check('2d_grant_everything_Town03', alive and bool(granted) and all(samples.values()),
          grant=granted, rtt_ms=report['everything_rtt_ms'], list=lst, samples=samples, bac_advancements=score,
          window_errors=report['everything_window_errors'][:50],
          note='file-level completeness verified post-stop (2d_file)')


def check_disconnect():
    # Command feedback is printed after the command ran on Main; the client closes the moment
    # that line is observed, i.e. the award/reward work and the disconnect land in the same second.
    races = {}
    for name, command, done in (
            ('Town04', f'advancement grant Town04 only {ADV_TOWN04}', r'Granted the advancement .* to Town04'),
            ('Town05', 'give Town05 minecraft:diamond 64', r'Gave 64 .* to Town05')):
        start = cursor()
        campaign.send(command)
        seen = close = None
        try:
            seen = campaign.wait(lambda line: re.search(done, line), start, 60)
            send(name, {'cmd': 'close'})
            close = time.time()
            campaign.wait(lambda line: f'{name} left the game' in line, start, 60)
        except Exception as error:
            races[name + '_error'] = repr(error)
        time.sleep(1)
        races[name] = {'command': command, 'feedback': seen, 'close_unix': close,
                       'log': lines_since(start, lambda l: name in l)}
        print('VERIFY_RACE', name, json.dumps(races[name]['log'])[:600], flush=True)
    report['town04_disconnect'] = races


def check_nether():
    blocks = [raw('nether', f'execute if block {x + 9} {Y + 1} {z + 8} minecraft:nether_portal') for x, z in PORTALS]
    raw('nether', 'function verifystand:feedloop')
    time.sleep(20)
    items_nether = raw('nether', 'execute in minecraft:the_nether if entity @e[type=item]')
    res = {}
    for name, (x, z) in zip(PORTAL_PLAYERS, PORTALS):
        r = res[name] = {}
        r['ow_before'] = in_dim('nether', name, 'overworld')
        inside = [x + 10.0, float(Y + 1), z + 8.5]
        r['tp_in'] = raw('nether', f'tp {name} {inside[0]} {inside[1]} {inside[2]}')
        r['reached_nether'], r['needed_move_nudge_in'] = wait_dim('nether', name, 'the_nether', 30, inside)
        time.sleep(2)
        r['nether_pos'] = raw('nether', f'data get entity {name} Pos')
    flight1 = raw('nether', 'fabrolia regions debug flightrec')
    time.sleep(15)  # portal cooldown
    for name in PORTAL_PLAYERS:
        r = res[name]
        p = parse_pos(r['nether_pos'])
        if not r['reached_nether'] or p is None:
            continue
        r['tp_out'] = raw('nether', f'execute in minecraft:the_nether run tp {name} {p[0]} {p[1] + 6} {p[2]}')
        time.sleep(3)
        r['still_nether_after_exit'] = in_dim('nether', name, 'the_nether')
        r['tp_back_in'] = raw('nether', f'execute in minecraft:the_nether run tp {name} {p[0]} {p[1]} {p[2]}')
        r['reached_overworld'], r['needed_move_nudge_back'] = wait_dim('nether', name, 'overworld', 30, p)
        time.sleep(2)
        r['final_pos'] = raw('nether', f'data get entity {name} Pos')
    for name in PORTAL_PLAYERS:
        res[name]['final_overworld'] = in_dim('nether', name, 'overworld')
        res[name]['final_not_nether'] = not in_dim('nether', name, 'the_nether')
    flight2 = raw('nether', 'fabrolia regions debug flightrec')
    items_nether2 = raw('nether', 'execute in minecraft:the_nether if entity @e[type=item]')
    with campaign.condition:
        recent = [line for serial, line in campaign.lines]
    skin = skin_fetch_noise(recent)
    ise = [l for i, l in enumerate(recent) if 'IllegalStateException' in l and i not in skin]
    ok = all(r.get('reached_nether') and r.get('reached_overworld') and r.get('final_overworld')
             and r.get('final_not_nether') for r in res.values()) and not ise \
        and all(any('Test passed' in l for l in b) for b in blocks)
    check('4_nether_portals', ok, portal_blocks=blocks, items_in_nether=[items_nether, items_nether2], players=res,
          flightrec=[flight1, flight2], illegal_state=ise[:20])


def check_saves():
    rtts = []
    for cmd in ('save-all', 'save-all', 'save-all', 'save-all flush'):
        t0 = time.monotonic(); ex('list'); t1 = time.monotonic()
        out = ex(cmd, timeout=600); t2 = time.monotonic()
        rtts.append({'cmd': cmd, 'list_rtt_ms': round((t1 - t0) * 1000, 1), 'rtt_ms': round((t2 - t1) * 1000, 1),
                     'out': out})
        print('VERIFY_SAVE', json.dumps(rtts[-1])[:400], flush=True)
        time.sleep(5)
    report['saves'] = rtts
    report['probe_pos'] = raw('saves', 'data get entity Town00 Pos')


def town02_announcements():
    """Per online client: count of BACAP 'has completed the challenge' messages about Town02 after the grant."""
    t0 = report.get('town02_grant_unix_ns', 0)
    out = {}
    for name in report.get('town02_online', []):
        n = 0
        try:
            for line in (run / f'wire-{name}.jsonl').read_text(errors='replace').splitlines():
                if '"chat_probe"' not in line:
                    continue
                e = json.loads(line)
                if (t0 <= e['unix_ns'] <= t0 + 10_000_000_000 and 'has completed the challenge' in e['text']
                        and '/tell Town02' in e['text']):
                    n += 1
        except Exception as error:
            n = repr(error)
        out[name] = n
    return out


def post_stop():
    server = run / 'server'
    world = server / 'world-native-burst'
    files = autosave_check.check_world(server, NAMES, 'minecraft:story/root', report.get('probe_pos'))
    cache = {}
    try:
        cache = {e['name']: e['uuid'] for e in json.loads((server / 'usercache.json').read_text())}
    except Exception:
        pass

    def adv(name):
        u = cache.get(name) or autosave_check.offline_uuid(name)
        return json.loads((world / 'players' / 'advancements' / (u + '.json')).read_text())

    def dat(name):
        u = cache.get(name) or autosave_check.offline_uuid(name)
        return autosave_check.read_nbt(world / 'players' / 'data' / (u + '.dat'))
    probes = {}
    for name, a in (('Town00', 'minecraft:story/root'), ('Town01', 'minecraft:story/mine_diamond'),
                    ('Town01', 'blazeandcave:mining/diamond_miner'), ('Town02', ADV_TOWN02), ('Town04', ADV_TOWN04),
                    ('Town05', 'minecraft:story/mine_diamond'), ('Town05', 'blazeandcave:mining/diamond_miner')):
        try:
            probes[f'{name} {a}'] = bool(adv(name).get(a, {}).get('done'))
        except Exception as error:
            probes[f'{name} {a}'] = repr(error)
    tmp = sorted(str(p.relative_to(server)) for p in world.rglob('*.tmp'))
    check('5_saves_and_files', files['dat_ok'] == 40 and files['adv_ok'] == 40 and files['stats_ok'] == 40
          and files.get('level_dat_ok') and files.get('probe_pos_match') and not files['leftover_tmp'] and not tmp
          and all(v is True for v in probes.values()) and not files['errors'],
          saves=report.get('saves'), files=files, adv_probes=probes, tmp_files=tmp)
    td = report.get('town04_disconnect', {})
    race_probes = {k: v for k, v in probes.items() if k.startswith(('Town04', 'Town05'))}
    check('3_disconnect_durability', all(v is True for v in race_probes.values())
          and all((td.get(n) or {}).get('feedback') for n in ('Town04', 'Town05')),
          races=td, file_done=race_probes)
    try:
        uni = verify_universe.analyze(server, 'Town03')
        m = None
        for l in report['checks'].get('2d_grant_everything_Town03', {}).get('grant', []):
            m = re.search(r'Granted (\d+) advancements', l) or m
        granted = int(m.group(1)) if m else None
        report['town03_file'] = uni
        check('2d_file_Town03_complete', granted == uni['universe'] and not uni['unexplained'],
              granted_count=granted, universe=uni['universe'], done_count=uni['done'],
              missing_count=uni['missing_count'], unexplained=uni['unexplained'],
              missing_all_hidden_self_revoked=[x['id'] for x in uni['missing']])
    except Exception as error:
        import traceback
        check('2d_file_Town03_complete', False, error=traceback.format_exc())
    # Nether players' saved dimension
    dims = {}
    for name in PORTAL_PLAYERS:
        try:
            tag = dat(name)
            dims[name] = {'Dimension': tag.get('Dimension'), 'Pos': tag.get('Pos')}
        except Exception as error:
            dims[name] = repr(error)
    report['portal_players_saved'] = dims
    c = report['checks'].get('2c_bacap_reward_Town02')
    if c is not None:
        ann = town02_announcements()
        c['announcement_per_online_client'] = ann
        if not ann or any(v != 1 for v in ann.values()):
            c['result'] = 'FAIL'
            c['announcement_note'] = 'not exactly one Town02 challenge announcement per online client'
    # Log scan
    log = (server / 'logs' / 'latest.log').read_text(errors='replace').splitlines()
    skin = skin_fetch_noise(log)
    hits = [l for i, l in enumerate(log) if PATTERN.search(l) and i not in skin]
    bad = [l for l in hits if not any(b in l for b in BENIGN)]
    noise = {b: sum(1 for l in log if b in l) for b in BENIGN}
    noise['SkinRestorer fetch exceptions (network)'] = sum(1 for i in skin if 'Exception' in log[i])
    crash = sorted(p.name for p in (server / 'crash-reports').glob('*')) if (server / 'crash-reports').exists() else []
    check('6_log_and_crash', not bad and not crash, bad_lines=bad[:200], bad_count=len(bad), noise_counts=noise,
          crash_reports=crash, tps=report['raw'].get('tps_warm', []) + report['raw'].get('tps_end', []))


try:
    campaign.launch()
    time.sleep(2)
    standenv.pin_process(standenv.SERVER_CPUS, campaign.proc.pid)
    campaign.wait(lambda line: re.search(r'Done \([0-9.]+s\)! For help, type', line), 0, recipe['boot_timeout_seconds'])
    standenv.pin_process(standenv.SERVER_CPUS, campaign.proc.pid)
    phase('booted')
    for c in recipe['setup_commands']:
        campaign.exchange(c, optional=True)
    for c in ('gamerule doDaylightCycle false', 'time set midnight', 'gamerule doWeatherCycle false',
              'fabrolia regions async on', 'defaultgamemode creative'):
        ex(c)
    report['datapacks'] = raw('datapacks', 'datapack list enabled')
    if not any('verifystand' in l for l in report['datapacks']):
        report['datapack_enable'] = raw('datapacks', 'datapack enable "file/verifystand"')
    players = [(f'Town{i:02d}', p) for i, p in enumerate(TOWN)] + [(f'Far{i:02d}', p) for i, p in enumerate(SPREAD)]
    for name, (x, z) in players:
        start_client(name)
        campaign.wait(lambda line, n=name: n + ' joined the game' in line, 0, 120)
        ex(f'gamemode creative {name}')
        ex(f'tp {name} {x} {Y} {z}')
    phase('joined')
    deadline = time.time() + 900
    # Protocol clients request view distance 2. Distant town-edge chunks are
    # not requested by any client, so waiting for them always spent 900s here.
    # Wait for the actual client locations before populating the fixture.
    need = TOWN + SPREAD
    while time.time() < deadline and not all(any('Test passed' in l for l in ex(f'execute if loaded {x} {Y} {z}'))
                                             for x, z in need):
        time.sleep(2)
    phase('loaded')
    ex(f'execute positioned 0 {Y} 0 run function perfstand:town', timeout=600)
    for x, z in SPREAD:
        ex(f'execute positioned {x} {Y} {z} run function perfstand:spread', timeout=300)
    walker = threading.Thread(target=walk, args=(players[20:20 + WALKERS],), daemon=True)
    walker.start()
    for x, z in PORTALS:
        raw('portal_build', f'execute positioned {x} {Y} {z} run function verifystand:portal', timeout=60)
    phase('populated')
    time.sleep(WARM_SECONDS)
    phase('warm')
    spark_tps('tps_warm')
    for step, fn in (('carpet_spawn_tracking', check_carpet_spawn_tracking),
                     ('functions', check_functions), ('advancements', check_advancements),
                     ('disconnect', check_disconnect), ('nether', check_nether), ('saves', check_saves)):
        phase(step)
        try:
            fn()
        except Exception as error:
            import traceback
            report.setdefault('step_errors', {})[step] = traceback.format_exc()
            print('VERIFY_STEP_ERROR', step, repr(error), flush=True)
        save_report()
    raw('fences_end', 'fabrolia functions fences')
    raw('advstats_end', 'fabrolia regions debug advstats')
    spark_tps('tps_end')
    report['list'] = raw('list', 'list')
    phase('checked')
    report['status'] = 'DONE'
except Exception as error:
    import traceback
    report.update(status='ERROR', error=repr(error), traceback=traceback.format_exc())
finally:
    stop_walk.set()
    # Normal stop with the remaining players still online (stop path saves them).
    try:
        campaign.stop()
    except Exception as error:
        report.update(stop_error=repr(error))
    report['cleanup'] = campaign.cleanup
    for name, proc in clients.items():
        try:
            proc.stdin.write(json.dumps({'cmd': 'close'}) + '\n')
            proc.stdin.flush()
        except Exception:
            pass
    for proc in clients.values():
        try:
            proc.wait(10)
        except Exception:
            proc.kill()
    try:
        post_stop()
    except Exception as error:
        import traceback
        report.update(post_stop_error=traceback.format_exc())
    save_report()
    print('EXPORT_RESULT ' + json.dumps({'status': report['status'], 'error': report.get('error'),
                                         'checks': {k: v['result'] for k, v in report['checks'].items()}}),
          flush=True)
sys.exit(0 if report['status'] == 'DONE' else 1)
