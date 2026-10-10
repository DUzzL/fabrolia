import json
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'tools/spark'))
import core

affected = re.compile(r'com.axalotl.fabrolia.(?:api.fastutil.Long2(?:Int|Long|Byte)ConcurrentHashMap|common.regions.(?:CrossDimArrivals|EndPlatformHandoff|EndArrivalSavedData|StagedEntityArrival))')
results = []
for path in sys.argv[1:]:
    analysis = core.Analysis(core.load(path), path)
    seconds = (analysis.md.end_time - analysis.md.start_time) / 1000
    result = {'profile': path, 'duration_seconds': seconds, 'norm_main_ticks': analysis.norm_ticks, 'groups': {}}
    for name, predicate in [('Main', lambda n: n == 'Server thread'),
                            ('Workers', lambda n: 'Region-Worker' in n)]:
        inclusive = defaultdict(float)
        running = affected_self = 0.0
        for thread in analysis.threads:
            if not predicate(thread.name): continue
            running += thread.running_total()
            affected_self += sum(method['running'] for method in analysis.flat_methods(thread)
                                 if affected.match(method['label']))
            for method in analysis.method_inclusive(thread):
                if affected.match(method['label']): inclusive[method['label']] += method['total']
        result['groups'][name] = {'running_ms_per_wall_second': running / seconds,
            'affected_running_self_ms_per_wall_second': affected_self / seconds,
            'inclusive_frames_ms_per_wall_second': {key: value / seconds for key, value in sorted(inclusive.items())}}
    results.append(result)
print(json.dumps(results, indent=2))
