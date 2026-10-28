"""Functional release check of Fabrolia on a dedicated server (Fabric or NeoForge).

Usage: python functest.py fabric|neoforge NAME [--port 26200]

Boots a fresh world with Fabrolia, logs four protocol clients in (two at spawn, two on
far-away islands), places an observer clock and animals next to each of them and checks:
  - the mod loads, its commands answer without errors,
  - every player's area keeps ticking (clock updates seen by the client),
  - synchronous mode (region threading) and production mode (async islands + player ticks),
  - islands form per player group, dimension change and island-to-island teleport,
  - clean stop with world save, restart with the saved entities and settings.
Writes runs/NAME/functest-report.json.
"""
import argparse
import json
import re
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import inputs  # noqa: E402
import mc  # noqa: E402

ROOT = mc.HOME
DL = ROOT / 'dl'

ap = argparse.ArgumentParser()
ap.add_argument('loader', choices=['fabric', 'neoforge'])
ap.add_argument('name')
ap.add_argument('--port', type=int, default=26200)
ap.add_argument('--vanilla', action='store_true', help='same world steps without Fabrolia (reference)')
a = ap.parse_args()
F = not a.vanilla

run = mc.copy_template(ROOT / 'tpl' / a.loader, ROOT / 'runs' / a.name)
mods = run / 'mods'
mods.mkdir(exist_ok=True)
# The newest build of this checkout for the chosen loader.
jar = max((mc.REPO / a.loader / 'build' / 'libs').glob('fabrolia-*.jar'), key=lambda p: p.stat().st_mtime)
if a.loader == 'fabric':
    shutil.copy2(DL / inputs.FABRIC_API, mods)
if F:
    shutil.copy2(jar, mods)

report = {'loader': a.loader, 'jar': jar.name, 'checks': [], 'commands': []}
ERRORS = ('Unknown or incomplete command', 'Incorrect argument', 'An unexpected error', 'Unknown command')


def check(name, ok, detail=''):
    report['checks'].append({'check': name, 'ok': bool(ok), 'detail': str(detail)[:600]})
    print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail)[:300] if detail else ''), flush=True)
    return ok


def cmd(srv, command, expect_ok=True):
    out = srv.rcon(command)
    report['commands'].append({'command': command, 'out': out[:4000]})
    if expect_ok and any(e in out for e in ERRORS):
        check('command ' + command, False, out)
    return out


def count(srv, selector):
    out = cmd(srv, 'execute if entity ' + selector, expect_ok=False)
    m = re.search(r'Count: (\d+)', out)
    return int(m.group(1)) if m else 0


CENSUS = ('item', 'falling_block', 'chest_minecart', 'cow', 'sheep', 'chicken', 'villager', 'egg')


def census(srv):
    return {t: count(srv, '@e[type=minecraft:%s]' % t) for t in CENSUS}


def boot(srv):
    t0 = time.monotonic()
    srv.start()
    srv.wait_ready()
    return round(time.monotonic() - t0, 1)


def join(names, port):
    bots = {}
    for n in names:
        b = mc.Bot(n, port)
        b.connect()
        bots[n] = b
    mc.wait_until(lambda: all(b.loaded or b.error for b in bots.values()), 180, what='bots joined')
    for n, b in bots.items():
        check('player %s joined' % n, b.loaded and not b.error, b.error or b.pos)
    return bots


def clock_rate(bot, seconds=8):
    bot.clock_times = []
    time.sleep(seconds)
    t = list(bot.clock_times)
    if len(t) < 2:
        return 0.0
    return 2.0 * (len(t) - 1) / (t[-1] - t[0])  # two clock packets per 4 ticks -> TPS


SPOTS = {'Near1': (16, 0), 'Near2': (40, 24), 'FarA': (3008, 3008), 'FarB': (-3008, 3008)}

# debugCommands: the synchronous section flips the mode live with `regions async off`,
# which exists only on a server started with this option.
srv = mc.Server(run, a.loader, a.port, xmx='6G', jvm_extra=['-Dfabrolia.debugCommands=true'])
srv.write_properties(level_seed='fabrolia-functest', gamemode='creative', view_distance=8,
                     simulation_distance=8, difficulty='easy')
report['completed'] = False
try:
    report['boot_seconds'] = boot(srv)
    log = srv.log_text()
    if F:
        check('server started with Fabrolia loaded', re.search(r'(?i)fabrolia', log), report['boot_seconds'])
        for c in ('fabrolia', 'fabrolia help', 'fabrolia stats', 'fabrolia regions'):
            cmd(srv, c)
        # Async is the default; select the synchronous control (debug command, not
        # saved) so the two sections still exercise different schedulers.
        cmd(srv, 'fabrolia regions playerticks off')
        cmd(srv, 'fabrolia regions async off')
    for rule in ('spawn_mobs false', 'advance_weather false'):
        cmd(srv, 'gamerule ' + rule)

    bots = join(list(SPOTS), a.port)
    for n, (x, z) in SPOTS.items():
        cmd(srv, 'tp %s %d 150 %d' % (n, x, z))
    time.sleep(10)
    for n, (x, z) in SPOTS.items():
        b = bots[n]
        check('player %s received chunks' % n, b.chunks > 100, b.chunks)
        cmd(srv, 'fill %d 140 %d %d 140 %d minecraft:grass_block' % (x - 8, z - 8, x + 8, z + 8))
        clock = [(x, 200, z), (x + 1, 200, z)]
        b.watch_clock(clock)
        cmd(srv, 'setblock %d 200 %d minecraft:observer[facing=east]' % (x, z))
        cmd(srv, 'setblock %d 200 %d minecraft:observer[facing=west]' % (x + 1, z))
        for i in range(20):
            cmd(srv, 'summon minecraft:%s %d 141 %d' % (('cow', 'sheep', 'chicken', 'villager')[i % 4],
                                                         x - 6 + (i % 5) * 3, z - 6 + (i // 5) * 3))

    # Synchronous configuration: region threading, no async islands/player actor.
    if F:
        out = cmd(srv, 'fabrolia regions')
        report['regions_sync'] = out
        plain = re.sub(r'\u00a7.', '', out)
        check('synchronous mode: async islands are off',
              re.search(r'Async islands[^\n]*\boff\b', plain, re.I), out[:500])
        check('synchronous mode: player actor is off',
              re.search(r'Players tick on their island[^\n]*\boff\b', plain, re.I), out[:500])
    for n in SPOTS:
        r = clock_rate(bots[n])
        mode = 'synchronous mode' if F else 'vanilla'
        check(mode + ': area of %s ticks at full speed' % n, r > 18.5, '%.2f TPS' % r)
    report['census_after_setup'] = census(srv)
    print('census', report['census_after_setup'], flush=True)
    if not F:
        raise SystemExit(0)
    # Production mode: async islands + players tick on their island.
    cmd(srv, 'fabrolia regions async on')
    cmd(srv, 'fabrolia regions playerticks on')
    time.sleep(5)
    out = cmd(srv, 'fabrolia regions list')
    report['island_list'] = out
    m = re.search(r'Overworld \((\d+)\)', out)
    islands = int(m.group(1)) if m else 0
    check('async mode: islands formed', islands >= 3, out[:500])
    for n in SPOTS:
        r = clock_rate(bots[n])
        check('async mode: area of %s ticks at full speed' % n, r > 18.5, '%.2f TPS' % r)
    for n in SPOTS:
        out = cmd(srv, 'fabrolia regions player ' + n)
        check('regions player %s answers' % n, 'TPS' in out or 'tps' in out, out[:200])
    for c in ('fabrolia regions top', 'fabrolia regions top 5 tps', 'fabrolia regions top verbose 100',
              'fabrolia regions crossdim', 'fabrolia regions islandentities', 'fabrolia regions playerticks',
              'fabrolia stats entity', 'fabrolia stats entity 10 20', 'fabrolia config synchronizedEntities',
              'fabrolia functions plan', 'tick query'):
        cmd(srv, c)

    # Island-to-island teleport and a dimension round trip.
    cmd(srv, 'tp FarA -3008 150 3012')
    time.sleep(4)
    check('teleport island->island accepted by client', bots['FarA'].teleports >= 3, bots['FarA'].teleports)
    cmd(srv, 'execute in minecraft:the_nether run tp FarB 0 100 0')
    time.sleep(6)
    cmd(srv, 'execute in minecraft:overworld run tp FarB -3008 150 3008')
    time.sleep(6)
    out = cmd(srv, 'execute as FarB run data get entity @s Dimension')
    check('nether round trip', 'overworld' in out, out)
    bots['FarB'].watch_clock([(-3008, 200, 3008), (-3007, 200, 3008)])
    r = clock_rate(bots['FarB'])
    check('area ticks after dimension round trip', r > 18.5, '%.2f TPS' % r)

    # Live switch back and forth.
    cmd(srv, 'fabrolia regions islandentities parallel')
    time.sleep(3)
    r = clock_rate(bots['Near1'])
    check('parallel island entities: still full speed', r > 18.5, '%.2f TPS' % r)
    cmd(srv, 'fabrolia regions islandentities vanilla')

    animals_before = count(srv, '@e[type=!minecraft:player,type=!minecraft:item,type=!minecraft:falling_block]')
    report['census_before_stop'] = census(srv)
    for b in bots.values():
        check('player %s still connected' % b.name, not b.error, b.error)
    cmd(srv, 'save-all flush')
    # Main wait ledger: RCON commands (the save above among them) run under the command fence,
    # so the fence section has that caller.
    out = cmd(srv, 'fabrolia regions debug fallbacks')
    report['fallbacks'] = out
    plain = re.sub(r'§.', '', out)
    check('fallbacks report lists the Main wait sections',
          all(s in plain for s in ('quiesceAllExcept', 'mainClaim', 'waitForRemaining')), plain[:500])
    check('fallbacks report names the command fence caller', 'fabrolia$fenceMainCommands' in plain,
          plain[:1500])
    for b in bots.values():
        b.close()
    time.sleep(2)
    rc = srv.stop()
    log = srv.log_text()
    check('clean stop (exit code 0)', rc == 0, rc)
    check('world saved on stop', 'Saving worlds' in log or 'All dimensions are saved' in log)
    report['log_errors_run1'] = [l for l in log.splitlines() if re.search(r'ERROR|Exception|FATAL', l)][:80]
    unexpected = mc.unexpected_log_errors(log)
    check('no unexpected ERROR/FATAL log lines (first run)', not unexpected, unexpected[:3])

    # Restart: saved entities survive and the server is back in the default async mode.
    srv = mc.Server(run, a.loader, a.port, xmx='6G')
    report['reboot_seconds'] = boot(srv)
    bots = join(list(SPOTS), a.port)
    time.sleep(8)
    animals_after = count(srv, '@e[type=!minecraft:player,type=!minecraft:item,type=!minecraft:falling_block]')
    report['census_after_restart'] = census(srv)
    check('entities persisted across restart', animals_after == animals_before > 0,
          '%s -> %s' % (animals_before, animals_after))
    out = cmd(srv, 'fabrolia stats')
    report['stats_after_restart'] = out
    out = cmd(srv, 'fabrolia regions list')
    check('async islands active after restart', re.search(r'Overworld \([1-9]', out), out[:300])
    for b in bots.values():
        b.close()
    time.sleep(1)
    rc = srv.stop()
    log = srv.log_text()
    check('second clean stop', rc == 0, rc)
    report['log_errors_run2'] = [l for l in log.splitlines() if re.search(r'ERROR|Exception|FATAL', l)][:80]
    unexpected = mc.unexpected_log_errors(log)
    check('no unexpected ERROR/FATAL log lines (restart)', not unexpected, unexpected[:3])
    crashes = list((run / 'crash-reports').glob('*')) if (run / 'crash-reports').exists() else []
    check('no crash reports', not crashes, [c.name for c in crashes])
    report['completed'] = True
finally:
    if srv.proc and srv.proc.poll() is None:
        srv.stop()
    report['passed'] = (report['completed'] or not F) and all(c['ok'] for c in report['checks'])
    (run / 'functest-report.json').write_text(json.dumps(report, indent=1), encoding='utf-8')
    print('RESULT', a.loader, 'ALL PASS' if report['passed'] else 'FAILURES',
          sum(1 for c in report['checks'] if not c['ok']), flush=True)
