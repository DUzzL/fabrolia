#!/usr/bin/env python3
"""Run original compact/Nash worlds on 26.3. Control the operator, output loot
and Nash's clock gate. Optionally clear legacy transient villager job-site memories.
Usage: stand.sh run NAME raid-design-driver.py compact|nash-v1|nash-v2 [SECONDS]
Build with --world-zip, optionally --vanilla-reference.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import subprocess
import time
import traceback

run = Path(sys.argv[1]).resolve()
design = sys.argv[2]
seconds = int(sys.argv[3]) if len(sys.argv) > 3 else 240
reset_sites = '--reset-transient-sites' in sys.argv[4:]
require_victory = '--require-victory' in sys.argv[4:]
if design not in {'compact', 'nash-v1', 'nash-v2'} or seconds < 60:
    raise ValueError('Choose compact|nash-v1|nash-v2 and at least 60 seconds')
if os.environ.get('FABROLIA_NATIVE_BURST_ISOLATED') != 'loopback-only':
    raise RuntimeError('Requires isolated stand')
spec = importlib.util.spec_from_file_location('native_transport', run / 'native-transport.py')
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)
sys.path.insert(0, str(run))
import autosave_check

selected = json.loads((run / 'selected.json').read_text())
if not selected.get('world_input') or transport.inventory(run / 'server') != selected['input_sha256']:
    raise RuntimeError('Expected a fresh imported world matching its manifest')
reference = selected['vanilla_reference']
recipe = json.loads((run / 'commands.json').read_text())
campaign = transport.Campaign(run, recipe)
report = {'status': 'RUNNING', 'design': design, 'vanilla_reference': reference,
          'world_input': selected['world_input'], 'seconds': seconds,
          'require_victory': require_victory,
          'candidate_sha256': selected['fabrolia_sha256'], 'checks': [], 'commands': [], 'samples': []}
PREFIX = 'RAID_DESIGN_PROBE'
client = None
client_log = None
sequence = 0
is_nash = design.startswith('nash')
chests = ([(x, 69, z) for x in (-672, -671) for z in range(-56, -51)] if is_nash
          else [(-677, 62, -69), (-677, 62, -68)])


def ex(command):
    out = campaign.exchange(command, timeout=90)
    report['commands'].append({'command': command, 'out': out})
    return out


def predicate(command):
    return any(PREFIX in line and '[Server]' in line for line in ex(command + ' run say ' + PREFIX))


def check(name, condition):
    report['checks'].append({'name': name, 'pass': bool(condition)})
    print('RAID_DESIGN_CHECK', name, 'PASS' if condition else 'FAIL', flush=True)
    if not condition:
        raise AssertionError(name)


def wait_until(name, fn, timeout=120):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if fn(): return
        time.sleep(.5)
    raise TimeoutError(name)


def raid_snapshot():
    ex('save-all flush')
    path = run / 'server/world-native-burst/dimensions/minecraft/overworld/data/minecraft/raids.dat'
    return autosave_check.read_nbt(path)['data']


def operator_state():
    return ex("script run p=player('FarmBot');[query(p,'pos'),query(p,'pose'),"
              "query(p,'eye_height'),query(p,'sneaking'),query(p,'look'),query(p,'trace')]")


def loot():
    rows = []
    total = 0
    for x, y, z in chests:
        out = ex(f'data get block {x} {y} {z} Items')
        for item in re.findall(r'\{[^{}]*\bid: "minecraft:(?:emerald|redstone|totem_of_undying|gunpowder|glowstone_dust)"[^{}]*\}', '\n'.join(out)):
            match = re.search(r'\bcount: (\d+)', item)
            if match: total += int(match[1])
        rows.append({'pos': [x, y, z], 'out': out})
    report.setdefault('loot_snapshots', []).append({'count': total, 'chests': rows})
    return total


def equip():
    ex('clear FarmBot')
    ex('item replace entity FarmBot weapon.mainhand with minecraft:netherite_sword'
       '[minecraft:enchantments={"minecraft:sharpness":5,"minecraft:sweeping_edge":3,"minecraft:looting":3}]')
    for slot, piece in (('head', 'helmet'), ('chest', 'chestplate'), ('legs', 'leggings'), ('feet', 'boots')):
        ex(f'item replace entity FarmBot armor.{slot} with minecraft:netherite_{piece}'
           '[minecraft:enchantments={"minecraft:protection":4,"minecraft:unbreaking":3}]')
    if is_nash:
        # The farm dispenses subsequent bottles on its own 600gt clock.
        # A stack in offhand would reset Raid Omen on every consumption.
        ex('item replace entity FarmBot weapon.offhand with '
           'minecraft:ominous_bottle[minecraft:ominous_bottle_amplifier=1] 1')
        for slot in range(1, 36):
            kind, index = ('hotbar', slot) if slot < 9 else ('inventory', slot - 9)
            ex(f'item replace entity FarmBot {kind}.{index} with minecraft:dirt 64')


def send(command):
    if client.poll() is not None:
        raise RuntimeError('Farm client exited: ' + str(client.returncode))
    client.stdin.write(json.dumps(command) + '\n')
    client.stdin.flush()


def use_bottle(yaw, pitch):
    global sequence
    sequence += 1
    send({'cmd': 'use-item', 'hand': 'OFF_HAND', 'sequence': sequence,
          'yaw': yaw, 'pitch': pitch})


try:
    campaign.launch()
    campaign.wait(lambda line: re.search(r'Done \([0-9.]+s\)! For help, type', line), 0,
                  recipe['boot_timeout_seconds'])
    print('RAID_DESIGN_PHASE booted', flush=True)
    for cmd in ('carpet commandPlayer true', 'carpet allowSpawningOfflinePlayers true',
                'gamerule minecraft:advance_time false', 'time set 6000',
                'gamerule minecraft:spawn_mobs false', 'difficulty hard'):
        ex(cmd)
    if not reference: ex('fabrolia regions async on')
    # Stay outside the output-chest slab and aim south into the killing gap.
    # Minecraft yaw 0 points south; yaw 180 points away from the farm.
    pos = '-671.5 71.1875 -48.5' if is_nash else '-676.5 62.875 -69.5'
    facing = '90 61' if is_nash else '0 12'
    if is_nash:
        # Carpet's fake player runs server-side movement physics. The wire
        # client intentionally does not simulate collision or piston/bubble motion.
        ex(f'player FarmBot spawn at {pos} facing {facing} in minecraft:overworld in creative')
        ex('gamerule minecraft:advance_time true')
    else:
        client_log = open(run / 'wire-FarmBot.jsonl', 'w')
        client = subprocess.Popen([sys.executable, str(run / 'perf-wire-client.py'),
            '--name', 'FarmBot', '--max-seconds', '3000', '--idle-timeout', '600'],
            stdin=subprocess.PIPE, stdout=client_log, stderr=subprocess.STDOUT,
            text=True, bufsize=1)
        campaign.wait(lambda line: 'FarmBot joined the game' in line, 0, 90)
        ex('gamemode creative FarmBot')
        ex(f'tp FarmBot {pos} {facing}')
    wait_until('farm chunks', lambda: predicate('execute if loaded -673 192 -67'))
    if reset_sites:
        if not is_nash: raise ValueError('Transient job-site reset is only for the imported Nash worlds')
        report['transient_site_reset_before'] = ex('execute positioned -675 80 -65 as '
            '@e[type=villager,distance=..160] run data get entity @s Brain.memories')
        report['transient_site_reset'] = ex('execute positioned -675 80 -65 as '
            '@e[type=villager,distance=..160,nbt={Brain:{memories:{"minecraft:potential_job_site":{}}}}] '
            'run data remove entity @s Brain.memories."minecraft:potential_job_site"')
    time.sleep(30)
    ex('execute positioned -675 80 -65 run kill @e[type=#minecraft:raiders,distance=..256]')
    time.sleep(10)
    for x, y, z in chests: ex(f'data remove block {x} {y} {z} Items')
    equip()
    ex(f'tp FarmBot {pos} {facing}')
    ex('gamemode survival FarmBot')
    for objective, criteria in (('RaidPillager', 'minecraft.killed:minecraft.pillager'),
                                ('RaidEvoker', 'minecraft.killed:minecraft.evoker')):
        ex(f'scoreboard objectives add {objective} {criteria}')
    report['initial_loot'] = loot()
    report['initial_raids'] = raid_snapshot()
    report['villagers_before'] = ex('execute positioned -675 80 -65 as '
        '@e[type=villager,distance=..160] run data get entity @s Brain.memories')
    ex('spark profiler start --thread * --interval 4')
    if is_nash:
        ex('player FarmBot use once')
        time.sleep(3)
        ex('player FarmBot attack interval 30')
        # The author's gate is manually closed while powered. Flipping the
        # nearby lever off leaves it closed, so that is not the on switch.
        ex('setblock -669 72 -47 minecraft:oak_fence_gate'
           '[facing=south,in_wall=false,powered=true,open=true]')
    else:
        # Trigger near the bed, then return to the lower killing station.
        ex('tp FarmBot -676.5 88 -65.5 0 0')
        ex('item replace entity FarmBot weapon.offhand with '
           'minecraft:ominous_bottle[minecraft:ominous_bottle_amplifier=2] 1')
        use_bottle(0, 0)
        time.sleep(3)
        report['omen_after_bottle'] = ex('data get entity FarmBot active_effects')
        ex(f'tp FarmBot {pos} {facing}')
        # Hit the raiders through the gap below the open trapdoor. Crouching
        # lowers the eye height so the ray does not hit the trapdoor itself.
        ex('player FarmBot sneak')
        send({'cmd': 'input', 'shift': True})
        ex('player FarmBot attack interval 30')
    report['operator_before'] = operator_state()
    print('RAID_DESIGN_PHASE operating', flush=True)
    start = time.monotonic()
    until = start + seconds
    while time.monotonic() < until:
        time.sleep(min(15, max(0, until - time.monotonic())))
        sample = {'elapsed': time.monotonic() - start, 'raids': raid_snapshot(),
                  'player': ex('data get entity FarmBot Pos'),
                  'health': ex('data get entity FarmBot Health'),
                  'effects': ex('data get entity FarmBot active_effects'),
                  'operator': operator_state(),
                  'kills': ex('scoreboard players list FarmBot')}
        report['samples'].append(sample)
        print('RAID_DESIGN_SAMPLE', round(sample['elapsed']), sample['raids'].get('next_id'),
              len(sample['raids'].get('raids', [])), flush=True)
        # Separately drive the normal Bad Omen input, to avoid Carpet's USE
        # action priority changing the authored 30gt attack sequence.
        if is_nash and not any('minecraft:bad_omen' in line or 'minecraft:raid_omen' in line
                              for line in sample['effects']):
            ex('effect give FarmBot minecraft:bad_omen 6000 1 true')
            report['operator_input'] = 'Carpet movement/30gt attack; Vanilla Bad Omen effect input'
        if any('entity data: 0.0f' in line for line in sample['health']):
            raise AssertionError('operator died; this is not a valid farm comparison')
        if not is_nash and any(r.get('status') == 'victory'
                               for r in sample['raids'].get('raids', [])):
            report['victory_elapsed_seconds'] = sample['elapsed']
            break
    report['final_loot'] = loot()
    report['final_player'] = ex('data get entity FarmBot')
    report['villagers_after'] = ex('execute positioned -675 80 -65 as '
        '@e[type=villager,distance=..160] run data get entity @s Brain.memories')
    report['tps'] = ex('spark tps')
    if not reference: report['regions'] = ex('fabrolia regions top verbose 100')
    ex('player FarmBot attack once')
    if is_nash:
        ex('setblock -669 72 -47 minecraft:oak_fence_gate'
           '[facing=south,in_wall=false,powered=true,open=false]')
    report['profile_stop'] = ex('spark profiler stop --save-to-file')
    wait_until('profile saved', lambda: bool(list((run / 'server/config/spark').glob('*.sparkprofile'))))
    all_raids = [r for s in report['samples'] for r in s['raids'].get('raids', [])]
    report['seen_raid_ids'] = sorted({r['id'] for r in all_raids})
    report['max_groups_spawned'] = max((r['groups_spawned'] for r in all_raids), default=0)
    report['new_raid_ids'] = max(s['raids']['next_id'] for s in report['samples']) - report['initial_raids']['next_id']
    check('actual_raid_created', report['new_raid_ids'] > 0)
    check('actual_wave_spawned', report['max_groups_spawned'] > 0)
    if not is_nash:
        check('native_bottle_effect', any('minecraft:raid_omen' in line or 'minecraft:bad_omen' in line
                                         for line in report['omen_after_bottle']))
    check('new_raid_loot', report['final_loot'] > report['initial_loot'])
    if require_victory: check('complete_raid_victory', 'victory_elapsed_seconds' in report)
    if is_nash: check('multiple_raids_created', report['new_raid_ids'] >= 3)
    report['status'] = 'PASS'
except Exception as error:
    report.update(status='FAIL', error=repr(error), traceback=traceback.format_exc())
finally:
    try:
        if client and client.poll() is None:
            send({'cmd': 'close'})
            client.wait(timeout=15)
    except Exception as error:
        report.update(status='FAIL', client_stop_error=repr(error))
        if client and client.poll() is None:
            client.terminate()
            client.wait(timeout=15)
    if client_log: client_log.close()
    try:
        campaign.stop()
        report['cleanup'] = campaign.cleanup
        if report['status'] == 'PASS':
            check('clean_shutdown', campaign.cleanup.get('method') == 'native-stop'
                  and campaign.cleanup.get('returncode') == 0)
            check('no_crash_reports', not list((run / 'server/crash-reports').glob('*')))
            lines = (run / 'server/logs/latest.log').read_text(errors='replace').splitlines()
            pattern = re.compile(r'IllegalState|Fatal|fail-stop|STALLED|stuck|BARRIER-LEAK|ownership|'
                                 r'Failed to execute function|lacks exact')
            benign = ('anti_ender_dragon_grief', 'Block-attached entity at invalid position',
                      'restored saved player-tick ownership preference')
            report['critical_log_lines'] = [line for line in lines if pattern.search(line)
                                          and not any(word in line for word in benign)]
            check('no_critical_log_lines', not report['critical_log_lines'])
    except Exception as error:
        report.update(status='FAIL', stop_error=repr(error))
    (run / 'raid-design-report.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print('EXPORT_RESULT ' + json.dumps({k: report.get(k) for k in
          ('status', 'error', 'design', 'new_raid_ids', 'max_groups_spawned', 'final_loot')}), flush=True)
sys.exit(0 if report['status'] == 'PASS' else 1)
