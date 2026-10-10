#!/usr/bin/env python3
"""Isolated End-portal regression: held falling-block arrivals, save, restart,
scheduler handoff, and final publication. Uses real portal transfers; gravity
is disabled on the predecessor to hold the critical window deterministically.
Invoke using stand.sh build/run; never attaches to a running external server.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import struct
import sys
import time
import traceback

run = Path(sys.argv[1]).resolve()
expect_old_bug = '--expect-save-bypass' in sys.argv[2:]
if os.environ.get('FABROLIA_NATIVE_BURST_ISOLATED') != 'loopback-only':
    raise RuntimeError('Requires the isolated stand launcher')
spec = importlib.util.spec_from_file_location('native_transport', run / 'native-transport.py')
transport = importlib.util.module_from_spec(spec); spec.loader.exec_module(transport)
sys.path.insert(0, str(run))
import autosave_check

selected = json.loads((run / 'selected.json').read_text())
if transport.inventory(run / 'server') != selected['input_sha256']:
    raise RuntimeError('Fresh input manifest does not match')
recipe = json.loads((run / 'commands.json').read_text())
campaign = None
report = {'status': 'RUNNING', 'checks': [], 'commands': [], 'cleanup': []}
PREFIX = 'END_HANDOFF_PROBE'
OW = 'minecraft:overworld'
END = 'minecraft:the_end'


def ex(command):
    out = campaign.exchange(command, timeout=90)
    report['commands'].append({'command': command, 'out': out})
    return out


def lifecycle(command):
    # Console commands enter inside tickChildren; lifecycle changes require
    # Main's between-tick task venue. RCON schedules there on the isolated server.
    props = (run / 'server/server.properties').read_text()
    port = int(re.search(r'^rcon.port=(\d+)$', props, re.M)[1])
    password = re.search(r'^rcon.password=(.+)$', props, re.M)[1]
    with socket.create_connection(('127.0.0.1', port), timeout=90) as sock:
        def read_exact(size):
            result = b''
            while len(result) < size:
                piece = sock.recv(size - len(result))
                if not piece: raise EOFError('RCON closed')
                result += piece
            return result
        def packet(request_id, kind, body):
            payload = struct.pack('<ii', request_id, kind) + body.encode() + b'\x00\x00'
            sock.sendall(struct.pack('<i', len(payload)) + payload)
            size = struct.unpack('<i', read_exact(4))[0]
            if size < 10 or size > 1024 * 1024: raise ValueError('Invalid RCON packet length')
            data = read_exact(size)
            return struct.unpack('<ii', data[:8]), data[8:-2].decode(errors='replace')
        (request_id, _), _ = packet(1, 3, password)
        if request_id != 1: raise RuntimeError('RCON login refused')
        (_, _), output = packet(2, 2, command)
    report['commands'].append({'command': command, 'venue': 'rcon', 'out': output})
    if 'cannot change' in output or 'unexpected error' in output:
        raise RuntimeError('Lifecycle command refused: ' + output)
    return output


def predicate(command):
    return any(PREFIX in line and '[Server]' in line for line in ex(command + ' run say ' + PREFIX))


def present(tag):
    return predicate(f'execute in {END} positioned 100 50 0 if entity '
                     f'@e[type=minecraft:falling_block,tag={tag},distance=..64]')


def marker_present():
    return predicate(f'execute in {END} if block 98 51 -1 minecraft:emerald_block')


def check(name, condition):
    report['checks'].append({'name': name, 'pass': bool(condition)})
    print('END_HANDOFF_CHECK', name, 'PASS' if condition else 'FAIL', flush=True)
    if not condition: raise AssertionError(name)


def wait_until(name, predicate_fn, seconds=60):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        if predicate_fn(): return
        time.sleep(.25)
    raise TimeoutError(name)


def boot():
    global campaign
    campaign = transport.Campaign(run, recipe)
    campaign.launch()
    campaign.wait(lambda line: re.search(r'Done \([0-9.]+s\)! For help, type', line), 0,
                  recipe['boot_timeout_seconds'])
    print('END_HANDOFF_PHASE booted', flush=True)


def stop_and_archive(label):
    campaign.stop()
    report['cleanup'].append(campaign.cleanup)
    check(label + '_clean_shutdown', campaign.cleanup.get('method') == 'native-stop'
          and campaign.cleanup.get('returncode') == 0)
    campaign.console.close(); campaign.commands.close()
    for name in ('console.log', 'commands.jsonl'):
        (run / name).rename(run / (label + '-' + name))


def send_sand(tag, block):
    ex(f'execute in {OW} run summon falling_block 1000.5 -58.9 1000.5 '
       f'{{BlockState:"minecraft:{block}",Time:-30000,NoGravity:1b,'
       f'Motion:[0.0d,0.0d,0.0d],Tags:["{tag}"]}}')


def saved_queue():
    paths = list((run / 'server' / 'world-native-burst').rglob('end_arrivals.dat'))
    if len(paths) != 1: raise AssertionError('Expected exactly one destination snapshot: ' + str(paths))
    data = autosave_check.read_nbt(paths[0])['data']
    report.setdefault('saved_snapshots', []).append(data)
    return data


try:
    boot()
    ex('fabrolia regions async on')
    ex(f'execute in {OW} run forceload add 1000 1000')
    ex(f'execute in {END} run forceload add 80 -16 127 15')
    wait_until('source chunk', lambda: predicate(f'execute in {OW} if loaded 1000 -59 1000'))
    wait_until('destination chunk', lambda: predicate(f'execute in {END} if loaded 100 50 0'))
    ex(f'execute in {OW} run fill 998 -60 998 1002 -60 1002 minecraft:stone')
    ex(f'execute in {OW} run setblock 1000 -59 1000 minecraft:end_portal')
    # Console has no player for `rate here`. Resolve actual End island handles
    # and require the command receipt, rather than silently assuming a rate.
    handles = set()
    def find_end_islands():
        handles.update(re.findall(r'\[(end-\d+g\d+)\]', '\n'.join(ex('fabrolia regions top verbose 100'))))
        return bool(handles)
    wait_until('End island handles', find_end_islands)
    for handle in sorted(handles):
        receipt = ex(f'fabrolia regions rate handle {handle} 5')
        check('unequal_clocks_' + handle, any('rate request 5.0 tps' in line for line in receipt))
    send_sand('handoff_predecessor', 'sand')
    wait_until('first End arrival', lambda: present('handoff_predecessor'))
    ex(f'execute in {OW} positioned 1000 -59 1000 run kill '
       '@e[type=minecraft:falling_block,tag=handoff_predecessor,distance=..8]')
    ex(f'execute in {END} run setblock 98 51 -1 minecraft:emerald_block')
    send_sand('handoff_successor', 'gravel')
    time.sleep(2)
    ex(f'execute in {OW} positioned 1000 -59 1000 run kill '
       '@e[type=minecraft:falling_block,tag=handoff_successor,distance=..8]')
    check('predecessor_airborne', present('handoff_predecessor'))
    check('successor_held_before_save', not present('handoff_successor'))
    check('platform_unchanged_before_save', marker_present())
    ex('save-all flush')
    if expect_old_bug:
        check('old_save_bypass_reproduced', not marker_present() and present('handoff_successor'))
        report['status'] = 'BUG_REPRODUCED'
    else:
        check('save_keeps_predecessor', present('handoff_predecessor'))
        check('save_keeps_successor_held', not present('handoff_successor'))
        check('save_does_not_regenerate_platform', marker_present())
        data = saved_queue()
        check('held_ops_and_predecessor_are_saved', len(data['pending']) >= 2 and len(data['airborne']) >= 1)
        check('queued_gravel_state_is_saved', any(op.get('entity', {}).get('BlockState') == 'minecraft:gravel'
                                                for op in data['pending']))
        platform_pos = next(op['pos'] for op in data['pending'] if op['kind'] == 'regen')
        landing_y = platform_pos & 4095
        if landing_y >= 2048: landing_y -= 4096
        report['platform_y'] = landing_y
        check('platform_floor_matches_saved_regen', predicate(
            f'execute in {END} if block 100 {landing_y - 1} 0 minecraft:obsidian'))
        ex(f'execute in {END} run forceload remove all')
        check('End_has_no_forceloads', any('No force loaded chunks were found' in line
              for line in ex(f'execute in {END} run forceload query')))
        # Every following boot must recover the End solely using its arrival
        # tickets; no players or persistent forceloads supply visibility.
        stop_and_archive('first-stop')
        check('clean_stop_keeps_snapshot', len(saved_queue()['pending']) >= 2)
        boot()
        wait_until('restored predecessor', lambda: present('handoff_predecessor'))
        time.sleep(2)
        check('restart_keeps_successor_held', not present('handoff_successor'))
        check('restart_does_not_regenerate_platform', marker_present())
        ex('save-all flush')
        check('restored_queue_can_be_saved_again', len(saved_queue()['pending']) >= 2)
        # async -> sync preserves queue ownership; a full scheduler stop uses
        # the Main fallback lane. Both must keep the same predecessor barrier.
        ex('fabrolia regions async off')
        time.sleep(.5)
        check('sync_keeps_successor_held', not present('handoff_successor') and marker_present())
        ex('save-all flush')
        check('sync_queue_can_be_saved_again', len(saved_queue()['pending']) >= 2)
        output = lifecycle('fabrolia regions off')
        wait_until('scheduler stopped', lambda: any('Region scheduler dormant' in line
                   for line in ex('fabrolia regions')))
        check('scheduler_actually_stopped', True)
        time.sleep(.5)
        check('scheduler_stop_keeps_successor_held', not present('handoff_successor') and marker_present())
        ex('save-all flush')
        check('fallback_snapshot_survives_save', len(saved_queue()['pending']) >= 2)
        ex(f'execute in {END} positioned 100 50 0 run kill '
           '@e[type=minecraft:falling_block,tag=handoff_predecessor,distance=..64]')
        wait_until('released successor on Main', lambda: present('handoff_successor'))
        check('regeneration_follows_predecessor_removal', not marker_present())
        # New arrivals while the scheduler is off must join the fallback gate,
        # rather than bypassing the predecessor just published on Main.
        ex(f'execute in {END} run setblock 98 51 -1 minecraft:emerald_block')
        send_sand('handoff_fallback_successor', 'sand')
        time.sleep(2)
        ex(f'execute in {OW} positioned 1000 -59 1000 run kill '
           '@e[type=minecraft:falling_block,tag=handoff_fallback_successor,distance=..8]')
        check('new_fallback_arrival_is_held', not present('handoff_fallback_successor') and marker_present())
        ex('save-all flush')
        check('new_fallback_arrival_is_saved', len(saved_queue()['pending']) >= 2)
        output = lifecycle('fabrolia regions on')
        wait_until('scheduler started', lambda: any('Region scheduler ACTIVE' in line
                   for line in ex('fabrolia regions')))
        check('scheduler_actually_started', True)
        ex('fabrolia regions async on')
        time.sleep(.5)
        check('scheduler_restart_keeps_published_successor', present('handoff_successor'))
        check('scheduler_restart_keeps_new_successor_held', not present('handoff_fallback_successor') and marker_present())
        ex(f'execute in {END} positioned 100 50 0 run kill '
           '@e[type=minecraft:falling_block,tag=handoff_successor,distance=..64]')
        wait_until('released fallback successor', lambda: present('handoff_fallback_successor'))
        check('fallback_cohort_regenerates_after_landing', not marker_present())
        ex('save-all flush')
        check('published_ops_retired_from_snapshot', not saved_queue()['pending'])
        stop_and_archive('second-stop')
        boot()
        wait_until('successor survives final restart', lambda: present('handoff_fallback_successor'))
        check('successor_not_requeued_after_restart', not saved_queue()['pending'])
        ex('scoreboard objectives add handoff_count dummy')
        ex('scoreboard players set arrivals handoff_count 0')
        ex(f'execute in {END} positioned 100 50 0 as '
           '@e[type=minecraft:falling_block,tag=handoff_fallback_successor,distance=..64] '
           'run scoreboard players add arrivals handoff_count 1')
        check('exactly_one_successor_after_restart', predicate(
            'execute if score arrivals handoff_count matches 1'))
        # Restore normal gravity on the recovered block, then prove an actual
        # placement and the next portal regeneration's sand-item harvest.
        ex(f'execute in {END} positioned 100 50 0 run data merge entity '
           '@e[type=minecraft:falling_block,tag=handoff_fallback_successor,distance=..64,limit=1] '
           '{NoGravity:0b,Time:1}')
        wait_until('recovered falling block lands', lambda: predicate(
            f'execute in {END} if block 100 {landing_y} 0 minecraft:sand'))
        check('recovered_block_places_normally', not present('handoff_fallback_successor'))
        send_sand('handoff_harvest', 'sand')
        wait_until('harvest cohort arrives', lambda: present('handoff_harvest'))
        check('next_regeneration_removes_placed_sand', predicate(
            f'execute in {END} if block 100 {landing_y} 0 minecraft:air'))
        check('next_regeneration_drops_sand_item', predicate(
            f'execute in {END} positioned 100 50 0 if entity '
            '@e[type=minecraft:item,distance=..16,nbt={Item:{id:"minecraft:sand"}}]'))
        report['status'] = 'PASS'
except Exception as error:
    report.update(status='FAIL', error=repr(error), traceback=traceback.format_exc())
finally:
    if campaign is not None:
        try:
            campaign.stop()
            report['cleanup'].append(campaign.cleanup)
            if report['status'] in ('PASS', 'BUG_REPRODUCED'):
                check('final_clean_shutdown', campaign.cleanup.get('method') == 'native-stop'
                      and campaign.cleanup.get('returncode') == 0)
                check('no_crash_reports', not list((run / 'server/crash-reports').glob('*')))
        except Exception as error:
            report['status'] = 'FAIL'
            report['stop_error'] = repr(error)
    (run / 'end-handoff-report.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print('EXPORT_RESULT ' + json.dumps({'status': report['status'], 'error': report.get('error'),
                                       'checks': report['checks']}), flush=True)
sys.exit(0 if report['status'] in ('PASS', 'BUG_REPRODUCED') else 1)
