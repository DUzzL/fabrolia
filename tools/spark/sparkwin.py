#!/usr/bin/env python3
"""sparkwin.py PROFILE MODE ... — windowed spark analysis (ms per wall second unless noted).

  groups  HOT_KS BASE_KS                 per thread group: states, top self/incl by hot-base delta
  subtree THREAD_RE ROOT_SUBSTR HOT_KS BASE_KS [DEPTH]   descendants of the topmost matching frames
  callers THREAD_RE LABEL_SUBSTR KS [DEPTH]              caller chains of the topmost matching frames
  perwin  THREAD_RE LABEL_SUBSTR                          per-window inclusive ms of the topmost matching frames

KS = comma list of window indices or a range a-b. Aggregates for `groups` are cached next to the profile."""
import pickle, re, sys
from collections import defaultdict
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import core  # noqa: E402


def ks(s):
    out = []
    for part in s.split(','):
        if '-' in part:
            a, b = part.split('-'); out.extend(range(int(a), int(b) + 1))
        else:
            out.append(int(part))
    return out


def avg(v, kk):
    return sum(v[k] for k in kk) / len(kk) / 60.0


def lab(nd):
    return core.frame_label(nd)


def topmost(ta, pred):
    stack = list(ta.root_refs)
    while stack:
        i = stack.pop()
        if pred(lab(ta.pool[i])):
            yield i
            continue
        stack.extend(ta.pool[i].children_refs)


def load(path):
    return core.Analysis(core.load(path), path)


def mode_groups(path, hot, base):
    cache = Path(path).with_suffix('.groups.pickle')
    if cache.exists():
        data = pickle.loads(cache.read_bytes())
    else:
        a = load(path)
        nwin = len(a.window_ids)
        groups = {}
        for ta in a.threads:
            gname = re.sub(r'[-#]?\d+$', '', ta.name)
            g = groups.setdefault(gname, {'threads': 0, 'states': [[0.0] * nwin for _ in core.STATE_NAMES],
                                          'incl': {}, 'self': {}, 'owner': {}})
            g['threads'] += 1
            for s, v in enumerate(a.state_windows(ta)):
                for k, x in enumerate(v):
                    g['states'][s][k] += x
            for e in a.method_inclusive(ta, with_windows=True):
                cur = g['incl'].get(e['label'])
                g['incl'][e['label']] = e['w'] if cur is None else [x + y for x, y in zip(cur, e['w'])]
                g['owner'][e['label']] = e['owner']
            for i, v in ta.iter_excl_windows():
                if ta.state[i] != core.RUNNING:
                    continue
                key = lab(ta.pool[i])
                cur = g['self'].get(key)
                g['self'][key] = v if cur is None else [x + y for x, y in zip(cur, v)]
        data = {'rows': a.window_rows(), 'groups': groups}
        cache.write_bytes(pickle.dumps(data))
    for gname in sorted(data['groups'], key=lambda n: -sum(sum(s) for s in data['groups'][n]['states'])):
        g = data['groups'][gname]
        if sum(avg(s, hot) for s in g['states']) < 50 and gname != 'Server thread':
            continue
        print(f"\n## {gname} x{g['threads']}  (hot vs base, ms/s)")
        print('   states:', ', '.join(f"{n}={avg(s, hot):.0f}/{avg(s, base):.0f}" for n, s in zip(core.STATE_NAMES, g['states'])
                                       if avg(s, hot) > 1 or avg(s, base) > 1))
        for kind in ('self', 'incl'):
            items = [(k, avg(v, hot), avg(v, base)) for k, v in g[kind].items()]
            print(f'   -- {kind} by delta')
            for k, h, b in sorted(items, key=lambda x: -(x[1] - x[2]))[:25]:
                if h - b < 2:
                    break
                print(f'   {h:8.1f} {b:8.1f} {h - b:+8.1f}  {k}  [{g["owner"].get(k, "")}]')
            print(f'   -- {kind} top in hot')
            for k, h, b in sorted(items, key=lambda x: -x[1])[:20]:
                print(f'   {h:8.1f} {b:8.1f}  {k}  [{g["owner"].get(k, "")}]')


def mode_subtree(path, thread_re, root_sub, hot, base, max_depth=99):
    a = load(path)
    incl = defaultdict(lambda: [0.0, 0.0]); selfm = defaultdict(lambda: [0.0, 0.0]); child1 = defaultdict(lambda: [0.0, 0.0])
    root = [0.0, 0.0]
    for ta in a.threads:
        if not re.search(thread_re, ta.name):
            continue
        pool = ta.pool
        for r in topmost(ta, lambda s: root_sub in s):
            root[0] += avg(pool[r].times, hot); root[1] += avg(pool[r].times, base)
            for c in pool[r].children_refs:
                e = child1[lab(pool[c])]; e[0] += avg(pool[c].times, hot); e[1] += avg(pool[c].times, base)
            onpath = defaultdict(int)
            st = [(r, False, 0)]
            while st:
                i, leaving, d = st.pop()
                key = lab(pool[i])
                if leaving:
                    onpath[key] -= 1; continue
                if onpath[key] == 0 and d <= max_depth:
                    e = incl[key]; e[0] += avg(pool[i].times, hot); e[1] += avg(pool[i].times, base)
                v = list(pool[i].times)
                for c in pool[i].children_refs:
                    v = [x - y for x, y in zip(v, pool[c].times)]
                e = selfm[key]; e[0] += max(avg(v, hot), 0); e[1] += max(avg(v, base), 0)
                onpath[key] += 1
                st.append((i, True, d)); st.extend((c, False, d + 1) for c in pool[i].children_refs)
    print(f'root {root_sub}: hot {root[0]:.1f} base {root[1]:.1f} ms/s')
    for title, m, n in (('direct children', child1, 25), ('inclusive', incl, 70), ('self', selfm, 40)):
        print(f'-- {title}')
        for k, (h, b) in sorted(m.items(), key=lambda x: -x[1][0])[:n]:
            print(f'{h:8.1f} {b:8.1f}  {k}')


def mode_callers(path, thread_re, sub, kk, depth=3):
    a = load(path)
    agg = defaultdict(float); total = 0.0
    for ta in a.threads:
        if not re.search(thread_re, ta.name):
            continue
        for i in topmost(ta, lambda s: sub in s):
            v = avg(ta.pool[i].times, kk)
            chain, j = [], ta.parent[i]
            while j != -1 and len(chain) < depth:
                l = lab(ta.pool[j])
                if not any(s in l for s in ('Lambda', 'mixinextras$bridge', '$wrapped$', 'DirectMethodHandle')):
                    chain.append(l.split('(')[0])
                j = ta.parent[j]
            agg[' <- '.join(chain)] += v; total += v
    print(f'{sub}: {total:.1f} ms/s')
    for k, v in sorted(agg.items(), key=lambda x: -x[1])[:30]:
        print(f'{v:8.1f}  {k}')


def mode_perwin(path, thread_re, sub):
    a = load(path)
    nwin = len(a.window_ids)
    w = [0.0] * nwin
    for ta in a.threads:
        if not re.search(thread_re, ta.name):
            continue
        for i in topmost(ta, lambda s: sub in s):
            for k, t in enumerate(ta.pool[i].times):
                w[k] += t
    rows = a.window_rows()
    for k in range(nwin):
        print(f"k={k:2d} {core.local_ts(rows[k]['start'], '%H:%M')} {w[k]:9.1f} ms  max_mspt={rows[k]['mspt_max']:.0f}")


MIN_ARGS = {'groups': 2, 'subtree': 4, 'callers': 3, 'perwin': 2}

if __name__ == '__main__':
    if len(sys.argv) < 3 or sys.argv[1] in ('-h', '--help') \
            or sys.argv[2] not in MIN_ARGS or len(sys.argv) - 3 < MIN_ARGS[sys.argv[2]]:
        sys.exit(__doc__)
    p, mode = sys.argv[1], sys.argv[2]
    r = sys.argv[3:]
    if mode == 'groups':
        mode_groups(p, ks(r[0]), ks(r[1]))
    elif mode == 'subtree':
        mode_subtree(p, r[0], r[1], ks(r[2]), ks(r[3]), int(r[4]) if len(r) > 4 else 99)
    elif mode == 'callers':
        mode_callers(p, r[0], r[1], ks(r[2]), int(r[3]) if len(r) > 3 else 3)
    elif mode == 'perwin':
        mode_perwin(p, r[0], r[1])
