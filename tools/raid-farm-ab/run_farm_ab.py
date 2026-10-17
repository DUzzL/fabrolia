"""A/B run of Lemon_Iced's raid farm with a real protocol client operating it.

Usage (see docs/RAID-FARM-OMEN-2026-10-17.md):
  python -I run_farm_ab.py NAME --port 26100 --minutes 10 --world DIR --template DIR \
      --carpet JAR --fabric-api JAR --lithium JAR [--fabrolia JAR] [--parallel]
Creates build/raid-farm-ab/NAME: a copy of the Fabric 26.3 server template, the
farm world, Carpet (only to flip the farm lever), Fabric API, Lithium, the
observational probe mod (tools/raid-farm-ab/probe) and optionally Fabrolia.
"""
import argparse
import hashlib
import json
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import wireclient  # noqa: E402

TOOL = Path(__file__).resolve().parent
REPO = TOOL.parent.parent
PROBE = TOOL / 'probe' / 'build' / 'libs' / 'raidprobe-1.0.0.jar'

# The saved player's Y=76.12 is above the closed trapdoor at Y=76.0.
# A real client falls onto it. Pinning the saved Y without gravity makes the
# server's onGround flag depend on packet timing and can suppress every sweep.
SPOT = (0.43, 76.0, 14.55)
ROT = (92.37, 49.18)
# Entity type registry id in the pinned 26.3 server (confirmed by the attack probe).
ARMOR_STAND_TYPE = 5

p = argparse.ArgumentParser()
p.add_argument('name')
p.add_argument('--port', type=int, required=True)
p.add_argument('--minutes', type=float, default=20)
p.add_argument('--fabrolia', type=Path)
p.add_argument('--parallel', action='store_true')
p.add_argument('--disabled', action='store_true', help='Load Fabrolia with its global kill switch on; isolate non-scheduler mixins')
p.add_argument('--resume', action='store_true', help='Use a saved running farm; preserve its already enabled lever')
p.add_argument('--attack-interval', type=int, default=20)
p.add_argument('--world', type=Path, required=True, help='output of prepare_world.py')
p.add_argument('--template', type=Path, required=True,
               help='Fabric 26.3 server dir: fabric-server-launch.jar, server.jar, libraries/')
p.add_argument('--carpet', type=Path, required=True)
p.add_argument('--fabric-api', type=Path, required=True)
p.add_argument('--lithium', type=Path, help='Optional; omit for the Minecraft AI reference without Lithium changes')
p.add_argument('--spark', type=Path, help='Optional all-thread Spark profile during the same farm scenario')
p.add_argument('--profile-from', type=int, default=180, help='Seconds of operator warmup before profiling')
p.add_argument('--profile-for', type=int, default=120, help='Seconds to profile (rounded up to a sample interval)')
a = p.parse_args()
if a.spark and (a.profile_from < 0 or a.profile_for <= 0
                or a.minutes * 60 < a.profile_from + a.profile_for + 30):
    p.error('profiling needs nonnegative warmup, positive duration and 30 seconds before the run ends')

RUN = REPO / 'build' / 'raid-farm-ab' / a.name
if RUN.exists():
    raise SystemExit('Run exists: ' + str(RUN))
if not PROBE.exists():
    raise SystemExit('Build the probe first: gradlew -p tools/raid-farm-ab/probe build')
shutil.copytree(a.template, RUN)
mods = RUN / 'mods'
mods.mkdir(exist_ok=True)
for source in [a.carpet, a.fabric_api, PROBE] + ([a.lithium] if a.lithium else []) + ([a.spark] if a.spark else []):
    shutil.copy2(source, mods / source.name)
if a.fabrolia:
    shutil.copy2(a.fabrolia, mods / a.fabrolia.name)
shutil.copytree(a.world, RUN / 'world')
for junk in ('session.lock', 'desktop.ini'):
    (RUN / 'world' / junk).unlink(missing_ok=True)
(RUN / 'eula.txt').write_text('eula=true\n')
RCON_PASS = 'raid-ab-local-only'
(RUN / 'server.properties').write_text(
    'server-ip=127.0.0.1\nserver-port=%d\nonline-mode=false\nenable-rcon=true\nrcon.port=%d\nrcon.password=%s\n'
    'level-name=world\nspawn-protection=0\nview-distance=8\nsimulation-distance=8\ndifficulty=hard\n'
    'max-tick-time=-1\nenable-command-block=true\nnetwork-compression-threshold=256\n'
    'motd=Isolated raid farm A/B\n' % (a.port, a.port + 1, RCON_PASS))
(RUN / 'config').mkdir(exist_ok=True)
if a.fabrolia:
    (RUN / 'config' / 'fabrolia.toml').write_text(
        'disabled = %s\nasyncIslands = %s\nenableRegionThreading = %s\nenablePlayerTickActor = %s\n'
        'regionWorkerCount = 4\nislandEntityParallel = %s\n' %
        ((str(a.disabled).lower(),) + (str(not a.disabled).lower(),) * 3 + (str(a.parallel).lower(),)))

report = {'name': a.name, 'fabrolia': str(a.fabrolia) if a.fabrolia else None, 'parallel': a.parallel,
          'disabled': a.disabled,
          'resume': a.resume,
          'lithium': str(a.lithium) if a.lithium else None,
          'spark': str(a.spark) if a.spark else None,
          'minutes': a.minutes, 'attack_interval': a.attack_interval, 'samples': [], 'commands': [],
          'operator': {}}
world_manifest = hashlib.sha256()
world_files = 0
for path in sorted(a.world.rglob('*')):
    if not path.is_file() or path.name in ('session.lock', 'desktop.ini'):
        continue
    relative = path.relative_to(a.world).as_posix()
    world_manifest.update(relative.encode('utf-8') + b'\0' + hashlib.sha256(path.read_bytes()).digest())
    world_files += 1
report['world_input'] = {'path': str(a.world), 'files': world_files, 'manifest_sha256': world_manifest.hexdigest()}
proc = None
T0 = time.monotonic()


def ex(command, record=True):
    with socket.create_connection(('127.0.0.1', a.port + 1), timeout=90) as sock:
        def read(n):
            b = b''
            while len(b) < n:
                piece = sock.recv(n - len(b))
                if not piece:
                    raise EOFError('RCON closed')
                b += piece
            return b

        def packet(ident, kind, body):
            b = struct.pack('<ii', ident, kind) + body.encode() + b'\0\0'
            sock.sendall(struct.pack('<i', len(b)) + b)
            size = struct.unpack('<i', read(4))[0]
            b = read(size)
            return struct.unpack('<ii', b[:8]), b[8:-2].decode(errors='replace')

        ident, out = packet(1, 3, RCON_PASS)
        if ident[0] != 1:
            raise RuntimeError('RCON authentication refused')
        ident, out = packet(2, 2, command)
    if record:
        report['commands'].append({'t': round(time.monotonic() - T0, 1), 'command': command, 'out': out})
    if any(s in out for s in ['Unknown or incomplete', 'Incorrect argument', 'An unexpected error']):
        raise RuntimeError(command + ' => ' + out)
    return out


def wait_for(fn, seconds):
    stop = time.monotonic() + seconds
    while time.monotonic() < stop:
        if proc and proc.poll() is not None:
            raise RuntimeError('Server exited ' + str(proc.returncode))
        try:
            if fn():
                return
        except (OSError, EOFError):
            pass
        time.sleep(0.2)
    raise TimeoutError('condition not satisfied')


def score(objective):
    out = ex('scoreboard players get Sora ' + objective, record=False)
    try:
        return int(out.split(' has ')[1].split(' ')[0])
    except Exception:
        return 0


class Operator(threading.Thread):
    """Real-client behaviour: right button held, autoclicker on the left button."""

    def __init__(self, client, interval):
        super().__init__(daemon=True)
        self.client = client
        self.interval = interval
        self.stop_flag = False
        self.error = None
        self.stats = {'interacts': 0, 'drinks': 0, 'attacks': 0, 'skipped_attacks': 0, 'ticks': 0}

    def stand(self):
        for entity_id, (type_id, x, y, z) in list(self.client.entities.items()):
            if type_id == ARMOR_STAND_TYPE and -1.0 <= x <= 0.0 and 14.0 <= z <= 15.0 and 74.5 <= y <= 78.5:
                return entity_id
        return None

    def run(self):
        try:
            self.operate()
        except Exception as error:
            self.error = error  # The driver must fail if input generation stops.
            raise

    def operate(self):
        c = self.client
        tick, use_until, rc_delay, next_attack, seq = 0, -1, 0, self.interval, 0
        next_time = time.monotonic()
        while not self.stop_flag:
            if tick % 20 == 0:
                c.move(SPOT[0], SPOT[1], SPOT[2], ROT[0], ROT[1])
            stand = self.stand()
            using = tick < use_until
            if tick >= next_attack:
                next_attack = tick + self.interval
                if not using and stand is not None:
                    c.attack(stand)
                    c.punch()
                    self.stats['attacks'] += 1
                else:
                    # A left click on the bare tripwire would break the farm; skip.
                    self.stats['skipped_attacks'] += 1
            if not using:
                if rc_delay > 0:
                    rc_delay -= 1
                else:
                    rc_delay = 4
                    if stand is not None:
                        c.interact(stand, 0, (0.25, 1.77, 0.05))
                        self.stats['interacts'] += 1
                    else:
                        hit = (0.063, 0.156, 0.51)
                        for hand in (0, 1):
                            seq += 1
                            c.use_item_on(hand, seq, -1, 76, 14, 1, hit)
                            seq += 1
                            c.use_item(hand, seq, ROT[0], ROT[1])
                        use_until = tick + 41
                        self.stats['drinks'] += 1
            c.tick_end()
            tick += 1
            self.stats['ticks'] = tick
            next_time += 0.05
            delay = next_time - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            else:
                next_time = time.monotonic()


client = None
operator = None
try:
    log = open(RUN / 'console.log', 'w', encoding='utf-8')
    proc = subprocess.Popen(['java', '-Xms2G', '-Xmx4G', '-jar', 'fabric-server-launch.jar', 'nogui'], cwd=RUN,
                            stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    wait_for(lambda: 'Done (' in (RUN / 'console.log').read_text(errors='replace'), 300)
    wait_for(lambda: 'players online' in ex('list', record=False), 60)
    print('BOOTED', a.name, flush=True)
    for command in ['whitelist off', 'carpet commandPlayer true', 'difficulty hard',
                    'scoreboard objectives add rtrig minecraft.custom:minecraft.raid_trigger',
                    'scoreboard objectives add rwin minecraft.custom:minecraft.raid_win',
                    'scoreboard objectives add kp minecraft.killed:minecraft.pillager',
                    'scoreboard objectives add kv minecraft.killed:minecraft.vindicator',
                    'scoreboard objectives add ke minecraft.killed:minecraft.evoker',
                    'scoreboard objectives add kw minecraft.killed:minecraft.witch',
                    'scoreboard objectives add bott minecraft.used:minecraft.ominous_bottle']:
        ex(command)
    if a.fabrolia and not a.disabled:
        ex('fabrolia regions async on')
        ex('fabrolia regions islandentities ' + ('parallel' if a.parallel else 'vanilla'))
    # Farm switch: a helper bot flips the lever on the redstone lamp like a player.
    if not a.resume:
        ex('player Lever spawn at 2.975 76.0 14.05 facing 0 0 in minecraft:overworld in survival')
        time.sleep(3)
        lever = ''
        for aim_y in ('78.6', '78.5', '78.7'):
            ex('player Lever look at 2.5 %s 15.75' % aim_y)
            ex('player Lever use once')
            time.sleep(1)
            lever = ex('execute if block 2 78 15 minecraft:lever[powered=true]')
            if 'passed' in lever:
                break
        ex('player Lever kill')
    if not a.resume and 'passed' not in lever:
        raise RuntimeError('farm lever did not switch on: ' + lever)
    time.sleep(2)
    # The operator: a real protocol client.
    client = wireclient.WireClient('Sora', a.port)
    # ClientInformation with view distance 8 (production's render distance).
    client.configure = lambda: client.send('CLIENT_INFORMATION', wireclient.pwc.utf('en_us') + b'\x08\x00\x01\x7f\x01\x00\x01\x00')
    client.connect()
    def joined():
        if client.error:
            raise client.error
        return client.loaded and client.native_position is not None
    wait_for(joined, 120)
    ex('tp Sora %s %s %s %s %s' % (SPOT + ROT))
    time.sleep(1)
    if a.resume:
        wait_for(lambda: 'passed' in ex('execute if loaded 2 78 15', record=False), 60)
        lever = ex('execute if block 2 78 15 minecraft:lever[powered=true]')
        if 'passed' not in lever:
            raise RuntimeError('saved farm lever is not enabled: ' + lever)
    ex('item replace entity Sora weapon.mainhand with minecraft:diamond_sword[minecraft:enchantments={'
       '"minecraft:sharpness":5,"minecraft:sweeping_edge":3,"minecraft:looting":3,"minecraft:unbreaking":3,'
       '"minecraft:mending":1}]')
    ex('item replace entity Sora weapon.offhand with minecraft:ominous_bottle[minecraft:ominous_bottle_amplifier=1] 64')
    # "Consume one ominous bottle": the bottle's exact Bad Omen II (120000 ticks, no particles).
    ex('effect give Sora minecraft:bad_omen 6000 1 true')
    report['initial_scores'] = {obj: score(obj) for obj in ('rtrig', 'rwin', 'kp', 'kv', 'ke', 'kw', 'bott')}
    operator = Operator(client, a.attack_interval)
    operator.start()
    operated_at = time.monotonic()
    end = operated_at + a.minutes * 60
    profile_started = None
    profile_done = False
    refills = 0
    while time.monotonic() < end:
        time.sleep(30)
        if client.error:
            raise client.error
        if operator.error:
            raise operator.error
        if a.spark and not profile_done:
            now = time.monotonic()
            if profile_started is None and now - operated_at >= a.profile_from:
                report['profile_start'] = ex('spark profiler start --thread * --interval 4')
                profile_started = now
            elif profile_started is not None and now - profile_started >= a.profile_for:
                report['profile_stop'] = ex('spark profiler stop --save-to-file')
                wait_for(lambda: list((RUN / 'config' / 'spark').glob('*.sparkprofile')), 60)
                report['profile_seconds'] = round(time.monotonic() - profile_started, 1)
                report['spark_profiles'] = [str(path.relative_to(RUN))
                                           for path in (RUN / 'config' / 'spark').glob('*.sparkprofile')]
                profile_done = True
        offhand = ex('data get entity Sora equipment.offhand.count', record=False)
        try:
            count = int(offhand.split('data: ')[1].strip())
        except Exception:
            count = 0
        if count < 16:
            ex('item replace entity Sora weapon.offhand with minecraft:ominous_bottle[minecraft:ominous_bottle_amplifier=1] 64')
            refills += 1
        sample = {'t': round(time.monotonic() - T0, 1), 'offhand': count, 'refills': refills}
        for obj in ('rtrig', 'rwin', 'kp', 'kv', 'ke', 'kw', 'bott'):
            sample[obj] = score(obj)
        sample['op'] = dict(operator.stats)
        sample['pos'] = ex('data get entity Sora Pos', record=False).split('data: ')[-1]
        sample['mainhand'] = ex('data get entity Sora SelectedItem', record=False).split('data: ')[-1]
        target_id = operator.stand()
        sample['attack_target'] = {'id': target_id, 'spawn': client.entities.get(target_id)}
        sample['tps'] = ex('tick query', record=False).replace('\n', ' ')[:120]
        report['samples'].append(sample)
        print(a.name, json.dumps({k: v for k, v in sample.items() if k != 'tps'}), flush=True)
    report['status'] = 'DONE'
except Exception as e:
    report['status'] = 'ERROR ' + repr(e)
    raise
finally:
    if operator:
        operator.stop_flag = True
        report['operator'] = dict(operator.stats)
    try:
        if proc and proc.poll() is None:
            try:
                ex('save-all flush')
            except Exception:
                pass
            if client:
                client.close()
            proc.stdin.write(b'stop\n')
            proc.stdin.flush()
            proc.wait(120)
    except Exception:
        if proc:
            proc.kill()
    (RUN / 'report.json').write_text(json.dumps(report, indent=1))
    print('FINISHED', a.name, report.get('status'), flush=True)
