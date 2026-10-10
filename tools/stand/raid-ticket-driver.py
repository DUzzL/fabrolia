#!/usr/bin/env python3
"""Compare real 26.3 raid admission and village loss on the isolated stand.

Build with --raid-fixture and pass --expect-no-wave for simulation distance 2.
Use a normal build and --loaded-ring for distance 10, optionally adding
--remove-village-after-start. --expect-old-failure preserves the historical probe.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import time
import traceback

run = Path(sys.argv[1]).resolve()
expect_old = '--expect-old-failure' in sys.argv[2:]
expect_no_wave = expect_old or '--expect-no-wave' in sys.argv[2:]
loaded_ring = '--loaded-ring' in sys.argv[2:]
remove_village = '--remove-village-after-start' in sys.argv[2:]
if os.environ.get('FABROLIA_NATIVE_BURST_ISOLATED') != 'loopback-only':
    raise RuntimeError('Requires the isolated stand launcher')
spec = importlib.util.spec_from_file_location('native_transport', run / 'native-transport.py')
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)
sys.path.insert(0, str(run))
import autosave_check

selected = json.loads((run / 'selected.json').read_text())
if transport.inventory(run / 'server') != selected['input_sha256']:
    raise RuntimeError('Fresh input manifest does not match')
distance = 10 if loaded_ring else 2
if not re.search(r'^simulation-distance=' + str(distance) + '$', (run / 'server/server.properties').read_text(), re.M):
    raise RuntimeError('Simulation distance must match the selected scenario')
recipe = json.loads((run / 'commands.json').read_text())
campaign = transport.Campaign(run, recipe)
report = {'status': 'RUNNING', 'expect_old_failure': expect_old, 'checks': [], 'commands': [],
          'candidate_sha256': selected['fabrolia_sha256'], 'center': [4111, 64, 4111],
          'vanilla_reference': selected.get('vanilla_reference', False), 'simulation_distance': distance,
          'expect_no_wave': expect_no_wave, 'remove_village': remove_village}
PREFIX = 'RAID_TICKET_PROBE'
X = Z = 4111


def ex(command):
    out = campaign.exchange(command, timeout=90)
    report['commands'].append({'command': command, 'out': out})
    return out


def predicate(command):
    return any(PREFIX in line and '[Server]' in line for line in ex(command + ' run say ' + PREFIX))


def check(name, condition):
    report['checks'].append({'name': name, 'pass': bool(condition)})
    print('RAID_TICKET_CHECK', name, 'PASS' if condition else 'FAIL', flush=True)
    if not condition:
        raise AssertionError(name)


def wait_until(name, fn, seconds=90):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        if fn():
            return
        time.sleep(.5)
    raise TimeoutError(name)


def raids():
    ex('save-all flush')
    paths = list((run / 'server/world-native-burst/dimensions/minecraft/overworld').rglob('raids.dat'))
    if len(paths) != 1:
        raise AssertionError('Expected one raid snapshot: ' + str(paths))
    snapshot = autosave_check.read_nbt(paths[0])['data']
    report.setdefault('raid_snapshots', []).append(snapshot)
    return snapshot


def wave_present():
    return predicate(f'execute positioned {X} 64 {Z} if entity '
                     '@e[type=minecraft:pillager,distance=..160]')


try:
    campaign.launch()
    campaign.wait(lambda line: re.search(r'Done \([0-9.]+s\)! For help, type', line), 0,
                  recipe['boot_timeout_seconds'])
    print('RAID_TICKET_PHASE booted', flush=True)
    for c in ('carpet commandPlayer true', 'carpet allowSpawningOfflinePlayers true',
              'gamerule minecraft:advance_time false', 'time set 6000',
              'gamerule minecraft:spawn_mobs false', 'difficulty hard'):
        ex(c)
    if not report['vanilla_reference']: ex('fabrolia regions async on')
    ex(f'player RaidProbe spawn at {X + .5} 64 {Z + .5} facing 0 0 in minecraft:overworld in creative')
    wait_until('player loaded village', lambda: predicate(f'execute if loaded {X} 64 {Z}'))
    ex(f'forceload add {X - 112} {Z - 112} {X + 112} {Z + 112}')
    for dx, dz in ((-106, 0), (106, 0), (0, -106), (0, 106)):
        wait_until('remote platform loaded', lambda dx=dx, dz=dz: predicate(
            f'execute if loaded {X + dx} 64 {Z + dz}'))
    # Flat terrain is at -60: >96 blocks below the raid center, so vanilla
    # rejects it. These are the only eligible surfaces on the outer ring.
    for x0, z0, x1, z1 in ((80, -32, 106, 32), (-106, -32, -80, 32),
                            (-32, 80, 32, 106), (-32, -106, 32, -80)):
        ex(f'fill {X + x0} 63 {Z + z0} {X + x1} 63 {Z + z1} stone')
    ex(f'fill {X - 3} 63 {Z - 3} {X + 3} 67 {Z + 3} glass hollow')
    ex(f'fill {X - 3} 63 {Z - 3} {X + 3} 63 {Z + 3} stone_slab[type=bottom]')
    ex(f'fill {X - 3} 67 {Z - 3} {X + 3} 67 {Z + 3} stone_slab[type=bottom]')
    ex(f'setblock {X} 64 {Z} fletching_table')
    ex(f'summon villager {X - .5} 64 {Z + .5} '
       '{PersistenceRequired:1b,Invulnerable:1b,Tags:["raid_villager"],'
       'VillagerData:{type:"minecraft:plains",profession:"minecraft:none",level:1}}')
    wait_until('villager claimed job site', lambda: any('minecraft:job_site' in line
               for line in ex('data get entity @e[type=villager,tag=raid_villager,limit=1] Brain.memories')))
    # View distance keeps distant chunks FULL. Remove the fixture's entity-
    # ticking tickets before the raid; player simulation distance is only 2.
    ex('forceload remove all')
    check('no_forceloads', any('No force loaded chunks were found' in line for line in ex('forceload query')))
    time.sleep(18)  # let old transient tickets lapse before the comparison
    # The bot may have fallen onto the flat world's ground while we built.
    ex(f'tp RaidProbe {X - .5} 64 {Z + .5}')
    report['player_position'] = ex('data get entity RaidProbe Pos')
    check('no_pillagers_before_raid', not wave_present())
    if not report['vanilla_reference']: report['before'] = ex('fabrolia regions top verbose 100')
    ex('spark profiler start --thread * --interval 4')
    start = time.monotonic()
    ex('effect give RaidProbe minecraft:bad_omen 10 0 true')
    # Vanilla converts Bad Omen in a village into 30 seconds of Raid Omen,
    # then begins the 300-local-tick first-wave countdown.
    time.sleep(35)
    initial = raids()
    report['first_raid_elapsed_seconds'] = time.monotonic() - start
    check('raid_started', bool(initial.get('raids')))
    if remove_village:
        ex(f'setblock {X} 64 {Z} air')
        time.sleep(1)
        check('missing_village_stops_within_one_second', not raids().get('raids'))
    remaining = 65 - (time.monotonic() - start)
    if remaining > 0:
        time.sleep(remaining)
    report['wave_present'] = wave_present()
    if not report['vanilla_reference']: report['after'] = ex('fabrolia regions top verbose 100')
    report['tps'] = ex('spark tps')
    final = raids()
    if expect_no_wave or remove_village:
        check('no_outer_wave_without_admission', not report['wave_present'])
        check('raid_stopped', not final.get('raids'))
    else:
        check('outer_wave_spawned', report['wave_present'])
        check('first_wave_recorded', any(r.get('groups_spawned') == 1 for r in final.get('raids', [])))
        report['raider_before'] = ex('data get entity @e[type=pillager,limit=1,sort=nearest] Pos')
        time.sleep(2)
        report['raider_after'] = ex('data get entity @e[type=pillager,limit=1,sort=nearest] Pos')
    report['profile_stop'] = ex('spark profiler stop --save-to-file')
    wait_until('profile saved', lambda: bool(list((run / 'server/config/spark').glob('*.sparkprofile'))))
    report['spark_profiles'] = [str(p.relative_to(run)) for p in (run / 'server/config/spark').glob('*.sparkprofile')]
    report['status'] = 'BUG_REPRODUCED' if expect_old else 'PASS'
except Exception as error:
    report.update(status='FAIL', error=repr(error), traceback=traceback.format_exc())
finally:
    try:
        campaign.stop()
        report['cleanup'] = campaign.cleanup
        if report['status'] in ('PASS', 'BUG_REPRODUCED'):
            check('clean_shutdown', campaign.cleanup.get('method') == 'native-stop'
                  and campaign.cleanup.get('returncode') == 0)
            check('no_crash_reports', not list((run / 'server/crash-reports').glob('*')))
    except Exception as error:
        report.update(status='FAIL', stop_error=repr(error))
    (run / 'raid-ticket-report.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print('EXPORT_RESULT ' + json.dumps({'status': report['status'], 'error': report.get('error'),
                                       'checks': report['checks']}), flush=True)
sys.exit(0 if report['status'] in ('PASS', 'BUG_REPRODUCED') else 1)
