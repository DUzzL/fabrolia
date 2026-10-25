"""Server configurations under test and how a run directory is built for each of them."""
import os
import shutil
from pathlib import Path

import inputs
import mc

ROOT = mc.HOME
DL = ROOT / 'dl'
FABRIC_API = DL / inputs.FABRIC_API
LITHIUM = DL / inputs.LITHIUM
C2ME = DL / inputs.C2ME
SCALABLELUX = DL / inputs.SCALABLELUX
# The jar under test: FABROLIA_JAR, else the newest Fabric build of this checkout.
FABROLIA = Path(os.environ['FABROLIA_JAR']) if os.environ.get('FABROLIA_JAR') else max(
    (mc.REPO / 'fabric' / 'build' / 'libs').glob('fabrolia-*.jar'), key=lambda p: p.stat().st_mtime)

CONFIGS = {
    'vanilla': {'kind': 'vanilla', 'mods': []},
    'lithium': {'kind': 'fabric', 'mods': [LITHIUM]},
    'c2me': {'kind': 'fabric', 'mods': [C2ME]},
    'lithium-c2me': {'kind': 'fabric', 'mods': [LITHIUM, C2ME]},
    'paper': {'kind': 'paper', 'jar': DL / inputs.PAPER},
    'folia': {'kind': 'paper', 'jar': DL / inputs.FOLIA},
    'fabrolia': {'kind': 'fabric', 'mods': [FABRIC_API, FABROLIA], 'fabrolia': 'default'},
    'fabrolia-async': {'kind': 'fabric', 'mods': [FABRIC_API, FABROLIA], 'fabrolia': 'async'},
    'fabrolia-async-lithium-c2me': {'kind': 'fabric', 'mods': [FABRIC_API, FABROLIA, LITHIUM, C2ME],
                                    'fabrolia': 'async'},
    'scalablelux': {'kind': 'fabric', 'mods': [SCALABLELUX]},
    'fabrolia-async-scalablelux': {'kind': 'fabric', 'mods': [FABRIC_API, FABROLIA, SCALABLELUX], 'fabrolia': 'async'},
    'fabrolia-parallel': {'kind': 'fabric', 'mods': [FABRIC_API, FABROLIA], 'fabrolia': 'parallel'},
}


def prepare(config, run, world_template, port, xmx, view_distance, simulation_distance):
    """Fresh run directory with the server, its mods and a copy of the world; returns mc.Server."""
    cfg = CONFIGS[config]
    if run.exists():
        shutil.rmtree(run)
    if cfg['kind'] == 'paper':
        shutil.copytree(ROOT / 'tpl' / cfg['jar'].stem, run)
        loader = 'paper'
    else:
        run.mkdir(parents=True)
        shutil.copy2(ROOT / 'tpl' / 'fabric' / 'server.jar', run)
        for lib in ('libraries', 'versions', '.fabric'):  # unpacked bundler and loader cache
            shutil.copytree(ROOT / 'tpl' / 'fabric-cache' / lib, run / lib)
        loader = 'vanilla'
        if cfg['kind'] == 'fabric':
            loader = 'fabric'
            shutil.copy2(ROOT / 'tpl' / 'fabric' / 'fabric-server-launch.jar', run)
            (run / 'mods').mkdir()
            for m in cfg['mods']:
                shutil.copy2(m, run / 'mods')
        mode = cfg.get('fabrolia')
        if mode:
            (run / 'config').mkdir(exist_ok=True)
            on = 'false' if mode == 'default' else 'true'
            (run / 'config' / 'fabrolia.toml').write_text(
                'asyncIslands = %s\nenablePlayerTickActor = %s\nenableRegionThreading = true\n'
                'islandEntityParallel = %s\n' % (on, on, 'true' if mode == 'parallel' else 'false'))
    if world_template is not None:
        shutil.copytree(world_template, run / 'world')
    srv = mc.Server(run, loader, port, xmx=xmx, jvm_extra=mc.AIKAR)
    if loader == 'paper':
        jar = cfg['jar'].name
        srv.command_line = lambda: [mc.JAVA, '-Xms' + xmx, '-Xmx' + xmx] + mc.AIKAR + ['-jar', jar, '--nogui']
    srv.write_properties(gamemode='creative', force_gamemode='true', view_distance=view_distance,
                         simulation_distance=simulation_distance, difficulty='easy')
    return srv


def describe(config):
    cfg = CONFIGS[config]
    return {'config': config, 'kind': cfg['kind'], 'mods': [m.name for m in cfg.get('mods', [])],
            'jar': cfg['jar'].name if 'jar' in cfg else None, 'fabrolia_mode': cfg.get('fabrolia')}
