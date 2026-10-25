#!/usr/bin/env python3
"""Compare real Minecraft 26.3 minecart pushing on Linux or Windows.

The template is a world-free Fabric server directory with its launcher, server
bundle, and libraries. --mods supplies explicit Carpet/Fabric API jars; add spark
for --perf-seconds. No production server or external client is contacted.

Example (the same template/mods must be used for every comparison):
  python minecart-parity-driver.py RUN --template TEMPLATE --mods API CARPET
  python minecart-parity-driver.py RUN --template TEMPLATE --mods API CARPET \
    --jar FABROLIA --mode parallel --compare-report VANILLA_REPORT

Without --template, RUN/server must already be an isolated, prepared server.
Its properties must bind both game traffic and RCON to loopback. A completed run
is never reused. Item.Age verifies every physical tick without introducing TNT,
which would independently force serial island entity ticks.

This focused mechanics/performance fixture does not replace the production
modpack's 40-client verify/perf stand.
"""

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import time
import traceback
import zipfile


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    parser.add_argument('--template', type=Path)
    parser.add_argument('--mods', type=Path, nargs='*', default=[])
    parser.add_argument('--jar', type=Path, help='Fabrolia candidate; omit for Vanilla reference')
    parser.add_argument('--mode', choices=('sync', 'serial', 'parallel'), default='parallel')
    parser.add_argument('--suite', choices=('basic', 'extra', 'all'), default='basic')
    parser.add_argument('--compare-report', type=Path)
    parser.add_argument('--expect-old-failure', action='store_true')
    parser.add_argument('--java', default=os.environ.get('FABROLIA_STAND_JAVA', 'java'))
    parser.add_argument('--launcher', default='fabric-server-launch.jar')
    parser.add_argument('--heap', default='3G')
    parser.add_argument('--server-port', type=int, default=25945)
    parser.add_argument('--rcon-port', type=int, default=25946)
    parser.add_argument('--boot-timeout', type=int, default=180)
    parser.add_argument('--perf-seconds', type=int, default=0)
    parser.add_argument('--perf-only', action='store_true')
    args = parser.parse_args()
    if args.expect_old_failure and (not args.jar or not args.compare_report):
        parser.error('--expect-old-failure requires a candidate and Vanilla comparison')
    if args.perf_only and args.perf_seconds <= 0:
        parser.error('--perf-only requires --perf-seconds')
    if args.perf_only and (args.compare_report or args.expect_old_failure):
        parser.error('--perf-only cannot skip a requested mechanics comparison')
    if not re.fullmatch(r'[1-9][0-9]*[GM]', args.heap):
        parser.error('--heap must be a positive integer followed by G or M')
    if args.server_port == args.rcon_port or not all(
            1024 <= port <= 65535 for port in (args.server_port, args.rcon_port)):
        parser.error('game and RCON need different unprivileged ports')
    return args


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def properties(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines()
                if '=' in line and not line.lstrip().startswith('#'))


def build_run(args):
    run = args.run.resolve()
    if args.template:
        if run.exists():
            raise RuntimeError('Fresh run already exists: ' + str(run))
        template = args.template.resolve(strict=True)
        if any(template.glob('*/level.dat')):
            raise RuntimeError('Template must not contain a saved world')
        for path in [*args.mods, *([args.jar] if args.jar else [])]:
            path.resolve(strict=True)
        run.mkdir(parents=True)
        server = run / 'server'
        shutil.copytree(template, server)
        mods = server / 'mods'
        mods.mkdir(exist_ok=True)
        if list(mods.glob('fabrolia*.jar')):
            raise RuntimeError('Template must not contain a Fabrolia jar')
        for source in [*args.mods, *([args.jar] if args.jar else [])]:
            shutil.copy2(source, mods / source.name)
        (server / 'eula.txt').write_text('eula=true\n')
        settings = {
            'server-ip': '127.0.0.1', 'server-port': str(args.server_port),
            'online-mode': 'false', 'enable-rcon': 'true',
            'rcon.port': str(args.rcon_port), 'rcon.password': 'minecart-parity-local-only',
            'level-type': 'minecraft\\:flat', 'level-name': 'world',
            'generator-settings': json.dumps({'layers': [
                {'block': 'minecraft:bedrock', 'height': 1},
                {'block': 'minecraft:dirt', 'height': 2},
                {'block': 'minecraft:grass_block', 'height': 1}], 'biome': 'minecraft:plains'}),
            'spawn-protection': '0', 'view-distance': '6', 'simulation-distance': '6',
            'max-tick-time': '-1', 'allow-nether': 'false',
            'motd': 'Isolated Minecart Parity Fixture',
        }
        (server / 'server.properties').write_text(
            ''.join(key + '=' + value + '\n' for key, value in settings.items()))
        (server / 'config').mkdir(exist_ok=True)
        (server / 'config/fabrolia.toml').write_text(
            'enableRegionThreading = true\nenablePlayerTickActor = true\n'
            'regionWorkerCount = 2\nislandEntityParallel = '
            + str(args.mode == 'parallel').lower() + '\n')
    server = run / 'server'
    if (run / 'minecart-parity-report.json').exists():
        raise RuntimeError('Completed run must not be reused: ' + str(run))
    settings = properties(server / 'server.properties')
    if settings.get('server-ip') != '127.0.0.1' or settings.get('enable-rcon') != 'true':
        raise RuntimeError('Requires an isolated loopback server with RCON enabled')
    if settings.get('online-mode') != 'false':
        raise RuntimeError('Fixture requires an offline local server')
    if not (server / args.launcher).is_file():
        raise RuntimeError('Missing Fabric launcher: ' + args.launcher)
    deployed = []
    for mod in (server / 'mods').glob('*.jar'):
        with zipfile.ZipFile(mod) as archive:
            metadata = json.loads(archive.read('fabric.mod.json'))
        if metadata.get('id') == 'fabrolia':
            deployed.append(mod)
        if args.jar is None and metadata.get('id') not in {'fabric-api', 'carpet', 'spark'}:
            raise RuntimeError('Vanilla reference contains gameplay mod: ' + str(mod))
    if args.jar:
        if len(deployed) != 1 or sha256(deployed[0]) != sha256(args.jar):
            raise RuntimeError('--jar must match the single deployed Fabrolia artifact')
    elif deployed:
        raise RuntimeError('Vanilla reference must not deploy Fabrolia')
    for port in (int(settings['server-port']), int(settings['rcon.port'])):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    return run, server, settings


class Fixture:
    def __init__(self, args, run, server, settings):
        self.args, self.run, self.server, self.settings = args, run, server, settings
        self.process = None
        self.log = None
        self.report = {
            'status': 'RUNNING', 'vanilla_reference': args.jar is None,
            'mode': args.mode, 'suite': args.suite, 'cases': {}, 'commands': [],
            'clock': 'actual Item.Age NBT', 'cell_boundary_x': 128,
            'jar_sha256': sha256(args.jar) if args.jar else None,
            'mod_sha256': {path.name: sha256(path) for path in sorted((server / 'mods').glob('*.jar'))},
        }

    def command(self, command):
        with socket.create_connection(('127.0.0.1', int(self.settings['rcon.port'])), timeout=45) as sock:
            def read_exact(size):
                data = b''
                while len(data) < size:
                    piece = sock.recv(size - len(data))
                    if not piece:
                        raise EOFError('RCON closed')
                    data += piece
                return data

            def packet(request_id, kind, body):
                payload = struct.pack('<ii', request_id, kind) + body.encode() + b'\x00\x00'
                sock.sendall(struct.pack('<i', len(payload)) + payload)
                size = struct.unpack('<i', read_exact(4))[0]
                if not 10 <= size <= 1024 * 1024:
                    raise ValueError('Invalid RCON packet length')
                response = read_exact(size)
                return struct.unpack('<ii', response[:8]), response[8:-2].decode(errors='replace')

            (request_id, _), _ = packet(1, 3, self.settings['rcon.password'])
            if request_id != 1:
                raise RuntimeError('RCON authentication refused')
            (_, _), output = packet(2, 2, command)
        self.report['commands'].append({'command': command, 'out': output})
        if any(message in output for message in (
                'Unknown or incomplete', 'Incorrect argument', 'An unexpected error')):
            raise RuntimeError(command + ': ' + output)
        return output

    def wait_until(self, name, predicate, seconds=30):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError('Server exited: ' + str(self.process.returncode))
            try:
                if predicate():
                    return
            except (OSError, EOFError):
                pass
            time.sleep(.05)
        raise TimeoutError(name)

    def launch(self):
        version = subprocess.run([self.args.java, '-version'], capture_output=True,
                                 text=True, timeout=15, check=True)
        self.report['java_version'] = (version.stdout + version.stderr).strip()
        if not re.search(r'version "25(?:\.|\")', self.report['java_version']):
            raise AssertionError('This regression requires Java 25')
        self.log = (self.run / 'console.log').open('w', encoding='utf-8')
        self.process = subprocess.Popen(
            [self.args.java, '-Xms512M', '-Xmx' + self.args.heap,
             '-XX:ActiveProcessorCount=4']
            # Async islands are always on; the synchronous control is a JVM option.
            + (['-Dfabrolia.syncMode=true'] if self.args.mode == 'sync' else [])
            + ['-jar', self.args.launcher, 'nogui'],
            cwd=self.server, stdin=subprocess.PIPE, stdout=self.log, stderr=subprocess.STDOUT,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        self.wait_until('server boot', lambda: 'Done (' in
                        (self.run / 'console.log').read_text(errors='replace'), self.args.boot_timeout)
        boot_log = (self.run / 'console.log').read_text(errors='replace')
        match = re.search(r'Loading Minecraft ([^ ]+) with Fabric Loader ([^\r\n]+)', boot_log)
        if not match or match[1] != '26.3':
            raise AssertionError('This regression requires an actual Minecraft 26.3 Fabric server')
        self.report['minecraft_version'], self.report['fabric_loader_version'] = match.groups()
        self.report['java_args'] = self.process.args
        self.wait_until('RCON ready', lambda: 'players online' in self.command('list'))
        print('MINECART_PARITY_PHASE booted', flush=True)

    def setup(self):
        for command in ('carpet commandPlayer true', 'carpet allowSpawningOfflinePlayers true',
                        'gamerule minecraft:spawn_mobs false',
                        'gamerule minecraft:advance_time false',
                        'gamerule minecraft:advance_weather false', 'time set 6000'):
            self.command(command)
        if self.args.jar:
            self.command('fabrolia regions async ' + ('off' if self.args.mode == 'sync' else 'on'))
            self.command('fabrolia regions islandentities '
                         + ('parallel' if self.args.mode == 'parallel' else 'vanilla'))
        self.command('player CartProbe spawn at 96.5 66 8.5 facing 0 0 in minecraft:overworld in creative')
        self.command('forceload add 32 -16 159 31')
        for x in (64, 96, 128):
            marker = f'MINECART_PARITY_LOADED_{x}'
            def loaded(x=x, marker=marker):
                self.command(f'execute if loaded {x} 64 0 run say {marker}')
                return marker in (self.run / 'console.log').read_text(errors='replace')
            self.wait_until('fixture chunk loaded', loaded)
        time.sleep(1)
        if self.args.jar and self.args.mode != 'sync':
            output = self.command('execute positioned 96 64 0 run fabrolia regions coherence add')
            if 'pinned to the global tick phase' not in output:
                raise AssertionError('Coherence pin refused: ' + output)
        time.sleep(1)
        self.command('tick freeze')
        if self.args.jar and self.args.mode != 'sync':
            for attempt in range(30):
                top = self.command('fabrolia regions top verbose 100')
                clocks = re.findall(r' L=(\d+) gt=(\d+) ', top)
                if clocks and all(local == global_ for local, global_ in clocks):
                    break
                self.command('tick unfreeze')
                time.sleep(1)
                self.command('tick freeze')
            else:
                raise AssertionError('Pinned island never level with game time: ' + top)
            self.report['islands_before'] = top
            self.report['fixture_level_attempts'] = attempt
        for center in (64, 128):
            self.command(f'fill {center-16} 62 -2 {center+15} 66 2 minecraft:air')
            self.command(f'fill {center-16} 63 0 {center+15} 63 0 minecraft:stone')
            self.command(f'fill {center-16} 64 0 {center+15} 64 0 minecraft:rail[shape=east_west]')

    def entity_data(self, tag, path):
        output = self.command(f'data get entity @e[tag={tag},limit=1] {path}')
        marker = 'following entity data: '
        if marker not in output:
            raise AssertionError('Missing entity NBT: ' + output)
        return output.split(marker, 1)[1].strip()

    def age(self):
        return int(self.entity_data('parity_clock', 'Age').rstrip('sS'))

    def state(self, tag):
        def vector(path):
            value = self.entity_data(tag, path)
            return [float(part.strip().rstrip('dDfF')) for part in value[1:-1].split(',')]
        return {'pos': vector('Pos'), 'motion': vector('Motion')}

    def case(self, name, specs, reverse):
        self.command('kill @e[tag=parity_cart]')
        self.command('kill @e[tag=parity_clock]')
        self.command('summon minecraft:item 96.5 90 20.5 '
                     '{NoGravity:1b,Age:0s,PickupDelay:32767s,'
                     'Item:{id:"minecraft:stone",count:1},Tags:["parity_clock"]}')
        for tag, kind, x, velocity in reversed(specs) if reverse else specs:
            self.command(f'summon minecraft:{kind} {x} 64.0625 .5 '
                         f'{{Motion:[{velocity}d,0d,0d],Tags:["parity_cart","parity_{tag}"]}}')
        observed = []
        for tick in range(6):
            if tick:
                self.command('tick step 1t')
                self.wait_until(name + ' physical tick', lambda: self.age() >= tick, 15)
            if self.age() != tick:
                raise AssertionError(name + ': unexpected physical entity tick count')
            observed.append({'tick': tick, **{tag: self.state('parity_' + tag)
                                             for tag, _, _, _ in specs}})
        self.report['cases'][name] = observed
        print('MINECART_PARITY_CASE', name, flush=True)

    def mechanics(self):
        for center in (64, 128):
            for types in (('minecart', 'minecart'), ('minecart', 'hopper_minecart'),
                          ('hopper_minecart', 'hopper_minecart')):
                for reverse in (False, True):
                    motions = []
                    if self.args.suite in ('basic', 'all'):
                        motions += [(.2, 0), (.2, -.2)]
                    if self.args.suite in ('extra', 'all'):
                        motions += [(.02, 0), (0, 0)]
                    for velocities in motions:
                        # Keep the historical basic-case names for existing reports.
                        extra = 'slow_' if velocities[0] != .2 else ''
                        name = (f'x{center}_{extra}{types[0]}_{types[1]}_reverse{reverse}'
                                f'_v{velocities[1]}_a{velocities[0]}' if extra else
                                f'x{center}_{types[0]}_{types[1]}_reverse{reverse}_v{velocities[1]}')
                        self.case(name, [('a', types[0], center-.45, velocities[0]),
                                         ('b', types[1], center+.45, velocities[1])], reverse)
            if self.args.suite in ('extra', 'all'):
                for reverse in (False, True):
                    for middle in ('minecart', 'hopper_minecart'):
                        self.case(f'x{center}_chain_{middle}_reverse{reverse}', [
                            ('a', 'minecart', center-.9, .2),
                            ('b', middle, center, 0),
                            ('c', 'hopper_minecart', center+.9, 0)], reverse)
        if self.age() != 5:
            raise AssertionError('Frozen observation clock changed after the final fixture')
        self.report['frozen_observation_clock_stable'] = True

    def compare(self):
        if not self.args.compare_report:
            return
        reference = json.loads(self.args.compare_report.read_text())
        is_vanilla = reference.get('vanilla_reference', reference.get('jar') is None)
        if reference.get('status') != 'PASS' or not is_vanilla:
            raise AssertionError('Comparison must be a passing Vanilla reference')
        if set(reference['cases']) != set(self.report['cases']):
            raise AssertionError('Reference and candidate case sets differ')
        differences = []

        def compare(actual, expected, path):
            if isinstance(actual, dict):
                if set(actual) != set(expected):
                    raise AssertionError('Reference object keys differ: ' + path)
                for key, value in actual.items():
                    compare(value, expected[key], path + '.' + key)
            elif isinstance(actual, list):
                if len(actual) != len(expected):
                    raise AssertionError('Reference array size differs: ' + path)
                for index, value in enumerate(actual):
                    compare(value, expected[index], path + f'[{index}]')
            elif isinstance(actual, float):
                if not math.isclose(actual, expected, rel_tol=0, abs_tol=1e-7):
                    differences.append({'path': path, 'actual': actual, 'expected': expected})
            elif actual != expected:
                differences.append({'path': path, 'actual': actual, 'expected': expected})

        compare(self.report['cases'], reference['cases'], 'cases')
        self.report['differences'] = differences
        self.report['matches_reference'] = not differences
        print('MINECART_PARITY_DIFFERENCES', len(differences), flush=True)
        if self.args.expect_old_failure:
            if not differences or any('.x64_' in difference['path'] for difference in differences):
                raise AssertionError('Old failure must reproduce at a cell boundary only')
            self.report['expected_old_failure_observed'] = True
        elif differences:
            raise AssertionError('Minecart trajectories differ from Vanilla')

    def performance(self):
        if not self.args.perf_seconds:
            return
        self.command('kill @e[tag=parity_cart]')
        self.command('kill @e[tag=parity_clock]')
        # Four powered rectangular loops cover multiple member regions. Forty
        # moving carts exercise each loop, including two cell-boundary crossings.
        for z in (0, 12, 24, 36):
            self.command(f'fill 111 63 {z-1} 145 63 {z+9} minecraft:redstone_block')
            self.command(f'fill 112 64 {z} 144 64 {z+8} minecraft:air')
            for line in (z, z+8):
                self.command(f'fill 113 64 {line} 143 64 {line} '
                             'minecraft:powered_rail[shape=east_west,powered=true]')
            for x in (112, 144):
                self.command(f'fill {x} 64 {z+1} {x} 64 {z+7} '
                             'minecraft:powered_rail[shape=north_south,powered=true]')
            for x, dz, shape in ((112, 0, 'south_east'), (144, 0, 'south_west'),
                                 (112, 8, 'north_east'), (144, 8, 'north_west')):
                self.command(f'setblock {x} 64 {z+dz} minecraft:rail[shape={shape}]')
            for index in range(40):
                kind = 'minecart' if index % 2 else 'hopper_minecart'
                x = 113.5 + index * .75
                self.command(f'summon minecraft:{kind} {x} 64.0625 {z+.5} '
                             '{Motion:[.2d,0d,0d],Tags:["parity_cart"]}')
        if self.args.jar and self.args.mode != 'sync':
            self.command('execute positioned 96 64 0 run fabrolia regions coherence remove')
        self.command('tick unfreeze')
        time.sleep(10)
        if self.args.jar:
            self.report['perf_islands_before'] = self.command('fabrolia regions top verbose 100')
        self.command('spark profiler start --thread * --interval 4')
        deadline = time.monotonic() + self.args.perf_seconds
        while time.monotonic() < deadline:
            time.sleep(min(10, deadline - time.monotonic()))
            if self.process.poll() is not None:
                raise RuntimeError('Server exited during profiling')
        self.command('spark profiler stop --save-to-file')
        self.wait_until('saved Spark profile', lambda: bool(list(
            (self.server / 'config/spark').glob('*.sparkprofile'))), 30)
        self.report['spark_profiles'] = [str(path) for path in
                                       (self.server / 'config/spark').glob('*.sparkprofile')]
        if self.args.jar:
            self.report['perf_islands_after'] = self.command('fabrolia regions top verbose 100')

    def stop(self):
        if self.process and self.process.poll() is None:
            try:
                self.command('stop')
                self.process.wait(timeout=120)
            except Exception as error:
                self.report['stop_error'] = repr(error)
                self.process.kill()
                self.process.wait(timeout=10)
        if self.log:
            self.log.close()
        self.report['returncode'] = self.process.returncode if self.process else None
        self.report['crash_reports'] = [str(path) for path in
                                        (self.server / 'crash-reports').glob('*')]
        if self.report['returncode'] != 0 or self.report['crash_reports']:
            self.report['status'] = 'FAIL'


def main():
    args = arguments()
    run, server, settings = build_run(args)
    fixture = Fixture(args, run, server, settings)
    try:
        fixture.launch()
        fixture.setup()
        if not args.perf_only:
            fixture.mechanics()
            fixture.compare()
        fixture.performance()
        fixture.report['status'] = 'PASS'
    except Exception as error:
        fixture.report.update(status='FAIL', error=repr(error), traceback=traceback.format_exc())
        print(fixture.report['traceback'], flush=True)
    finally:
        fixture.stop()
        report_path = run / 'minecart-parity-report.json'
        report_path.write_text(json.dumps(fixture.report, indent=2) + '\n')
        print('MINECART_PARITY_RESULT', fixture.report['status'], report_path, flush=True)
    return 0 if fixture.report['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
