"""Benchmark runs for the scenarios beyond the mob ramp (see SCENARIOS).

Usage: python bench2.py SCENARIO CONFIG NAME [--threads N]

bases scenarios (chunks, hoppers, redstone): every player's base is pre-built in a world
  template; players join PER_STEP at a time, so the active load grows base by base. TPS is
  each present player's own observer clock (2 x block updates per second).
worldgen: players join PER_STEP at a time into terrain that does not exist yet; an anchor
  player at spawn measures TPS, the joining players measure chunks delivered per second.
scaling: a fixed load (the mob pens after the full ramp) with the server limited to N
  logical CPUs; TPS times the entity count gives entity ticks per second.
Writes runs/NAME/bench.json.
"""
import argparse
import json
import shutil
import sys
import threading
import time
from pathlib import Path

import psutil

sys.path.insert(0, str(Path(__file__).resolve().parent))
import chunks_scenario as cs  # noqa: E402
import configs  # noqa: E402
import mc  # noqa: E402

ROOT = mc.HOME
PORT = 26600


def grid_spots(n=32):
    return [((i - 4) * 1024 + 512, (j - 2) * 1024 + 512) for j in range(4) for i in range(8)][:n]


SCENARIOS = {
    'chunks': dict(template='chunks-template', view=cs.VIEW_DISTANCE, sim=cs.SIMULATION_DISTANCE,
                   bots=cs.BOTS, spots=cs.SPOTS, order=cs.ORDER, per_step=4, step=45, settle=25,
                   clock_y=cs.CLOCK_Y),
    'hoppers': dict(template='hoppers-template', view=10, sim=10, bots=['Base%02d' % (i + 1) for i in range(32)],
                    spots=grid_spots(), order=cs.ORDER, per_step=2, step=35, settle=15, clock_y=300),
    'redstone': dict(template='redstone-template', view=10, sim=10, bots=['Base%02d' % (i + 1) for i in range(32)],
                     spots=grid_spots(), order=cs.ORDER, per_step=2, step=35, settle=15, clock_y=300),
    'worldgen': dict(template='worldgen-template', view=12, sim=10, bots=['Gen%02d' % (i + 1) for i in range(32)],
                     spots=[(x * 3, z * 3) for x, z in grid_spots()], order=cs.ORDER, per_step=4, step=40,
                     settle=5, clock_y=300, anchor=('Anchor', (0, 0))),
    'scaling': dict(template='scaling-template', view=10, sim=10, bots=['Bench%d' % (i + 1) for i in range(8)],
                    settle=40, measure=60, clock_y=250),
}

ap = argparse.ArgumentParser()
ap.add_argument('scenario', choices=sorted(SCENARIOS))
ap.add_argument('config', choices=sorted(configs.CONFIGS))
ap.add_argument('name')
ap.add_argument('--threads', type=int, default=0, help='scaling: logical CPUs for the server (0 = all)')
ap.add_argument('--xmx', default='16G')
ap.add_argument('--quick', action='store_true', help='smoke test on the 2-spot template')
a = ap.parse_args()
sc = dict(SCENARIOS[a.scenario])
if a.quick:
    sc.update(template=sc['template'].replace('-template', '-quick-template'), step=20, settle=5,
              measure=10, per_step=1, order=[0, 1])
meta = json.loads((ROOT / 'runs' / sc['template'] / 'bench-meta.json').read_text())

run = ROOT / 'runs' / a.name
srv = configs.prepare(a.config, run, ROOT / 'runs' / sc['template'], PORT, a.xmx, sc['view'], sc['sim'])
result = dict(configs.describe(a.config), scenario=a.scenario, name=a.name, xmx=a.xmx, threads=a.threads,
              cpu=psutil.cpu_count(), template_meta=meta, params={k: v for k, v in sc.items() if k != 'bots'},
              samples=[], events=[])
T0 = None


def event(msg, **kw):
    t = round(time.monotonic() - T0, 1) if T0 else 0.0
    result['events'].append(dict(t=t, msg=msg, **kw))
    print('[%7.1f] %s %s' % (t, msg, json.dumps(kw) if kw else ''), flush=True)


bots = {}
stop = threading.Event()
try:
    me = psutil.Process()
    if a.threads:
        # Windows hands the parent's affinity to the child; the JVM sizes its pools from it.
        me.cpu_affinity(list(range(a.threads)))
    t_boot = time.monotonic()
    srv.start()
    if a.threads:
        me.cpu_affinity(list(range(psutil.cpu_count())))
    srv.wait_ready(timeout=1200)
    result['boot_seconds'] = round(time.monotonic() - t_boot, 1)
    proc = psutil.Process(srv.proc.pid)
    result['server_affinity'] = len(proc.cpu_affinity())
    proc.cpu_percent(None)
    me.cpu_percent(None)
    T0 = time.monotonic()

    def join(names_spots):
        new = []
        for name, (x, z) in names_spots:
            b = mc.Bot(name, PORT, view_distance=sc['view'])
            b.connect()
            bots[name] = b
            new.append((b, x, z))
        mc.wait_until(lambda: all(b.loaded or b.error for b, _, _ in new),
                      900 if a.scenario == 'scaling' else 300, what='join')
        for b, x, z in new:
            if b.error:
                raise RuntimeError('%s: %s' % (b.name, b.error))
            if abs(b.pos[0] - x - 0.5) > 3 or abs(b.pos[2] - z - 0.5) > 3:
                raise RuntimeError('%s not at its spot: %s' % (b.name, b.pos))
            if a.scenario != 'worldgen' or b.name == sc.get('anchor', ('',))[0]:
                b.watch_clock([(x, sc['clock_y'], z), (x + 1, sc['clock_y'], z)])
        return new

    def sampler():
        last = {}
        while not stop.is_set():
            time.sleep(1.0)
            row = {'t': round(time.monotonic() - T0, 2), 'cpu': round(proc.cpu_percent(None) / psutil.cpu_count(), 1),
                   'rss_gb': round(proc.memory_info().rss / 2 ** 30, 2),
                   'bots_cpu': round(me.cpu_percent(None) / psutil.cpu_count(), 1), 'bots': {}}
            for name, b in list(bots.items()):
                c = len(b.clock_times)
                row['bots'][name] = [c - last.get(name, (0, 0))[0], b.chunks - last.get(name, (0, 0))[1],
                                     1 if b.error else 0]
                last[name] = (c, b.chunks)
            result['samples'].append(row)

    threading.Thread(target=sampler, daemon=True).start()

    if a.scenario == 'scaling':
        join(list(zip(meta['bots'], meta['spots'])))
        event('joined', bots=len(bots))
        time.sleep(sc['settle'])
        event('measure')
        time.sleep(sc['measure'])
        event('end')
    else:
        if 'anchor' in sc:
            join([sc['anchor']])
            event('anchor joined')
            time.sleep(20)
        order = [(sc['bots'][i], sc['spots'][i]) for i in sc['order']]
        step = 0
        while order:
            batch, order = order[:sc['per_step']], order[sc['per_step']:]
            step += 1
            t_step = time.monotonic()
            event('step', n=step, players=len(bots) + len(batch))
            join(batch)
            event('step joined', n=step, join_seconds=round(time.monotonic() - t_step, 1))
            errs = [b.name for b in bots.values() if b.error]
            if errs:
                raise RuntimeError('bot errors: %s' % errs)
            if srv.proc.poll() is not None:
                raise RuntimeError('server exited %s' % srv.proc.returncode)
            time.sleep(max(0.0, sc['step'] - (time.monotonic() - t_step)))
        time.sleep(10)
    stop.set()
    try:
        result['entities_end'] = srv.rcon('execute if entity @e[type=!minecraft:player]').strip()
    except Exception as e:
        result['entities_end'] = 'n/a: %r' % e
    result['status'] = 'ok'
    event('done', entities=result['entities_end'])
except Exception as e:
    result['status'] = 'error: %r' % e
    event('ERROR', err=repr(e))
finally:
    stop.set()
    for b in bots.values():
        b.close()
    result['exit_code'] = srv.stop(timeout=180) if srv.proc else None
    result['bot_errors'] = {n: b.error for n, b in bots.items() if b.error}
    (run / 'bench.json').write_text(json.dumps(result), encoding='utf-8')
    shutil.rmtree(run / 'world', ignore_errors=True)
    print('RESULT', a.name, result['status'], 'exit', result['exit_code'], flush=True)
