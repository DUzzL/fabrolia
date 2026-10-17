"""Compare the raid frames changed by 3.1.6 in all-thread local farm profiles.

Usage: python tools/raid-farm-ab/profile_costs.py OLD.sparkprofile NEW.sparkprofile
Inclusive rows overlap; do not sum them. Sampling zeros are below resolution.
"""
import argparse
import hashlib
import json
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'spark'))
import core  # noqa: E402

TARGETS = ('RaidIslandDispatch.service', 'RaidIslandDispatch.tickOwned',
           'fabrolia$nearby', 'fabrolia$lookupPublishedRaid',
           'CopyOnWriteInt2ObjectMap.mutableValuesIterator',
           'CopyOnWriteInt2ObjectMap$1.remove',
           'CopyOnWriteInt2ObjectMap.values', 'Int2ObjectConcurrentHashMap.values')


def costs(path):
    analysis = core.Analysis(core.load(path), str(path))
    seconds = sum(max(0, min(analysis.md.end_time, analysis.window_start(k) + analysis.window_duration(k))
                      - max(analysis.md.start_time, analysis.window_start(k)))
                  for k in range(len(analysis.window_ids))) / 1000
    if seconds <= 0:
        raise ValueError('No retained capture duration: ' + str(path))
    totals = defaultdict(lambda: [0.0, 0.0])
    for thread in analysis.threads:
        if 'Region-Worker' not in thread.name and thread.name != 'Server thread':
            continue
        group = 'workers' if 'Region-Worker' in thread.name else 'main'
        for entry in analysis.method_inclusive(thread):
            for target in TARGETS:
                if entry['label'].endswith(target):
                    totals[group, target][0] += entry['total']
                    totals[group, target][1] += entry['excl']
    return {'profile': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'engine': core.SAMPLER_ENGINE.get(analysis.md.sampler_engine),
            'seconds': seconds, 'included_ticks': analysis.norm_ticks,
            'frames': [{
                'group': group, 'frame': target,
                'inclusive_ms_per_second': totals[group, target][0] / seconds,
                'inclusive_ms_per_tick': totals[group, target][0] / analysis.norm_ticks if analysis.norm_ticks else None,
                'self_ms_per_second': totals[group, target][1] / seconds,
            } for group in ('main', 'workers') for target in TARGETS]}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('profiles', type=Path, nargs='+')
    args = parser.parse_args()
    print(json.dumps([costs(path) for path in args.profiles], indent=2))
