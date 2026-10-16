"""Summarize completed farm runs from reports and observational logs.

Usage: python -I tools/raid-farm-ab/summarize.py build/raid-farm-ab/NAME [...]
Sent attack counts alone do not validate combat: compare accepted attacks,
onGround, strength and actual sweeps before attributing missing kills to a mod.
"""
import argparse
import collections
import hashlib
import json
import re
from pathlib import Path


def summarize(directory):
    report = json.loads((directory / 'report.json').read_text(encoding='utf-8'))
    lines = (directory / 'raidprobe.log').read_text(encoding='utf-8').splitlines()
    sample = (report.get('samples') or [{}])[-1]
    five_minute = report['samples'][9] if len(report.get('samples', [])) >= 10 else None
    attacks = [line for line in lines if ' PLAYER_ATTACK ' in line]
    releases = [line for line in lines if ' VILLAGER_RELEASE minecraft:meeting_point ' in line]
    instrumented = 'PlayerAttackProbeMixin' in probe_mixins(directory)
    events = {name: sum(marker in line for line in lines) for name, marker in {
        'raids': ' RAID_NEW ', 'waves': ' RAID_WAVE ',
        'victories': ' RAID_STATE VICTORY ', 'losses': ' RAID_STATE LOSS ',
        'bell_releases': ' POI_RELEASE -1, 192, -1 ',
        'sweeps': ' PLAYER_SWEEP ',
        'omen_conversions': ' BAD_OMEN_CONVERT ',
    }.items()}
    events['bad_omen_removed'] = sum(' EFFECT_REMOVED ' in line and 'minecraft:bad_omen' in line for line in lines)
    if not instrumented:
        events['sweeps'] = None  # Older probes did not observe combat.
    clocks = []
    for line in releases:
        match = re.search(r'cantReachSince=(\d+) ctxTime=(\d+) rawTime=(\d+)', line)
        if match:
            stamp, context, raw = map(int, match.groups())
            clocks.append({'age': context - stamp, 'wall_offset': raw - context})
    stops = []
    vacancies = []
    pending_release = None
    for line in lines:
        if ' RAID_STOP ' in line:
            match = re.search(r't=(\d+) th=(.*?) RAID_STOP village=(\S+) raid=(\w+) center=(.*?) lvl=\d+ groups=(\d+) cd=(-?\d+) active=', line)
            if match is None:
                raise ValueError('Unrecognized stop record: ' + line)
            stops.append({'tick': int(match[1]), 'thread': match[2], 'village': match[3],
                          'raid': match[4], 'center': match[5], 'waves': int(match[6]),
                          'countdown': int(match[7])})
        if ' POI_RELEASE -1, 192, -1 ' in line:
            pending_release = int(line.split()[0][2:])
        elif ' POI_ACQUIRE -1, 192, -1 ' in line and pending_release is not None:
            vacancies.append({'release_tick': pending_release,
                              'ticks': int(line.split()[0][2:]) - pending_release})
            pending_release = None
    artifacts = {}
    for jar in sorted((directory / 'mods').glob('*.jar')):
        artifacts[jar.name] = hashlib.sha256(jar.read_bytes()).hexdigest()
    return {
        'run': directory.name, 'status': report.get('status'),
        'minutes': report.get('minutes'), 'parallel': report.get('parallel'),
        'scoreboard': {key: sample.get(key) for key in ('rtrig', 'rwin', 'kp', 'kv', 'ke', 'kw', 'bott')},
        'five_minute_sample': None if five_minute is None else {
            'client_ticks': five_minute['op']['ticks'],
            'scoreboard': {key: five_minute.get(key) for key in ('rtrig', 'rwin', 'kp', 'kv', 'ke', 'kw', 'bott')},
        },
        'events': events,
        'accepted_attacks': len(attacks) if instrumented else None,
        'attack_ground': dict(collections.Counter(
            re.search(r'onGround=(\w+)', line)[1] for line in attacks)),
        'meeting_release_clocks': clocks,
        'bell_vacancies': vacancies,
        'raid_stops': stops,
        'combat_instrumented': instrumented,
        'controlled_bell_gap': json.loads((directory / 'bell-gap-control.json').read_text(encoding='utf-8'))
        if (directory / 'bell-gap-control.json').exists() else None,
        'artifacts_sha256': artifacts,
    }


def probe_mixins(directory):
    import zipfile
    with zipfile.ZipFile(directory / 'mods' / 'raidprobe-1.0.0.jar') as jar:
        return json.loads(jar.read('raidprobe.mixins.json'))['mixins']


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runs', type=Path, nargs='+')
    args = parser.parse_args()
    print(json.dumps([summarize(run) for run in args.runs], indent=2))
