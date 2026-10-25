"""Teleport storm: command teleports racing island topology rebuilds.

Usage: python teleport_storm.py NAME [--config fabrolia-async-lithium-c2me] [--bots 16]
       [--seconds 120] [--port 26720]

Players stand in the pens of the benchmark world (prep_world.py) while RCON teleports
them, back to back, into other pens and onto ground without an island. Each same-level
teleport is an audited A5 lifecycle mutation with its own Main handoff; each move can
make the next level tick rebuild the islands before that handoff has drained (the
`A5 topology-gap handoff lost its exact player/session binding` crash of 3.0.1, fixed in
3.1.13). Passes when the server survives, every player stays connected, the server stops
cleanly and its log has no unexpected ERROR/FATAL line. A crash report or an ERROR line
(a Fabrolia fail-stop leaves the process running) ends the run at once; vanilla's RCON
stop would wait forever on the command in flight.
Writes runs/NAME/teleport-storm-report.json; exit code 1 on failure.
"""
import argparse
import hashlib
import json
import math
import random
import shutil
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import configs  # noqa: E402
import mc  # noqa: E402
import scenario as sc  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument('name')
ap.add_argument('--config', default='fabrolia-async-lithium-c2me',
                choices=sorted(c for c in configs.CONFIGS if c.startswith('fabrolia-async')))
ap.add_argument('--bots', type=int, default=16)
ap.add_argument('--seconds', type=float, default=120)
ap.add_argument('--port', type=int, default=26720)
ap.add_argument('--seed', type=int, default=1)
ap.add_argument('--anchors', action='store_true',
                help='one player stays in every pen and the others move between pens only, '
                     'so no teleport ends in a cell without an island')
ap.add_argument('--no-playerticks', action='store_true',
                help='async islands with players ticking on Main (enablePlayerTickActor = false)')
a = ap.parse_args()

ROOT = mc.HOME
run = ROOT / 'runs' / a.name
if run.exists():
    raise SystemExit('run exists: %s' % run)
srv = configs.prepare(a.config, run, ROOT / 'runs' / 'world-template', a.port, '6G',
                      view_distance=6, simulation_distance=6)
srv.write_properties(gamemode='creative', force_gamemode='true', view_distance=6, simulation_distance=6,
                     difficulty='easy', max_players=a.bots + 8)
if a.no_playerticks:
    toml = run / 'config' / 'fabrolia.toml'
    toml.write_text(toml.read_text().replace('enablePlayerTickActor = true', 'enablePlayerTickActor = false'))
report = {'name': a.name, **configs.describe(a.config), 'jar_under_test': configs.FABROLIA.name,
          'jar_sha256': hashlib.sha256(configs.FABROLIA.read_bytes()).hexdigest(), 'params': vars(a),
          'teleports': 0, 'checks': []}

# Pen interiors, and open ground between neighbouring pens where no player stands.
TARGETS = [(cx + 6, sc.FLOOR_Y + 1, cz + 6) for cx, cz in sc.CENTERS]
for i in range(len(sc.CENTERS)):
    ang = (i + 0.5) * math.pi / 4
    TARGETS.append((int(1000 * math.cos(ang)), 200, int(1000 * math.sin(ang))))


def check(name, ok, detail=''):
    report['checks'].append({'check': name, 'ok': bool(ok), 'detail': str(detail)[:800]})
    print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail)[:400] if detail else ''), flush=True)


def crash_reports():
    folder = run / 'crash-reports'
    return sorted(p.name for p in folder.glob('*')) if folder.exists() else []


bots = []
stop = threading.Event()
crashed = threading.Event()
try:
    srv.start()
    srv.wait_ready(timeout=1200)
    names = ['T%03d' % (i + 1) for i in range(a.bots)]
    if a.anchors:
        # prep_world.py saved Bench1..8 inside their pens: they join there
        # without a teleport, so even the set-up never leaves an ownerless cell.
        names = list(sc.BOTS) + names
    for n in names:
        b = mc.Bot(n, a.port, view_distance=6)
        b.connect()
        bots.append(b)
    mc.wait_until(lambda: all(b.loaded or b.error for b in bots), 300, what='join')
    check('players joined', all(b.loaded and not b.error for b in bots), [(b.name, b.error) for b in bots if b.error])
    rng = random.Random(a.seed)
    movers, targets = bots, TARGETS
    if a.anchors:
        pens = len(sc.CENTERS)
        anchors = bots[:pens]
        placed = [b.name for b, (cx, cz) in zip(anchors, sc.CENTERS)
                  if b.pos and abs(b.pos[0] - cx) < sc.HALF and abs(b.pos[2] - cz) < sc.HALF]
        check('anchors joined inside their pens', len(placed) == pens, [(b.name, b.pos) for b in anchors])
        time.sleep(10)
        movers, targets = bots[pens:], TARGETS[:pens]
        report['anchors'] = placed

    def storm():
        end = time.monotonic() + a.seconds
        try:
            while time.monotonic() < end and not stop.is_set():
                b = rng.choice(movers)
                x, y, z = rng.choice(targets)
                srv.rcon('tp %s %d %d %d' % (b.name, x + rng.randint(-3, 3), y, z + rng.randint(-3, 3)))
                report['teleports'] += 1
        except Exception as e:  # the killed server closes RCON
            if not crashed.is_set():
                report['storm_error'] = repr(e)

    log_pos = [0]

    def new_log_errors():
        """ERROR/FATAL lines appended to the console since the last call (whole lines only).
        A Fabrolia fail-stop leaves a live process without a crash report."""
        with open(run / 'console.log', 'rb') as fh:
            fh.seek(log_pos[0])
            chunk = fh.read()
        cut = chunk.rfind(b'\n') + 1
        log_pos[0] += cut
        return mc.unexpected_log_errors(chunk[:cut].decode('utf-8', 'replace'))

    th = threading.Thread(target=storm, daemon=True)
    t0 = time.monotonic()
    th.start()
    while th.is_alive():
        th.join(1.0)
        errors = new_log_errors()
        if crash_reports() or srv.proc.poll() is not None or errors:
            report['fatal_log'] = errors[:5]
            crashed.set()
            stop.set()
            srv.proc.kill()
            break
    report['storm_seconds'] = round(time.monotonic() - t0, 1)
    print('teleports', report['teleports'], 'in', report['storm_seconds'], 's', flush=True)
    check('server survived the storm', not crashed.is_set(), crash_reports() or report.get('fatal_log'))
    if not crashed.is_set():
        check('storm ran its full time', 'storm_error' not in report, report.get('storm_error'))
        time.sleep(3)
        check('every player still connected', not [b for b in bots if b.error],
              [(b.name, b.error) for b in bots if b.error])
        for b in bots:
            b.close()
        time.sleep(1)
        rc = srv.stop(timeout=300)
        check('clean stop (exit code 0)', rc == 0, rc)
        unexpected = mc.unexpected_log_errors(srv.log_text())
        check('no unexpected ERROR/FATAL log lines', not unexpected, unexpected[:3])
        check('no crash reports', not crash_reports(), crash_reports())
except Exception as e:
    check('storm set-up and teardown', False, repr(e))
finally:
    stop.set()
    for b in bots:
        b.close()
    if srv.proc and srv.proc.poll() is None:
        if crashed.is_set():
            srv.proc.kill()
        else:
            srv.stop(timeout=300)
    report['crash_reports'] = crash_reports()
    crash = run / 'crash-reports'
    if report['crash_reports']:
        text = (crash / report['crash_reports'][0]).read_text(encoding='utf-8', errors='replace')
        report['crash_description'] = next((l for l in text.splitlines() if 'Exception' in l), '')[:400]
    else:
        log = (run / 'console.log').read_text(encoding='utf-8', errors='replace')
        report['crash_description'] = next((l for l in log.splitlines()
                                            if 'IllegalStateException: A5' in l), '')[:600]
    report['passed'] = bool(report['checks']) and all(c['ok'] for c in report['checks'])
    (run / 'teleport-storm-report.json').write_text(json.dumps(report, indent=1), encoding='utf-8')
    # Keep logs, crash reports and the report; drop the world copy, as bench.py does.
    shutil.rmtree(run / 'world', ignore_errors=True)
    print('RESULT', a.name, 'PASS' if report['passed'] else 'FAIL', report.get('crash_description', ''), flush=True)
sys.exit(0 if report['passed'] else 1)
