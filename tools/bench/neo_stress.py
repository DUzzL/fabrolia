"""Concurrency stress for loader-specific global state (NeoForge drop and block-snapshot capture).

Usage: python neo_stress.py fabric|neoforge NAME JAR [--minutes 3] [--port 26500]

Four far-away islands each decay ~5800 leaves per refill (random_tick_speed 300: every
leaf drops within seconds, on its island's thread) and run 248 observer clocks; a fifth
player places blocks at 20/s like a client (USE_ITEM_ON -> NeoForge's snapshot capture on
Main) while the islands write blocks. Reports server liveness, crash reports, exception
lines and the clock rate of every island. Writes runs/NAME/stress-report.json.
"""
import argparse
import json
import re
import shutil
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import inputs  # noqa: E402
import mc  # noqa: E402

ROOT = mc.HOME
ap = argparse.ArgumentParser()
ap.add_argument('loader', choices=['fabric', 'neoforge'])
ap.add_argument('name')
ap.add_argument('jar', type=Path)
ap.add_argument('--minutes', type=float, default=3)
ap.add_argument('--port', type=int, default=26500)
ap.add_argument('--sync', action='store_true', help='keep the default (synchronous region) mode')
a = ap.parse_args()

run = mc.copy_template(ROOT / 'tpl' / a.loader, ROOT / 'runs' / a.name)
(run / 'mods').mkdir(exist_ok=True)
shutil.copy2(a.jar, run / 'mods')
if a.loader == 'fabric':
    shutil.copy2(ROOT / 'dl' / inputs.FABRIC_API, run / 'mods')

DECAY = {'Decay1': (2048, 2048), 'Decay2': (-2048, 2048), 'Decay3': (2048, -2048), 'Decay4': (-2048, -2048)}
report = {'loader': a.loader, 'jar': a.jar.name, 'mode': 'sync' if a.sync else 'async', 'events': []}


def ev(msg, **kw):
    t = round(time.monotonic() - T0, 1)
    report['events'].append({'t': t, 'msg': msg, **kw})
    print('[%6.1f] %s %s' % (t, msg, json.dumps(kw) if kw else ''), flush=True)


def leaves(srv, x, z):
    srv.rcon('fill %d 120 %d %d 125 %d minecraft:oak_leaves[distance=7,persistent=false]' % (x - 15, z - 15, x + 15, z + 15))


T0 = time.monotonic()
srv = mc.Server(run, a.loader, a.port, xmx='8G')
srv.write_properties(level_seed='fabrolia-stress', gamemode='creative', view_distance=8, simulation_distance=8)
stop_builder = threading.Event()
placed = [0]
try:
    srv.start()
    srv.wait_ready()
    for c in ('gamerule spawn_mobs false', 'gamerule advance_weather false', 'gamerule random_tick_speed 300'):
        srv.rcon(c)
    if not a.sync:
        ev('async', out=srv.rcon('fabrolia regions async on')[:80])
        ev('playerticks', out=srv.rcon('fabrolia regions playerticks on')[:80])
    bots = {}
    for n in ['Builder'] + list(DECAY):
        bots[n] = mc.Bot(n, a.port, view_distance=8)
        bots[n].connect()
    mc.wait_until(lambda: all(b.loaded or b.error for b in bots.values()), 180, what='join')
    srv.rcon('tp Builder 0.5 150 0.5')
    for n, (x, z) in DECAY.items():
        srv.rcon('tp %s %d 150 %d' % (n, x, z))
    time.sleep(10)
    srv.rcon('fill -4 149 -4 4 149 4 minecraft:stone')
    srv.rcon('item replace entity Builder weapon.mainhand with minecraft:oak_planks')
    for n, (x, z) in DECAY.items():
        leaves(srv, x, z)
        for k in range(8):
            srv.rcon('fill %d 130 %d %d 130 %d minecraft:observer[facing=east]' % (x + 2 * k, z - 15, x + 2 * k, z + 15))
            srv.rcon('fill %d 130 %d %d 130 %d minecraft:observer[facing=west]' % (x + 2 * k + 1, z - 15, x + 2 * k + 1, z + 15))
        bots[n].watch_clock([(x, 130, z - 15), (x + 1, 130, z - 15)])
    ev('fixture built', islands=srv.rcon('fabrolia regions list')[:400])

    def builder():
        b = bots['Builder']
        cols = [(dx, dz) for dx in range(-2, 3) for dz in range(-2, 3) if (dx, dz) != (0, 0)]
        while not stop_builder.is_set():
            for h in range(3):
                for dx, dz in cols:
                    if stop_builder.is_set():
                        return
                    b.use_item_on(dx, 149 + h, dz)
                    placed[0] += 1
                    time.sleep(0.05)
            srv.rcon('fill -2 150 -2 2 152 2 minecraft:air')

    th = threading.Thread(target=builder, daemon=True)
    th.start()
    end = time.monotonic() + a.minutes * 60
    while time.monotonic() < end:
        time.sleep(10)
        if srv.proc.poll() is not None:
            ev('SERVER EXITED', rc=srv.proc.returncode)
            break
        rates = {}
        for n, b in bots.items():
            if n == 'Builder':
                continue
            t = [x for x in b.clock_times if x > time.monotonic() - 10]
            rates[n] = round(2.0 * (len(t) - 1) / (t[-1] - t[0]), 1) if len(t) > 1 else 0.0
        try:
            items = srv.rcon('execute if entity @e[type=minecraft:item]')
            for n, (x, z) in DECAY.items():
                leaves(srv, x, z)
        except Exception as e:
            ev('rcon failed', err=repr(e))
            break
        ev('sample', placed=placed[0], island_tps=rates, items=items[-12:],
           bot_errors={n: b.error for n, b in bots.items() if b.error})
    stop_builder.set()
    report['alive_at_end'] = srv.proc.poll() is None
finally:
    stop_builder.set()
    rc = srv.stop() if srv.proc and srv.proc.poll() is None else (srv.proc.returncode if srv.proc else None)
    log = srv.log_text()
    report['exit_code'] = rc
    report['crash_reports'] = [p.name for p in (run / 'crash-reports').glob('*')] if (run / 'crash-reports').exists() else []
    report['exception_lines'] = [l[:300] for l in log.splitlines()
                                 if re.search(r'Exception|Error:|FATAL|fail-stop|fatal', l)
                                 and 'Error loading class' not in l][:60]
    report['unexpected_log_errors'] = [l[:300] for l in mc.unexpected_log_errors(log)][:20]
    report['placed'] = placed[0]
    report['passed'] = (bool(report.get('alive_at_end')) and rc == 0 and not report['crash_reports']
                        and not report['unexpected_log_errors'])
    (run / 'stress-report.json').write_text(json.dumps(report, indent=1), encoding='utf-8')
    print('RESULT', a.name, 'PASS' if report['passed'] else 'FAIL', 'exit', rc, 'crashes', report['crash_reports'],
          'exceptions', len(report['exception_lines']), 'log errors', len(report['unexpected_log_errors']), flush=True)
