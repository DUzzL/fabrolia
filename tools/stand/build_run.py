#!/usr/bin/env python3
"""build_run.py NAME FABROLIA_JAR [options] — build a fresh stand run.

Layout (FABROLIA_STAND_HOME, set by stand.sh):
  template/server/   production-like 26.3 server inputs (mods, libraries, configs; no world)
  template/commands.json   transport recipe (JVM args, timeouts)
  datapacks/         production datapacks (BACAP etc.), copied into the run's world
  fabrolia.toml      Fabrolia config used by the run
  extra-mods/*.jar   jars added to every run (MCFC)
  runs/NAME/         the run built here

The run gets the harness scripts from this directory, the jar under test, the perf
population pack (perfstand:town/spread) and optional scenario functions.

Options:
  --scenario NAME    add a scenario function and call it after population
                     (items: 3000 items in flowing water near Far05;
                      pistons: 225 observer/piston clocks near Far05)
  --heap             take two `jcmd GC.class_histogram` snapshots after warm-up
  --export           enable RCON and -Dmixin.debug.export=true (mixin export run)
"""
import argparse, hashlib, importlib.util, json, os, re, shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
HARNESS = ['native-transport.py', 'standenv.py', 'perf-wire-client.py', 'perf-driver.py',
           'verify-driver.py', 'autosave_check.py', 'verify_universe.py', 'mixin-export-driver.py',
           'scenarios.py']

p = argparse.ArgumentParser()
p.add_argument('name'); p.add_argument('jar')
p.add_argument('--scenario', action='append', default=[], choices=['items', 'pistons'])
p.add_argument('--heap', action='store_true')
p.add_argument('--export', action='store_true')
a = p.parse_args()

home = Path(os.environ['FABROLIA_STAND_HOME']).resolve()
template = home / 'template'
dst = home / 'runs' / a.name
jar = Path(a.jar).resolve()
if dst.exists():
    raise SystemExit('exists: ' + str(dst))
dst.mkdir(parents=True)
for name in HARNESS:
    shutil.copy2(HERE / name, dst / name)
shutil.copyfile(template / 'commands.json', dst / 'commands.json')
shutil.copytree(template / 'server', dst / 'server')
server = dst / 'server'
mods = server / 'mods'
if list(mods.glob('fabrolia-*.jar')):
    raise SystemExit('template must not contain a Fabrolia jar')
shutil.copyfile(jar, mods / jar.name)
for extra in sorted((home / 'extra-mods').glob('*.jar')):
    shutil.copyfile(extra, mods / extra.name)

import standenv  # noqa: E402  (this directory is on sys.path when run as a script)

props_path = server / 'server.properties'
props = props_path.read_text()
level = re.search(r'^level-name=(.*)$', props, re.M)[1]
settings = {'level-type': 'minecraft\\:flat', 'view-distance': '10', 'simulation-distance': '10',
            'max-tick-time': '-1'}
if a.export:
    settings.update({'enable-rcon': 'true', 'rcon.port': '25975', 'rcon.password': 'export-only-263'})
for k, v in settings.items():
    if re.search(r'^' + re.escape(k) + '=', props, re.M):
        props = re.sub(r'^' + re.escape(k) + '=.*$', lambda m: k + '=' + v, props, flags=re.M)
    else:
        props += f'\n{k}={v}\n'
props_path.write_text(props)

packs = server / level / 'datapacks'
packs.mkdir(parents=True, exist_ok=True)
for pack in sorted((home / 'datapacks').iterdir()):
    (shutil.copytree if pack.is_dir() else shutil.copyfile)(pack, packs / pack.name)
(server / 'config').mkdir(parents=True, exist_ok=True)
shutil.copyfile(home / 'fabrolia.toml', server / 'config' / 'fabrolia.toml')

import subprocess  # noqa: E402
subprocess.run(['python3', str(dst / 'perf-driver.py'), '--make-pack', str(packs)], check=True,
               stdout=subprocess.DEVNULL)
import scenarios  # noqa: E402
functions = [scenarios.write(packs / 'perfstand', s) for s in a.scenario]
(dst / 'options.json').write_text(json.dumps({'functions': functions, 'heap_histogram': a.heap}, indent=2))

recipe = json.loads((dst / 'commands.json').read_text())
args = [x for x in recipe['java_args'] if not x.startswith('-Xmx')]
jar_at = args.index('-jar')
extra = ['-Xmx' + os.environ.get('FABROLIA_STAND_XMX', '16G')]
if standenv.SERVER_CPU_COUNT:
    extra.append(f'-XX:ActiveProcessorCount={standenv.SERVER_CPU_COUNT}')
if a.export:
    extra.append('-Dmixin.debug.export=true')
recipe['java_args'] = args[:jar_at] + extra + args[jar_at:]
(dst / 'commands.json').write_text(json.dumps(recipe, indent=2, sort_keys=True))

spec = importlib.util.spec_from_file_location('native_transport', dst / 'native-transport.py')
t = importlib.util.module_from_spec(spec); spec.loader.exec_module(t)
manifest = {'input_sha256': t.inventory(server),
            'fabrolia_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
            'commands_sha256': t.digest(dst / 'commands.json'),
            'transport_sha256': t.digest(dst / 'native-transport.py')}
(dst / 'selected.json').write_text(json.dumps(manifest, indent=2, sort_keys=True))
print('built', dst, 'files', len(manifest['input_sha256']), 'functions', functions)
