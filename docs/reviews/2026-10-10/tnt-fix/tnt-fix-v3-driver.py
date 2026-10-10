#!/usr/bin/env python3
"""Real 26.3 TNT entity-order, birth, explosion and migration regressions.

Build a fresh isolated run with --rcon; this driver launches and stops that run.
Both /tick step and /tick sprint start frozen and check an independent fuse clock.
Use --compare-report VANILLA_REPORT to compare deterministic trajectories against
the same cases on --vanilla-reference. Random priming motion/fuse is checked by
its per-entity next-tick relation, rather than assuming identical random streams.
"""
import argparse
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import socket
import struct
import sys
import time
import traceback


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    parser.add_argument('--compare-report', type=Path)
    parser.add_argument('--modes', choices=('both', 'step', 'sprint'), default='both')
    return parser.parse_args()


args = arguments()
run = args.run.resolve()
if os.environ.get('FABROLIA_NATIVE_BURST_ISOLATED') != 'loopback-only':
    raise RuntimeError('Requires the isolated stand launcher')
spec = importlib.util.spec_from_file_location('native_transport', run / 'native-transport.py')
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)
selected = json.loads((run / 'selected.json').read_text())
if transport.inventory(run / 'server') != selected['input_sha256']:
    raise RuntimeError('Fresh input manifest does not match')
props = (run / 'server/server.properties').read_text()
if not re.search(r'^enable-rcon=true$', props, re.M):
    raise RuntimeError('Build this isolated run with --rcon')
recipe = json.loads((run / 'commands.json').read_text())
campaign = transport.Campaign(run, recipe)
reference = selected.get('vanilla_reference', False)
comparison = json.loads(args.compare_report.read_text()) if args.compare_report else None
if comparison is not None and (comparison.get('status') != 'PASS'
                               or not comparison.get('vanilla_reference')):
    raise RuntimeError('Comparison must be a passing Vanilla-reference TNT report')
report = {'status': 'RUNNING', 'checks': [], 'commands': [], 'cases': {},
          'candidate_sha256': selected['fabrolia_sha256'], 'vanilla_reference': reference,
          'comparison_report': str(args.compare_report.resolve()) if comparison else None,
          'cell_boundary_x': 128, 'clock': 'actual PrimedTnt.fuse NBT'}
PREFIX = 'TNT_PARITY_PROBE'
OBJECTIVE = 'tntParity'
PULSE_TAG = 'tnt_parity_pulse'
NUMBER = r'[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?'
clock_fuse = 20000


def ex(command):
    output = campaign.exchange(command, timeout=90)
    report['commands'].append({'command': command, 'out': output})
    return output


def lifecycle(command):
    # Lifecycle commands belong between ticks, where RCON schedules its task.
    # The owned, manifest-checked Campaign is the only server this driver uses.
    port = int(re.search(r'^rcon.port=(\d+)$', props, re.M)[1])
    password = re.search(r'^rcon.password=(.+)$', props, re.M)[1]
    with socket.create_connection(('127.0.0.1', port), timeout=90) as sock:
        def read_exact(size):
            data = b''
            while len(data) < size:
                piece = sock.recv(size - len(data))
                if not piece:
                    raise EOFError('RCON closed')
                data += piece
            return data

        def packet(request_id, kind, body):
            payload = struct.pack('<ii', request_id, kind) + body.encode() + b'\x00\x00'
            sock.sendall(struct.pack('<i', len(payload)) + payload)
            size = struct.unpack('<i', read_exact(4))[0]
            if size < 10 or size > 1024 * 1024:
                raise ValueError('Invalid RCON packet length')
            data = read_exact(size)
            return struct.unpack('<ii', data[:8]), data[8:-2].decode(errors='replace')

        (request_id, _), _ = packet(1, 3, password)
        if request_id != 1:
            raise RuntimeError('RCON login refused')
        (_, _), output = packet(2, 2, command)
    report['commands'].append({'command': command, 'venue': 'rcon', 'out': output})
    if 'cannot change' in output or 'unexpected error' in output or 'Unknown' in output:
        raise RuntimeError('Lifecycle command refused: ' + output)
    return output


def check(name, condition):
    report['checks'].append({'name': name, 'pass': bool(condition)})
    print('TNT_PARITY_CHECK', name, 'PASS' if condition else 'FAIL', flush=True)
    if not condition:
        raise AssertionError(name)


def predicate(command):
    return any(PREFIX in line and '[Server]' in line
               for line in ex(command + ' run say ' + PREFIX))


def wait_until(name, fn, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if fn():
            return
        time.sleep(.05)
    raise TimeoutError(name)


def selector(tag=None):
    condition = f'tag={tag}' if tag else f'tag=!{PULSE_TAG}'
    return f'@e[type=minecraft:tnt,{condition},distance=..96,limit=1]'


def entity_data(tag, path):
    output = '\n'.join(ex('execute positioned 128 64 0 run data get entity '
                          + selector(tag) + ' ' + path))
    values = re.findall(r'following entity data: (.+)', output)
    if len(values) != 1:
        raise AssertionError('Expected one entity NBT value: ' + output)
    return values[0].strip()


def fuse(tag):
    value = entity_data(tag, 'fuse')
    if not re.fullmatch(r'-?\d+[sS]?', value):
        raise AssertionError('Unexpected fuse NBT: ' + value)
    return int(value.rstrip('sS'))


def vector(tag, path):
    value = entity_data(tag, path)
    if not re.fullmatch(r'\[\s*' + NUMBER + r'[dDfF]?\s*,\s*' + NUMBER
                       + r'[dDfF]?\s*,\s*' + NUMBER + r'[dDfF]?\s*\]', value):
        raise AssertionError('Unexpected vector NBT: ' + value)
    return [float(part.strip().rstrip('dDfF')) for part in value[1:-1].split(',')]


def state(tag=None):
    return {'pos': vector(tag, 'Pos'), 'motion': vector(tag, 'Motion'), 'fuse': fuse(tag)}


def count_tnt():
    ex(f'scoreboard players set #count {OBJECTIVE} 0')
    ex('execute positioned 128 64 0 as '
       f'@e[type=minecraft:tnt,tag=!{PULSE_TAG},distance=..32] '
       f'run scoreboard players add #count {OBJECTIVE} 1')
    output = '\n'.join(ex(f'scoreboard players get #count {OBJECTIVE}'))
    match = re.search(r'#count has (-?\d+) \[', output)
    if not match:
        raise AssertionError('Unexpected TNT count: ' + output)
    return int(match[1])


def near(actual, expected, tolerance=1e-7):
    if isinstance(actual, list):
        return len(actual) == len(expected) and all(
            near(left, right, tolerance) for left, right in zip(actual, expected))
    return math.isclose(actual, expected, rel_tol=0, abs_tol=tolerance)


def compare(case, snapshot):
    if comparison is None:
        return
    expected = comparison['cases'][case]['observed']
    for key in ('pos', 'motion', 'fuse'):
        check(case + '_vanilla_' + key, near(snapshot[key], expected[key]))


def summon(tag, x, y=64, z=.5, lifetime=80, motion=(0, 0, 0)):
    ex(f'summon minecraft:tnt {x} {y} {z} '
       f'{{fuse:{lifetime}s,NoGravity:1b,Motion:[{motion[0]}d,{motion[1]}d,{motion[2]}d],'
       f'Tags:["{tag}"]}}')


def prepare(mode, case):
    global clock_fuse
    clock_fuse = 20000
    # Remain frozen throughout fixture creation and all observations.
    ex('execute positioned 128 64 0 run kill @e[type=minecraft:tnt,distance=..128]')
    ex('kill @e[type=minecraft:armor_stand,tag=tnt_parity_target]')
    ex('fill 120 60 -8 136 70 8 minecraft:air')
    summon(PULSE_TAG, 96.5, y=100, z=20.5, lifetime=clock_fuse)
    report['cases'][mode + '_' + case] = {'mode': mode, 'case': case}


def advance(mode, count, case):
    global clock_fuse
    # In 26.3, starting sprint from frozen admits one ordinary thaw tick
    # before the requested sprint ticks (verified on the actual reference).
    # Wait for its completion, then assert the physical fuse clock rather
    # than mistaking the command's requested count for completed entity ticks.
    actual_count = count + (1 if mode == 'sprint' else 0)
    clock_fuse -= actual_count
    cursor = campaign.serial
    lifecycle(f'tick {mode} {count}t')
    if mode == 'sprint':
        campaign.wait(lambda line: 'Sprint completed with' in line, cursor, 45)
    wait_until(case + '_clock_completed', lambda: fuse(PULSE_TAG) <= clock_fuse)
    check(case + '_exact_clock_' + str(clock_fuse), fuse(PULSE_TAG) == clock_fuse)
    report['cases'][case].setdefault('advances', []).append({
        'requested': count, 'physical_entity_ticks': actual_count})
    return actual_count


def newborn_vertical(steps):
    y, velocity = 64.0, .2
    drag = struct.unpack('f', struct.pack('f', .98))[0]
    for _ in range(steps):
        velocity -= .04
        y += velocity
        velocity *= drag
    return y, velocity


def impulse_case(mode):
    name = mode + '_older_blast_before_younger_move'
    prepare(mode, 'older_blast_before_younger_move')
    # Older high-color cell A must explode before younger low-color cell B moves.
    summon('tnt_parity_a', 128.5, lifetime=1)
    summon('tnt_parity_b', 127.5)
    advance(mode, 1, name)
    observed = state('tnt_parity_b')
    report['cases'][name]['observed'] = observed
    actual_count = report['cases'][name]['advances'][-1]['physical_entity_ticks']
    check(name + '_fuse', observed['fuse'] == 80 - actual_count)
    check(name + '_impulse_moved_in_same_phase', observed['pos'][0] < 127.49)
    compare(name, observed)


def birth_case(mode):
    name = mode + '_blast_birth_waits_next_entity_phase'
    prepare(mode, 'blast_birth_waits_next_entity_phase')
    # Make the target cell a participant even before the explosion creates TNT.
    ex('summon minecraft:armor_stand 129.5 64 8.5 '
       '{NoGravity:1b,Invulnerable:1b,Marker:1b,Tags:["tnt_parity_target"]}')
    ex('setblock 128 64 0 minecraft:tnt')
    summon('tnt_parity_a', 127.5, lifetime=1)
    actual_count = advance(mode, 1, name)
    check(name + '_one_birth', count_tnt() == 1)
    first = state()
    report['cases'][name]['birth'] = first
    expected_y, expected_motion = newborn_vertical(actual_count - 1)
    if actual_count == 1:
        check(name + '_no_birth_tick_move', near(first['pos'], [128.5, 64, .5]))
    check(name + '_birth_waited_first_phase_vertical_position', near(first['pos'][1], expected_y))
    check(name + '_birth_waited_first_phase_vertical_motion', near(first['motion'][1], expected_motion))
    check(name + '_random_vanilla_short_fuse', 10 - (actual_count - 1) <= first['fuse'] <= 29 - (actual_count - 1))
    next_count = advance(mode, 1, name)
    second = state()
    report['cases'][name]['next_phase'] = second
    check(name + '_next_phase_fuse_steps', second['fuse'] == first['fuse'] - next_count)
    check(name + '_next_phase_motion_steps', near(second['pos'][1],
          newborn_vertical(actual_count - 1 + next_count)[0]))


def repeated_blast_case(mode):
    name = mode + '_two_blasts_prime_one_block_once'
    prepare(mode, 'two_blasts_prime_one_block_once')
    ex('setblock 128 64 0 minecraft:tnt')
    summon('tnt_parity_a', 127.5, z=-.5, lifetime=1)
    summon('tnt_parity_b', 127.5, z=.5, lifetime=1)
    advance(mode, 1, name)
    count = count_tnt()
    report['cases'][name]['tnt_count'] = count
    check(name + '_one_birth', count == 1)
    check(name + '_source_block_removed', predicate('execute if block 128 64 0 minecraft:air'))


def migration_case(mode):
    name = mode + '_migration_keeps_one_fuse_step'
    prepare(mode, 'migration_keeps_one_fuse_step')
    summon('tnt_parity_b', 127.5, motion=(.8, 0, 0))
    actual_count = advance(mode, 8, name)
    observed = state('tnt_parity_b')
    report['cases'][name]['observed'] = observed
    check(name + '_crossed_cell', observed['pos'][0] > 128)
    check(name + '_migration_fuse_steps', observed['fuse'] == 80 - actual_count)
    # Actual TNT air drag is the float 0.98, widened to double by Vanilla.
    drag = struct.unpack('f', struct.pack('f', .98))[0]
    check(name + '_migration_motion_steps', near(observed['pos'][0],
          127.5 + sum(.8 * drag ** tick for tick in range(actual_count))))
    compare(name, observed)


try:
    campaign.launch()
    campaign.wait(lambda line: re.search(r'Done \([0-9.]+s\)! For help, type', line), 0,
                  recipe['boot_timeout_seconds'])
    print('TNT_PARITY_PHASE booted', flush=True)
    for command in ('carpet commandPlayer true', 'carpet allowSpawningOfflinePlayers true',
                    'gamerule minecraft:spawn_mobs false',
                    'gamerule minecraft:advance_time false', 'time set 6000'):
        ex(command)
    if not reference:
        ex('fabrolia regions async on')
    ex('player TNTProbe spawn at 128.5 64 64.5 facing 0 0 in minecraft:overworld in creative')
    ex('forceload add 80 -32 175 79')
    for x, z in ((96, 20), (127, 0), (128, 0), (160, 0)):
        wait_until('loaded fixture chunk', lambda x=x, z=z:
                   predicate(f'execute if loaded {x} 64 {z}'))
    ex('scoreboard objectives add ' + OBJECTIVE + ' dummy')
    # Autonomous islands can run multiple local units during one wall step.
    # Pin this fixture to the wall clock so /tick step measures mechanics at
    # an exact, reproducible unit count; the island coordinator remains active.
    if not reference:
        output = ex('execute positioned 128 64 0 run fabrolia regions coherence add')
        check('fixture_coherence_pin_accepted', any('pinned to the global tick phase' in line for line in output))
        report['step_fixture_cadence'] = 'explicit coherence pin; independent worker ownership retained'
    # Let the fresh ticket/topology and cadence publications settle.
    time.sleep(1)
    lifecycle('tick freeze')
    if not reference:
        report['islands_before'] = ex('fabrolia regions top verbose 100')
    modes = ('step', 'sprint') if args.modes == 'both' else (args.modes,)
    for mode in modes:
        for case in (impulse_case, birth_case, repeated_blast_case, migration_case):
            case(mode)
    check('frozen_observation_clock_is_stable', fuse(PULSE_TAG) == clock_fuse)
    report['status'] = 'PASS'
except Exception as error:
    report.update(status='FAIL', error=repr(error), traceback=traceback.format_exc())
finally:
    try:
        campaign.stop()
        report['cleanup'] = campaign.cleanup
        if report['status'] == 'PASS':
            check('clean_shutdown', campaign.cleanup.get('method') == 'native-stop'
                  and campaign.cleanup.get('returncode') == 0)
            check('no_crash_reports', not list((run / 'server/crash-reports').glob('*')))
    except Exception as error:
        report.update(status='FAIL', stop_error=repr(error))
    (run / 'tnt-parity-report.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print('EXPORT_RESULT ' + json.dumps({'status': report['status'], 'error': report.get('error'),
                                       'checks': report['checks']}), flush=True)
sys.exit(0 if report['status'] == 'PASS' else 1)
