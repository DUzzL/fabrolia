"""Compare saved mob benchmark runs in the same measurement window.

Run from the repo with Python 3. Defaults match this workspace. CPU values in
bench.json are percent of all 16 logical CPUs, not one CPU. TPS values are
per-second protocol clock counts multiplied by two.
"""
import argparse
import hashlib
import json
import statistics
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--runs', type=Path,
                   default=Path(r'C:\Users\duzzl\Documents\Neuer Ordner\fabrolia-release\runs'))
    p.add_argument('--output', type=Path,
                   default=Path(__file__).with_name('historical-bench-summary.json'))
    p.add_argument('--names', nargs='+', default=['mobs-3112-jfr', 'mobs-b1-jfr'])
    a = p.parse_args()
    out = {'window_seconds': [60, 690], 'window_end_exclusive': True,
           'limitations': ['One sequential desktop run per build, not a repeated experiment.',
                           'Eight stationary clients, not the production 40-client stand.',
                           'Identify builds by their SHA-256, not only by the jar filename.',
                           'Match scenario parameters and heap size before interpreting a comparison.',
                           'One-second TPS minima are noisy clock packet measurements.'],
           'runs': {}}
    for name in a.names:
        path = a.runs / name / 'bench.json'
        r = json.loads(path.read_text(encoding='utf-8'))
        samples = [s for s in r['samples'] if 60 <= s['t'] < 690]
        means = [2 * statistics.mean(s['clock']) for s in samples]
        jar = next((a.runs / name / 'mods').glob('fabrolia-*.jar'))
        out['runs'][name] = {
            'bench_json': str(path.resolve()), 'bench_json_sha256': sha(path),
            'jar': str(jar.resolve()), 'jar_sha256': sha(jar),
            'config': r['config'], 'mods': r['mods'], 'params': r['params'],
            'xmx': r['xmx'], 'logical_cpus': r['cpu'],
            'status': r['status'], 'exit_code': r['exit_code'],
            'spawned': r['spawned'], 'entities_end': r['entities_end'],
            'samples': len(samples),
            'mean_process_cpu_percent_all_cpus': statistics.mean(s['cpu'] for s in samples),
            'mean_tps_all_clients': statistics.mean(means),
            'min_one_second_tps_mean_all_clients': min(means),
            'min_one_second_tps_one_client': min(2 * c for s in samples for c in s['clock']),
            'max_client_errors': max(s['errors'] for s in samples),
        }
    old = next(iter(out['runs'].values()))
    out['comparisons_vs_first_run'] = {}
    for name, new in list(out['runs'].items())[1:]:
        out['comparisons_vs_first_run'][name] = {
            'mean_process_cpu_reduction_percent': 100 * (
                1 - new['mean_process_cpu_percent_all_cpus'] / old['mean_process_cpu_percent_all_cpus']),
            'matching_params': old['params'] == new['params'],
            'matching_config': old['config'] == new['config'],
            'matching_xmx': old['xmx'] == new['xmx'],
            'matching_logical_cpus': old['logical_cpus'] == new['logical_cpus'],
        }
    a.output.write_text(json.dumps(out, indent=2) + '\n', encoding='utf-8')
    print(a.output.resolve())


if __name__ == '__main__':
    main()
