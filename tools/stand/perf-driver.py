#!/usr/bin/env python3
"""Production-like load on a flat 26.3 world with 40 REAL protocol clients
(perf-wire-client.py): 20 in a dense town, 20 spread (Far00-Far04 walk), thousands of
villagers, zombies, cows, item frames and items, the production datapacks. Server and
clients are pinned to separate CPUs (standenv.py). Warm up, run the scenario
functions from options.json, then take a spark profile of every thread
(server/config/spark/*.sparkprofile) and, with options.json heap_histogram, two
`jcmd GC.class_histogram` snapshots (heap-histo-*.txt).

python3 perf-driver.py RUN [PROFILE_SECONDS]
`python3 perf-driver.py --make-pack DIR` writes the perfstand datapack instead.
"""
import importlib.util, json, os, re, subprocess, sys, threading, time
from pathlib import Path

import standenv

Y = -60  # classic flat surface
TOWN = [(x, z) for x in (-96, -48, 0, 48, 96) for z in (-72, -24, 24, 72)]
SPREAD = [(2000 + 600 * i, 2000 + 600 * j) for i in range(5) for j in range(4)]
WALKERS = 5


def make_pack(root):
    root = Path(root) / 'perfstand'
    fn = root / 'data' / 'perfstand' / 'function'
    fn.mkdir(parents=True, exist_ok=True)
    (root / 'pack.mcmeta').write_text(json.dumps(
        {'pack': {'description': 'perf stand', 'min_format': [121, 0], 'max_format': [121, 0]}}))
    items = ['diamond', 'emerald', 'iron_ingot', 'gold_ingot', 'apple', 'bread', 'stick', 'coal']
    town = []
    for i in range(800):  # villagers
        town.append(f'summon villager ~{-80 + (i % 40) * 4} ~ ~{-60 + (i // 40) * 6} {{PersistenceRequired:1b}}')
    for i in range(300):  # zombies, far from the villagers
        town.append(f'summon zombie ~{160 + (i % 20) * 5} ~ ~{-50 + (i // 20) * 6} {{PersistenceRequired:1b}}')
    for i in range(300):
        town.append(f'summon cow ~{-250 + (i % 20) * 5} ~ ~{-50 + (i // 20) * 6}')
    town.append('fill ~-100 ~ ~-92 ~100 ~7 ~-92 minecraft:stone')
    town.append('fill ~-100 ~ ~-84 ~100 ~7 ~-84 minecraft:stone')
    n = 0
    for wall_z, facing in ((-91, 3), (-85, 2)):
        for y in range(8):
            for x in range(-100, 101):
                if n >= 1600:
                    break
                town.append(f'summon item_frame ~{x} ~{y} ~{wall_z} {{Facing:{facing}b,'
                            f'Item:{{id:"minecraft:{items[n % len(items)]}",count:1}}}}')
                n += 1
    for i in range(900):
        town.append(f'summon item ~{-45 + (i % 45) * 2} ~ ~{100 + (i // 45) * 2} '
                    f'{{Age:-32768s,PickupDelay:32767s,Item:{{id:"minecraft:{items[i % len(items)]}",count:1}}}}')
    (fn / 'town.mcfunction').write_text('\n'.join(town) + '\n')
    spread = []
    for i in range(40):
        spread.append(f'summon villager ~{-30 + (i % 10) * 6} ~ ~{-30 + (i // 10) * 6} {{PersistenceRequired:1b}}')
    for i in range(25):
        spread.append(f'summon zombie ~{60 + (i % 5) * 5} ~ ~{-15 + (i // 5) * 6} {{PersistenceRequired:1b}}')
    for i in range(30):
        spread.append(f'summon cow ~{-80 + (i % 6) * 5} ~ ~{-15 + (i // 6) * 6}')
    spread.append('fill ~-40 ~ ~-50 ~40 ~3 ~-50 minecraft:stone')
    for i in range(60):
        spread.append(f'summon item_frame ~{-30 + i % 60} ~{i // 60} ~-49 {{Facing:3b,'
                      f'Item:{{id:"minecraft:{items[i % len(items)]}",count:1}}}}')
    for i in range(40):
        spread.append(f'summon item ~{-20 + (i % 20) * 2} ~ ~{40 + (i // 20) * 2} '
                      f'{{Age:-32768s,PickupDelay:32767s,Item:{{id:"minecraft:{items[i % len(items)]}",count:1}}}}')
    (fn / 'spread.mcfunction').write_text('\n'.join(spread) + '\n')
    print('pack', root, len(town), len(spread))


if sys.argv[1] == '--make-pack':
    make_pack(sys.argv[2])
    sys.exit(0)

run = Path(sys.argv[1]).resolve()
PROFILE_SECONDS = int(sys.argv[2]) if len(sys.argv) > 2 else 120
if os.environ.get('FABROLIA_NATIVE_BURST_ISOLATED') != 'loopback-only':
    raise RuntimeError('Requires loopback-only isolation')
spec = importlib.util.spec_from_file_location('native_transport', run / 'native-transport.py')
transport = importlib.util.module_from_spec(spec); spec.loader.exec_module(transport)
selected = json.loads((run / 'selected.json').read_text())
if transport.inventory(run / 'server') != selected['input_sha256']:
    raise RuntimeError('Fresh input manifest does not match')
recipe = json.loads((run / 'commands.json').read_text())
options = json.loads((run / 'options.json').read_text()) if (run / 'options.json').is_file() else {}
campaign = transport.Campaign(run, recipe)
report = {'status': 'RUNNING', 'phases': {}}
clients = {}
stop_walk = threading.Event()


def ex(command, timeout=None):
    return [l for l in campaign.exchange(command, timeout=timeout, optional=True) if 'NATIVE_BURST_ACK' not in l]


def passed(command):
    return any('Test passed' in l for l in ex(command))


def phase(name):
    report['phases'][name] = time.time()
    print('PERF_PHASE', name, flush=True)


def start_client(name):
    log = open(run / f'wire-{name}.jsonl', 'w')
    argv = [sys.executable, str(run / 'perf-wire-client.py'),
            '--name', name, '--max-seconds', '3000', '--idle-timeout', '300']
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


try:
    campaign.launch()
    time.sleep(2)
    standenv.pin_process(standenv.SERVER_CPUS, campaign.proc.pid)
    campaign.wait(lambda line: re.search(r'Done \([0-9.]+s\)! For help, type', line), 0, recipe['boot_timeout_seconds'])
    # Threads created after the first pin inherit it; repin once for any created before.
    standenv.pin_process(standenv.SERVER_CPUS, campaign.proc.pid)
    phase('booted')
    for c in recipe['setup_commands']:
        campaign.exchange(c, optional=True)
    for c in ('gamerule doDaylightCycle false', 'time set midnight', 'gamerule doWeatherCycle false',
              'fabrolia regions async on', 'defaultgamemode creative'):
        ex(c)
    players = [(f'Town{i:02d}', p) for i, p in enumerate(TOWN)] + [(f'Far{i:02d}', p) for i, p in enumerate(SPREAD)]
    for name, (x, z) in players:
        start_client(name)
        campaign.wait(lambda line, n=name: n + ' joined the game' in line, 0, 120)
        ex(f'gamemode creative {name}')
        ex(f'tp {name} {x} {Y} {z}')
    phase('joined')
    deadline = time.time() + 900
    need = [(0, 0), (-250, 0), (250, 0), (0, -92)] + SPREAD
    while time.time() < deadline and not all(passed(f'execute if loaded {x} {Y} {z}') for x, z in need):
        time.sleep(2)
    phase('loaded')
    ex(f'execute positioned 0 {Y} 0 run function perfstand:town', timeout=600)
    for x, z in SPREAD:
        ex(f'execute positioned {x} {Y} {z} run function perfstand:spread', timeout=300)
    for function in options.get('functions', []):
        report.setdefault('scenarios', {})[function] = ex(f'function {function}', timeout=600)
    walker = threading.Thread(target=walk, args=(players[20:20 + WALKERS],), daemon=True)
    walker.start()
    phase('populated')
    time.sleep(90)
    phase('warm')
    if options.get('heap_histogram'):
        jcmd = str(standenv.java().parent / 'jcmd')
        for label, pause in (('warm', 0), ('warm+60s', 60)):
            time.sleep(pause)
            for what, name in (('GC.class_histogram', 'heap-histo'), ('GC.heap_info', 'heap-info')):
                out = subprocess.run([jcmd, str(campaign.proc.pid), what], capture_output=True, text=True,
                                     timeout=300).stdout
                (run / f'{name}-{label}.txt').write_text(out)
    report['before'] = {'regions': ex('fabrolia regions'), 'top': ex('fabrolia regions top verbose 100')[:8]}
    ex('spark profiler start --thread * --interval 4')
    time.sleep(PROFILE_SECONDS)
    report['tps'] = ex('spark tps')
    report['advstats'] = ex('fabrolia regions debug advstats')
    report['after'] = {'regions': ex('fabrolia regions'), 'top': ex('fabrolia regions top verbose 100')[:8]}
    report['list'] = ex('list')
    report['spark_stop'] = ex('spark profiler stop --save-to-file', timeout=300)
    time.sleep(5)
    with campaign.condition:
        lines = [line for serial, line in campaign.lines]
    report['spark_files'] = [l for l in lines if '.sparkprofile' in l][-3:]
    report['tps_lines'] = [l for l in lines if 'TPS from last' in l or re.search(r'\d+\.\d+, \d+\.\d+, ', l)][-4:]
    phase('profiled')
    report['status'] = 'DONE'
except Exception as error:
    import traceback
    report.update(status='ERROR', error=repr(error), traceback=traceback.format_exc())
finally:
    stop_walk.set()
    for name, proc in clients.items():
        try:
            proc.stdin.write(json.dumps({'cmd': 'close'}) + '\n')
            proc.stdin.flush()
        except Exception:
            pass
    try:
        campaign.stop()
    except Exception as error:
        report.update(stop_error=repr(error))
    for proc in clients.values():
        try:
            proc.wait(10)
        except Exception:
            proc.kill()
    report['cleanup'] = campaign.cleanup
    (run / 'perf-report.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print('EXPORT_RESULT ' + json.dumps({k: report.get(k) for k in ('status', 'error', 'tps_lines', 'spark_files')}),
          flush=True)
sys.exit(0 if report['status'] == 'DONE' else 1)
