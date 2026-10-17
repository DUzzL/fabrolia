#!/usr/bin/env python3
"""Local 26.3 portal transport and autonomous chunkloader regression.

Uses physical observer clocks and stocked droppers on both sides of a real
Nether portal. After bootstrap there are no force-load tickets or nearby
players. Only entity portal tickets can keep the machinery ticking. A real
protocol client stays at spawn so the dedicated server does not pause.

This focused fixture is not the production modpack's 40-client stand, nor a
throughput guarantee for arbitrary farm designs. Run Vanilla, an old Fabrolia
jar, and the candidate with identical template/mod inputs and separate paths.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import sys
import time
import traceback


HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('minecart_fixture', HERE / 'minecart-parity-driver.py')
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
sys.path.insert(0, str(HERE.parent / 'raid-farm-ab'))
from wireclient import WireClient, pwc

SITES = [('overworld', 4096, 4096, 'minecraft:glowstone_dust'),
         ('the_nether', 512, 512, 'minecraft:quartz')]
Y = 65


class PortalFixture(base.Fixture):
    def dim(self, dim, command):
        _, x, z, _ = next(site for site in SITES if site[0] == dim)
        return self.command(f'execute in minecraft:{dim} positioned {x} {Y} {z} run {command}')

    def loaded(self, dim, x, z):
        return 'Test passed' in self.dim(dim, f'execute if loaded {x} {Y} {z}')

    def inventory(self, dim, x, y, z):
        output = self.dim(dim, f'data get block {x} {y} {z} Items')
        if 'following block data:' not in output:
            raise AssertionError('Inventory unavailable: ' + output)
        return sum(map(int, re.findall(r'\bcount: (\d+)', output)))

    def snapshot(self, label):
        sample = {'phase': label, 'time': round(time.monotonic() - self.started, 3), 'sites': {}}
        for dim, x, z, item in SITES:
            if not self.loaded(dim, x, z):
                raise AssertionError(f'{dim}: loader chunk stopped during {label}')
            source = sum(self.inventory(dim, x - 1, y, z) for y in (Y, Y + 1, Y + 2))
            received = sum(self.inventory(dim, x + dx, Y - 1, z + dz)
                           for dx in (1, 2) for dz in (0, 1))
            # Query every loose stack, including merged stacks, without loading
            # a chunk or spawning an entity to make the observation succeed.
            self.command('scoreboard players set loose portal_test 0')
            self.dim(dim, 'execute as @e[type=minecraft:item,distance=..24] store result score @s portal_test '
                     'run data get entity @s Item.count')
            self.dim(dim, 'execute as @e[type=minecraft:item,distance=..24] run scoreboard players operation '
                     'loose portal_test += @s portal_test')
            out = self.command('scoreboard players get loose portal_test')
            loose = int(re.search(r' has (\d+) ', out)[1])
            sample['sites'][dim] = {'source': source, 'received': received, 'loose': loose}
        if self.args.jar:
            sample['coupling'] = self.command('fabrolia regions crossdim')
            sample['islands'] = self.command('fabrolia regions top verbose 100')
        self.report.setdefault('samples', []).append(sample)
        print('PORTAL_PARITY_SAMPLE', json.dumps(sample), flush=True)
        return sample

    def sleep_checked(self, seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if self.client.error:
                raise self.client.error
            if self.process.poll() is not None:
                raise RuntimeError('Server stopped during fixture')
            time.sleep(min(.25, max(0, deadline - time.monotonic())))

    def setup(self):
        self.started = time.monotonic()
        self.command('whitelist off')
        self.client = WireClient('PortalProbe', int(self.settings['server-port']))
        self.client.configure = lambda: self.client.send(
            'CLIENT_INFORMATION', pwc.utf('en_us') + b'\x06\x00\x01\x7f\x01\x00\x01\x00')
        self.client.connect()
        self.wait_until('real client joined', lambda: self.client.loaded and
                        self.client.native_position is not None, 120)
        for cmd in ('gamemode creative PortalProbe', 'tp PortalProbe 0 80 0',
                    'gamerule minecraft:spawn_mobs false', 'gamerule minecraft:mob_drops false',
                    'scoreboard objectives add portal_test dummy'):
            self.command(cmd)
        if self.args.jar:
            self.command('fabrolia regions async on')
            self.command('fabrolia regions islandentities ' +
                         ('parallel' if self.args.mode == 'parallel' else 'vanilla'))
        for dim, x, z, item in SITES:
            self.dim(dim, f'forceload add {x-16} {z-16} {x+16} {z+16}')
            self.wait_until('bootstrap chunks', lambda d=dim, x=x, z=z: self.loaded(d, x, z), 60)
            self.dim(dim, f'fill {x-5} 62 {z-3} {x+5} 73 {z+4} minecraft:air')
            self.dim(dim, f'fill {x-5} 64 {z-3} {x+5} 64 {z+4} minecraft:stone')
            self.dim(dim, f'fill {x} 64 {z-1} {x} 68 {z+2} minecraft:obsidian')
            self.dim(dim, f'fill {x} 65 {z} {x} 67 {z+1} minecraft:nether_portal[axis=z]')
            # Two exit hoppers/chests catch items emerging from the portal.
            for dz in (0, 1):
                self.dim(dim, f'setblock {x+2} 64 {z+dz} minecraft:chest[type=single]')
                self.dim(dim, f'setblock {x+1} 64 {z+dz} minecraft:hopper[facing=east]')
            self.dim(dim, f'setblock {x-1} 65 {z} minecraft:dropper[facing=east]')
            self.dim(dim, f'setblock {x-1} 66 {z} minecraft:hopper[facing=down]')
            self.dim(dim, f'setblock {x-1} 67 {z} minecraft:chest')
        # Real mobs touch the actual portal blocks; /execute in ... tp is never
        # used to perform or substitute for a tested cross-dimension transfer.
        for dim, x, z, item in SITES:
            for i in range(8):
                self.dim(dim, f'summon minecraft:pig {x+.5} 65.1 {z+.5} '
                         + '{NoAI:1b,Invulnerable:1b,Tags:["from_' + dim + '"]}')
        for source, x, z, item in SITES:
            target = 'the_nether' if source == 'overworld' else 'overworld'
            def all_arrived(target=target, source=source):
                self.command('scoreboard players set mobs portal_test 0')
                self.dim(target, f'execute as @e[tag=from_{source},distance=..24] run scoreboard players add mobs portal_test 1')
                return ' has 8 ' in self.command('scoreboard players get mobs portal_test')
            self.wait_until('eight pigs arrive from ' + source, all_arrived, 45)
        self.report['mob_transfers'] = {'overworld_to_nether': 8, 'nether_to_overworld': 8}
        for dim, x, z, item in SITES:
            self.dim(dim, 'kill @e[type=minecraft:pig,distance=..24]')
            self.dim(dim, 'kill @e[type=minecraft:item,distance=..24]')
            for y in (65, 67):
                for slot in range(9):
                    self.dim(dim, f'item replace block {x-1} {y} {z} container.{slot} with {item} 64')
            # Mutually observing observers pulse a physical dropper. No tick
            # function, command block or replenishment command runs this loader.
            self.dim(dim, f'setblock {x-2} 65 {z} minecraft:observer[facing=west]')
            self.dim(dim, f'setblock {x-3} 65 {z} minecraft:observer[facing=east]')
        self.sleep_checked(4)
        for dim, x, z, item in SITES:
            self.dim(dim, 'forceload remove all')
            tickets = self.dim(dim, 'forceload query')
            if 'No force loaded chunks' not in tickets:
                raise AssertionError('Bootstrap ticket survived: ' + tickets)
        self.report['force_load_tickets_removed'] = True
        self.report['observer_position'] = self.command('data get entity PortalProbe Pos')
        self.report['initial_stock_each_direction'] = 1152

    def mechanics(self):
        start = self.snapshot('autonomous_start')
        if self.args.profile:
            self.command('spark profiler start --thread * --interval 4')
            self.profile_started = time.monotonic()
        self.sleep_checked(25)  # already exceeds the vanilla 300-tick portal ticket
        middle = self.snapshot('autonomous_25s')
        self.sleep_checked(25)
        end = self.snapshot('autonomous_50s')
        if self.args.profile:
            self.command('spark profiler stop --save-to-file')
            self.report['profile_seconds'] = time.monotonic() - self.profile_started
            self.wait_until('saved Spark profile', lambda: list((self.server / 'config/spark').glob('*.sparkprofile')), 30)
            self.report['spark_profiles'] = [str(p.relative_to(self.run)) for p in
                                            (self.server / 'config/spark').glob('*.sparkprofile')]
        for dim, *_ in SITES:
            values = [s['sites'][dim]['received'] for s in (start, middle, end)]
            if not values[0] < values[1] < values[2]:
                raise AssertionError(f'{dim}: destination stopped receiving: {values}')
        if self.args.jar:
            if 'COUPLED sharedTps=20.0 anchored' not in end['coupling']:
                raise AssertionError('Test did not exercise active cross-dimension coupling')
            handles = re.findall(r'(ow-\d+g\d+) <-> (ne-\d+g\d+)', end['coupling'])
            if not handles:
                raise AssertionError('No portal group handle found')
            if self.args.stress:
                # The test-only cost hook searches all dimensions by id. Use
                # the farm's Overworld id only when it identifies exactly one
                # island; never delay a second island through an id alias.
                island_id = int(re.search(r'ow-(\d+)g', handles[-1][0])[1])
                ids = re.findall(r'island#(\d+)/', end['islands'])
                if ids.count(str(island_id)) != 1:
                    raise AssertionError('Stress hook id aliases a different island')
                self.report['stress_member'] = handles[-1][0]
                self.command(f'fabrolia regions debug islanddebug {island_id} 200')
                self.sleep_checked(8)
                self.snapshot('overworld_200ms_overload')
                self.command(f'fabrolia regions debug islanddebug {island_id} 0')
                self.sleep_checked(8)
                recovered = self.snapshot('overload_recovered')
                for dim, *_ in SITES:
                    if recovered['sites'][dim]['received'] <= end['sites'][dim]['received']:
                        raise AssertionError('Transport did not recover after overload')
            self.command(f'fabrolia regions rate handle {handles[-1][0]} 10')
            self.sleep_checked(20)
            slow = self.snapshot('shared_rate_10')
            if 'COUPLED sharedTps=10.0 anchored' not in slow['coupling']:
                raise AssertionError('Slowest-member rate was not shared')
            if self.args.expect_shared_target:
                self.check_verbose_targets(slow, handles[-1], 10, True)
            for dim, *_ in SITES:
                if slow['sites'][dim]['received'] <= end['sites'][dim]['received']:
                    raise AssertionError('Portal transport stopped at shared rate 10')
            self.command(f'fabrolia regions rate handle {handles[-1][0]} 20')
            self.sleep_checked(15)
            restored = self.snapshot('shared_rate_restored')
            if self.args.expect_shared_target:
                self.check_verbose_targets(restored, handles[-1], 20, False)
        # Stop both clocks. Pending entities are allowed to complete before
        # conservation is measured. Commands here only address already loaded
        # chunks; the later expiry observations use `if loaded` alone.
        for dim, x, z, item in SITES:
            self.dim(dim, f'setblock {x-3} 65 {z} minecraft:air')
        self.sleep_checked(4)
        # Feed hoppers keep moving stock even after the clocks stop. Separate
        # RCON inventory reads can otherwise miss/double-count a stack moving
        # between two inventories. Freeze vanilla simulation before counting.
        self.command('tick freeze')
        self.sleep_checked(1)
        final = self.snapshot('clocks_stopped')
        total = sum(sum(s.values()) for s in final['sites'].values())
        self.report['item_conservation'] = {'expected': 2304, 'actual': total}
        if total != 2304:
            raise AssertionError(f'Items lost or duplicated: {total} != 2304')
        self.command('tick unfreeze')
        for dim, *_ in SITES:
            self.dim(dim, 'kill @e[type=minecraft:item,distance=..24]')
        self.sleep_checked(40)
        expired = {dim: not self.loaded(dim, x, z) for dim, x, z, _ in SITES}
        self.report['ticket_expired_after_clocks_stop'] = expired
        if not all(expired.values()):
            raise AssertionError('Portal chunks retained after final ticket: ' + str(expired))

    def check_verbose_targets(self, sample, handles, target, override):
        rows = re.split(r'(?=#\d+ )', sample['islands'])
        for handle in handles:
            row = next((row for row in rows if f'[{handle}]' in row), None)
            if row is None or not re.search(rf'tps=[^ /]+/{target}\.0 mspt=', row):
                raise AssertionError(f'{handle}: verbose target is not {target}: {row}')
            if ('RATE-OVERRIDE' in row) != override:
                raise AssertionError(f'{handle}: wrong verbose override marker: {row}')
        self.report.setdefault('verbose_target_checks', []).append(
            {'phase': sample['phase'], 'handles': list(handles), 'target': target, 'override': override})


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('run', type=Path)
    p.add_argument('--template', type=Path, required=True)
    p.add_argument('--mods', type=Path, nargs='+', required=True)
    p.add_argument('--jar', type=Path)
    p.add_argument('--mode', choices=('serial', 'parallel'), default='parallel')
    p.add_argument('--java', required=True)
    p.add_argument('--server-port', type=int, required=True)
    p.add_argument('--profile', action='store_true', help='Requires a Spark jar in --mods')
    p.add_argument('--stress', action='store_true', help='Exercise one overdue linked island for eight seconds')
    p.add_argument('--expect-shared-target', action='store_true',
                   help='Require both verbose rows to show the shared target and override marker')
    a = p.parse_args()
    if a.expect_shared_target and not a.jar:
        p.error('--expect-shared-target requires --jar')
    a.rcon_port, a.heap, a.launcher, a.boot_timeout, a.suite = a.server_port + 1, '3G', 'fabric-server-launch.jar', 240, 'portals'
    run, server, settings = base.build_run(a)
    settings.update({'allow-nether': 'true', 'level-seed': '732647', 'pause-when-empty-seconds': '-1',
                     'white-list': 'false', 'enforce-whitelist': 'false',
                     'view-distance': '4', 'simulation-distance': '4'})
    (server / 'server.properties').write_text(''.join(k + '=' + v + '\n' for k, v in settings.items()))
    f = PortalFixture(a, run, server, settings)
    f.report.update(clock='physical observer clock; wall time', fixture='portal-parity-v2',
                    stress=a.stress, profile=a.profile, expect_shared_target=a.expect_shared_target,
                    driver_sha256=base.sha256(Path(__file__)))
    try:
        f.launch()
        f.setup()
        f.mechanics()
        f.report['status'] = 'PASS'
    except Exception as error:
        f.report.update(status='FAIL', error=repr(error), traceback=traceback.format_exc())
        print(f.report['traceback'], flush=True)
    finally:
        if getattr(f, 'client', None):
            f.client.close()
        f.stop()
        log = (run / 'console.log').read_text(errors='replace')
        optional_warnings = [line for line in log.splitlines() if '[main/WARN]: Error loading class:' in line
                             and 'ClassNotFoundException:' in line]
        f.report['optional_integration_warnings'] = optional_warnings
        f.report['runtime_errors'] = [line for line in log.splitlines() if line not in optional_warnings and re.search(
            r'ERROR\]|Exception|fail-stop|BARRIER-LEAK|lacks exact|ownership violation', line)]
        if f.report['runtime_errors']:
            f.report['status'] = 'FAIL'
        (run / 'portal-parity-report.json').write_text(json.dumps(f.report, indent=2) + '\n')
        print('PORTAL_PARITY_RESULT', f.report['status'], run, flush=True)
    return 0 if f.report['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
