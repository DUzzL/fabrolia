"""One benchmark run: a server configuration under a growing, identical mob load.

Usage: python bench.py CONFIG NAME [--steps 20] [--step-seconds 30] [--per-step 24]
       [--warmup 60] [--tail 30] [--xmx 16G]

Eight protocol-level players stand in eight pens ~1500 blocks apart (world from
prep_world.py). After a warm-up, every player spawns PER_STEP mobs with spawn eggs
(creative mode, like a real client) every STEP_SECONDS. The load therefore enters every
server the same way: no commands, no plugins, no mods on the measuring side.

TPS is measured by the players themselves: an observer clock above each pen changes
every 2 game ticks and the server sends one block update per change, so
TPS = 2 x clock updates per second at that pen. The server process CPU use is sampled
every second. Writes runs/NAME/bench.json.
"""
import argparse
import json
import os
import shutil
import sys
import threading
import time
from pathlib import Path

import psutil

sys.path.insert(0, str(Path(__file__).resolve().parent))
import inputs  # noqa: E402
import mc  # noqa: E402
import scenario as sc  # noqa: E402

ROOT = mc.HOME
DL = ROOT / 'dl'
FABRIC_API = DL / inputs.FABRIC_API
LITHIUM = DL / inputs.LITHIUM
C2ME = DL / inputs.C2ME
# The jar under test: FABROLIA_JAR, else the newest Fabric build of this checkout.
FABROLIA = Path(os.environ['FABROLIA_JAR']) if os.environ.get('FABROLIA_JAR') else max(
    (mc.REPO / 'fabric' / 'build' / 'libs').glob('fabrolia-*.jar'), key=lambda p: p.stat().st_mtime)

CONFIGS = {
    'vanilla': {'kind': 'vanilla', 'mods': []},
    'lithium': {'kind': 'fabric', 'mods': [LITHIUM]},
    'c2me': {'kind': 'fabric', 'mods': [C2ME]},
    'lithium-c2me': {'kind': 'fabric', 'mods': [LITHIUM, C2ME]},
    'paper': {'kind': 'paper', 'jar': DL / inputs.PAPER},
    'folia': {'kind': 'paper', 'jar': DL / inputs.FOLIA},
    'fabrolia': {'kind': 'fabric', 'mods': [FABRIC_API, FABROLIA], 'fabrolia': 'default'},
    'fabrolia-async': {'kind': 'fabric', 'mods': [FABRIC_API, FABROLIA], 'fabrolia': 'async'},
    'fabrolia-async-lithium-c2me': {'kind': 'fabric', 'mods': [FABRIC_API, FABROLIA, LITHIUM, C2ME],
                                    'fabrolia': 'async'},
}

ap = argparse.ArgumentParser()
ap.add_argument('config', choices=sorted(CONFIGS))
ap.add_argument('name')
ap.add_argument('--steps', type=int, default=20)
ap.add_argument('--step-seconds', type=float, default=30)
ap.add_argument('--per-step', type=int, default=24, help='mobs per player and step')
ap.add_argument('--warmup', type=float, default=60)
ap.add_argument('--tail', type=float, default=30)
ap.add_argument('--xmx', default='16G')
a = ap.parse_args()
cfg = CONFIGS[a.config]

# ------------------------------------------------------------------ server directory
run = ROOT / 'runs' / a.name
if run.exists():
    shutil.rmtree(run)
if cfg['kind'] == 'paper':
    tpl = ROOT / 'tpl' / cfg['jar'].stem
    if not tpl.exists():
        raise SystemExit('run setup.py first: ' + str(tpl))
    shutil.copytree(tpl, run)
    loader = 'paper'
else:
    run.mkdir(parents=True)
    shutil.copy2(ROOT / 'tpl' / 'fabric' / 'server.jar', run)
    for lib in ('libraries', 'versions', '.fabric'):  # unpacked bundler and loader cache
        shutil.copytree(ROOT / 'tpl' / 'fabric-cache' / lib, run / lib)
    loader = 'vanilla'
    if cfg['kind'] == 'fabric':
        loader = 'fabric'
        shutil.copy2(ROOT / 'tpl' / 'fabric' / 'fabric-server-launch.jar', run)
        (run / 'mods').mkdir()
        for m in cfg['mods']:
            shutil.copy2(m, run / 'mods')
    if cfg.get('fabrolia'):
        (run / 'config').mkdir(exist_ok=True)
        on = 'true' if cfg['fabrolia'] == 'async' else 'false'
        (run / 'config' / 'fabrolia.toml').write_text(
            'asyncIslands = %s\nenablePlayerTickActor = %s\nenableRegionThreading = true\n' % (on, on))
shutil.copytree(ROOT / 'runs' / 'world-template', run / 'world')

srv = mc.Server(run, loader, sc.PORT, xmx=a.xmx, jvm_extra=mc.AIKAR)
if loader == 'paper':
    srv.command_line = lambda: ([mc.JAVA, '-Xms' + a.xmx, '-Xmx' + a.xmx] + mc.AIKAR
                                + ['-jar', cfg['jar'].name, '--nogui'])
srv.write_properties(level_seed=sc.SEED, gamemode='creative', force_gamemode='true', view_distance=10,
                     simulation_distance=10, difficulty='easy')

result = {'config': a.config, 'name': a.name, 'kind': cfg['kind'], 'jar_under_test': FABROLIA.name,
          'mods': [m.name for m in cfg.get('mods', [])], 'jar': cfg['jar'].name if 'jar' in cfg else None,
          'fabrolia_mode': cfg.get('fabrolia'), 'xmx': a.xmx, 'cpu': psutil.cpu_count(),
          'params': {'steps': a.steps, 'step_seconds': a.step_seconds, 'per_step': a.per_step,
                     'warmup': a.warmup, 'tail': a.tail},
          'samples': [], 'events': []}


def event(msg, **kw):
    t = round(time.monotonic() - T0, 1) if T0 else 0
    result['events'].append({'t': t, 'msg': msg, **kw})
    print('[%7.1f] %s %s' % (t, msg, json.dumps(kw) if kw else ''), flush=True)


T0 = None
bots = []
spawned = [0]
stop = threading.Event()
try:
    t_boot = time.monotonic()
    srv.start()
    srv.wait_ready(timeout=1200)
    result['boot_seconds'] = round(time.monotonic() - t_boot, 1)
    proc = psutil.Process(srv.proc.pid)
    proc.cpu_percent(None)
    me = psutil.Process()
    me.cpu_percent(None)

    bots = [mc.Bot(n, sc.PORT) for n in sc.BOTS]
    for b in bots:
        b.connect()
    mc.wait_until(lambda: all(b.loaded or b.error for b in bots), 300, what='bots joined')
    for b, (cx, cz) in zip(bots, sc.CENTERS):
        if b.error or not b.pos or abs(b.pos[0] - cx - 0.5) > 2 or abs(b.pos[2] - cz - 0.5) > 2:
            raise RuntimeError('%s not in its pen: %s %s' % (b.name, b.pos, b.error))
        b.watch_clock(sc.clock_blocks(cx, cz))
        for slot, (_, item) in enumerate(sc.EGGS):
            b.set_creative_slot(36 + slot, item)
    T0 = time.monotonic()
    event('bots in place', boot_seconds=result['boot_seconds'])

    def sampler():
        last = [0] * len(bots)
        while not stop.is_set():
            time.sleep(1.0)
            now = time.monotonic() - T0
            counts = [len(b.clock_times) for b in bots]
            result['samples'].append({
                't': round(now, 2),
                'clock': [c - l for c, l in zip(counts, last)],
                'cpu': round(proc.cpu_percent(None) / psutil.cpu_count(), 1),
                'rss_gb': round(proc.memory_info().rss / 2 ** 30, 2),
                'bots_cpu': round(me.cpu_percent(None) / psutil.cpu_count(), 1),
                'spawned': spawned[0],
                'errors': sum(1 for b in bots if b.error),
            })
            last = counts

    threading.Thread(target=sampler, daemon=True).start()

    order = []
    for kind, n in sc.MIX.items():
        order += [kind] * n
    slots = {kind: i for i, (kind, _) in enumerate(sc.EGGS)}
    offsets = sc.spawn_offsets()

    def spawn_step(bot, cx, cz, step):
        current = None
        for i in range(a.per_step):
            kind = order[(step * a.per_step + i) % len(order)]
            if slots[kind] != current:
                current = slots[kind]
                bot.select_hotbar(current)
            dx, dz = offsets[(step * a.per_step + i) % len(offsets)]
            bot.use_item_on(cx + dx, sc.FLOOR_Y, cz + dz)
            spawned[0] += 1
            time.sleep(0.05)

    time.sleep(a.warmup)
    for step in range(a.steps):
        t_step = time.monotonic()
        event('step', n=step + 1, spawned_before=spawned[0])
        threads = [threading.Thread(target=spawn_step, args=(b, cx, cz, step))
                   for b, (cx, cz) in zip(bots, sc.CENTERS)]
        for th in threads:
            th.start()
        for th in threads:
            th.join()
        if srv.proc.poll() is not None:
            raise RuntimeError('server exited %s' % srv.proc.returncode)
        errs = [b.name + ': ' + b.error for b in bots if b.error]
        if errs:
            raise RuntimeError('bot errors: %s' % errs)
        time.sleep(max(0.0, a.step_seconds - (time.monotonic() - t_step)))
    time.sleep(a.tail)
    stop.set()
    try:
        out = srv.rcon('execute if entity @e[type=!minecraft:player]')
        result['entities_end'] = out.strip()
    except Exception as e:  # Folia may refuse world commands from the console
        result['entities_end'] = 'n/a: %r' % e
    event('done', spawned=spawned[0], entities=result['entities_end'])
    result['status'] = 'ok'
except Exception as e:
    result['status'] = 'error: %r' % e
    event('ERROR', err=repr(e))
finally:
    stop.set()
    for b in bots:
        b.close()
    rc = srv.stop(timeout=180) if srv.proc else None
    result['exit_code'] = rc
    result['spawned'] = spawned[0]
    (run / 'bench.json').write_text(json.dumps(result), encoding='utf-8')
    # Keep logs and results; drop the world copy (100+ MB per run).
    shutil.rmtree(run / 'world', ignore_errors=True)
    print('RESULT', a.name, result['status'], 'exit', rc, flush=True)
