"""Download the pinned inputs and build the server templates under FABROLIA_BENCH_HOME.

Usage: python setup.py

Creates (once; existing parts are kept):
  dl/                       every file of inputs.DOWNLOADS, SHA-256 checked
  tpl/fabric/               vanilla server.jar + Fabric server launcher
  tpl/fabric-cache/         libraries the vanilla bundler and Fabric unpack on first start
  tpl/neoforge/             NeoForge server from the installer
  tpl/paper-*/, tpl/folia-*/  patched jars, libraries and default configs from a first start
"""
import hashlib
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import inputs  # noqa: E402
import mc  # noqa: E402

HOME = mc.HOME
DL = HOME / 'dl'
TPL = HOME / 'tpl'


def download():
    DL.mkdir(parents=True, exist_ok=True)
    for name, (url, sha) in inputs.DOWNLOADS.items():
        target = DL / name
        if not target.exists():
            print('download', name, flush=True)
            req = urllib.request.Request(url, headers={'User-Agent': 'fabrolia-bench'})
            with urllib.request.urlopen(req) as r:
                target.write_bytes(r.read())
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        if digest != sha:
            target.unlink()
            raise SystemExit('SHA-256 mismatch for %s: %s' % (name, digest))


def first_start(directory, loader, command=None):
    """Start a server once in an empty world and stop it again; returns the directory."""
    srv = mc.Server(directory, loader, 26790, xmx='4G')
    if command:
        srv.command_line = command
    srv.write_properties(level_seed='template', gamemode='creative')
    srv.start()
    srv.wait_ready(timeout=1200)
    rc = srv.stop()
    if rc != 0:
        raise SystemExit('%s: first start ended with exit code %s' % (directory, rc))
    for name in ('world', 'world_nether', 'world_the_end', 'logs'):
        shutil.rmtree(directory / name, ignore_errors=True)


def fabric():
    tpl = TPL / 'fabric'
    if not tpl.exists():
        tpl.mkdir(parents=True)
        shutil.copy2(DL / inputs.SERVER, tpl / 'server.jar')
        shutil.copy2(DL / inputs.FABRIC_LAUNCHER, tpl / 'fabric-server-launch.jar')
        (tpl / 'eula.txt').write_text('eula=true\n')
    cache = TPL / 'fabric-cache'
    if not cache.exists():
        scratch = TPL / '.fabric-first-start'
        shutil.rmtree(scratch, ignore_errors=True)
        shutil.copytree(tpl, scratch)
        first_start(scratch, 'fabric')
        cache.mkdir()
        for name in ('libraries', 'versions', '.fabric'):
            shutil.copytree(scratch / name, cache / name)
        shutil.rmtree(scratch)


def neoforge():
    tpl = TPL / 'neoforge'
    if tpl.exists():
        return
    tpl.mkdir(parents=True)
    subprocess.run([mc.JAVA, '-jar', str(DL / inputs.NEOFORGE_INSTALLER), '--installServer', '.'],
                   cwd=tpl, check=True, stdout=subprocess.DEVNULL)
    (tpl / 'eula.txt').write_text('eula=true\n')


def paper(jar_name):
    tpl = TPL / Path(jar_name).stem
    if tpl.exists():
        return
    tpl.mkdir(parents=True)
    shutil.copy2(DL / jar_name, tpl)
    first_start(tpl, 'paper', lambda: [mc.JAVA, '-Xmx4G', '-jar', jar_name, '--nogui'])


if __name__ == '__main__':
    print('bench home:', HOME, '| java:', mc.JAVA, flush=True)
    download()
    fabric()
    neoforge()
    paper(inputs.PAPER)
    paper(inputs.FOLIA)
    print('ready; next: python prep_world.py', flush=True)
