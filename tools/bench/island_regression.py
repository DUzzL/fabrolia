"""The island regression scenario on one machine.

Usage: python island_regression.py NAME [--config fabrolia-async-lithium-c2me] [--bots 100]
       [--xmx 8G] [--port 26700] [--phase-seconds 60] [--slow-ms 120] [--wedge-ms 8000]

Protocol players stand in the eight pens of the mob world (prep_world.py), about
twelve per pen, so every pen is one island, and every pen gets the same mob load. Phases:
steady load; one island slowed by an artificial unit cost (`debug islanddebug`); Main
parked (`debug wedgemain`); players changing dimension and island; a save; stop, restart
and the saved entities. Each phase is measured three ways:
  - client TPS: the observer clock above each pen as its players receive it
    (2 x block updates per second),
  - island TPS: completed ticks of the pen's island per wall second, from its logical
    tick L in `fabrolia regions top verbose`, next to the TPS and MSPT the island
    reports in the same row,
  - Main TPS: the level's game time (gt in the same row) per wall second.
Writes runs/NAME/island-regression-report.json; exit code 1 if a check fails.
FABROLIA_JAR selects the jar under test (default: the newest Fabric build).
The script starts the server with -Dfabrolia.debugCommands=true: the fault-injection and
stress subcommands it uses (`debug wedgemain`, `failunittail`, `offmainpkt`, `chunksender
inject|stresstp`) are registered only under that system property.
"""
import argparse
import bisect
import hashlib
import json
import re
import shutil
import statistics
import sys
import threading
import time
from pathlib import Path

import psutil

sys.path.insert(0, str(Path(__file__).resolve().parent))
import configs  # noqa: E402
import mc  # noqa: E402
import scenario as sc  # noqa: E402

DEBUG_COMMANDS_FLAG = ['-Dfabrolia.debugCommands=true']

ap = argparse.ArgumentParser()
ap.add_argument('name')
ap.add_argument('--config', default='fabrolia-async-lithium-c2me',
                choices=sorted(c for c in configs.CONFIGS if c.startswith('fabrolia-async')))
ap.add_argument('--bots', type=int, default=100)
ap.add_argument('--xmx', default='8G')
ap.add_argument('--port', type=int, default=26700)
ap.add_argument('--mobs-per-pen', type=int, default=72)
ap.add_argument('--phase-seconds', type=float, default=60)
ap.add_argument('--slow-ms', type=int, default=120, help='artificial unit cost of the slowed island')
ap.add_argument('--wedge-ms', type=int, default=8000, help='how long Main is parked')
a = ap.parse_args()

ROOT = mc.HOME
PENS = len(sc.CENTERS)
VIEW = 8
run = ROOT / 'runs' / a.name
if run.exists():
    raise SystemExit('run exists: %s' % run)
srv = configs.prepare(a.config, run, ROOT / 'runs' / 'world-template', a.port, a.xmx,
                      view_distance=VIEW, simulation_distance=VIEW)
srv.jvm_extra = srv.jvm_extra + DEBUG_COMMANDS_FLAG
PROPS = dict(gamemode='creative', force_gamemode='true', view_distance=VIEW, simulation_distance=VIEW,
             difficulty='easy', max_players=a.bots + 16)
srv.write_properties(**PROPS)

report = {'name': a.name, **configs.describe(a.config), 'jar_under_test': configs.FABROLIA.name,
          'jar_sha256': hashlib.sha256(configs.FABROLIA.read_bytes()).hexdigest(),
          'params': vars(a), 'cpu': psutil.cpu_count(), 'checks': [], 'phases': [],
          'events': [], 'samples': [], 'islands': []}
T0 = time.monotonic()


def event(msg, **kw):
    t = round(time.monotonic() - T0, 1)
    report['events'].append({'t': t, 'msg': msg, **kw})
    print('[%7.1f] %s %s' % (t, msg, json.dumps(kw) if kw else ''), flush=True)


def check(name, ok, detail=''):
    report['checks'].append({'check': name, 'ok': bool(ok), 'detail': str(detail)[:800]})
    print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail)[:400] if detail else ''), flush=True)
    return ok


RCON_LOCK = threading.Lock()


def rcmd(command):
    with RCON_LOCK:
        return re.sub('§.', '', srv.rcon(command))


# One row per island; see IslandMetrics.row.
ROW = re.compile(r'#\d+ (?P<label>.+?) \[(?P<handle>[^\]]+)\] (?P<dim>\S+) island#(?P<id>\d+)/gen(?P<gen>\d+) '
                 r'cells=\S+ L=(?P<L>-?\d+) gt=(?P<gt>-?\d+) ev=\S+ tps=(?P<tps>[\d.]+)/(?P<target>[\d.]+) '
                 r'mspt=(?P<mspt>[\d.]+) max=(?P<max>[\d.]+) lag=(?P<lag>\d+)ms missed=(?P<missed>\d+)')


def island_sample():
    """Every island row with the wall time the server answered (Main executes the command)."""
    out = rcmd('fabrolia regions top verbose 100')
    t = time.monotonic()
    rows = {}
    for m in ROW.finditer(out):
        d = m.groupdict()
        rows[int(d['id'])] = {'label': d['label'], 'dim': d['dim'], 'gen': int(d['gen']), 'L': int(d['L']),
                              'gt': int(d['gt']), 'tps': float(d['tps']), 'mspt': float(d['mspt']),
                              'max': float(d['max']), 'lag': int(d['lag']), 'missed': int(d['missed'])}
    if not rows:
        raise RuntimeError('no island rows in: ' + out[:300])
    sample = {'t': t, 'islands': rows}
    report['islands'].append({'t': round(t - T0, 2), 'islands': rows})
    return sample


ISLAND_SAMPLES = []


def sampler(stop, proc, bots, pen_bots):
    """Per second: client clock updates per pen, process CPU/RSS, bot errors; every 2 s an island row sample."""
    proc.cpu_percent(None)
    last = {n: 0 for n in bots}
    tick = 0
    while not stop.is_set():
        time.sleep(1.0)
        counts = {n: len(b.clock_times) for n, b in bots.items()}
        per_pen = []
        for p in range(PENS):
            per_pen.append(statistics.median(counts[n] - last[n] for n in pen_bots[p]))
        last = counts
        report['samples'].append({'t': round(time.monotonic() - T0, 2), 'clock': per_pen,
                                  'cpu': round(proc.cpu_percent(None) / psutil.cpu_count(), 1),
                                  'rss_gb': round(proc.memory_info().rss / 2 ** 30, 2),
                                  'bot_errors': sum(1 for b in bots.values() if b.error)})
        tick += 1
        if tick % 2 == 0 and not PAUSE_ISLAND_SAMPLER.is_set():
            try:
                ISLAND_SAMPLES.append(island_sample())
            except Exception as e:  # recorded, the phase checks fail on missing samples
                event('island sample failed', err=repr(e))


PAUSE_ISLAND_SAMPLER = threading.Event()


def client_tps(bot, t0, t1):
    times = bot.clock_times
    n = bisect.bisect_right(times, t1) - bisect.bisect_left(times, t0)
    return 2.0 * n / (t1 - t0)


def max_gap(bot, t0, t1):
    times = [t0] + [x for x in bot.clock_times if t0 <= x <= t1] + [t1]
    return max(b - a_ for a_, b in zip(times, times[1:]))


def island_rates(t0, t1):
    """Per island id over [t0, t1]: completed ticks/s, Main ticks/s, median reported tps and mspt."""
    # Two threads append samples; order them by the time the server answered.
    inside = sorted((s for s in ISLAND_SAMPLES if t0 <= s['t'] <= t1), key=lambda s: s['t'])
    if len(inside) < 2:
        return None
    s0, s1 = inside[0], inside[-1]
    dt = s1['t'] - s0['t']
    out = {}
    for iid, r1 in s1['islands'].items():
        r0 = s0['islands'].get(iid)
        if r0 is None:
            continue
        rows = [s['islands'][iid] for s in inside if iid in s['islands']]
        out[iid] = {'label': r1['label'], 'ticks_per_s': (r1['L'] - r0['L']) / dt,
                    'main_ticks_per_s': (r1['gt'] - r0['gt']) / dt,
                    'reported_tps': statistics.median(r['tps'] for r in rows),
                    'reported_mspt': statistics.median(r['mspt'] for r in rows),
                    'missed': r1['missed'] - r0['missed'], 'gen': r1['gen']}
    return out


def pen_island_ids(pen_bots):
    """Island id of every pen, from `regions player` of its first player and the island rows."""
    sample = island_sample()
    ids = {}
    for p in range(PENS):
        out = rcmd('fabrolia regions player ' + pen_bots[p][0])
        m = re.search(r'Island: (.+?) \((?:Overworld|Nether|The End)\)', out)
        label = m.group(1) if m else None
        hit = [iid for iid, r in sample['islands'].items() if r['label'] == label]
        if len(hit) != 1:  # labels name at most three players (IslandMetrics.autoLabel)
            hit = [iid for iid, r in sample['islands'].items() if pen_bots[p][0] in r['label'].split('+')]
        ids[p] = hit[0] if len(hit) == 1 else None
    return ids


def measure_phase(name, t0, t1, bots, pen_bots, ids, **extra):
    rates = island_rates(t0, t1) or {}
    pens = []
    for p in range(PENS):
        tps = [client_tps(bots[n], t0, t1) for n in pen_bots[p]]
        iid = ids.get(p)
        isl = rates.get(iid) if iid is not None else None
        pens.append({'pen': p, 'island': iid, 'client_tps': round(statistics.median(tps), 2),
                     'client_max_gap_s': round(max_gap(bots[pen_bots[p][0]], t0, t1), 2),
                     'island_ticks_per_s': round(isl['ticks_per_s'], 2) if isl else None,
                     'reported_tps': isl['reported_tps'] if isl else None,
                     'reported_mspt': isl['reported_mspt'] if isl else None,
                     'missed': isl['missed'] if isl else None})
    mains = [r['main_ticks_per_s'] for r in rates.values()]
    phase = {'phase': name, 't0': round(t0 - T0, 1), 't1': round(t1 - T0, 1), 'pens': pens,
             'main_ticks_per_s': round(statistics.median(mains), 2) if mains else None, **extra}
    report['phases'].append(phase)
    event('phase ' + name, main=phase['main_ticks_per_s'],
          client=[x['client_tps'] for x in pens], island=[x['island_ticks_per_s'] for x in pens],
          reported=[x['reported_tps'] for x in pens])
    return phase


def at_full_speed(phase, pens, floor=19.0):
    bad = [(x['pen'], x['client_tps'], x['island_ticks_per_s']) for x in phase['pens'] if x['pen'] in pens
           and not (x['client_tps'] >= floor and x['island_ticks_per_s'] is not None
                    and x['island_ticks_per_s'] >= floor)]
    return not bad, bad


def honest(phase, pens, rel=0.15):
    """The reported TPS of each island matches its completed ticks per wall second."""
    bad = []
    for x in phase['pens']:
        if x['pen'] not in pens:
            continue
        r, m = x['reported_tps'], x['island_ticks_per_s']
        if r is None or m is None or abs(r - m) > max(1.0, rel * m):
            bad.append((x['pen'], r, m))
    return not bad, bad


def count_entities():
    out = rcmd('execute if entity @e[type=!minecraft:player,type=!minecraft:item]')
    m = re.search(r'Count: (\d+)', out)
    return int(m.group(1)) if m else -1


def count_pen_entities():
    """Mobs inside each pen's walls. The pens stay loaded before the stop and after the
    restart; animals in the terrain around them do not (fewer players keep fewer chunks
    loaded after the restart), so a level-wide count is not comparable."""
    counts = []
    for cx, cz in sc.CENTERS:
        out = rcmd('execute if entity @e[type=!minecraft:player,type=!minecraft:item,x=%d,y=%d,z=%d,dx=%d,dy=3,dz=%d]'
                   % (cx - sc.HALF, sc.FLOOR_Y + 1, cz - sc.HALF, 2 * sc.HALF, 2 * sc.HALF))
        m = re.search(r'Count: (\d+)', out)
        counts.append(int(m.group(1)) if m else -1)
    return counts


def spot(pen_bots, name):
    p = next(q for q in range(PENS) if name in pen_bots[q])
    k = pen_bots[p].index(name)
    cx, cz = sc.CENTERS[p]
    if k == 0:
        return p, cx, cz  # the spawner stands at the pen centre
    row = (k - 1) // 6  # two rows of six, 10 blocks north and south of the spawn ring
    return p, cx - 15 + 6 * ((k - 1) % 6) + 2 * (row // 2), cz - 10 + 20 * (row % 2)


def in_pen(bot, p):
    cx, cz = sc.CENTERS[p]
    return bot.pos is not None and abs(bot.pos[0] - cx) < sc.HALF and abs(bot.pos[2] - cz) < sc.HALF


def spawn_mobs(bot, cx, cz, total):
    order = []
    for kind, n in sc.MIX.items():
        order += [kind] * n
    slots = {kind: i for i, (kind, _) in enumerate(sc.EGGS)}
    offsets = sc.spawn_offsets()
    for slot, (_, item) in enumerate(sc.EGGS):
        bot.set_creative_slot(36 + slot, item)
    time.sleep(0.5)
    current = None
    for i in range(total):
        kind = order[i % len(order)]
        if slots[kind] != current:
            current = slots[kind]
            bot.select_hotbar(current)
        dx, dz = offsets[i % len(offsets)]
        bot.use_item_on(cx + dx, sc.FLOOR_Y, cz + dz)
        time.sleep(0.05)


names = ['R%03d' % (i + 1) for i in range(a.bots)]
pen_bots = {p: [n for i, n in enumerate(names) if i % PENS == p] for p in range(PENS)}
bots = {}
stop = threading.Event()
report['completed'] = False
try:
    t_boot = time.monotonic()
    srv.start()
    srv.wait_ready(timeout=1200)
    report['boot_seconds'] = round(time.monotonic() - t_boot, 1)
    event('server ready', boot_seconds=report['boot_seconds'])
    proc = psutil.Process(srv.proc.pid)

    # ---------------------------------------------------------------- join, ten at a time
    for i in range(0, len(names), 10):
        batch = names[i:i + 10]
        for n in batch:
            b = mc.Bot(n, a.port, view_distance=VIEW)
            b.connect()
            bots[n] = b
            time.sleep(0.1)
        mc.wait_until(lambda: all(bots[n].loaded or bots[n].error for n in batch), 300, what='batch joined')
        for n in batch:
            _, x, z = spot(pen_bots, n)
            rcmd('tp %s %d %d %d' % (n, x, sc.FLOOR_Y + 1, z))
    mc.wait_until(lambda: all(in_pen(bots[n], spot(pen_bots, n)[0]) or bots[n].error for n in names), 120,
                  what='players in pens')
    placed = [n for n in names if in_pen(bots[n], spot(pen_bots, n)[0]) and not bots[n].error]
    check('%d players joined and stand in their pens' % a.bots, len(placed) == a.bots,
          '%d placed; errors: %s' % (len(placed), [(n, b.error) for n, b in bots.items() if b.error][:5]))
    for p in range(PENS):
        for n in pen_bots[p]:
            bots[n].watch_clock(sc.clock_blocks(*sc.CENTERS[p]))
    event('players placed')

    # ---------------------------------------------------------------- same mob load in every pen
    spawners = [threading.Thread(target=spawn_mobs, args=(bots[pen_bots[p][0]], *sc.CENTERS[p], a.mobs_per_pen))
                for p in range(PENS)]
    for th in spawners:
        th.start()
    for th in spawners:
        th.join()
    time.sleep(15)
    report['entities_after_setup'] = count_entities()
    event('mob load placed', entities=report['entities_after_setup'])
    threading.Thread(target=sampler, args=(stop, proc, bots, pen_bots), daemon=True).start()
    time.sleep(10)

    # ---------------------------------------------------------------- 1. steady load
    out = rcmd('fabrolia regions list')
    m = re.search(r'Overworld \((\d+)\)', out)
    check('one island per pen', m and int(m.group(1)) >= PENS, out[:400])
    ids = pen_island_ids(pen_bots)
    check('every pen maps to one island', all(ids[p] is not None for p in range(PENS)) and len(set(ids.values())) == PENS, ids)
    t0 = time.monotonic()
    time.sleep(a.phase_seconds)
    ph = measure_phase('steady', t0, time.monotonic(), bots, pen_bots, ids)
    ok, bad = at_full_speed(ph, range(PENS))
    check('steady: every pen at full speed (client and island >= 19 TPS)', ok, bad)
    check('steady: Main at full speed (>= 19 TPS)', (ph['main_ticks_per_s'] or 0) >= 19.0, ph['main_ticks_per_s'])
    ok, bad = honest(ph, range(PENS))
    check('steady: reported island TPS matches completed ticks', ok, bad)

    # ---------------------------------------------------------------- 2. one island slowed down
    ids = pen_island_ids(pen_bots)
    slow = 0
    if ids[slow] is None:
        raise RuntimeError('the slowed pen has no identifiable island: %s' % ids)
    rcmd('fabrolia regions debug islanddebug %d %d' % (ids[slow], a.slow_ms))
    time.sleep(10)
    t0 = time.monotonic()
    time.sleep(a.phase_seconds)
    ph = measure_phase('slow island', t0, time.monotonic(), bots, pen_bots, ids, slow_pen=slow, slow_ms=a.slow_ms)
    rcmd('fabrolia regions debug islanddebug %d 0' % ids[slow])
    others = [p for p in range(PENS) if p != slow]
    sp = ph['pens'][slow]
    check('slow island: the cost takes effect (island < 15 TPS)',
          sp['island_ticks_per_s'] is not None and sp['island_ticks_per_s'] < 15.0, sp)
    ok, bad = at_full_speed(ph, others)
    check('slow island: every other pen keeps full speed', ok, bad)
    check('slow island: Main keeps full speed', (ph['main_ticks_per_s'] or 0) >= 19.0, ph['main_ticks_per_s'])
    ok, bad = honest(ph, range(PENS))
    check('slow island: every island reports the TPS it completes', ok, bad)
    check('slow island: its MSPT shows the cost (>= %d ms)' % int(0.9 * a.slow_ms),
          (sp['reported_mspt'] or 0) >= 0.9 * a.slow_ms, sp['reported_mspt'])
    check('slow island: its players see its real speed (client within 15 %)',
          sp['island_ticks_per_s'] and abs(sp['client_tps'] - sp['island_ticks_per_s'])
          <= max(1.0, 0.15 * sp['island_ticks_per_s']), sp)
    time.sleep(15)

    # ---------------------------------------------------------------- 3. Main parked
    ids = pen_island_ids(pen_bots)
    PAUSE_ISLAND_SAMPLER.set()
    before = island_sample()
    ISLAND_SAMPLES.append(before)
    rcmd('fabrolia regions debug wedgemain %d' % a.wedge_ms)
    after = island_sample()  # Main answers once it is released
    ISLAND_SAMPLES.append(after)
    PAUSE_ISLAND_SAMPLER.clear()
    wall = after['t'] - before['t']
    ph = measure_phase('main parked', before['t'], after['t'], bots, pen_bots, ids, wedge_ms=a.wedge_ms,
                       wall_s=round(wall, 2))
    check('main parked: Main stood still for the wedge (wall >= %.1f s, Main <= 2 TPS)' % (0.9 * a.wedge_ms / 1000),
          wall >= 0.9 * a.wedge_ms / 1000 and (ph['main_ticks_per_s'] or 0) <= 2.0,
          {'wall_s': round(wall, 2), 'main': ph['main_ticks_per_s']})
    slow_islands = [(x['pen'], x['island_ticks_per_s']) for x in ph['pens']
                    if x['island_ticks_per_s'] is None or x['island_ticks_per_s'] < 18.0]
    check('main parked: every island keeps ticking (>= 18 completed TPS)', not slow_islands, slow_islands)
    time.sleep(15)

    # ---------------------------------------------------------------- 4. dimension and island changes
    ids = pen_island_ids(pen_bots)
    movers = [pen_bots[p][1 + k] for k in range(2) for p in range(PENS) if len(pen_bots[p]) > 1 + k]
    plan = []
    for k, n in enumerate(movers):
        p, x, z = spot(pen_bots, n)
        t_out = 3.0 * k
        if k % 2 == 0:
            plan.append((t_out, 'execute in minecraft:the_nether run tp %s %d 100 %d' % (n, x // 8, z // 8)))
        else:
            q = (p + 3) % PENS
            plan.append((t_out, 'tp %s %d %d %d' % (n, sc.CENTERS[q][0] + 12, sc.FLOOR_Y + 1, sc.CENTERS[q][1] + 12)))
        plan.append((t_out + 5.0, 'execute in minecraft:overworld run tp %s %d %d %d' % (n, x, sc.FLOOR_Y + 1, z)))
    plan.sort(key=lambda e: e[0])
    teleports_before = {n: bots[n].teleports for n in movers}
    t0 = time.monotonic()
    for t_rel, command in plan:
        time.sleep(max(0.0, t0 + t_rel - time.monotonic()))
        rcmd(command)
    time.sleep(max(0.0, t0 + a.phase_seconds - time.monotonic()))
    ph = measure_phase('dimension and island changes', t0, time.monotonic(), bots, pen_bots, ids,
                       movers=movers, actions=len(plan))
    back = [n for n in movers if in_pen(bots[n], spot(pen_bots, n)[0])
            and 'overworld' in rcmd('execute as %s run data get entity @s Dimension' % n)]
    check('changes: every moved player came back to its pen in the Overworld', len(back) == len(movers),
          sorted(set(movers) - set(back)))
    check('changes: every move reached its client', all(bots[n].teleports >= teleports_before[n] + 2 for n in movers),
          {n: bots[n].teleports - teleports_before[n] for n in movers})
    ok, bad = at_full_speed(ph, range(PENS))
    check('changes: every pen keeps full speed', ok, bad)
    check('changes: Main keeps full speed', (ph['main_ticks_per_s'] or 0) >= 19.0, ph['main_ticks_per_s'])

    # ---------------------------------------------------------------- 5. save under load
    ids = pen_island_ids(pen_bots)
    time.sleep(4)
    t0 = time.monotonic()
    out = rcmd('save-all flush')
    t_saved = time.monotonic()
    time.sleep(6)
    ph = measure_phase('save', t0 - 4, time.monotonic(), bots, pen_bots, ids, save_seconds=round(t_saved - t0, 2))
    check('save: completed', 'Saved the game' in out, out[:200])
    slow_islands = [(x['pen'], x['island_ticks_per_s']) for x in ph['pens']
                    if x['island_ticks_per_s'] is None or x['island_ticks_per_s'] < 18.0]
    check('save: every island keeps ticking (>= 18 completed TPS)', not slow_islands, slow_islands)

    errs = [(n, b.error) for n, b in bots.items() if b.error]
    check('no player lost its connection', not errs, errs[:5])

    # ---------------------------------------------------------------- 6. stop, restart, saved state
    stop.set()
    entities_before = count_entities()
    report['entities_before_stop'] = entities_before
    pens_before = count_pen_entities()
    report['pen_entities_before_stop'] = pens_before
    for b in bots.values():
        b.close()
    time.sleep(2)
    rc = srv.stop(timeout=300)
    log = srv.log_text()
    check('clean stop (exit code 0)', rc == 0, rc)
    unexpected = mc.unexpected_log_errors(log)
    check('no unexpected ERROR/FATAL log lines (first run)', not unexpected, unexpected[:3])
    report['wedge_log'] = [l for l in log.splitlines() if '[wedgemain]' in l][:4]
    (run / 'console-run1.log').write_text(log, encoding='utf-8')

    srv = mc.Server(run, 'fabric', a.port, xmx=a.xmx, jvm_extra=mc.AIKAR + DEBUG_COMMANDS_FLAG)
    srv.start()
    srv.wait_ready(timeout=1200)
    again = {}
    for p in range(PENS):
        n = pen_bots[p][0]
        b = mc.Bot(n, a.port, view_distance=VIEW)
        b.connect()
        again[n] = b
    mc.wait_until(lambda: all(b.loaded or b.error for b in again.values()), 300, what='rejoin')
    time.sleep(15)
    entities_after = count_entities()
    report['entities_after_restart'] = entities_after
    pens_after = count_pen_entities()
    report['pen_entities_after_restart'] = pens_after
    check('saved entities survive the restart (every pen)', pens_after == pens_before and min(pens_before) > 0,
          '%s -> %s (level-wide, loaded chunks only: %s -> %s)'
          % (pens_before, pens_after, entities_before, entities_after))
    check('players rejoin in their pens', all(in_pen(again[pen_bots[p][0]], p) for p in range(PENS)),
          {n: b.pos for n, b in again.items()})
    out = rcmd('fabrolia regions list')
    check('async islands active after restart', re.search(r'Overworld \([1-9]', out), out[:300])
    for b in again.values():
        b.close()
    time.sleep(1)
    rc = srv.stop(timeout=300)
    log = srv.log_text()
    check('second clean stop', rc == 0, rc)
    unexpected = mc.unexpected_log_errors(log)
    check('no unexpected ERROR/FATAL log lines (restart)', not unexpected, unexpected[:3])
    crashes = list((run / 'crash-reports').glob('*')) if (run / 'crash-reports').exists() else []
    check('no crash reports', not crashes, [c.name for c in crashes])
    report['completed'] = True
except Exception as e:
    check('scenario ran to the end', False, repr(e))
    event('ERROR', err=repr(e))
finally:
    stop.set()
    for b in bots.values():
        b.close()
    if srv.proc and srv.proc.poll() is None:
        srv.stop(timeout=300)
    report['passed'] = report['completed'] and all(c['ok'] for c in report['checks'])
    (run / 'island-regression-report.json').write_text(json.dumps(report, indent=1), encoding='utf-8')
    # Keep logs, crash reports and the report; drop the world copy.
    shutil.rmtree(run / 'world', ignore_errors=True)
    failed = [c['check'] for c in report['checks'] if not c['ok']]
    print('RESULT', a.name, 'ALL PASS' if report['passed'] else 'FAILURES', len(failed), flush=True)
sys.exit(0 if report['passed'] else 1)
