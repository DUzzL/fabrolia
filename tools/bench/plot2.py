"""Benchmark charts from the archived results (results/<scenario>/<run>/bench.json).

Usage: python plot2.py RESULTS_DIR OUT_DIR

One chart per scenario with every configuration that was measured in it (black
background, bold lines; Paper and Folia dashed because they are Bukkit servers, not
Fabric mods), lines smoothed with a 3-point moving average, headline numbers and a
ranking computed from the data, and one "at full speed" overview per scenario.
The unsmoothed points go to <scenario>.csv, everything to summary.json.
"""
import csv
import json
import sys
from pathlib import Path

import matplotlib

matplotlib.use('Agg')
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.lines import Line2D  # noqa: E402
from matplotlib.ticker import FuncFormatter, MaxNLocator  # noqa: E402

RES, OUT = Path(sys.argv[1]), Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)

# Every configuration keeps its name, colour and line in every chart.
SERIES = {
    'vanilla': ('Vanilla', '#22d422', '-'),
    'lithium': ('Lithium', '#ff9f1c', '-'),
    'c2me': ('C2ME', '#ffd60a', '-'),
    'lithium-c2me': ('Lithium + C2ME', '#ff3434', '-'),
    'paper': ('Paper', '#ff5fd2', '--'),
    'folia': ('Folia', '#b18cff', '--'),
    'scalablelux': ('ScalableLux', '#b8b8b8', '-'),
    'fabrolia-async': ('Fabrolia', '#2f6bff', '-'),
    'fabrolia-async-scalablelux': ('Fabrolia + ScalableLux', '#8fb3ff', '-'),
    'fabrolia-async-lithium-c2me': ('Fabrolia + Lithium + C2ME', '#00e5ff', '-'),
}
FABROLIA = {'fabrolia-async', 'fabrolia-async-lithium-c2me', 'fabrolia-async-scalablelux'}
BUKKIT = {'paper', 'folia'}
WHITE, GREY = '#ffffff', '#9a9a9a'
FOOT = ('AMD Ryzen 7 5700G (8 cores / 16 threads), 32 GB DDR4-3200, 16 GB heap · Minecraft 26.3 · '
        'lines smoothed (3-point moving average), raw values in docs/benchmark/data · setup: docs/BENCHMARK.md')


def load(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return None


def tps_window(samples, lo, hi, who=None):
    """Mean TPS over (lo, hi]: 2 clock updates per tick pair, averaged over the players."""
    group = [s for s in samples if lo < s['t'] <= hi]
    if len(group) < 3:
        return None
    dur = group[-1]['t'] - group[0]['t'] + 1.0
    if 'clock' in group[0]:
        per = [2.0 * sum(s['clock'][k] for s in group) / dur for k in range(len(group[0]['clock']))]
    else:
        names = who or [n for n in group[0]['bots']]
        per = [2.0 * sum(s['bots'].get(n, [0])[0] for s in group) / dur for n in names if n in group[0]['bots']]
    return min(20.0, sum(per) / len(per)) if per else None


def mob_points(res):
    p = res['params']
    starts = [e['t'] for e in res['events'] if e['msg'] == 'step']
    ends = starts[1:] + [starts[-1] + p['step_seconds'] + p['tail']]
    out = [(0, tps_window(res['samples'], 10.0, starts[0]))]
    for k, (s, e) in enumerate(zip(starts, ends)):
        out.append(((k + 1) * p['per_step'] * 8, tps_window(res['samples'], s + 5.0, e)))
    return [(x, y) for x, y in out if y is not None]


def base_points(res, x_kind):
    """bench2 bases/worldgen: one point per step, players present at the window start."""
    ev = res['events']
    steps = [e for e in ev if e['msg'] == 'step']
    joined = {e['n']: e['t'] for e in ev if e['msg'] == 'step joined'}
    end = next((e['t'] for e in ev if e['msg'] in ('done', 'ERROR')), res['samples'][-1]['t'])
    settle = res['params'].get('settle', 15)
    out = []
    for i, st in enumerate(steps):
        lo = joined.get(st['n'], st['t']) + settle
        hi = steps[i + 1]['t'] if i + 1 < len(steps) else end
        group = [s for s in res['samples'] if lo < s['t'] <= hi]
        if len(group) < 3:
            continue
        present = [n for n in group[0]['bots']]
        if res['scenario'] == 'worldgen':
            tps = tps_window(res['samples'], lo, hi, ['Anchor'])
            explorers = [n for n in present if n != 'Anchor']
            dur = group[-1]['t'] - group[0]['t'] + 1.0
            rate = sum(s['bots'][n][1] for s in group for n in explorers if n in s['bots']) / dur
            out.append((len(explorers), tps, rate))
            continue
        tps = tps_window(res['samples'], lo, hi, present)
        if x_kind == 'chunks':
            loaded = sum(s['bots'][n][1] for s in res['samples'] if s['t'] <= hi for n in s['bots'])
            out.append((loaded, tps))
        else:
            out.append((len(present), tps))
    return [p for p in out if p[1] is not None]


def scaling_points(results):
    """{config: [(threads, entity ticks per second, tps)]} from the sc-* runs."""
    out = {}
    for res in results:
        ev = {e['msg']: e['t'] for e in res['events']}
        if 'measure' not in ev or 'end' not in ev:
            continue
        tps = tps_window(res['samples'], ev['measure'], ev['end'])
        if tps is not None:
            out.setdefault(res['config'], []).append((res['threads'], res['template_meta']['entities'] * tps, tps))
    return {k: sorted(v) for k, v in out.items()}


def smooth(ys):
    """Centred 3-point moving average; the ends average with their one neighbour."""
    out = []
    for i in range(len(ys)):
        w = ys[max(0, i - 1):i + 2]
        out.append(sum(w) / len(w))
    return out


def capacity(points, floor=19.5):
    """Largest x up to which every (unsmoothed) step kept at least `floor` TPS."""
    best = 0
    for x, y, *_ in points:
        if y < floor:
            break
        best = x
    return best


def k_fmt(v, _=None):
    return '%dk' % round(v / 1000) if v >= 1000 else '%d' % v


def ratio_text(r):
    return '%.1fx' % r if r < 10 else '%dx' % round(r)


# ------------------------------------------------------------------ drawing
def chart(name, title, xlabel, ylabel, data, idx, headline, ranking, ylim=None, markers=False, partial=(),
          smoothed=True):
    fig = plt.figure(figsize=(12, 6), dpi=170)
    fig.patch.set_facecolor('black')
    ax = fig.add_axes([0.06, 0.13, 0.60, 0.74])
    ax.set_facecolor('black')
    for s in ax.spines.values():
        s.set_color(WHITE)
        s.set_linewidth(0.8)
    ax.tick_params(colors=WHITE, labelsize=9)
    order = sorted(data, key=lambda k: (k in FABROLIA, list(SERIES).index(k)))  # Fabrolia drawn on top
    for key in order:
        pts = data[key]
        label, color, ls = SERIES[key]
        if key in BUKKIT:
            label += ' (Paper platform, not Fabric)'
        if key in partial:
            label += ' (stopped: players could not join)'
        xs = [p[0] for p in pts]
        ys = smooth([p[idx] for p in pts]) if smoothed else [p[idx] for p in pts]
        ax.plot(xs, ys, color=color, linestyle=ls, linewidth=3.2 if key in FABROLIA else 2.2, label=label,
                marker='o' if markers else None, markersize=4, zorder=3 if key in FABROLIA else 2)
    if ylim:
        ax.set_ylim(*ylim)
    else:
        ax.set_ylim(bottom=0)
    ax.yaxis.set_major_locator(MaxNLocator(nbins=6))
    ax.xaxis.set_major_formatter(FuncFormatter(k_fmt))
    ax.yaxis.set_major_formatter(FuncFormatter(k_fmt))
    ax.set_xlabel(xlabel, color=WHITE, fontsize=10)
    ax.set_ylabel(ylabel, color=WHITE, fontsize=10)
    fig.text(0.36, 0.955, title, color=WHITE, fontsize=14, fontweight='bold', ha='center', va='top')
    # Right column: headline, ranking, legend.
    y = 0.86
    for text, size in headline:
        fig.text(0.69, y, text, color=WHITE, fontsize=size, ha='left', va='top',
                 fontweight='bold' if size >= 14 else 'normal')
        y -= 0.065 if size >= 14 else 0.045
    y -= 0.02
    # The ranking doubles as the legend: a line sample in each configuration's style.
    for key, text in ranking:
        if key:
            _, color, ls = SERIES[key]
            fig.add_artist(Line2D([0.69, 0.715], [y - 0.012, y - 0.012], color=color, linestyle=ls,
                                  linewidth=3.2 if key in FABROLIA else 2.2, transform=fig.transFigure))
            if key in partial:
                text += ' †'
        fig.text(0.722 if key else 0.69, y, text, color=WHITE, fontsize=9, ha='left', va='top', family='monospace')
        y -= 0.036
    notes = []
    if any(k in BUKKIT for k in data):
        notes.append('dashed: Paper platform (Bukkit), not Fabric')
    if partial:
        notes.append('† run stopped early: new players could not join')
    for note in notes:
        y -= 0.01
        fig.text(0.69, y, note, color=GREY, fontsize=8, ha='left', va='top')
        y -= 0.03
    foot = FOOT if smoothed else FOOT.replace('lines smoothed (3-point moving average), ', 'measured points, ')
    fig.text(0.01, 0.012, foot, color=GREY, fontsize=6.5, ha='left', va='bottom')
    fig.savefig(OUT / name, facecolor='black')
    plt.close(fig)
    return name


def capacity_chart(name, title, caps):
    items = sorted(caps.items(), key=lambda kv: kv[1])
    fig, ax = plt.subplots(figsize=(10, 0.42 * len(items) + 1.5), dpi=170)
    fig.patch.set_facecolor('black')
    ax.set_facecolor('black')
    for s in ax.spines.values():
        s.set_visible(False)
    top = max(caps.values()) or 1
    for i, (key, cap) in enumerate(items):
        label, color, _ = SERIES[key]
        ax.barh(i, cap, height=0.6, color=color, hatch='//' if key in BUKKIT else None, edgecolor='black',
                linewidth=0)
        ax.text(-top * 0.02, i, label + (' (Paper platform)' if key in BUKKIT else ''), ha='right',
                va='center', color=WHITE, fontsize=9.5)
        ax.text(cap + top * 0.01, i, '{:,}'.format(cap), va='center', color=WHITE, fontsize=9.5)
    ax.set_yticks([])
    ax.tick_params(colors=WHITE, labelsize=8)
    ax.xaxis.set_major_formatter(FuncFormatter(k_fmt))
    ax.set_xlim(0, top * 1.15)
    fig.text(0.33, 0.95, title, color=WHITE, fontsize=13, fontweight='bold', va='top')
    fig.text(0.01, 0.01, FOOT.split(' · lines')[0], color=GREY, fontsize=6.5)
    fig.subplots_adjust(left=0.33, right=0.97, top=0.86, bottom=0.12)
    fig.savefig(OUT / name, facecolor='black')
    plt.close(fig)
    return name


def rank_lines(values, base, fmt):
    """(key, 'Name  value  x vs vanilla') rows, best first."""
    out = []
    for key, v in sorted(values.items(), key=lambda kv: -kv[1]):
        r = '' if key == 'vanilla' or not base else '%6s' % ratio_text(v / base)
        out.append((key, '%-25s %7s %s' % (SERIES[key][0], fmt(v), r)))
    return out


def load_scenario(scenario, prefix, fn):
    data, partial = {}, set()
    for key in SERIES:
        res = load(RES / scenario / (prefix + key) / 'bench.json')
        if not res or not res.get('samples'):
            continue
        pts = fn(res)
        if len(pts) < 3:
            continue
        data[key] = pts
        if res.get('status') != 'ok':
            partial.add(key)
    return data, partial


def write_csv(name, data, header):
    with open(OUT / name, 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['config'] + header)
        for key, pts in data.items():
            for p in pts:
                w.writerow([key] + [round(v, 3) if isinstance(v, float) else v for v in p])


SCEN = {
    'mobs': ('c1-', mob_points, 'Mobs spawned by 8 players in 8 bases', 'mobs',
             'Mob load · 8 players, 8 bases'),
    'chunks': ('ch-', lambda r: base_points(r, 'chunks'), 'Chunks loaded by the players', 'loaded chunks',
               'Many loaded chunks · up to 32 players'),
    'hoppers': ('ho-', lambda r: base_points(r, 'players'), 'Active hopper bases (one player each)', 'bases',
                'Hoppers · 4,140 per base, bases join one by one'),
    'redstone': ('rs-', lambda r: base_points(r, 'players'), 'Active redstone bases (one player each)', 'bases',
                 'Redstone · 1,536 pistons per base'),
    'worldgen': ('wg-', lambda r: base_points(r, 'players'), 'Players exploring new terrain', 'players',
                 'World generation · TPS of a player at spawn'),
}
summary = {}
for scenario, (prefix, fn, xlabel, unit, title) in SCEN.items():
    data, partial = load_scenario(scenario, prefix, fn)
    if not data:
        continue
    write_csv(scenario + '.csv', data, ['x', 'tps'] + (['chunks_per_second'] if scenario == 'worldgen' else []))
    caps = {k: capacity(v) for k, v in data.items()}
    base = caps.get('vanilla', 0)
    best = max((k for k in caps if k in FABROLIA), key=lambda k: caps[k], default=None)
    rival = max((k for k in caps if k not in FABROLIA and k != 'vanilla'), key=lambda k: caps[k], default=None)
    headline = []
    if best and base:
        headline += [('Up to ~%s the %s' % (ratio_text(caps[best] / base), unit), 15),
                     ('at full speed compared to vanilla', 10), ('(%s)' % SERIES[best][0], 10)]
    if rival and caps.get(rival, 0) > caps.get(best, 0):
        headline += [('%s carries the most here' % SERIES[rival][0], 11)]
    ranking = [(None, '%s at >= 19.5 TPS:' % unit.capitalize())] + rank_lines(caps, base, '{:,}'.format)
    made = [chart('%s.png' % scenario, title, xlabel, 'TPS', data, 1, headline, ranking, ylim=(0, 21.5),
                  partial=partial)]
    made.append(capacity_chart('%s-capacity.png' % scenario, '%s at full speed (>= 19.5 TPS)' % unit.capitalize(),
                               caps))
    if scenario == 'worldgen':
        rates = {k: max(p[2] for p in v) for k, v in data.items()}
        made.append(chart('worldgen-chunks.png', 'World generation · chunks delivered to exploring players',
                          xlabel, 'Chunks per second', data, 2,
                          [('Peak chunks per second', 14)],
                          [(None, 'Chunks per second (peak):')] + rank_lines(rates, rates.get('vanilla'),
                                                                             lambda v: '%d' % v)))
    summary[scenario] = {'capacity': caps, 'partial': sorted(partial), 'charts': made}
    print(scenario, json.dumps(caps))

runs = [load(p) for p in sorted((RES / 'scaling').glob('sc-*/bench.json'))] if (RES / 'scaling').exists() else []
sc = scaling_points([r for r in runs if r and r.get('status') == 'ok'])
if sc:
    write_csv('scaling.csv', sc, ['threads', 'entity_ticks_per_second', 'tps'])
    peak = {k: max(p[1] for p in v) for k, v in sc.items()}
    best = max((k for k in peak if k in FABROLIA), key=lambda k: peak[k])
    headline = [('Up to ~%s perf' % ratio_text(peak[best] / peak['vanilla']), 15), ('compared to vanilla', 10),
                ('(%s)' % SERIES[best][0], 10)]
    ranking = [(None, 'Peak entity ticks per second:')] + rank_lines(peak, peak['vanilla'], k_fmt)
    made = [chart('scaling.png', 'Thread scaling · 11,520 mobs in 8 bases', 'Threads',
                  'Entity ticks per second', sc, 1, headline, ranking, markers=True, smoothed=False)]
    summary['scaling'] = {'peak': peak, 'points': sc, 'charts': made}
    print('scaling', json.dumps({k: round(v) for k, v in peak.items()}))
(OUT / 'summary.json').write_text(json.dumps(summary, indent=1))
