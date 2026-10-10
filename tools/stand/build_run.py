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
import argparse, hashlib, importlib.util, json, os, re, shutil, zipfile
from pathlib import Path, PurePosixPath

HERE = Path(__file__).resolve().parent
HARNESS = ['native-transport.py', 'standenv.py', 'perf-wire-client.py', 'perf-driver.py',
           'verify-driver.py', 'autosave_check.py', 'verify_universe.py', 'mixin-export-driver.py',
           'scenarios.py', 'end-handoff-driver.py', 'raid-ticket-driver.py', 'raid-design-driver.py',
           'tnt-parity-driver.py']

p = argparse.ArgumentParser()
p.add_argument('name'); p.add_argument('jar')
p.add_argument('--scenario', action='append', default=[], choices=['items', 'pistons'])
p.add_argument('--heap', action='store_true')
p.add_argument('--export', action='store_true')
p.add_argument('--rcon', action='store_true', help='enable RCON for isolated lifecycle tests')
p.add_argument('--raid-fixture', action='store_true', help='use simulation distance 2 for the raid-ticket regression')
p.add_argument('--world-zip', type=Path, help='import a published world into this fresh isolated run')
p.add_argument('--vanilla-reference', action='store_true', help='only Carpet, spark and Fabric API; no Fabrolia or other gameplay mods')
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
# Preserve the artifact's identity, including alpha_X.Y.Z releases and old baselines.
shutil.copyfile(jar, mods / jar.name)
for extra in sorted((home / 'extra-mods').glob('*.jar')):
    shutil.copyfile(extra, mods / extra.name)
if a.vanilla_reference:
    for mod in mods.glob('*.jar'):
        with zipfile.ZipFile(mod) as archive:
            mod_id = json.loads(archive.read('fabric.mod.json')).get('id')
        if mod_id not in {'carpet', 'spark', 'fabric-api'}:
            mod.unlink()

import standenv  # noqa: E402  (this directory is on sys.path when run as a script)

props_path = server / 'server.properties'
props = props_path.read_text()
level = re.search(r'^level-name=(.*)$', props, re.M)[1]
settings = {'level-type': 'minecraft\\:flat', 'view-distance': '10', 'simulation-distance': '10',
            'max-tick-time': '-1'}
if a.raid_fixture:
    settings['simulation-distance'] = '2'
if a.export or a.rcon:
    settings.update({'enable-rcon': 'true', 'rcon.port': '25975', 'rcon.password': 'export-only-263'})
for k, v in settings.items():
    if re.search(r'^' + re.escape(k) + '=', props, re.M):
        props = re.sub(r'^' + re.escape(k) + '=.*$', lambda m: k + '=' + v, props, flags=re.M)
    else:
        props += f'\n{k}={v}\n'
props_path.write_text(props)

world_input = None
if a.world_zip:
    archive_path = a.world_zip.resolve(strict=True)
    world_input = {'path': str(archive_path), 'sha256': hashlib.sha256(archive_path.read_bytes()).hexdigest()}
    world = server / level
    if (world / 'level.dat').exists():
        raise SystemExit('template must not contain the imported world')
    # The stand template may already supply the empty world's datapacks.
    if a.vanilla_reference and (world / 'datapacks').exists():
        shutil.rmtree(world / 'datapacks')
    with zipfile.ZipFile(archive_path) as archive:
        levels = [n for n in archive.namelist() if n.endswith('/level.dat') and not n.startswith('__MACOSX/')]
        if len(levels) != 1:
            raise SystemExit('world ZIP must contain exactly one level.dat')
        prefix = levels[0][:-len('level.dat')]
        world_input['prefix'] = prefix
        total = 0
        for entry in archive.infolist():
            if not entry.filename.startswith(prefix) or entry.is_dir():
                continue
            rel = PurePosixPath(entry.filename[len(prefix):])
            if rel.is_absolute() or '..' in rel.parts or '\\' in str(rel):
                raise SystemExit('unsafe world ZIP path')
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise SystemExit('world ZIP symlink forbidden')
            if any(part.startswith('._') for part in rel.parts):
                continue
            # Only saved Minecraft world data, never executable tools or packs.
            if rel.parts[0] not in {'level.dat', 'region', 'entities', 'poi', 'data', 'DIM-1', 'DIM1', 'dimensions'}:
                continue
            if rel.name in {'raids.dat', 'session.lock'}:
                continue  # start with no saved raid, preserve the actual build
            if rel.suffix not in {'.dat', '.mca', '.mcc'}:
                continue
            total += entry.file_size
            if total > 512 * 1024 * 1024:
                raise SystemExit('world ZIP exceeds the stand import limit')
            target = world / Path(*rel.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))

packs = server / level / 'datapacks'
packs.mkdir(parents=True, exist_ok=True)
for pack in ([] if a.vanilla_reference else sorted((home / 'datapacks').iterdir())):
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
            'vanilla_reference': a.vanilla_reference, 'world_input': world_input,
            'commands_sha256': t.digest(dst / 'commands.json'),
            'transport_sha256': t.digest(dst / 'native-transport.py')}
(dst / 'selected.json').write_text(json.dumps(manifest, indent=2, sort_keys=True))
print('built', dst, 'files', len(manifest['input_sha256']), 'functions', functions)
