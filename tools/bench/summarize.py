"""Print a compact TPS table of one or more bench.json results (10-second buckets)."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mc  # noqa: E402

ROOT = mc.HOME


def series(res, bucket=10.0):
    """[(t_end, mean_tps, min_tps, max_tps, cpu, spawned)] per bucket; TPS = 2 x clock updates/s."""
    out = []
    samples = res['samples']
    i = 0
    while i < len(samples):
        t0 = samples[i]['t']
        group = [s for s in samples[i:] if s['t'] < t0 + bucket]
        i += len(group)
        if not group:
            break
        dur = group[-1]['t'] - t0 + 1.0
        per_bot = [2.0 * sum(s['clock'][k] for s in group) / dur for k in range(len(group[0]['clock']))]
        out.append((group[-1]['t'], sum(per_bot) / len(per_bot), min(per_bot), max(per_bot),
                    sum(s['cpu'] for s in group) / len(group), group[-1]['spawned'],
                    sum(s['bots_cpu'] for s in group) / len(group)))
    return out


if __name__ == '__main__':
    for name in sys.argv[1:]:
        res = json.loads((ROOT / 'runs' / name / 'bench.json').read_text())
        print('==', name, res['config'], res['status'], 'entities', res.get('entities_end'))
        for t, mean, lo, hi, cpu, sp, bcpu in series(res):
            print('%6.0fs  spawned %5d  TPS mean %5.2f  min %5.2f  max %5.2f  cpu %5.1f%%  bots %4.1f%%'
                  % (t, sp, mean, lo, hi, cpu, bcpu))
