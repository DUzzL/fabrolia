"""
Core analysis layer for spark `.sparkprofile` (SamplerData) files.

Shared by all renderers. Does the expensive work exactly once:
  - parse protobuf
  - reconstruct the flattened call tree (children_refs -> flat pool)
  - inclusive (total) and exclusive (excl) value per node
  - thread state per node: running vs park/wait/blocked/sleep/io/safepoint
    (spark's async engine samples WALL time, so parked threads are in the data)
  - wait sites: the application frame that issued each wait
  - mod attribution via method_sources / line_sources / class_sources
  - per-tick normalisation over the retained profiling windows
  - recursion-safe inclusive totals per method, per window
  - window statistics, 10s metric series, world statistics

Units: EXECUTION mode values are milliseconds, ALLOCATION mode values are bytes.
Use Analysis.fmt() to format any node value.
"""
import bisect
import json
import os
import re
import sys
from datetime import datetime

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "gen"))
from spark import spark_sampler_pb2 as S  # noqa: E402

# enum label maps (mirror the .proto)
PLATFORM_TYPE = {0: "SERVER", 1: "CLIENT", 2: "PROXY", 3: "APPLICATION"}
SAMPLER_MODE = {0: "EXECUTION", 1: "ALLOCATION"}
SAMPLER_ENGINE = {0: "JAVA", 1: "ASYNC"}
DUMPER_TYPE = {0: "ALL", 1: "SPECIFIC", 2: "REGEX"}
AGG_TYPE = {0: "SIMPLE", 1: "TICKED"}
GROUPER = {0: "BY_NAME", 1: "BY_POOL", 2: "AS_ONE"}
ONLINE_MODE = {0: "UNKNOWN", 1: "OFFLINE", 2: "ONLINE"}
SENDER_TYPE = {0: "OTHER", 1: "PLAYER"}

WINDOW_MS = 60_000  # spark ProfilingWindowUtils.WINDOW_SIZE_MILLIS


def load(path):
    sd = S.SamplerData()
    with open(path, "rb") as f:
        sd.ParseFromString(f.read())
    return sd


# --------------------------------------------------------------------------- #
# frame classification
# --------------------------------------------------------------------------- #
# async-profiler synthetic / runtime frames that aren't real Java classes
NATIVE_STUBS = {
    "itable stub", "vtable stub", "call_stub", "[unknown]", "Interpreter",
    "StubRoutines", "I2C/C2I adapters", "flush_icache_stub", "deoptimization blob",
    "vdso", "[vdso]",
}
JDK_PREFIXES = ("java.", "javax.", "jdk.", "sun.", "com.sun.", "jakarta.")


def is_native(cn):
    """True if a class_name denotes a native / runtime-stub frame, not a Java class."""
    if cn == "native":
        return True
    if ".so" in cn or cn.startswith("/") or cn.startswith("["):
        return True
    return cn in NATIVE_STUBS


def is_jdk(cn):
    return cn.startswith(JDK_PREFIXES)


def frame_label(node):
    """Human label for a stack frame."""
    cn = node.class_name
    mn = node.method_name
    if cn == "native":
        return mn  # native frame: method_name holds the .so path / symbol
    return f"{cn}.{mn}" if mn else cn


def short_label(node):
    """Last two dotted components (Class.method) for width-limited views."""
    parts = frame_label(node).split(".")
    return ".".join(parts[-2:]) if len(parts) >= 2 else parts[0]


# --------------------------------------------------------------------------- #
# thread states (wall-clock sampling => waiting time is part of the profile)
# --------------------------------------------------------------------------- #
RUNNING, PARK, WAIT, BLOCKED, SLEEP, IO, SAFEPOINT = range(7)
STATE_NAMES = ("running", "park", "wait", "blocked", "sleep", "io", "safepoint")
STATE_HELP = {
    RUNNING: "executing code (CPU); includes spinning",
    PARK: "LockSupport/Unsafe.park — j.u.c locks, executors, futures, pool idle",
    WAIT: "Object.wait or JVM-internal condvar/semaphore/monitor wait",
    BLOCKED: "contended synchronized monitor or JVM mutex",
    SLEEP: "Thread.sleep / Thread.yield / nanosleep",
    IO: "inside a blocking syscall: poll/epoll/read/write/recv/accept",
    SAFEPOINT: "stopped at a JVM safepoint/handshake (GC pause, deopt, ...)",
}

# Java frames that start a wait. Matched top-down: the first wait frame on the
# root->leaf path decides the state of the whole subtree below it.
JAVA_WAIT = {
    ("jdk.internal.misc.Unsafe", "park"): PARK,
    ("sun.misc.Unsafe", "park"): PARK,
    ("java.lang.Object", "wait"): WAIT,
    ("java.lang.Object", "wait0"): WAIT,
    ("java.lang.Thread", "sleep"): SLEEP,
    ("java.lang.Thread", "sleep0"): SLEEP,
    ("java.lang.Thread", "sleepNanos"): SLEEP,
    ("java.lang.Thread", "sleepNanos0"): SLEEP,
    ("java.lang.Thread", "yield"): SLEEP,
    ("java.lang.Thread", "yield0"): SLEEP,
    ("sun.nio.ch.EPoll", "wait"): IO,
    ("sun.nio.ch.Net", "poll"): IO,
    ("sun.nio.ch.Net", "accept"): IO,
    ("io.netty.channel.epoll.Native", "epollWait"): IO,
    ("io.netty.channel.epoll.Native", "epollWait0"): IO,
    ("io.netty.channel.epoll.Native", "epollBusyWait0"): IO,
}
# libc / syscall wrappers (native frame method names)
NATIVE_WAIT_EXACT = {
    **dict.fromkeys(("pthread_cond_wait", "pthread_cond_timedwait", "pthread_cond_clockwait",
                     "__pthread_cond_wait", "__pthread_cond_timedwait",
                     "sem_wait", "sem_timedwait", "sem_clockwait"), WAIT),
    **dict.fromkeys(("poll", "__poll", "ppoll", "epoll_wait", "epoll_pwait", "epoll_pwait2",
                     "select", "pselect", "read", "__read", "pread", "pread64", "readv",
                     "recv", "recvfrom", "recvmsg", "accept", "accept4",
                     "write", "__write", "pwrite", "pwrite64", "writev",
                     "send", "sendto", "sendmsg", "fsync", "fdatasync"), IO),
    **dict.fromkeys(("nanosleep", "__nanosleep", "clock_nanosleep", "usleep"), SLEEP),
}
# HotSpot C++ symbols (substring match, first hit wins)
NATIVE_WAIT_SUBSTR = (
    ("ObjectMonitor::enter", BLOCKED),
    ("ObjectSynchronizer::enter", BLOCKED),
    ("Mutex::lock_contended", BLOCKED),
    ("ObjectMonitor::wait", WAIT),
    ("SafepointMechanism::process", SAFEPOINT),
    ("SafepointSynchronize::block", SAFEPOINT),
    ("Parker::park", PARK),
    ("PlatformEvent::park", WAIT),
    ("PlatformMonitor::wait", WAIT),
    ("Monitor::wait", WAIT),
    ("PosixSemaphore::wait", WAIT),
    ("os::signal_wait", WAIT),
    ("JVM_Sleep", SLEEP),
    ("os::naked_short_sleep", SLEEP),
    # libjli launcher thread: pthread_join() on the Java main thread (libc frames unsymbolized)
    ("CallJavaMainInNewThread", WAIT),
)
# frames skipped when looking for "who is waiting" (lock plumbing, not a cause)
LOCK_PLUMBING_PREFIXES = ("java.util.concurrent.locks.", "jdk.internal.misc.", "sun.misc.")
LOCK_PLUMBING_CLASSES = {"java.lang.Object", "java.lang.Thread", "java.lang.VirtualThread"}


def classify_frame(node):
    """Wait state started by this frame, or RUNNING if it is not a wait primitive."""
    cn = node.class_name
    st = JAVA_WAIT.get((cn, node.method_name))
    if st is not None:
        return st
    if not is_native(cn):
        return RUNNING
    mn = node.method_name
    st = NATIVE_WAIT_EXACT.get(mn)
    if st is not None:
        return st
    for sub, st in NATIVE_WAIT_SUBSTR:
        if sub in mn:
            return st
    return RUNNING


class ThreadAnalysis:
    def __init__(self, tnode, classify_states=True):
        self.name = tnode.name
        pool = self.pool = list(tnode.children)   # flat pool of StackTraceNode
        self.root_refs = list(tnode.children_refs)
        self.window_totals = list(tnode.times)     # per-window inclusive value of the whole thread
        self.grand_total = sum(self.window_totals)

        n = len(pool)
        total = self.total = [sum(nd.times) for nd in pool]   # inclusive
        parent = self.parent = [-1] * n
        excl = self.excl = [0.0] * n                          # exclusive (self)
        for i, nd in enumerate(pool):
            child_sum = 0.0
            for r in nd.children_refs:
                child_sum += total[r]
                parent[r] = i
            s = total[i] - child_sum
            excl[i] = s if s > 0 else 0.0  # clamp float noise

        # state + wait site, top-down so a wait frame colours its whole subtree
        state = self.state = [RUNNING] * n
        site = self.site = [-1] * n
        if classify_states:
            stack = list(self.root_refs)
            while stack:
                i = stack.pop()
                p = parent[i]
                if p != -1 and state[p] != RUNNING:
                    state[i] = state[p]
                    site[i] = site[p]
                else:
                    st = classify_frame(pool[i])
                    if st != RUNNING:
                        state[i] = st
                        site[i] = self._find_site(i)
                stack.extend(pool[i].children_refs)

    def _find_site(self, i):
        """Nearest caller that explains a wait: app frame > JDK frame > native frame."""
        jdk = nat = -1
        j = self.parent[i]
        while j != -1:
            nd = self.pool[j]
            cn = nd.class_name
            if is_native(cn):
                if nat == -1 and classify_frame(nd) == RUNNING and not cn.startswith("libc."):
                    nat = j
            elif is_jdk(cn):
                if jdk == -1 and cn not in LOCK_PLUMBING_CLASSES and not cn.startswith(LOCK_PLUMBING_PREFIXES):
                    jdk = j
            else:
                return j
            j = self.parent[j]
        return jdk if jdk != -1 else nat

    def iter_excl_windows(self):
        """Yield (node index, per-window exclusive values) for nodes with self time."""
        pool = self.pool
        for i, nd in enumerate(pool):
            if self.excl[i] <= 0:
                continue
            v = list(nd.times)
            for r in nd.children_refs:
                v = [a - b for a, b in zip(v, pool[r].times)]
            yield i, v

    def running_total(self):
        return sum(e for e, s in zip(self.excl, self.state) if s == RUNNING)


# --------------------------------------------------------------------------- #
class Analysis:
    def __init__(self, sd, source_path=None):
        self.sd = sd
        self.source_path = source_path
        md = self.md = sd.metadata
        self.is_alloc = md.sampler_mode == 1
        self.unit = "bytes" if self.is_alloc else "ms"
        self.threads = [ThreadAnalysis(t, classify_states=not self.is_alloc) for t in sd.threads]
        self.class_sources = dict(sd.class_sources)
        self.method_sources = dict(sd.method_sources)
        self.line_sources = dict(sd.line_sources)
        self.source_ids = {k.lower() for k in md.sources.keys()}
        self._owner_cache = {}

        # ---- profiling windows ----
        self.window_ids = list(sd.time_windows)
        tws = sd.time_window_statistics
        self.window_stats = [tws[w] if w in tws else None for w in self.window_ids]
        offset = 0
        for w, st in zip(self.window_ids, self.window_stats):
            if st is not None and st.start_time:
                offset = st.start_time - w * WINDOW_MS
                break
        self._window_offset = offset

        # ---- tick normalisation ----
        agg = md.data_aggregator
        self.ticked = agg.type == 1
        if self.ticked:
            self.tick_windows = []
            self.norm_ticks = agg.number_of_included_ticks
        else:
            self.tick_windows = [k for k, st in enumerate(self.window_stats) if st is not None and st.ticks > 0]
            self.norm_ticks = sum(self.window_stats[k].ticks for k in self.tick_windows)
        self._all_windows_ticked = len(self.tick_windows) == len(self.window_ids)

    # ---- units ----
    def fmt(self, v):
        return fmt_bytes(v) if self.is_alloc else fmt_ms(v)

    def fmt_tick(self, v):
        """Format a per-tick value (ms with 2 decimals, or bytes)."""
        if v is None:
            return "—"
        return fmt_bytes(v) if self.is_alloc else f"{v:.2f}"

    @property
    def tick_unit(self):
        return "B/tick" if self.is_alloc else "ms/tick"

    # ---- per-tick normalisation ----
    def per_tick(self, total, windows=None):
        """Value per tick. `total` = whole-profile value; `windows` = its per-window
        vector (needed only when some retained windows have no tick count)."""
        if not self.norm_ticks:
            return None
        if self.ticked or self._all_windows_ticked:
            return total / self.norm_ticks
        if windows is None:
            return None
        return sum(windows[k] for k in self.tick_windows) / self.norm_ticks

    def node_per_tick(self, ta, i):
        if self._all_windows_ticked or self.ticked:
            return self.per_tick(ta.total[i])
        return self.per_tick(ta.total[i], ta.pool[i].times)

    # ---- windows ----
    def window_start(self, k):
        st = self.window_stats[k]
        if st is not None and st.start_time:
            return st.start_time
        return self.window_ids[k] * WINDOW_MS + self._window_offset

    def window_duration(self, k):
        st = self.window_stats[k]
        if st is not None and st.start_time and st.duration:
            return st.duration
        return WINDOW_MS

    def windows_retained_ms(self):
        return sum(self.window_duration(k) for k in range(len(self.window_ids)))

    def is_pruned(self):
        """True when spark dropped old windows (continuous profiling keeps 60)."""
        dur = self.md.end_time - self.md.start_time
        return bool(self.window_ids) and dur > self.windows_retained_ms() + 2 * WINDOW_MS

    def window_rows(self):
        out = []
        for k, w in enumerate(self.window_ids):
            st = self.window_stats[k]
            row = {"k": k, "window": w, "start": self.window_start(k),
                   "duration": self.window_duration(k), "has_stats": st is not None and bool(st.start_time)}
            for f in ("ticks", "tps", "mspt_median", "mspt_max", "cpu_process", "cpu_system",
                      "players", "entities", "tile_entities", "chunks"):
                row[f] = getattr(st, f) if st is not None else 0
            out.append(row)
        return out

    def wall_ms_per_tick_by_window(self):
        """{window index: wall ms per tick} for windows with ticks (server-side tick pace)."""
        out = {}
        for k in self.tick_windows:
            out[k] = self.window_duration(k) / self.window_stats[k].ticks
        return out

    # ---- mod attribution ----
    _MIXIN_RE = re.compile(r"^(?:[A-Za-z]+\$[a-z]{3}\d{3}\$)?([a-z][a-z0-9_\-]*)\$")

    def owner_of(self, node):
        """Owning mod/plugin, or a synthetic bucket: (native), (jdk), (unattributed: pkg)."""
        cn = node.class_name
        key = (cn, node.method_name, node.method_desc, node.line_number)
        o = self._owner_cache.get(key)
        if o is None:
            o = self._owner_cache[key] = self._owner_uncached(node)
        return o

    def _owner_uncached(self, node):
        cn = node.class_name
        if is_native(cn):
            return "(native)"
        # most specific first: mixin-injected methods carry their own source
        if node.method_desc:
            src = self.method_sources.get(f"{cn};{node.method_name};{node.method_desc}")
            if src:
                return src
        elif node.line_number:
            src = self.line_sources.get(f"{cn};{node.line_number}")
            if src:
                return src
        m = self._MIXIN_RE.match(node.method_name)
        if m and m.group(1) in self.source_ids and m.group(1) != "minecraft":
            return m.group(1)
        src = self.class_sources.get(cn)
        if src:
            return src
        if is_jdk(cn):
            return "(jdk)"
        return f"(unattributed: {package_prefix(cn)})"

    # ---- aggregations ----
    def state_totals(self, ta):
        out = [0.0] * len(STATE_NAMES)
        for e, s in zip(ta.excl, ta.state):
            out[s] += e
        return out

    def state_windows(self, ta):
        """state -> per-window exclusive vector."""
        nwin = len(self.window_ids)
        out = [[0.0] * nwin for _ in STATE_NAMES]
        for i, v in ta.iter_excl_windows():
            acc = out[ta.state[i]]
            for k, x in enumerate(v):
                if x > 0:
                    acc[k] += x
        return out

    def wait_sites(self, ta, with_windows=False):
        """Aggregate waiting time by (state, site label). Returns list sorted by time."""
        agg = {}
        it = ta.iter_excl_windows() if with_windows else ((i, None) for i in range(len(ta.pool)) if ta.excl[i] > 0)
        for i, v in it:
            st = ta.state[i]
            if st == RUNNING:
                continue
            s = ta.site[i]
            label = frame_label(ta.pool[s]) if s != -1 else "(no caller frame)"
            key = (st, label)
            e = agg.get(key)
            if e is None:
                e = agg[key] = {"state": st, "site": label,
                                "owner": self.owner_of(ta.pool[s]) if s != -1 else "—",
                                "total": 0.0, "w": [0.0] * len(self.window_ids) if with_windows else None}
            e["total"] += ta.excl[i]
            if with_windows:
                w = e["w"]
                for k, x in enumerate(v):
                    if x > 0:
                        w[k] += x
        return sorted(agg.values(), key=lambda e: e["total"], reverse=True)

    def flat_methods(self, ta):
        """Exclusive time by (class, method), split into running / waiting."""
        agg = {}
        for i, nd in enumerate(ta.pool):
            x = ta.excl[i]
            if x <= 0:
                continue
            key = (nd.class_name, nd.method_name)
            e = agg.get(key)
            if e is None:
                e = agg[key] = {"excl": 0.0, "running": 0.0, "owner": self.owner_of(nd), "label": frame_label(nd)}
            e["excl"] += x
            if ta.state[i] == RUNNING:
                e["running"] += x
        return sorted(agg.values(), key=lambda e: (e["running"], e["excl"]), reverse=True)

    def by_mod(self, ta):
        """Exclusive time by owning mod/plugin: (owner, running, waiting), sorted by running."""
        run, wait = {}, {}
        for i, nd in enumerate(ta.pool):
            x = ta.excl[i]
            if x <= 0:
                continue
            o = self.owner_of(nd)
            d = run if ta.state[i] == RUNNING else wait
            d[o] = d.get(o, 0.0) + x
        owners = set(run) | set(wait)
        rows = [(o, run.get(o, 0.0), wait.get(o, 0.0)) for o in owners]
        return sorted(rows, key=lambda r: (r[1], r[2]), reverse=True)

    def method_inclusive(self, ta, with_windows=False):
        """Inclusive time per (class, method), counted once per stack even under
        recursion. Returns list sorted by total."""
        pool, total = ta.pool, ta.total
        nwin = len(self.window_ids)
        agg = {}
        onpath = {}
        stack = [(r, False) for r in ta.root_refs]
        while stack:
            i, leaving = stack.pop()
            nd = pool[i]
            key = (nd.class_name, nd.method_name)
            if leaving:
                c = onpath[key] - 1
                if c:
                    onpath[key] = c
                else:
                    del onpath[key]
                continue
            e = agg.get(key)
            if e is None:
                e = agg[key] = {"total": 0.0, "excl": 0.0, "running": 0.0,
                                "owner": self.owner_of(nd), "label": frame_label(nd),
                                "w": [0.0] * nwin if with_windows else None}
            x = ta.excl[i]
            e["excl"] += x
            if ta.state[i] == RUNNING:
                e["running"] += x
            if key not in onpath:
                e["total"] += total[i]
                if with_windows:
                    w = e["w"]
                    for k, t in enumerate(nd.times):
                        w[k] += t
            onpath[key] = onpath.get(key, 0) + 1
            stack.append((i, True))
            stack.extend((r, False) for r in nd.children_refs)
        return sorted(agg.values(), key=lambda e: e["total"], reverse=True)

    def worst_vs_best(self, ta, mi=None, sites=None, frac=0.25):
        """Per-tick inclusive time per method (and per wait site) in the slowest vs
        fastest windows, ranked by the server's wall ms/tick. None if <4 ticked
        windows. `mi` / `sites` = precomputed method_inclusive / wait_sites with
        windows. Rows: (delta, slow, fast, entry), sorted by delta descending."""
        pace = self.wall_ms_per_tick_by_window()
        if self.ticked or len(pace) < 4:
            return None
        order = sorted(pace, key=pace.get)
        g = max(1, int(round(len(order) * frac)))
        best, worst = order[:g], order[-g:]
        tb = sum(self.window_stats[k].ticks for k in best)
        tw = sum(self.window_stats[k].ticks for k in worst)

        def diff(entries):
            rows = []
            for e in entries:
                w = e["w"]
                vb = sum(w[k] for k in best) / tb
                vw = sum(w[k] for k in worst) / tw
                rows.append((vw - vb, vw, vb, e))
            rows.sort(key=lambda r: r[0], reverse=True)
            return rows

        if mi is None or (mi and mi[0]["w"] is None):
            mi = self.method_inclusive(ta, with_windows=True)
        return {
            "best": best, "worst": worst,
            "best_pace": sum(pace[k] for k in best) / len(best),
            "worst_pace": sum(pace[k] for k in worst) / len(worst),
            "best_thread": sum(ta.window_totals[k] for k in best) / tb,
            "worst_thread": sum(ta.window_totals[k] for k in worst) / tw,
            "methods": diff(mi),
            "sites": diff(e for e in (sites or []) if e["w"] is not None),
        }

    # ---- metadata helpers ----
    def server_configs(self):
        """{config file: dict or raw string}"""
        out = {}
        for name, raw in sorted(self.md.server_configurations.items()):
            try:
                out[name] = json.loads(raw)
            except ValueError:
                out[name] = raw
        return out

    def metric_rows(self):
        """Merge the 10s metric series onto one timeline (master = tick_duration or tps)."""
        m = self.md.metrics
        if not self.md.HasField("metrics"):
            return []
        series = {}
        for fd, s in m.ListFields():
            ts, t = [], s.start_timestamp_ms
            for d in s.timestamp_deltas_ms:
                t += d
                ts.append(t)
            series[fd.name] = (ts, list(s.values))
        master = next((k for k in ("tick_duration", "tps", "cpu_usage_process") if k in series), None)
        if master is None:
            master = max(series, key=lambda k: len(series[k][0]))
        rows = []
        for t in series[master][0]:
            row = {"t": t}
            for name, (ts, vals) in series.items():
                j = bisect.bisect_left(ts, t)
                best = None
                for c in (j - 1, j):
                    if 0 <= c < len(ts) and abs(ts[c] - t) <= 6000 and (best is None or abs(ts[c] - t) < abs(ts[best] - t)):
                        best = c
                row[name] = vals[best] if best is not None else None
            rows.append(row)
        return rows


# ---- formatting helpers ----
_PKG_DEEP = {"com", "org", "net", "io", "it", "dev", "me", "ca", "de", "gg", "xyz", "top", "fr", "ru", "uk", "cn", "nl", "pl"}


def package_prefix(cn):
    pk = cn.split(".")[:-1]
    if not pk:
        return cn
    depth = 3 if pk[0] in _PKG_DEEP and len(pk) >= 3 else 2
    return ".".join(pk[:depth])


def fmt_ms(ms):
    if ms >= 1000:
        return f"{ms/1000:.1f}s"
    return f"{ms:.0f}ms"


def fmt_bytes(b):
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if abs(b) < 1024 or unit == "TiB":
            return f"{b:.1f} {unit}" if unit != "B" else f"{int(b)} B"
        b /= 1024


def fmt_pct(part, whole):
    return f"{(100.0*part/whole):.2f}%" if whole else "—"


def local_ts(ms, fmt="%Y-%m-%d %H:%M:%S %Z"):
    if not ms:
        return "—"
    return datetime.fromtimestamp(ms / 1000).astimezone().strftime(fmt)


if __name__ == "__main__":
    # quick sanity dump of one file
    path = sys.argv[1]
    a = Analysis(load(path), path)
    md = a.md
    pm = md.platform_metadata
    print(f"# {os.path.basename(path)}")
    print("platform:", PLATFORM_TYPE.get(pm.type), pm.name, pm.version, "MC", pm.minecraft_version,
          "spark", pm.spark_version, f"(data v{pm.spark_data_version})")
    print("engine:", SAMPLER_ENGINE.get(md.sampler_engine), md.sampler_engine_version,
          "| mode:", SAMPLER_MODE.get(md.sampler_mode), "| windows:", len(a.window_ids),
          "| ticks in windows:", a.norm_ticks)
    for ta in a.threads:
        st = a.state_totals(ta)
        parts = ", ".join(f"{STATE_NAMES[s]} {fmt_pct(v, ta.grand_total)}" for s, v in enumerate(st) if v)
        print(f"=== {ta.name}: {a.fmt(ta.grand_total)}, {len(ta.pool):,} nodes — {parts}")
        for e in a.flat_methods(ta)[:10]:
            print(f"  {a.fmt(e['running']):>8}  {fmt_pct(e['running'], ta.grand_total):>7}  [{e['owner']}]  {e['label']}")
