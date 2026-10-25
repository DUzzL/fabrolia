"""Overnight benchmark queue: every scenario for every configuration, one run at a time.

Every finished run is archived immediately under results/<scenario>/<run>/ (bench.json with
all per-second samples, the server console, the run log), so charts can be redrawn later.
Progress goes to results/overnight.log.
"""
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mc  # noqa: E402

HERE = Path(__file__).resolve().parent
ROOT = mc.HOME
RUNS = ROOT / 'runs'
RESULTS = ROOT / 'results'
PY = sys.executable
CAMPAIGN_OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else None
ENV = dict(os.environ, PYTHONIOENCODING='utf-8')

# Fabrolia is measured with async islands and player ticks on (its defaults from 3.1.11 on).
ALL = ['vanilla', 'lithium', 'c2me', 'lithium-c2me', 'paper', 'folia', 'fabrolia-async',
       'fabrolia-async-lithium-c2me']
SCALING_CONFIGS = ['vanilla', 'lithium', 'lithium-c2me', 'paper', 'folia', 'fabrolia-async',
                   'fabrolia-async-lithium-c2me']
THREADS = [1, 2, 4, 6, 8, 12, 16]
RESULTS.mkdir(exist_ok=True)
LOG = open(RESULTS / 'overnight.log', 'a', encoding='utf-8')
if os.name == 'nt':
    # Keep the machine awake while this process runs (ES_CONTINUOUS | ES_SYSTEM_REQUIRED);
    # no power settings are changed, the request ends with the process.
    import ctypes
    ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | 0x00000001)


def log(msg):
    line = '%s %s' % (time.strftime('%H:%M:%S'), msg)
    print(line, flush=True)
    LOG.write(line + '\n')
    LOG.flush()


def archive(scenario, name):
    dst = RESULTS / scenario / name
    dst.mkdir(parents=True, exist_ok=True)
    for src in (RUNS / name / 'bench.json', RUNS / name / 'console.log', RUNS / (name + '.log')):
        if src.exists():
            shutil.copy2(src, dst / src.name)
    crashes = RUNS / name / 'crash-reports'
    if crashes.exists():
        shutil.copytree(crashes, dst / 'crash-reports', dirs_exist_ok=True)
    shutil.rmtree(RUNS / name, ignore_errors=True)  # libraries and caches; the evidence is archived


def status(name):
    try:
        return json.loads((RUNS / name / 'bench.json').read_text()).get('status', '?')
    except (OSError, ValueError):
        return 'no result'


def done(scenario, name):
    try:
        return json.loads((RESULTS / scenario / name / 'bench.json').read_text()).get('status') == 'ok'
    except (OSError, ValueError):
        return False


def run(scenario, name, args, timeout=3600):
    if done(scenario, name):  # resume: finished runs stay as they are
        return 'ok'
    shutil.rmtree(RUNS / name, ignore_errors=True)
    log('start %s %s' % (scenario, name))
    with open(RUNS / (name + '.log'), 'w', encoding='utf-8') as out:
        try:
            subprocess.run([PY] + args, cwd=HERE, stdout=out, stderr=subprocess.STDOUT, env=ENV, timeout=timeout)
        except subprocess.TimeoutExpired:
            out.write('TIMEOUT\n')
    st = status(name)
    log('  -> %s' % st)
    archive(scenario, name)
    time.sleep(15)
    return st


def prep(scenario, size=0, reuse=True):
    meta_path = RUNS / (scenario + '-template') / 'bench-meta.json'
    if reuse and meta_path.exists():  # resume: keep the template the finished runs used
        return json.loads(meta_path.read_text())
    log('prep %s size=%s' % (scenario, size or 'default'))
    with open(RUNS / ('prep-%s.log' % scenario), 'w', encoding='utf-8') as out:
        subprocess.run([PY, 'prep2.py', scenario] + (['--size', str(size)] if size else []), cwd=HERE,
                       stdout=out, stderr=subprocess.STDOUT, env=ENV, timeout=5400)
    meta = json.loads((RUNS / (scenario + '-template') / 'bench-meta.json').read_text())
    dst = RESULTS / scenario
    dst.mkdir(parents=True, exist_ok=True)
    shutil.copy2(RUNS / (scenario + '-template') / 'bench-meta.json', dst / 'template-meta.json')
    shutil.copy2(RUNS / ('prep-%s.log' % scenario), dst / ('prep-%s.log' % scenario))
    log('  template %s' % json.dumps({k: v for k, v in meta.items() if k not in ('spots', 'bots')}))
    return meta


def calibrated(scenario, size, cap):
    """Rebuild until 32 bases put vanilla at ~2.5-6 TPS (its 20 TPS limit near 6 bases)."""
    if (RUNS / (scenario + '-template') / 'bench-meta.json').exists():
        return prep(scenario)
    for _ in range(4):
        meta = prep(scenario, size, reuse=False)
        tps = meta.get('prep_tps_all_present', 0)
        if 2.5 <= tps <= 6.0:
            return meta
        new = size * 3 if tps >= 19 else max(1, round(size * tps * 0.267))
        new = min(cap, new)
        if new == size:
            return meta
        size = new
    return meta


if CAMPAIGN_OUT:
    while 'campaign done' not in CAMPAIGN_OUT.read_text(errors='replace'):
        time.sleep(20)
log('=== overnight queue starts')

# 1. Mob ramp: repeat the two runs a network drop cut off, same settings as the campaign.
for c in ('c2me', 'fabrolia'):
    run('mobs', 'c1-' + c, ['bench.py', c, 'c1-' + c, '--steps', '30', '--step-seconds', '20', '--per-step', '48',
                            '--warmup', '30', '--tail', '20'])
for c in ALL:  # archive the campaign's runs too
    if (RUNS / ('c1-' + c)).exists():
        archive('mobs', 'c1-' + c)



def section(title, fn):
    # One failing scenario must not cost the rest of the night.
    try:
        fn()
    except Exception as e:
        log('!!! %s failed: %r' % (title, e))


def scaling():
    prep('scaling')
    for threads in THREADS:
        for c in SCALING_CONFIGS:
            name = 'sc-%s-t%02d' % (c, threads)
            run('scaling', name, ['bench2.py', 'scaling', c, name, '--threads', str(threads)])


def bases(scenario, prefix, start_size=None, cap=None):
    if start_size:
        calibrated(scenario, start_size, cap)
    else:
        prep(scenario)
    for c in ALL:
        run(scenario, prefix + c, ['bench2.py', scenario, c, prefix + c])


section('scaling', scaling)                                       # 2. thread scaling, full mob load
section('chunks', lambda: bases('chunks', 'ch-'))                 # 3. many loaded chunks
section('hoppers', lambda: bases('hoppers', 'ho-', 20, 45))       # 4. hoppers, calibrated on vanilla
section('redstone', lambda: bases('redstone', 'rs-', 3, 30))      # 5. piston clocks, calibrated
section('worldgen', lambda: bases('worldgen', 'wg-'))             # 6. world generation

log('=== overnight queue done')
