#!/usr/bin/env python3
"""
spark2md — decode spark `.sparkprofile` (SamplerData) files into Markdown.

Produces, per input file:
  <name>.summary.md — compact diagnosis: health, metrics, windows, thread states,
                      wait sites, hot methods, worst-vs-best windows, pruned hot tree
  <name>.ai.md      — everything, full fidelity, flat (greppable `## SECTION` markers)
  <name>.human.md   — everything, with collapsible <details> call tree
  <name>.pyramid.xlsx — merged-cell icicle per thread

Usage:
  python3 spark2md.py FILE [FILE ...] [--out-dir DIR] [--only summary,ai,human,xlsx]
                       [--min-percent P] [--summary-min-percent P]
"""
import argparse
import html
import json
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "gen"))

import xlsx_pyramid  # noqa: E402
from core import (  # noqa: E402
    Analysis, load, frame_label, fmt_ms, fmt_bytes, fmt_pct, local_ts,
    PLATFORM_TYPE, SAMPLER_MODE, SAMPLER_ENGINE, DUMPER_TYPE, AGG_TYPE,
    GROUPER, ONLINE_MODE, SENDER_TYPE, RUNNING, STATE_NAMES, STATE_HELP,
)

FORMATS = ("summary", "ai", "human", "xlsx")
MAIN_THREADS = ("Server thread", "Render thread", "Client thread")


# --------------------------------------------------------------------------- #
# small markdown helpers
# --------------------------------------------------------------------------- #
def td(x):
    return str(x).replace("|", "\\|").replace("\n", " ")


def md_table(headers, rows):
    out = ["| " + " | ".join(headers) + " |",
           "| " + " | ".join("---" for _ in headers) + " |"]
    for r in rows:
        out.append("| " + " | ".join(td(c) for c in r) + " |")
    return "\n".join(out)


def kv_table(pairs):
    return md_table(["Field", "Value"], pairs)


def code_block(lines, lang=""):
    return f"```{lang}\n" + "\n".join(lines) + "\n```"


def hm(ms):
    return local_ts(ms, "%H:%M:%S")


def dur(ms):
    s = ms / 1000
    if s < 60:
        return f"{s:.0f}s"
    m = s / 60
    if m < 60:
        return f"{m:.1f} min"
    return f"{m/60:.2f} h ({m:.0f} min)"


def pct(v, whole):
    return f"{100.0 * v / whole:.2f}%" if whole else "—"


def rate(bps):
    return f"{fmt_bytes(bps)}/s"


def label_line(nd):
    lbl = frame_label(nd)
    return f"{lbl}:{nd.line_number}" if nd.line_number else lbl


_MERGED_LAMBDA = re.compile(r"^md[0-9a-f]+\$")


def is_noise(cn, mn):
    """Synthetic wrapper frames (lambdas, MixinExtras bridges, mixin-merged lambdas)
    that carry no information of their own; hidden in the summary only."""
    return ("$$Lambda" in cn or mn.startswith("mixinextras$bridge$") or "$mixinextras$wrapped$" in mn
            or _MERGED_LAMBDA.match(mn) is not None)


def entry_is_noise(e):
    cn, _, mn = e["label"].rpartition(".")
    return is_noise(cn, mn)


def sorted_children(ta, refs):
    return sorted(refs, key=lambda r: ta.total[r], reverse=True)


# --------------------------------------------------------------------------- #
# JVM flag parsing
# --------------------------------------------------------------------------- #
def parse_flags(vm_args):
    flags = [f for f in vm_args.split() if f]
    notable = {}
    for f in flags:
        for key in ("-Xms", "-Xmx", "-Xmn", "-Xss"):
            if f.startswith(key):
                notable[key] = f[len(key):]
        if f.startswith("-XX:"):
            body = f[4:]
            if "=" in body:
                k, v = body.split("=", 1)
                notable["-XX:" + k] = v
            elif body.startswith(("+", "-")):
                notable["-XX:" + body[1:]] = body[0] == "+"
    return flags, notable


NOTABLE_FLAGS = ["-Xms", "-Xmx", "-Xmn", "-Xss", "-XX:UseG1GC", "-XX:UseZGC", "-XX:ZGenerational",
                 "-XX:UseShenandoahGC", "-XX:UseParallelGC", "-XX:MaxGCPauseMillis", "-XX:G1HeapRegionSize",
                 "-XX:G1NewSizePercent", "-XX:G1MaxNewSizePercent", "-XX:G1ReservePercent",
                 "-XX:InitiatingHeapOccupancyPercent", "-XX:ParallelGCThreads", "-XX:ConcGCThreads",
                 "-XX:ParallelRefProcEnabled", "-XX:DisableExplicitGC", "-XX:AlwaysPreTouch",
                 "-XX:UseLargePages", "-XX:UseTransparentHugePages", "-XX:+UseCompactObjectHeaders",
                 "-XX:UseCompactObjectHeaders", "-XX:EnableJVMCI", "-XX:UseJVMCICompiler",
                 "-XX:ReservedCodeCacheSize", "-XX:UseCodeCacheFlushing", "-XX:MaxTenuringThreshold"]


# --------------------------------------------------------------------------- #
# section builders (return markdown strings; shared by all renderers)
# --------------------------------------------------------------------------- #
def section_overview(a):
    md = a.md
    pm = md.platform_metadata
    agg = md.data_aggregator
    dumper = md.thread_dumper
    dumper_s = DUMPER_TYPE.get(dumper.type, dumper.type)
    if dumper.ids:
        dumper_s += f" · ids {list(dumper.ids)}"
    if dumper.patterns:
        dumper_s += f" · patterns {list(dumper.patterns)}"
    agg_s = f"{AGG_TYPE.get(agg.type)} · grouper {GROUPER.get(agg.thread_grouper)}"
    if a.ticked:
        agg_s += f" · only ticks > {agg.tick_length_threshold} ms ({agg.number_of_included_ticks:,} ticks included)"
    if a.is_alloc:
        interval = f"every {fmt_bytes(md.interval)} allocated"
        engine_note = ""
    else:
        interval = f"{md.interval/1000:g} ms"
        engine_note = (" · async-profiler `event=wall`: waiting threads are sampled too "
                       "(see THREAD STATES)") if md.sampler_engine == 1 else ""
    nwin = len(a.window_ids)
    if nwin:
        first, last = a.window_start(0), a.window_start(nwin - 1) + a.window_duration(nwin - 1)
        retained = f"{nwin} × 1-min windows, {hm(first)} → {hm(last)} ({dur(a.windows_retained_ms())})"
        if a.is_pruned():
            retained += (" — **spark kept only the last windows of a longer profile; older samples "
                         "were dropped. All call-tree numbers cover this range only.**")
    else:
        retained = "—"
    ticks = f"{md.number_of_ticks:,} over the whole profile"
    if not a.ticked:
        ticks += f"; {a.norm_ticks:,} inside retained windows (used for {a.tick_unit})"
    names = [t.name for t in a.threads]
    rows = [
        ("Source file", os.path.basename(a.source_path or "")),
        ("Creator", f"{md.creator.name} ({SENDER_TYPE.get(md.creator.type, md.creator.type)}) `{md.creator.unique_id}`"),
        ("Platform", f"{PLATFORM_TYPE.get(pm.type, pm.type)} · {pm.name} {pm.version} · brand {pm.brand}"),
        ("Minecraft", pm.minecraft_version or "—"),
        ("spark", f"{pm.spark_version or '?'} (data format v{pm.spark_data_version})"),
        ("Sampler", f"{SAMPLER_ENGINE.get(md.sampler_engine)} {md.sampler_engine_version} · "
                    f"mode {SAMPLER_MODE.get(md.sampler_mode)}{engine_note}"),
        ("Sampling interval", interval),
        ("Thread dumper", dumper_s),
        ("Data aggregator", agg_s),
        ("Start", local_ts(md.start_time)),
        ("End", local_ts(md.end_time)),
        ("Duration", dur(md.end_time - md.start_time)),
        ("Data retained", retained),
        ("Ticks", ticks),
        ("Profiled threads", f"{len(names)}: " + (", ".join(names) if len(names) <= 12 else ", ".join(names[:12]) + ", …")),
        ("Comment", md.comment or "—"),
    ]
    extra = dict(md.extra_platform_metadata)
    for k in sorted(extra):
        rows.append((f"extra: {k}", extra[k]))
    return kv_table(rows)


def gc_profile_total(a):
    gc = a.md.platform_statistics.gc
    return sum(g.total * g.avg_time for g in gc.values()), sum(g.total for g in gc.values())


def section_health(a):
    md = a.md
    ps = md.platform_statistics
    ss = md.system_statistics
    rows = []
    if ps.HasField("tps"):
        t = ps.tps
        rows.append(("TPS (1m / 5m / 15m)", f"{t.last1m:.2f} / {t.last5m:.2f} / {t.last15m:.2f}  (target {t.game_target_tps})"))
    if ps.HasField("mspt"):
        for lbl, m in (("1m", ps.mspt.last1m), ("5m", ps.mspt.last5m)):
            rows.append((f"MSPT {lbl} (mean / median / p95 / max)",
                         f"{m.mean:.2f} / {m.median:.2f} / {m.percentile95:.2f} / {m.max:.2f} ms"))
        rows.append(("MSPT ideal max", f"{ps.mspt.game_max_ideal_mspt} ms"))
    if ps.HasField("ping"):
        p = ps.ping.last15m
        rows.append(("Ping 15m (mean / median / p95 / max)", f"{p.mean:.0f} / {p.median:.0f} / {p.percentile95:.0f} / {p.max:.0f} ms"))
    rows.append(("Players", str(ps.player_count)))
    rows.append(("Online mode", ONLINE_MODE.get(ps.online_mode, ps.online_mode)))
    if ps.HasField("world"):
        rows.append(("Entities (total)", f"{ps.world.total_entities:,}"))
    rows.append(("CPU process (1m / 15m)", f"{ss.cpu.process_usage.last1m*100:.1f}% / {ss.cpu.process_usage.last15m*100:.1f}%"))
    rows.append(("CPU system (1m / 15m)", f"{ss.cpu.system_usage.last1m*100:.1f}% / {ss.cpu.system_usage.last15m*100:.1f}%"))
    if ps.HasField("memory"):
        h = ps.memory.heap
        rows.append(("Heap used / committed / max",
                     f"{fmt_bytes(h.used)} / {fmt_bytes(h.committed)} / {fmt_bytes(h.max)}"
                     + (f" ({100.0*h.used/h.max:.1f}% of max)" if h.max > 0 else "")))
        m = ps.memory
        if m.HasField("alloc_bps_last1m"):
            rows.append(("Allocation rate (1m / 5m / 15m mean)",
                         f"{rate(m.alloc_bps_last1m.mean)} / {rate(m.alloc_bps_last5m.mean)} / {rate(m.alloc_bps_last15m.mean)}"))
            rows.append(("Allocation rate 1m (p95 / max)", f"{rate(m.alloc_bps_last1m.percentile95)} / {rate(m.alloc_bps_last1m.max)}"))
    gc_ms, gc_n = gc_profile_total(a)
    prof = md.end_time - md.start_time
    if gc_n:
        rows.append(("GC during profile", f"{gc_n:,} collections · ≈{fmt_ms(gc_ms)} · {pct(gc_ms, prof)} of profile wall time"))
    return kv_table(rows)


def section_system(a):
    ss = a.md.system_statistics
    rows = [
        ("CPU model", ss.cpu.model_name or "—"),
        ("CPU threads", str(ss.cpu.threads)),
        ("OS", f"{ss.os.name} {ss.os.version} ({ss.os.arch})"),
        ("Java", f"{ss.java.vendor} {ss.java.version} ({ss.java.vendor_version})"),
        ("JVM", f"{ss.jvm.name} {ss.jvm.version} ({ss.jvm.vendor})"),
        ("JVM uptime", dur(ss.uptime)),
    ]
    if ss.HasField("memory"):
        m = ss.memory
        rows.append(("Physical RAM (used / total)", f"{fmt_bytes(m.physical.used)} / {fmt_bytes(m.physical.total)}"))
        if m.swap.total:
            rows.append(("Swap (used / total)", f"{fmt_bytes(m.swap.used)} / {fmt_bytes(m.swap.total)}"))
    if ss.HasField("disk"):
        rows.append(("Disk (used / total)", f"{fmt_bytes(ss.disk.used)} / {fmt_bytes(ss.disk.total)}"))
    for name in sorted(ss.net):
        n = ss.net[name]
        rows.append((f"Net {name} (rx / tx mean)",
                     f"{rate(n.rx_bytes_per_second.mean)} / {rate(n.tx_bytes_per_second.mean)} · "
                     f"{n.rx_packets_per_second.mean:.0f} / {n.tx_packets_per_second.mean:.0f} pkt/s"))
    return kv_table(rows)


def section_memory(a):
    ps = a.md.platform_statistics
    if not ps.HasField("memory"):
        return "_no memory statistics_"
    mem = ps.memory
    h, nh = mem.heap, mem.non_heap
    out = kv_table([
        ("Heap used / committed / max", f"{fmt_bytes(h.used)} / {fmt_bytes(h.committed)} / {fmt_bytes(h.max)}"),
        ("Non-heap used / committed", f"{fmt_bytes(nh.used)} / {fmt_bytes(nh.committed)}"),
    ])
    rows = []
    for p in mem.pools:
        u, cu = p.usage, p.collection_usage
        rows.append((p.name, fmt_bytes(u.used), fmt_bytes(u.committed), fmt_bytes(u.max) if u.max > 0 else "—",
                     fmt_bytes(cu.used) if p.HasField("collection_usage") else "—"))
    if rows:
        out += "\n\n" + md_table(["Memory pool", "Used", "Committed", "Max", "Used after last GC"], rows)
    return out


def _gc_table(gc, span_ms, span_label):
    """Interval = span ÷ collections. spark's own avg_frequency for the profile delta
    divides by server uptime, not by the profile length (PlatformStatisticsProvider)."""
    rows = []
    total = 0.0
    for name in sorted(gc):
        g = gc[name]
        est = g.total * g.avg_time
        total += est
        freq = f"every {span_ms / g.total / 1000:.1f}s" if g.total else "—"
        rows.append((name, f"{g.total:,}", f"{g.avg_time:.2f} ms", freq, fmt_ms(est), pct(est, span_ms)))
    out = md_table(["Collector", "Collections", "Avg time", "Avg interval", "Est. total", f"% of {span_label}"], rows)
    return out, total


def section_gc(a):
    md = a.md
    parts = []
    if md.platform_statistics.gc:
        t, total = _gc_table(md.platform_statistics.gc, md.end_time - md.start_time, "profile")
        parts.append("**During the profile:**\n\n" + t)
    if md.system_statistics.gc:
        t, total = _gc_table(md.system_statistics.gc, md.system_statistics.uptime, "JVM uptime")
        parts.append("**Since JVM start:**\n\n" + t)
    parts.append("_`Concurrent` collectors run alongside the application; their time is not a pause._")
    return "\n\n".join(parts) if len(parts) > 1 else "_no GC statistics_"


def section_flags(a):
    flags, notable = parse_flags(a.md.system_statistics.java.vm_args)
    if not flags:
        return "_no JVM flags recorded_"
    rows = []
    for k in NOTABLE_FLAGS:
        if k in notable:
            v = notable[k]
            rows.append((k, "on" if v is True else ("off" if v is False else v)))
    out = md_table(["Notable flag", "Value"], rows) if rows else ""
    return out + "\n\n" + code_block([a.md.system_statistics.java.vm_args])


def section_metrics(a):
    rows = a.metric_rows()
    if not rows:
        return "_no 10s metrics in this profile (spark < 1.10.18x)_"
    start, end = a.md.start_time, a.md.end_time
    out = []
    for r in rows:
        td_ = r.get("tick_duration")
        heap = r.get("memory_usage_heap")
        wi = r.get("world_info")
        ping = r.get("player_ping")
        mark = "" if start <= r["t"] <= end else " †"
        f = lambda v, spec: format(v, spec) if v is not None else "—"  # noqa: E731
        out.append((
            hm(r["t"]) + mark,
            f(r.get("tps"), ".2f"),
            f(td_.mean if td_ else None, ".1f"), f(td_.percentile95 if td_ else None, ".1f"),
            f(td_.max if td_ else None, ".1f"),
            f(r["cpu_usage_process"] * 100 if r.get("cpu_usage_process") is not None else None, ".0f"),
            f(r["cpu_usage_system"] * 100 if r.get("cpu_usage_system") is not None else None, ".0f"),
            fmt_bytes(heap.used) if heap else "—",
            rate(r["memory_allocation"]) if r.get("memory_allocation") is not None else "—",
            wi.players if wi else "—", f"{wi.entities:,}" if wi else "—", f"{wi.chunks:,}" if wi else "—",
            f(ping.mean if ping else None, ".0f"),
        ))
    head = ("_10-second samples (spark keeps the last hour). MSPT = tick duration in ms over the 10s. "
            "CPU in % of the whole machine. † = outside the profiling interval._\n\n")
    return head + md_table(["Time", "TPS", "MSPT mean", "MSPT p95", "MSPT max", "CPU proc %", "CPU sys %",
                            "Heap used", "Alloc rate", "Players", "Entities", "Chunks", "Ping"], out)


def section_windows(a):
    rows = []
    for w in a.window_rows():
        if not w["has_stats"]:
            rows.append((hm(w["start"]), "—", "no stats (window in progress when profile ended)",
                         "", "", "", "", "", "", "", ""))
            continue
        pace = f"{w['duration'] / w['ticks']:.1f}" if w["ticks"] else "—"
        rows.append((hm(w["start"]), f"{w['duration']/1000:.0f}s", f"{w['ticks']:,}", pace,
                     f"{w['tps']:.2f}", f"{w['mspt_median']:.1f}", f"{w['mspt_max']:.1f}",
                     f"{w['cpu_process']*100:.0f}%", f"{w['cpu_system']*100:.0f}%",
                     f"{w['players']}", f"{w['entities']:,} / {w['chunks']:,}"))
    if not rows:
        return "_no per-window statistics_"
    head = ("_One row per 1-minute profiling window. `wall ms/tick` = window length ÷ ticks counted in it "
            "(50 = 20 TPS). TPS / MSPT columns are spark's rolling 1-minute values at window end._\n\n")
    return head + md_table(["Start", "Length", "Ticks", "wall ms/tick", "TPS (1m)", "MSPT median", "MSPT max",
                            "CPU proc", "CPU sys", "Players", "Entities / chunks"], rows)


def thread_group_size(name):
    m = re.search(r"\(x(\d+)\)$", name)
    return int(m.group(1)) if m else 1


def section_threads_overview(a, stats):
    """stats: {ta.name: (state_totals, flat_methods)}"""
    span = a.windows_retained_ms() or (a.md.end_time - a.md.start_time)
    rows = []
    for ta in a.threads:
        st, fm = stats[ta.name]
        run = st[RUNNING]
        waits = [(v, s) for s, v in enumerate(st) if s != RUNNING and v > 0]
        top_wait = max(waits)[1] if waits else None
        top_m = fm[0] if fm and fm[0]["running"] > 0 else None
        rows.append((run, ta, top_wait, top_m))
    rows.sort(key=lambda r: r[0], reverse=True)
    out = []
    for run, ta, top_wait, top_m in rows:
        out.append((ta.name, thread_group_size(ta.name), a.fmt(ta.grand_total), a.fmt(run),
                    f"{run / span:.2f}" if not a.is_alloc else "—", pct(run, ta.grand_total),
                    STATE_NAMES[top_wait] if top_wait is not None else "—",
                    f"{top_m['label']} ({a.fmt(top_m['running'])})" if top_m else "—"))
    head = ("_Sorted by running (CPU) time. `cores` = running time ÷ retained wall time, i.e. how many CPU "
            "cores the group kept busy on average. Idle pool threads show up as park/wait._\n\n")
    return head + md_table(["Thread / pool", "Threads", "Sampled", "Running", "Cores", "Running %",
                            "Main wait state", "Top running method (self)"], out)


def section_states(a, ta, st):
    rows = []
    for s, v in enumerate(st):
        if v <= 0:
            continue
        rows.append((STATE_NAMES[s], a.fmt(v), pct(v, ta.grand_total), a.fmt_tick(a.per_tick(v, None) if a.per_tick(v, None) is not None else None),
                     STATE_HELP[s]))
    return md_table(["State", "Time", "% of thread", a.tick_unit, "Meaning"], rows)


def section_wait_sites(a, ta, sites, n=None):
    sel = sites if n is None else sites[:n]
    if not sel:
        return "_no waiting time_"
    rows = [(STATE_NAMES[e["state"]], e["site"], e["owner"], a.fmt(e["total"]), pct(e["total"], ta.grand_total),
             a.fmt_tick(a.per_tick(e["total"], e["w"]))) for e in sel]
    head = ("_Where the thread waited: nearest application frame above the wait primitive "
            "(JDK lock plumbing skipped). For `Server thread`, `MinecraftServer.waitForTasks` / "
            "`waitUntilNextTick` / `managedBlock` between ticks is **idle headroom**, not lag._\n\n")
    return head + md_table(["State", "Waiting in", "Source", "Time", "% of thread", a.tick_unit], rows)


def section_thread_windows(a, ta, sw, sites):
    """Per-window breakdown of a thread: running and each wait state per tick."""
    nwin = len(a.window_ids)
    used = [s for s in range(len(STATE_NAMES)) if sum(sw[s]) > 0.001 * ta.grand_total]
    top_sites = [e for e in sites[:3] if e["w"] is not None]
    rows = []
    for k in range(nwin):
        st = a.window_stats[k]
        ticks = st.ticks if st is not None else 0
        div = ticks if ticks and not a.ticked else None
        f = (lambda v: a.fmt_tick(v / div)) if div else a.fmt  # noqa: E731
        row = [hm(a.window_start(k)), f"{ticks:,}" if ticks else "—", f(ta.window_totals[k])]
        row += [f(sw[s][k]) for s in used]
        row += [f(e["w"][k]) for e in top_sites]
        rows.append(row)
    unit = a.tick_unit if not a.ticked else a.unit
    headers = ["Start", "Ticks", f"thread {unit}"] + [f"{STATE_NAMES[s]}" for s in used] + \
              [f"⌛ {e['site'].rsplit('.', 2)[-2] + '.' + e['site'].rsplit('.', 1)[-1] if e['site'].count('.') >= 2 else e['site']}"
               for e in top_sites]
    head = (f"_Per 1-minute window, values in {unit} (raw {a.unit} where the window has no tick count). "
            "⌛ columns = the thread's top wait sites._\n\n")
    return head + md_table(headers, rows)


def section_by_mod(a, ta, n=None):
    rows = []
    for owner, run, wait in a.by_mod(ta)[:n] if n else a.by_mod(ta):
        rows.append((owner, a.fmt(run), pct(run, ta.grand_total), a.fmt_tick(a.per_tick(run)), a.fmt(wait)))
    return md_table(["Source (mod / bucket)", "Running self", "% of thread", a.tick_unit, "Waiting self"], rows)


def section_top_self(a, ta, fm, n=None):
    sel = [e for e in fm if e["running"] > 0]
    sel = sel if n is None else sel[:n]
    rows = [(a.fmt(e["running"]), pct(e["running"], ta.grand_total), a.fmt_tick(a.per_tick(e["running"])),
             e["owner"], e["label"]) for e in sel]
    return md_table(["Running self", "% thread", a.tick_unit, "Source", "Method"], rows)


def section_top_inclusive(a, ta, mi, n=None):
    sel = mi if n is None else [e for e in mi if not entry_is_noise(e)][:n]
    rows = [(a.fmt(e["total"]), pct(e["total"], ta.grand_total), a.fmt_tick(a.per_tick(e["total"], e["w"])),
             a.fmt(e["running"]), e["owner"], e["label"]) for e in sel]
    head = ("_Inclusive time per method (frame + callees), counted once per stack under recursion; "
            "includes waits below the frame."
            + ("" if n is None else " Lambda / MixinExtras wrapper frames omitted.") + "_\n\n")
    return head + md_table(["Total", "% thread", a.tick_unit, "Running self", "Source", "Method"], rows)


def section_worst_best(a, ta, wb, n=None):
    if wb is None:
        return "_needs ≥4 one-minute windows with tick counts_"
    fmt_w = lambda ks: ", ".join(hm(a.window_start(k)) for k in sorted(ks))  # noqa: E731
    u = a.tick_unit
    head = (f"Slowest windows ({fmt_w(wb['worst'])}): **{wb['worst_pace']:.1f} wall ms/tick**, "
            f"thread {a.fmt_tick(wb['worst_thread'])} {u}.  \n"
            f"Fastest windows ({fmt_w(wb['best'])}): **{wb['best_pace']:.1f} wall ms/tick**, "
            f"thread {a.fmt_tick(wb['best_thread'])} {u}.\n\n"
            f"_Methods whose inclusive {u} grew the most in the slow windows. Nested methods "
            "repeat their callers' growth — read top-down to find where it originates._\n\n")
    methods = wb["methods"] if n is None else [r for r in wb["methods"] if not entry_is_noise(r[3])]
    cut = n or 40
    hdr = [f"Δ {u}", "slow", "fast", "Source", "Method"]
    grow = [(f"+{a.fmt_tick(d)}", a.fmt_tick(vw), a.fmt_tick(vb), e["owner"], e["label"])
            for d, vw, vb, e in methods[:cut] if d > 0]
    out = head + md_table(hdr, grow)
    shrink = [(a.fmt_tick(d), a.fmt_tick(vw), a.fmt_tick(vb), e["owner"], e["label"])
              for d, vw, vb, e in reversed(methods[-max(5, cut // 3):]) if d < 0]
    if shrink:
        out += "\n\n**Shrank in slow windows** (usually idle headroom being squeezed out):\n\n" + md_table(hdr, shrink)
    sites = [(f"{'+' if d > 0 else ''}{a.fmt_tick(d)}", a.fmt_tick(vw), a.fmt_tick(vb), STATE_NAMES[e["state"]], e["site"])
             for d, vw, vb, e in sorted(wb["sites"], key=lambda r: abs(r[0]), reverse=True)
             if abs(d) >= 0.01][:15 if n else None]
    if sites:
        out += "\n\n**Wait sites, slow vs fast:**\n\n" + md_table([f"Δ {u}", "slow", "fast", "State", "Waiting in"], sites)
    return out


def section_world(a, full):
    ps = a.md.platform_statistics
    if not ps.HasField("world"):
        return "_no world statistics_"
    w = ps.world
    parts = []
    ec = sorted(w.entity_counts.items(), key=lambda kv: kv[1], reverse=True)
    ec_sel = ec if full else ec[:30]
    parts.append(f"**Entities by type** ({w.total_entities:,} total, {len(ec)} types"
                 + ("" if full or len(ec) <= 30 else ", top 30") + "):\n\n"
                 + md_table(["Entity", "Count", "%"], [(k, f"{v:,}", pct(v, w.total_entities)) for k, v in ec_sel]))
    wrows, chunks = [], []
    for wd in w.worlds:
        nchunks = sum(len(r.chunks) for r in wd.regions)
        wrows.append((wd.name, f"{wd.total_entities:,}", len(wd.regions), nchunks))
        for r in wd.regions:
            for c in r.chunks:
                chunks.append((c.total_entities, wd.name, c))
    parts.append("**Worlds:**\n\n" + md_table(["World", "Entities", "Regions", "Chunks with entities"], wrows))
    chunks.sort(key=lambda t: t[0], reverse=True)
    n = 100 if full else 20
    crow = []
    for tot, wname, c in chunks[:n]:
        top = sorted(c.entity_counts.items(), key=lambda kv: kv[1], reverse=True)[:4]
        crow.append((wname, f"{c.x}, {c.z}", f"{c.x*16}, {c.z*16}", f"{tot:,}",
                     ", ".join(f"{k.removeprefix('minecraft:')} {v}" for k, v in top)))
    if crow:
        parts.append(f"**Most crowded chunks** (top {len(crow)}):\n\n"
                     + md_table(["World", "Chunk x, z", "Block x, z", "Entities", "Top types"], crow))
    if w.data_packs:
        parts.append("**Data packs:**\n\n" + md_table(
            ["Data pack", "Source", "Built-in", "Description"],
            [(d.name, d.source, "yes" if d.builtin else "", d.description) for d in w.data_packs]))
    if w.game_rules:
        grows = []
        for g in sorted(w.game_rules, key=lambda g: g.name):
            vals = dict(g.world_values)
            changed = {k: v for k, v in vals.items() if v != g.default_value}
            if full or changed:
                grows.append((g.name, g.default_value,
                              ", ".join(f"{k}={v}" for k, v in sorted((vals if full else changed).items()))))
        if grows:
            parts.append(("**Game rules:**" if full else "**Game rules changed from default:**") + "\n\n"
                         + md_table(["Rule", "Default", "Per world"], grows))
    return "\n\n".join(parts)


def section_server_config(a):
    cfgs = a.server_configs()
    if not cfgs:
        return "_no server configuration exported_"
    parts = []
    for name, data in cfgs.items():
        if isinstance(data, dict):
            lines = [f"{k}={v if isinstance(v, str) else json.dumps(v)}" for k, v in sorted(data.items())]
        else:
            lines = [str(data)]
        parts.append(f"**{name}**\n\n" + code_block(lines))
    return "\n\n".join(parts)


def section_mods(a, compact):
    srcs = a.md.sources
    keys = sorted(srcs.keys(), key=str.lower)
    if compact:
        return f"{len(keys)} sources: " + ", ".join(
            f"{srcs[k].name or k} {srcs[k].version}".strip() for k in keys if not srcs[k].builtin)
    rows = [(srcs[k].name or k, k, srcs[k].version, srcs[k].author, "built-in" if srcs[k].builtin else "")
            for k in keys]
    return md_table(["Mod / plugin", "Id", "Version", "Author", "Flags"], rows)


# --------------------------------------------------------------------------- #
# call tree emitters (iterative: stacks can be hundreds of frames deep)
# --------------------------------------------------------------------------- #
def _node_stats(a, ta, i):
    tot = ta.total[i]
    slf = ta.excl[i]
    tick = a.node_per_tick(ta, i)
    st = ta.state[i]
    return tot, slf, tick, st


def build_tree_html(a, ta, min_pct):
    thr = ta.grand_total * min_pct / 100
    out = [f"<details open><summary>{html.escape(f'100.00%  Thread: {ta.name}   ·   total {a.fmt(ta.grand_total)} · {len(ta.pool):,} nodes')}</summary>"]
    count = 0
    stack = [(False, r) for r in reversed(sorted_children(ta, ta.root_refs))]
    while stack:
        closing, i = stack.pop()
        if closing:
            out.append("</details>")
            continue
        if ta.total[i] < thr:
            continue
        count += 1
        tot, slf, tick, st = _node_stats(a, ta, i)
        nd = ta.pool[i]
        text = (f"{pct(tot, ta.grand_total):>7}  {label_line(nd)}   ·   total {a.fmt(tot)} · self {a.fmt(slf)} "
                f"({pct(slf, ta.grand_total)})" + (f" · {a.fmt_tick(tick)} {a.tick_unit}" if tick is not None else "")
                + f" · [{a.owner_of(nd)}]" + (f" · {STATE_NAMES[st]}" if st != RUNNING else ""))
        kids = [r for r in sorted_children(ta, nd.children_refs) if ta.total[r] >= thr]
        if kids:
            out.append(f"<details><summary>{html.escape(text)}</summary>")
            stack.append((True, i))
            stack.extend((False, r) for r in reversed(kids))
        else:
            out.append(f"<div>{html.escape(text)}</div>")
    out.append("</details>")
    return "\n".join(out), count


def build_tree_flat(a, ta, min_pct):
    thr = ta.grand_total * min_pct / 100
    out = [f"[100.00%] Thread: {ta.name}  (total {a.fmt(ta.grand_total)}, {len(ta.pool):,} nodes)"]
    stack = [(r, 1) for r in reversed(sorted_children(ta, ta.root_refs))]
    while stack:
        i, depth = stack.pop()
        if ta.total[i] < thr:
            continue
        tot, slf, tick, st = _node_stats(a, ta, i)
        nd = ta.pool[i]
        out.append(f"{'  ' * depth}[{100.0 * tot / ta.grand_total:6.2f}%] {label_line(nd)}  (self {a.fmt(slf)} "
                   f"{100.0 * slf / ta.grand_total:.2f}%" + (f", {a.fmt_tick(tick)} {a.tick_unit}" if tick is not None else "")
                   + f") [{a.owner_of(nd)}]" + (f" {{{STATE_NAMES[st]}}}" if st != RUNNING else ""))
        stack.extend((r, depth + 1) for r in reversed(sorted_children(ta, nd.children_refs)))
    return code_block(out), len(out) - 1


def build_hot_tree(a, ta, min_pct, max_lines=2500):
    """Pruned tree; single-child chains folded into one `A ▸ B ▸ C` line."""
    thr = ta.grand_total * min_pct / 100
    out = [f"[100.00%] Thread: {ta.name}  (total {a.fmt(ta.grand_total)})"]
    stack = [(r, 1) for r in reversed(sorted_children(ta, ta.root_refs))]
    truncated = False
    while stack:
        i, depth = stack.pop()
        if ta.total[i] < thr:
            continue
        if len(out) > max_lines:
            truncated = True
            break
        chain = [i]
        while True:
            last = chain[-1]
            kids = [r for r in ta.pool[last].children_refs if ta.total[r] >= thr]
            if (len(kids) == 1 and ta.total[kids[0]] >= ta.total[last] - thr
                    and ta.state[kids[0]] == ta.state[last]):
                chain.append(kids[0])
                continue
            break
        first, last = chain[0], chain[-1]
        p0 = 100.0 * ta.total[first] / ta.grand_total
        p1 = 100.0 * ta.total[last] / ta.grand_total
        tick = a.node_per_tick(ta, first)
        st = ta.state[last]
        stats = (f"self {100.0 * ta.excl[last] / ta.grand_total:.2f}%"
                 + (f", {a.fmt_tick(tick)} {a.tick_unit}" if tick is not None else "")
                 + (f", ends at {p1:.2f}%" if p0 - p1 >= 0.05 else ""))
        shown = [j for j in chain if j in (first, last) or not is_noise(ta.pool[j].class_name, ta.pool[j].method_name)]
        labels = " ▸ ".join(label_line(ta.pool[j]) for j in shown)
        if len(shown) < len(chain):
            labels += f"  (+{len(chain) - len(shown)} wrapper frames)"
        out.append(f"{'  ' * depth}[{p0:6.2f}%] {labels}  ({stats}) [{a.owner_of(ta.pool[last])}]"
                   + (f" {{{STATE_NAMES[st]}}}" if st != RUNNING else ""))
        stack.extend((r, depth + 1) for r in reversed(sorted_children(ta, ta.pool[last].children_refs)))
    if truncated:
        out.append(f"… truncated at {max_lines} lines; see .ai.md CALL_TREE")
    return code_block(out)


def build_icicle(a, ta, width=180, max_depth=26):
    """Text icicle / flame graph: cell width is proportional to total time, each
    level subdivides its parent. Branches narrower than ~1 char collapse."""
    lines = []
    drawn = 0
    level = [(0, width, ta.grand_total, sorted_children(ta, ta.root_refs), f"Thread: {ta.name}")]
    for _ in range(max_depth):
        if not level:
            break
        row = [" "] * width
        nxt = []
        for (start, w, ntot, kids, name) in level:
            if w <= 0:
                continue
            row[start] = "│"
            p = 100.0 * ntot / ta.grand_total if ta.grand_total else 0
            label = f"{name} {p:.1f}%"
            inner = w - 1
            if inner >= 3:
                for j, ch in enumerate(label[:inner]):
                    if start + 1 + j < width:
                        row[start + 1 + j] = ch
            if kids and ntot > 0:
                ctot = [ta.total[r] for r in kids]
                cw = min(w, round(w * sum(ctot) / ntot))
                cx = start
                for r, ww in zip(kids, xlsx_pyramid.alloc(cw, ctot)):
                    if ww <= 0:
                        continue
                    nd = ta.pool[r]
                    nxt.append((cx, ww, ta.total[r], sorted_children(ta, nd.children_refs), xlsx_pyramid.short_label(nd)))
                    cx += ww
                    drawn += 1
        lines.append("".join(row).rstrip())
        level = nxt
    note = (f"_Width ∝ time. {drawn:,} cells over {len(lines)} levels; branches thinner "
            f"than 1 char collapse — see the `.xlsx` pyramid or the full call tree._")
    return note + "\n\n" + code_block(lines)


# --------------------------------------------------------------------------- #
# renderer
# --------------------------------------------------------------------------- #
LEGEND = (
    "**How to read:** spark's async engine samples **wall-clock** time, so a thread's total includes time "
    "spent waiting. Every frame is classified as `running` or a wait state (`park`, `wait`, `blocked`, "
    "`sleep`, `io`, `safepoint`) by the first wait primitive on its stack. `total` = inclusive (frame + "
    "callees); `self` = exclusive. `ms/tick` = value ÷ ticks inside the retained windows (50 ms/tick of "
    "wall time = 20 TPS). `[source]` = owning mod via spark's mixin-aware source maps; `(native)` = "
    "JVM/libc/JIT stubs, `(jdk)` = JDK classes, `(unattributed: pkg)` = library or generated classes spark "
    "could not map (e.g. fastutil, datapack-compiled functions)."
)


def summary_threads(a, stats):
    """Threads that get detailed sections in the summary."""
    if len(a.threads) <= 3:
        return list(a.threads)
    total_run = sum(stats[t.name][0][RUNNING] for t in a.threads) or 1.0
    ranked = sorted(a.threads, key=lambda t: stats[t.name][0][RUNNING], reverse=True)
    pick = [t for t in ranked[:8] if stats[t.name][0][RUNNING] >= 0.02 * total_run]
    for t in a.threads:
        if t.name in MAIN_THREADS and t not in pick:
            pick.insert(0, t)
    return pick


def render(a, style, min_pct=0.0, summary_min_pct=1.0):
    """style: 'summary' (compact), 'ai' (flat, full) or 'human' (collapsible, full)."""
    full = style != "summary"
    human = style == "human"
    md = a.md
    P = []

    def H(level, key, title):
        """Human: decorated title. ai/summary: greppable KEY marker, emoji stripped."""
        if human:
            P.append(f"{'#' * level} {title}")
            return
        plain = re.sub(r"^[^\w`(]+", "", title).strip()
        P.append(f"{'#' * level} {key}" + (f" — {plain}" if plain and plain.strip("`") not in key else ""))

    name = os.path.basename(a.source_path or "")
    tps = md.platform_statistics.tps.last1m if md.platform_statistics.HasField("tps") else None
    kind = {"summary": "SUMMARY", "ai": "FULL DUMP", "human": "profile"}[style]
    P.append(f"# spark {kind} — {name} · {dur(md.end_time - md.start_time)}"
             + (f" · TPS {tps:.1f}" if tps is not None else ""))
    if style == "summary":
        P.append("> Compact diagnosis. Full data: `.ai.md` (flat, greppable) / `.human.md` (collapsible).\n>\n"
                 "> **Sections (grep `## `):** META · HEALTH · SYSTEM · GC · METRICS_10S · WINDOWS · "
                 "THREADS · THREAD (per thread: STATES · WAIT_SITES · PER_WINDOW · SELF_BY_SOURCE · TOP_SELF · "
                 "TOP_INCLUSIVE · WORST_VS_BEST · HOT_TREE) · WORLD · SERVER_CONFIG · JVM_FLAGS · MODS")
    elif style == "ai":
        P.append("> Navigable full dump for an agent with file access — grep/read sections as needed; nothing "
                 "is pruned.\n>\n> **Sections (grep `## `):** META · HEALTH · SYSTEM · MEMORY · GC · "
                 "METRICS_10S · WINDOWS · THREADS · THREAD (per thread: STATES · WAIT_SITES · PER_WINDOW · "
                 "CALL_PYRAMID · SELF_BY_SOURCE · METHODS_BY_SELF · METHODS_BY_INCLUSIVE · WORST_VS_BEST · "
                 "CALL_TREE) · WORLD · SERVER_CONFIG · JVM_FLAGS · MODS\n>\n> CALL_TREE lines: indentation = "
                 "depth, `[total%]`, `self`, per-tick value, `[source]`, `{state}` when waiting.")
    P.append(LEGEND)

    H(2, "META", "📋 Overview"); P.append(section_overview(a))
    H(2, "HEALTH", "❤️ Server health"); P.append(section_health(a))
    H(2, "SYSTEM", "🧮 System"); P.append(section_system(a))
    if full:
        H(2, "MEMORY", "🧠 Memory"); P.append(section_memory(a))
    H(2, "GC", "♻️ Garbage collection"); P.append(section_gc(a))
    H(2, "METRICS_10S", "📈 Metrics (10 s)"); P.append(section_metrics(a))
    H(2, "WINDOWS", "⏱️ Per-minute windows"); P.append(section_windows(a))

    stats = {ta.name: (a.state_totals(ta), a.flat_methods(ta)) for ta in a.threads}
    if len(a.threads) > 1:
        H(2, "THREADS", "🧵 Threads"); P.append(section_threads_overview(a, stats))

    detail = a.threads if full else summary_threads(a, stats)
    if not full and len(detail) < len(a.threads):
        P.append(f"_Detailed sections below cover {len(detail)} of {len(a.threads)} threads "
                 "(main thread + biggest CPU users); the rest are in `.ai.md`._")
    for ta in detail:
        st, fm = stats[ta.name]
        tick_total = a.per_tick(ta.grand_total, ta.window_totals)
        H(2, f"THREAD {ta.name}", f"🧵 `{ta.name}`")
        P.append(f"Sampled {a.fmt(ta.grand_total)} · {len(ta.pool):,} nodes"
                 + (f" · {a.fmt_tick(tick_total)} {a.tick_unit}" if tick_total is not None else ""))
        if not a.is_alloc:
            H(3, "STATES", "Thread states"); P.append(section_states(a, ta, st))
        has_wait = not a.is_alloc and st[RUNNING] < ta.grand_total * 0.999
        sites = a.wait_sites(ta, with_windows=len(a.window_ids) > 1) if has_wait else []
        if has_wait:
            H(3, "WAIT_SITES", "⌛ Wait sites"); P.append(section_wait_sites(a, ta, sites, None if full else 25))
        if len(a.window_ids) > 1 and not a.is_alloc:
            H(3, "PER_WINDOW", "Per-window breakdown")
            P.append(section_thread_windows(a, ta, a.state_windows(ta), sites))
        if full and not human:
            H(3, "CALL_PYRAMID", ""); P.append(build_icicle(a, ta, width=200))
        elif human:
            H(3, "CALL_PYRAMID", "🔺 Call pyramid (flame graph)")
            P.append("Top = whole thread; each level splits into what it called, width ∝ time. "
                     "Merged-cell version: companion `.pyramid.xlsx`.\n\n" + build_icicle(a, ta, width=180))
        H(3, "SELF_BY_SOURCE", "🔥 Self time by source"); P.append(section_by_mod(a, ta, None if full else 25))
        if full:
            H(3, "METHODS_BY_SELF", "Methods by running self time")
            if human:
                P.append("<details><summary>show all</summary>\n\n" + section_top_self(a, ta, fm) + "\n\n</details>")
            else:
                P.append(section_top_self(a, ta, fm))
        else:
            H(3, "TOP_SELF", "Top methods by running self time"); P.append(section_top_self(a, ta, fm, 40))
        want_wb = a.norm_ticks and len(a.tick_windows) >= 4 and (ta.name in MAIN_THREADS or len(a.threads) == 1)
        mi = a.method_inclusive(ta, with_windows=bool(want_wb) or (len(a.tick_windows) != len(a.window_ids)))
        if full:
            H(3, "METHODS_BY_INCLUSIVE", "Methods by inclusive time")
            body = section_top_inclusive(a, ta, mi)
            P.append(f"<details><summary>show all</summary>\n\n{body}\n\n</details>" if human else body)
        else:
            H(3, "TOP_INCLUSIVE", "Top methods by inclusive time"); P.append(section_top_inclusive(a, ta, mi, 40))
        if want_wb:
            H(3, "WORST_VS_BEST", "🐢 Slow vs fast windows")
            P.append(section_worst_best(a, ta, a.worst_vs_best(ta, mi, sites), None if full else 25))
        if style == "summary":
            H(3, "HOT_TREE", f"Hot call tree (≥{summary_min_pct:g}%)")
            P.append(f"_Branches ≥{summary_min_pct:g}% of the thread; single-child chains folded into "
                     "`A ▸ B ▸ C` (percent = first frame of the chain)._\n\n" + build_hot_tree(a, ta, summary_min_pct))

    if full:
        H(2, "CALL_TREE", "🌳 Call tree")
        P.append("_Children sorted heaviest-first"
                 + ("" if min_pct == 0 else f"; branches below {min_pct:g}% hidden") + "._")
        for ta in a.threads:
            if human:
                tree, nc = build_tree_html(a, ta, min_pct)
            else:
                tree, nc = build_tree_flat(a, ta, min_pct)
            H(3, f"CALL_TREE {ta.name}", f"`{ta.name}` — {nc:,} nodes shown")
            P.append(tree)

    H(2, "WORLD", "🌍 World"); P.append(section_world(a, full))
    H(2, "SERVER_CONFIG", "⚙️ Server configuration"); P.append(section_server_config(a))
    H(2, "JVM_FLAGS", "⚙️ JVM flags"); P.append(section_flags(a))
    H(2, "MODS", f"🧩 Mods / plugins ({len(md.sources)})")
    if human:
        P.append(f"<details><summary>show all {len(md.sources)}</summary>\n\n{section_mods(a, False)}\n\n</details>")
    else:
        P.append(section_mods(a, compact=not full))
    return "\n\n".join(P) + "\n"


# --------------------------------------------------------------------------- #
def _safe(name):
    return "".join(c if c.isalnum() else "_" for c in name).strip("_")


def process(path, out_dir, formats, min_pct=0.0, summary_min_pct=1.0, pyramid_cols=600):
    a = Analysis(load(path), path)
    base = os.path.splitext(os.path.basename(path))[0]
    out_dir = out_dir or os.path.dirname(os.path.abspath(path))
    os.makedirs(out_dir, exist_ok=True)
    written = []
    for style in ("summary", "ai", "human"):
        if style in formats:
            p = os.path.join(out_dir, f"{base}.{style}.md")
            with open(p, "w", encoding="utf-8") as f:
                f.write(render(a, style, min_pct, summary_min_pct))
            written.append(p)
    if "xlsx" in formats:
        multi = len(a.threads) > 1
        for ta in a.threads:
            suffix = f".{_safe(ta.name)}.pyramid.xlsx" if multi else ".pyramid.xlsx"
            p = os.path.join(out_dir, base + suffix)
            xlsx_pyramid.write_pyramid_xlsx(a, ta, p, ncols=pyramid_cols)
            written.append(p)
    return written


def parse_formats(s):
    fs = {x.strip() for x in s.split(",") if x.strip()}
    bad = fs - set(FORMATS)
    if bad:
        raise argparse.ArgumentTypeError(f"unknown format(s): {', '.join(sorted(bad))}; choose from {', '.join(FORMATS)}")
    return fs


def main():
    ap = argparse.ArgumentParser(description="Decode spark .sparkprofile files to Markdown.")
    ap.add_argument("files", nargs="+")
    ap.add_argument("--out-dir", default=None, help="output directory (default: next to the input)")
    ap.add_argument("--only", type=parse_formats, default=set(FORMATS),
                    help=f"comma-separated subset of {','.join(FORMATS)} (default: all)")
    ap.add_argument("--no-xlsx", action="store_true", help="skip the .pyramid.xlsx output")
    ap.add_argument("--min-percent", type=float, default=0.0,
                    help="hide full call-tree branches below this %% of thread total (default 0 = keep everything)")
    ap.add_argument("--summary-min-percent", type=float, default=1.0,
                    help="threshold for the summary's hot call tree (default 1)")
    ap.add_argument("--pyramid-cols", type=int, default=600,
                    help="resolution (columns) of the xlsx pyramid; higher = finer (default 600)")
    args = ap.parse_args()
    formats = set(args.only)
    if args.no_xlsx:
        formats.discard("xlsx")
    for path in args.files:
        written = process(path, args.out_dir, formats, args.min_percent, args.summary_min_percent, args.pyramid_cols)
        for w in written:
            sz = os.path.getsize(w)
            print(f"  wrote {w}  ({sz/1024/1024:.1f} MiB)" if sz > 1024 * 1024 else f"  wrote {w}  ({sz/1024:.0f} KiB)")


if __name__ == "__main__":
    main()
