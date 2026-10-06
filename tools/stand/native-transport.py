#!/usr/bin/env python3
"""Prepare/run isolated, fresh, native Carpet mass-player fixtures. Never attaches to a JVM."""
import argparse
from collections import deque
import hashlib
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import subprocess
import threading
import time
import zipfile

# Stand host settings (tools/stand/stand.sh exports FABROLIA_STAND_HOME / _JAVA).
ROOT = Path(os.environ.get('FABROLIA_STAND_HOME') or Path(__file__).resolve().parents[2])
RUNS = ROOT / 'runs'
# Fixture-preparation source (legacy `prepare` mode only; the perf/verify flow never reads it).
ORIGINAL = Path(os.environ.get('FABROLIA_STAND_ORIGINAL', '/nonexistent-fabrolia-original-server'))
JAVA = Path(os.environ.get('FABROLIA_STAND_JAVA') or shutil.which('java') or 'java').resolve()
SAFE_ID = re.compile(r'[a-z0-9][a-z0-9-]{0,39}')
ANSI = re.compile(r'\x1b\[[0-9;]*m')
ERRORS = ('Unknown or incomplete command', 'Incorrect argument', 'An unexpected error occurred',
          'Exception executing command', 'That position is not loaded')


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def fingerprint(value):
    return hashlib.sha256(encoded(value)).hexdigest()


def save(path, value):
    temp = path.with_suffix(path.suffix + '.tmp')
    temp.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')
    temp.replace(path)


def owned(run_id, exists=True):
    if not SAFE_ID.fullmatch(run_id):
        raise ValueError('run-id must be 1-40 lowercase letters/digits/hyphens, starting with letter/digit')
    run = RUNS / run_id
    if RUNS.is_symlink() or run.is_symlink() or ROOT.resolve() != ROOT:
        raise ValueError('Symlinked fixture roots are forbidden')
    if exists and not (run / 'selected.json').is_file():
        raise ValueError('Not a prepared owned run')
    return run


def inventory(root):
    result = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError('Symlink forbidden: ' + str(path))
        if path.is_file():
            result[str(path.relative_to(root))] = digest(path)
    return result


def copy_file(source, target):
    if source.is_symlink() or not source.is_file():
        raise ValueError('Expected regular source file: ' + str(source))
    target.parent.mkdir(parents=True, exist_ok=True)
    before = digest(source)
    shutil.copyfile(source, target)
    if digest(target) != before or digest(source) != before:
        raise RuntimeError('Input changed during copy: ' + str(source))


def copy_tree(source, target):
    if source.is_symlink() or not source.is_dir():
        raise ValueError('Expected regular source directory: ' + str(source))
    target.mkdir(parents=True, exist_ok=True)
    for path in sorted(source.rglob('*')):
        if path.is_symlink():
            raise ValueError('Symlink forbidden: ' + str(path))
        if path.is_file():
            copy_file(path, target / path.relative_to(source))


def mod_id(path):
    with zipfile.ZipFile(path) as jar:
        if 'fabric.mod.json' not in jar.namelist():
            return None
        return json.loads(jar.read('fabric.mod.json'))['id']


def prepare(a):
    run = owned(a.run_id, exists=False)
    candidate = a.candidate.resolve(strict=True)
    if a.gameplay and not a.fabric_probe:
        raise ValueError('--gameplay requires --fabric-probe')
    if a.handoff_profile:
        if not a.fabric_probe:
            raise ValueError('--handoff-profile requires --fabric-probe')
        if a.match_run or a.nearby_query_proof:
            raise ValueError('--handoff-profile excludes --match-run and --nearby-query-proof')
    if a.nearby_query_proof:
        if not a.fabric_probe:
            raise ValueError('--nearby-query-proof requires --fabric-probe')
        if a.gameplay or a.match_run or a.probe or a.replay_chunk or a.async_mode != 'on':
            raise ValueError('--nearby-query-proof requires async on and excludes --gameplay, --match-run, --probe and --replay-chunk')
    if a.panic_callback_proof:
        if not a.fabric_probe:
            raise ValueError('--panic-callback-proof requires --fabric-probe')
        if (a.nearby_query_proof or a.handoff_profile or a.gameplay or a.match_run
                or a.probe or a.replay_chunk or a.async_mode != 'on'):
            raise ValueError('--panic-callback-proof requires async on and excludes --nearby-query-proof, '
                             '--handoff-profile, --gameplay, --match-run, --probe and --replay-chunk')
    if mod_id(candidate) != 'fabrolia':
        raise ValueError('Candidate must be a native Fabrolia Fabric mod jar')
    match = owned(a.match_run) if a.match_run else None
    prior = json.loads((match / 'selected.json').read_text()) if match else None
    run.mkdir(parents=True, exist_ok=False)
    inputs = run / 'inputs'
    inputs.mkdir()
    if match:
        if inventory(match / 'inputs') != prior['input_sha256']:
            raise RuntimeError('Matched frozen inputs changed')
        copy_tree(match / 'inputs', inputs)
        fab_path = prior['fabrolia_path']
        distances = prior['original_distances']
    else:
        for name in ('fabric-server-launch.jar', 'fabric-server-launcher.properties'):
            copy_file(ORIGINAL / name, inputs / name)
        launch_properties = (inputs / 'fabric-server-launcher.properties').read_text()
        jars = re.findall(r'^serverJar=(.+)$', launch_properties, re.M)
        if len(jars) != 1 or not re.fullmatch(r'[A-Za-z0-9._+-]+\.jar', jars[0].strip()):
            raise ValueError('Launcher serverJar must name one local root jar')
        name = jars[0].strip()
        copy_file(ORIGINAL / name, inputs / name)
        for name in ('libraries', 'versions'):
            copy_tree(ORIGINAL / name, inputs / name)
        mods = sorted((ORIGINAL / 'mods').glob('*.jar'))
        fab = [p for p in mods if mod_id(p) == 'fabrolia']
        if len(fab) != 1 or not any(mod_id(p) == 'carpet' for p in mods):
            raise ValueError('Expected exactly one deployed Fabrolia and a Carpet root jar')
        fab_path = 'mods/' + fab[0].name
        for mod in mods:
            copy_file(mod, inputs / 'mods' / mod.name)
        libs = ORIGINAL / 'mods/luckperms/libs'
        if libs.is_dir():
            copy_tree(libs, inputs / 'mods/luckperms/libs')
        for name in ('fabrolia.toml', 'c2me.toml'):
            copy_file(ORIGINAL / 'config' / name, inputs / 'config' / name)
        # Read only the two public distance knobs; never copy original properties/secrets.
        props = dict(line.split('=', 1) for line in (ORIGINAL / 'server.properties').read_text().splitlines()
                     if line and not line.startswith('#') and '=' in line)
        distances = {k: int(props[k]) for k in ('view-distance', 'simulation-distance')}
        if any(not 2 <= n <= 16 for n in distances.values()):
            raise ValueError('Original distances outside safe 2..16 range; explicit harness change required')
    copy_file(candidate, inputs / fab_path)
    # A matched proof fixture may contain our auto-loaded Fabric observer.
    # Omitting the option must produce a genuinely uninstrumented comparison.
    inherited_probe = inputs / 'mods/fabrolia-parallel-native-proof.jar'
    if not a.fabric_probe and inherited_probe.exists():
        if mod_id(inherited_probe) != 'fabrolia_parallel_native_proof':
            raise ValueError('Unexpected mod at inherited proof artifact path')
        inherited_probe.unlink()
    config = inputs / 'config/fabrolia.toml'
    config.write_text(re.sub(r'^asyncIslands = .*$', 'asyncIslands = ' + str(a.async_mode == 'on').lower(),
                            config.read_text(), flags=re.M))
    props = {
        'server-ip': '127.0.0.1', 'server-port': '25935', 'online-mode': 'false',
        'enforce-secure-profile': 'false', 'enable-rcon': 'false', 'enable-query': 'false',
        'enable-jmx-monitoring': 'false', 'management-server-enabled': 'false',
        'management-server-host': '127.0.0.1', 'management-server-port': '0',
        'management-server-secret': '', 'rcon.password': '', 'enable-status': 'false',
        'white-list': 'false', 'enforce-whitelist': 'false', 'max-players': '200',
        'level-name': 'world-native-burst', 'level-seed': str(a.seed), 'level-type': 'minecraft:normal',
        'generator-settings': '{}', 'generate-structures': 'true',
        'gamemode': 'creative', 'force-gamemode': 'true',
        'difficulty': 'easy', 'allow-flight': 'true', 'hardcore': 'false',
        'spawn-protection': '0', 'max-tick-time': '-1', 'pause-when-empty-seconds': '-1',
        'player-idle-timeout': '0', 'op-permission-level': '4', 'function-permission-level': '4',
        'broadcast-console-to-ops': 'false', 'broadcast-rcon-to-ops': 'false',
        'motd': 'Isolated native Carpet scheduler replay', **{k: str(v) for k, v in distances.items()}}
    (inputs / 'server.properties').write_text(''.join(k + '=' + v.replace(':', '\\:') + '\n' for k, v in sorted(props.items())))
    (inputs / 'eula.txt').write_text('eula=true\n')
    if a.nearby_query_proof or a.panic_callback_proof:
        players = [{'name': 'Burst000', 'x': 300096.5, 'y': 300, 'z': 300096.5},
                   {'name': 'Burst001', 'x': 308288.5, 'y': 300, 'z': 300096.5}]
    else:
        source_positions = json.loads((ROOT / 'live-player-positions.json').read_text())
        players = [{'name': f'Burst{i:03d}', 'x': p['x'], 'y': p['y'], 'z': p['z']}
                   for i, p in enumerate(source_positions)]
    if a.replay_chunk:
        players[0].update(x=a.replay_chunk[0] * 16 + 8, z=a.replay_chunk[1] * 16 + 8)
    recipe = {
        'players': players, 'cadence_seconds': 0.1,
        'setup_commands': ['carpet commandPlayer true', 'carpet allowSpawningOfflinePlayers true',
                           'fabrolia regions playerticks on'],
        'spawn_commands': [f"player {p['name']} spawn at {p['x']} {p['y']} {p['z']} facing 0 0 in minecraft:overworld in creative" for p in players],
        'diagnostic_commands': ['fabrolia regions', 'fabrolia regions top verbose 100', 'fabrolia regions ticks status 256 256'],
        'local_clock_command': 'fabrolia regions top verbose 100', 'async_mode': a.async_mode,
        'list_command': 'list', 'time_command': 'execute in minecraft:overworld run time query gametime',
        'ack_template': 'say NATIVE_BURST_ACK_{sequence:06d}', 'stop_command': 'stop',
        'boot_timeout_seconds': 240, 'join_timeout_seconds': 240, 'progress_timeout_seconds': a.settled_seconds + 120,
        'command_timeout_seconds': 60, 'minimum_tick_delta': 100, 'progress_intervals': 3,
        'query_interval_seconds': 5, 'settled_observation_seconds': a.settled_seconds,
        'graceful_stop_seconds': 600, 'terminate_seconds': 5,
        'java_args': ['-Xms2G', '-Xmx24G', '-Duser.language=en', '-Duser.country=US',
                      '--add-modules=jdk.internal.vm.ci', '--add-opens=java.base/java.lang=ALL-UNNAMED',
                      '--enable-native-access=ALL-UNNAMED', '-XX:-UseCodeCacheFlushing',
                      '-XX:+UseZGC', '-XX:+DisableExplicitGC', '-XX:+UseDynamicNumberOfGCThreads',
                      '-XX:ReservedCodeCacheSize=512M',
                      '-XX:StartFlightRecording=filename={run}/profile.jfr,settings=profile,dumponexit=true,maxsize=256m,'
                      'method-timing=net.minecraft.server.level.ServerChunkCache::pollTask;'
                      'net.minecraft.server.level.ServerChunkCache$MainThreadExecutor::pollTask;'
                      'com.axalotl.fabrolia.common.regions.RegionWorkerPool::runExclusiveWorkerStep;'
                      'com.axalotl.fabrolia.common.regions.IslandTickUnit::runCoordinated;'
                      'net.minecraft.world.level.chunk.status.ChunkStatusTasks::generateNoise;'
                      'net.minecraft.world.level.chunk.status.ChunkStatusTasks::generateFeatures,'
                      'jdk.MethodTiming#period=5s,jdk.InitialEnvironmentVariable#enabled=false,'
                      'jdk.InitialSystemProperty#enabled=false,jdk.SystemProcess#enabled=false',
                      '-Duser.home={run}', '-jar', 'fabric-server-launch.jar', 'nogui']}
    if a.handoff_profile:
        methods = [
            'com.axalotl.fabrolia.common.regions.IslandCommands::' + method
            for method in ('acquireGlobalFrame', 'acquireGlobalClaims', 'executeQueryPass',
                           'fabrolia$drainRoutedBatchEntered', 'parkCommandWaiterForGlobalFence')]
        methods.extend([
            'com.axalotl.fabrolia.common.regions.NativeEntityDependencies::prepareEntityTick',
            'com.axalotl.fabrolia.common.regions.RegionizedServer::tickOneNativeAdmitted'])
        trace_methods = methods[:3] + [
            'com.axalotl.fabrolia.common.regions.RegionScheduler::executeGlobalMainBody',
            'com.axalotl.fabrolia.common.regions.RegionizedServer$EntityTickCursor::tickNextOnMain',
            'com.axalotl.fabrolia.common.regions.IslandEntityTracking::flush']
        recipe['handoff_profile'] = {
            'measurement_kind': 'diagnostic', 'performance_comparable': False,
            'status_command': 'parallelproof status', 'status_timeout_seconds': 15,
            'observer_seconds': 600, 'method_timing_filters_added': methods,
            'method_trace_filters': trace_methods, 'method_trace_threshold': '0ms',
            'window_recording': 'profile-window.jfr', 'recording_maxsize': '1g',
            'trace_scope': 'Individual method intervals with caller stacks; intersect and union per thread, never sum nested durations',
            'boundaries': {
                'before': 'handoff-status-before.json',
                'after': 'handoff-status-after.json'},
            'counter_scope': 'Cumulative observer snapshots; phase-local counts are after minus before',
            'timing_scope': 'JFR MethodTiming 5s periods; use recorded boundary timestamps, not whole-run totals'}
        recipe['java_args'] = [
            (argument.replace(',jdk.MethodTiming#',
                              ';' + ';'.join(methods) + ',jdk.MethodTiming#')
                     .replace('maxsize=256m', 'maxsize=1g')
             + ',method-trace=' + ';'.join(trace_methods) + ',jdk.MethodTrace#threshold=0ms')
            if argument.startswith('-XX:StartFlightRecording=') else argument
            for argument in recipe['java_args']]
    if a.probe:
        copy_file(a.probe.resolve(strict=True), inputs / 'observers/native-ride-probe.jar')
        recipe['java_args'].insert(0, '-javaagent:observers/native-ride-probe.jar={run}/native-ocean-ride.txt')
    if a.fabric_probe:
        probe = a.fabric_probe.resolve(strict=True)
        if mod_id(probe) != 'fabrolia_parallel_native_proof':
            raise ValueError('Expected the disposable native parallel proof mod')
        copy_file(probe, inputs / 'mods/fabrolia-parallel-native-proof.jar')
        if not (a.nearby_query_proof or a.panic_callback_proof):
            recipe['setup_commands'].append(
                'parallelproof observe ' + str(recipe.get('handoff_profile', {}).get('observer_seconds', 300)))
        recipe['native_parallel_probe'] = True
        recipe['java_args'].insert(0, '-Dmixin.debug.export=true')
        recipe['woven_class_evidence'] = 'server/.mixin.out'
    if a.nearby_query_proof or a.panic_callback_proof:
        kind = 'panic-callback' if a.panic_callback_proof else 'nearby-query'
        key = kind.replace('-', '_')
        command = 'parallelproof paniccallback' if a.panic_callback_proof else 'parallelproof nearbyquery'
        cow_tag = 'parallelproof_panic' if a.panic_callback_proof else 'parallelproof_nearby'
        recipe['campaign_kind'] = kind
        recipe[key] = {
            'timeout_seconds': 600, 'ownership_timeout_seconds': 60,
            'poll_interval_seconds': 1, 'observer_seconds': 600,
            'cow_position': [300098.5, 300, 300096.5],
            'cow_tag': cow_tag,
            'arena_commands': [
                {'command': 'fill 300092 299 300092 300100 303 300100 minecraft:stone hollow',
                 'response': r'Successfully filled \d+ block'},
                {'command': 'setblock 308288 299 300096 minecraft:stone',
                 'response': r'Changed the block at'},
                *[{'command': f"tp {p['name']} {p['x']} {p['y']} {p['z']} 0 0",
                   'response': 'Teleported ' + p['name'] + ' to'} for p in players],
                {'command': 'summon minecraft:cow 300098.5 300 300096.5 {Tags:["' + cow_tag + '"],PersistenceRequired:1b}',
                 'response': r'Summoned new Cow'}],
            'snapshot_commands': {p['name']: 'parallelproof gameplay snapshot ' + p['name'] for p in players},
            'census_command': 'parallelproof playerquery',
            'observer_command': 'parallelproof observe 600',
            'status_command': command + ' status',
            'disable_command': 'parallelproof disable',
            'phases': [
                {'phase': phase, 'item': item, 'timeout_seconds': 180,
                 'arm_command': 'parallelproof nearbyquery arm Burst000 ' + phase,
                 'item_command': 'item replace entity Burst000 weapon.mainhand with minecraft:' + item,
                 'item_response': 'Replaced a slot on Burst000 with',
                 'require_command': 'parallelproof nearbyquery require ' + phase}
                for phase, item in [('tempt', 'wheat'), ('look', 'stone')]],
            'mass_progress': 'NOT_RUN', 'mass_performance': 'NOT_RUN', 'gameplay': 'NOT_RUN'}
        if a.panic_callback_proof:
            recipe[key]['phases'] = [
                {'phase': phase, 'timeout_seconds': 180,
                 'arm_command': command + ' arm Burst000 ' + phase,
                 'require_command': command + ' require ' + phase}
                for phase in ('default', 'custom', 'opaque')]
            recipe[key]['proof'] = {
                'goal_graph': 'Complete native Cow AI; only exact PanicGoal replaced for custom/opaque',
                'positive': 'Exact goalQuery true, original worker prepare LOCAL, original worker E returned',
                'negative': 'Exact goalQuery false, original worker prepare GLOBAL, callback delta zero during prepare',
                'opaque_body': 'Original Main E invokes callback under physical Main and real global claims',
                'callback_scope': 'Per-prepare delta; earlier Main callbacks permitted',
                'evidence_marker': 'NATIVE_PANIC_CALLBACK_EVIDENCE',
                'stop_marker': 'NATIVE_PANIC_CALLBACK_STOP_PASS'}
            recipe['measurement_kind'] = 'diagnostic'
            recipe['performance_comparable'] = False
    if a.gameplay:
        copy_file(ROOT / 'parallel-native-proof/native-wire-client.py',
                  inputs / 'observers/native-wire-client.py')
        recipe['gameplay'] = {
            'after_measured_window': True, 'timeout_seconds': 600,
            'observation_timeout_seconds': 60, 'client_join_timeout_seconds': 120,
            'wire_asset': 'observers/native-wire-client.py',
            'real_player': 'WireGameplay', 'malformed_player': 'WireMalformed',
            'carpet_player': 'GameplayCarpet',
            'platform': [300000, 299, 300000, 300031, 299, 300015],
            'interior': [300008.0, 300.0, 300008.0],
            'boundary_from': [300015.5, 300.0, 300008.0],
            'boundary_to': [300016.5, 300.0, 300008.0],
            'boundary_scope': 'native chunk boundary; not an assertion of island ownership transfer',
            'handoff_anchor': [308200.0, 300.0, 300008.0],
            'handoff_scope': 'native teleport between distinct committed islands, followed by wire movement and L',
            'death_scope': 'native command-block minecart kills real wire player after E before owed Main L',
            'item': 'minecraft:shield', 'minimum_use_progress_ticks': 3,
            'async_transitions': ['off', 'on']}
    save(run / 'commands.json', recipe)
    copy_file(Path(__file__).resolve(), run / 'controller.py')
    hashes = inventory(inputs)
    code = {'inputs_except_fabrolia': {k: v for k, v in hashes.items() if k != fab_path},
            'commands': digest(run / 'commands.json'), 'controller': digest(run / 'controller.py'),
            'java': digest(JAVA), 'java_release': digest(JAVA.parent.parent / 'release')}
    if a.gameplay:
        code['gameplay_python'] = digest(Path('/usr/bin/python3'))
    selected = {'run_id': a.run_id, 'matched_run': a.match_run, 'candidate_source': str(candidate),
                'original_runtime': str(ORIGINAL), 'original_distances': distances,
                'fabrolia_path': fab_path, 'fabrolia_sha256': hashes[fab_path],
                'input_sha256': hashes, 'comparison_inputs': code, 'comparison_sha256': fingerprint(code),
                'code_only': True, 'world_or_playerdata_copied': False,
                'config_allowlist': ['fabrolia.toml', 'c2me.toml']}
    if prior and selected['comparison_sha256'] != prior['comparison_sha256']:
        raise RuntimeError('Matched run differs outside candidate jar; use the same harness and Java')
    copy_tree(inputs, run / 'server')
    save(run / 'selected.json', selected)
    print(json.dumps({'prepared': str(run), 'fabrolia_sha256': selected['fabrolia_sha256'],
                      'comparison_sha256': selected['comparison_sha256']}), flush=True)


class WireChild:
    """An owned transport subprocess; its output never proves gameplay acceptance."""
    def __init__(self, campaign, name):
        if os.environ.get('FABROLIA_NATIVE_BURST_ISOLATED') != 'loopback-only':
            raise RuntimeError('Wire subprocess requires isolated _control')
        self.campaign, self.name = campaign, name
        self.proc = None
        self.events = deque(maxlen=8192)
        self.serial = 0
        self.condition = threading.Condition()
        self.reader_error = None
        self.expected_disconnect = False
        self.threads = []
        self.files = []
        self.sequence = 0
        campaign.wires.append(self)
        settings = campaign.recipe['gameplay']
        argv = ['/usr/bin/python3', '-u', str(campaign.run / 'server' / settings['wire_asset']),
                '--name', name, '--join-timeout', str(settings['client_join_timeout_seconds']),
                '--max-seconds', str(settings['timeout_seconds'])]
        save(campaign.run / (name + '-command.json'), argv)
        self.proc = subprocess.Popen(argv, cwd=campaign.run, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        os.set_blocking(self.proc.stdin.fileno(), False)
        (campaign.run / (name + '-owned-pid.txt')).write_text(str(self.proc.pid) + '\n')
        for label, stream in [('stdout', self.proc.stdout), ('stderr', self.proc.stderr)]:
            log = (campaign.run / (name + '-' + label + '.jsonl')).open('xb')
            self.files.append(log)
            thread = threading.Thread(target=self.pump, args=(stream, log, label), daemon=True)
            self.threads.append(thread)
            thread.start()

    def pump(self, stream, log, label):
        try:
            for raw in iter(stream.readline, b''):
                log.write(raw)
                log.flush()
                if label == 'stdout':
                    event = json.loads(raw)
                    with self.condition:
                        self.serial += 1
                        self.events.append((self.serial, event))
                        self.condition.notify_all()
        except Exception as exc:
            with self.condition:
                self.reader_error = repr(exc)
                self.condition.notify_all()

    def send(self, command):
        self.sequence += 1
        command = dict(command, id=self.name + '-' + str(self.sequence))
        self.campaign.commands.write(json.dumps({'monotonic': time.monotonic(),
            'phase': self.campaign.phase, 'wire_player': self.name, 'wire_input': command}) + '\n')
        data = memoryview(encoded(command) + b'\n')
        end = min(time.monotonic() + 5, self.campaign.gameplay_deadline)
        while data:
            if self.proc.poll() is not None:
                raise RuntimeError('Owned wire exited before command: ' + self.name)
            left = end - time.monotonic()
            if left <= 0:
                raise TimeoutError('Owned wire stdin deadline: ' + self.name)
            if select.select([], [self.proc.stdin], [], left)[1]:
                try:
                    data = data[os.write(self.proc.stdin.fileno(), data):]
                except BlockingIOError:
                    pass
        return command['id']

    def wait(self, predicate, after=0, timeout=60, disconnect=False):
        end = min(time.monotonic() + timeout, self.campaign.gameplay_deadline)
        with self.condition:
            cursor = after
            while True:
                if self.events and cursor < self.events[0][0] - 1:
                    raise RuntimeError('Wire observation buffer overrun: ' + self.name)
                for serial, event in self.events:
                    if serial > cursor:
                        cursor = serial
                        if predicate(event):
                            return event
                        if event['event'] in ('command_error', 'error', 'disconnect', 'eof') and not disconnect:
                            raise RuntimeError('Wire failure: ' + json.dumps(event))
                if self.reader_error:
                    raise RuntimeError('Wire output reader failed: ' + self.reader_error)
                if self.proc.poll() is not None and not any(t.is_alive() for t in self.threads):
                    raise RuntimeError('Owned wire exited before observation: ' + self.name)
                left = end - time.monotonic()
                if left <= 0:
                    raise TimeoutError('Wire observation deadline: ' + self.name)
                self.condition.wait(min(left, 0.25))

    def stop(self):
        result = {'name': self.name, 'native_disconnect_expected': self.expected_disconnect}
        if self.proc is None:
            return dict(result, clean=False, method='launch-failed')
        result['pid'] = self.proc.pid
        method = 'already-exited'
        if self.proc.poll() is None:
            self.proc.stdin.close()  # wire EOF requests local graceful socket closure
            method = 'stdin-eof'
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.terminate()
                method = 'owned-child-sigterm'
                try:
                    self.proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.proc.kill()
                    method = 'owned-child-sigkill'
                    self.proc.wait(timeout=5)
        for thread in self.threads:
            thread.join(timeout=5)
        finished = not any(thread.is_alive() for thread in self.threads)
        for log in self.files:
            if finished:
                log.close()
        expected = 1 if self.expected_disconnect else 0
        return dict(result, method=method, returncode=self.proc.returncode,
                    output_readers_finished=finished, reader_error=self.reader_error,
                    clean=method in ('stdin-eof', 'already-exited') and
                    self.proc.returncode == expected and finished and not self.reader_error)


class Campaign:
    def __init__(self, run, recipe):
        self.run, self.recipe = run, recipe
        self.proc = None
        self.thread = None
        self.condition = threading.Condition()
        self.lines = deque(maxlen=100000)
        self.serial = 0
        self.sequence = 0
        self.joined = set()
        self.phase = 'boot'
        self.console = (run / 'console.log').open('x', buffering=1)
        self.commands = (run / 'commands.jsonl').open('x', buffering=1)
        self.diagnostics = []
        self.time_reports = []
        self.local_reports = []
        self.last_list = None
        self.cleanup = {}
        self.wires = []
        self.mass_progress_report = {'status': 'NOT_RUN'}
        self.gameplay_report = {'status': 'NOT_RUN', 'checks': [], 'clients': []}
        self.nearby_query_report = {'status': 'NOT_RUN', 'setup': [], 'snapshots': [],
                                    'phases': [], 'final_requirements': []}
        self.panic_callback_report = {'status': 'NOT_RUN', 'setup': [], 'snapshots': [],
                                      'phases': [], 'final_requirements': []}
        self.panic_callback_errors = []
        self.handoff_status = {}

    def launch(self):
        argv = [str(JAVA)] + [s.replace('{run}', str(self.run)) for s in self.recipe['java_args']]
        save(self.run / 'java-command.json', argv)
        self.proc = subprocess.Popen(argv, cwd=self.run / 'server', stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        os.set_blocking(self.proc.stdin.fileno(), False)
        (self.run / 'owned-java-pid.txt').write_text(str(self.proc.pid) + '\n')
        def pump():
            for raw in iter(self.proc.stdout.readline, b''):
                line = ANSI.sub('', raw.decode('utf-8', errors='replace').rstrip())
                with self.condition:
                    self.console.write(line + '\n')
                    self.serial += 1
                    self.lines.append((self.serial, line))
                    if (self.recipe.get('panic_callback')
                            and 'NATIVE_PANIC_CALLBACK_OBSERVER_ERROR' in line):
                        self.panic_callback_errors.append(line)
                    match = re.search(r'\b(Burst\d{3}) joined the game\b', line)
                    if match:
                        self.joined.add(match[1])
                    self.condition.notify_all()
        self.thread = threading.Thread(target=pump, daemon=True)
        self.thread.start()

    def send(self, command, timeout=5):
        self.commands.write(json.dumps({'monotonic': time.monotonic(), 'phase': self.phase, 'command': command}) + '\n')
        data = memoryview((command + '\n').encode())
        end = time.monotonic() + timeout
        while data:
            if self.proc.poll() is not None:
                raise RuntimeError('Native server exited before stdin command')
            left = end - time.monotonic()
            if left <= 0:
                raise TimeoutError('Native stdin stalled')
            if select.select([], [self.proc.stdin], [], left)[1]:
                try:
                    data = data[os.write(self.proc.stdin.fileno(), data):]
                except BlockingIOError:
                    pass

    def wait(self, predicate, after, timeout):
        end = time.monotonic() + timeout
        with self.condition:
            cursor = after
            while True:
                if self.lines and cursor < self.lines[0][0] - 1:
                    raise RuntimeError('Native output overran bounded observation buffer')
                for serial, line in self.lines:
                    if serial > cursor:
                        cursor = serial
                        if predicate(line):
                            return line
                if self.proc.poll() is not None:
                    raise RuntimeError('Native server exited in ' + self.phase)
                left = end - time.monotonic()
                if left <= 0:
                    raise TimeoutError('Native response deadline in ' + self.phase)
                self.condition.wait(min(left, 0.25))

    def exchange(self, command, timeout=None, optional=False):
        start = self.serial
        self.sequence += 1
        ack = self.recipe['ack_template'].format(sequence=self.sequence)
        self.send(command)
        self.send(ack)
        self.wait(lambda line: re.search(r'\[Server\] ' + re.escape(ack[4:]) + r'\s*$', line), start,
                  timeout if timeout is not None else self.recipe['command_timeout_seconds'])
        with self.condition:
            lines = [line for serial, line in self.lines if serial > start]
        errors = [line for line in lines if any(token in line for token in ERRORS)]
        if errors and not optional:
            raise RuntimeError('Native command rejected: ' + command + ': ' + '; '.join(errors))
        return lines

    def diagnostics_now(self):
        for command in self.recipe['diagnostic_commands']:
            lines = self.exchange(command, optional=True)
            self.diagnostics.append({'phase': self.phase, 'command': command, 'output': lines})
            save(self.run / 'diagnostics.json', self.diagnostics)

    def capture_handoff_status(self, boundary, reason):
        settings = self.recipe.get('handoff_profile')
        if not settings or boundary in self.handoff_status:
            return
        record = {'boundary': boundary, 'reason': reason, 'phase': self.phase,
                  'command': settings['status_command'], 'measurement_kind': 'diagnostic',
                  'performance_comparable': False, 'started_unix': time.time(),
                  'started_monotonic': time.monotonic(), 'status': 'FAIL'}
        self.handoff_status[boundary] = record
        start = self.serial
        try:
            record['output'] = self.exchange(settings['status_command'],
                                             timeout=settings['status_timeout_seconds'])
            if not any('PARALLEL_NATIVE_STATUS ' in line for line in record['output']):
                raise RuntimeError('Missing native observer status response')
            if not any('NATIVE_ADMISSION_CENSUS ' in line for line in record['output']):
                raise RuntimeError('Missing native admission census response')
            if any(marker in line for line in record['output'] for marker in (
                    'PARALLEL_NATIVE_CLAIM_TRACE_ERROR ', 'PARALLEL_NATIVE_CLAIM_TRACE_UNAVAILABLE ')):
                raise RuntimeError('Native observer reported a diagnostic failure')
            traces = {}
            for line in record['output']:
                marker = re.search(r'\bNATIVE_ADMISSION_GLOBAL_TRACE_(BEGIN|RETURN|END)\s+(.*)', line)
                if not marker:
                    continue
                fields = dict(re.findall(r'(\w+)=([^\s]+)', marker[2]))
                key = (fields['window'], fields['type'])
                if marker[1] == 'BEGIN':
                    if key in traces:
                        raise RuntimeError('Duplicate native admission trace')
                    traces[key] = [int(fields['records']), 0, False]
                else:
                    trace = traces.get(key)
                    if trace is None or trace[2]:
                        raise RuntimeError('Native admission trace lost its opening boundary')
                    if marker[1] == 'RETURN':
                        if int(fields['ordinal']) != trace[1]:
                            raise RuntimeError('Native admission trace has missing metadata rows')
                        trace[1] += 1
                    else:
                        trace[2] = True
            if any(not closed or expected != observed for expected, observed, closed in traces.values()):
                raise RuntimeError('Incomplete native admission trace response')
            record['status'] = 'CAPTURED'
        except InterruptedError:
            raise
        except Exception as exc:
            record['error'] = type(exc).__name__ + ': ' + str(exc)
        finally:
            if 'output' not in record:
                with self.condition:
                    record['output'] = [line for serial, line in self.lines if serial > start]
            record.update(finished_unix=time.time(), finished_monotonic=time.monotonic())
            try:
                save(self.run / settings['boundaries'][boundary], record)
            except OSError as exc:
                record['save_error'] = repr(exc)

    def player_list(self, timeout=None):
        lines = self.exchange(self.recipe['list_command'], timeout=timeout)
        for line in lines:
            match = re.search(r'There are (\d+) of a max of (\d+) players online:\s*(.*)', line)
            if match:
                self.last_list = {'count': int(match[1]), 'names': sorted(s.strip() for s in match[3].split(',') if s.strip()), 'line': line}
                return self.last_list
        raise RuntimeError('No native English player-list response')

    def complete_list(self, report):
        return report['count'] == len(self.recipe['players']) and set(report['names']) == {p['name'] for p in self.recipe['players']}

    def query_time(self, timeout=None):
        lines = self.exchange(self.recipe['time_command'], timeout=timeout)
        values = [int(m[1]) for line in lines if (m := re.search(r'The (?:game )?time is (\d+)', line))]
        if len(values) != 1:
            raise RuntimeError('Expected exactly one native gameTime report')
        self.time_reports.append({'ticks': values[0], 'monotonic': time.monotonic(), 'output': lines})
        return values[0]

    def query_local(self):
        before = time.monotonic()
        lines = self.exchange(self.recipe['local_clock_command'])
        values = {}
        for line in lines:
            match = re.search(r'Burst\d{3}.*island#(\d+)/gen(\d+).* L=(\d+)', line)
            if match:
                values[match[1] + '/' + match[2]] = int(match[3])
        self.local_reports.append({'monotonic': time.monotonic(), 'command_started': before,
                                   'clocks': values, 'output': lines})
        save(self.run / 'local-clocks.json', self.local_reports)

    def capture_profile(self):
        if 'before' in self.handoff_status:
            self.capture_handoff_status('after', 'final-capture-after-incomplete-window')
        if self.proc is None or self.proc.poll() is not None:
            return
        # Keep the measured snapshot distinct from the JVM's shutdown dump.
        # A forced native stop can interrupt dumponexit while it rewrites that file.
        recording = self.recipe.get('handoff_profile', {}).get('window_recording', 'profile.jfr')
        for name, args in [('threads-final.txt', ['Thread.print','-l']),
                           ('jfr-dump.txt', ['JFR.dump','filename=' + str(self.run / recording)])]:
            try:
                result = subprocess.run([str(JAVA.parent / 'jcmd'), str(self.proc.pid), *args],
                                        capture_output=True, timeout=15)
                (self.run / name).write_bytes(result.stdout + result.stderr)
            except (OSError, subprocess.TimeoutExpired) as exc:
                (self.run / name).write_text(repr(exc))

    @staticmethod
    def native_record(line):
        marker = 'PARALLEL_NATIVE_GAMEPLAY '
        if marker not in line:
            return None
        return json.JSONDecoder().raw_decode(line.split(marker, 1)[1])[0]

    def gameplay_check(self, label, evidence):
        self.gameplay_report['checks'].append({'check': label, 'evidence': evidence,
                                               'monotonic': time.monotonic()})
        save(self.run / 'gameplay-report.json', self.gameplay_report)

    def gameplay_exchange(self, command, timeout=None):
        left = self.gameplay_deadline - time.monotonic()
        if left <= 0:
            raise TimeoutError('Native gameplay campaign deadline')
        if timeout is not None:
            left = min(left, timeout)
        if left <= 0:
            raise TimeoutError('Native gameplay observation deadline')
        return self.exchange(command, timeout=min(left, self.recipe['command_timeout_seconds']))

    def gameplay_terrain_ready(self, box, label):
        chunks = [[x, z] for x in range(min(box[0], box[3]) // 16, max(box[0], box[3]) // 16 + 1)
                  for z in range(min(box[2], box[5]) // 16, max(box[2], box[5]) // 16 + 1)]
        end = min(self.gameplay_deadline, time.monotonic() +
                  self.recipe['gameplay']['observation_timeout_seconds'])
        evidence = {'box': list(box), 'chunks': chunks, 'attempts': [], 'status': 'WAITING'}
        self.gameplay_report.setdefault('setup', []).append(evidence)
        evidence['check'] = label
        try:
            while time.monotonic() < end:
                receipt = f'NATIVE_GAMEPLAY_LOADED_{self.sequence + 1:06d}'
                command = 'execute in minecraft:overworld' + ''.join(
                    f' if loaded {x * 16} {min(box[1], box[4])} {z * 16}' for x, z in chunks)
                command += ' run say ' + receipt
                attempt = {'command': command}
                evidence['attempts'].append(attempt)
                start = self.serial
                try:
                    attempt['output'] = self.gameplay_exchange(command, timeout=end - time.monotonic())
                finally:
                    if 'output' not in attempt:
                        with self.condition:
                            attempt['output'] = [line for serial, line in self.lines if serial > start]
                if any(re.search(r'\[Server\] ' + re.escape(receipt) + r'\s*$', line)
                       for line in attempt['output']):
                    evidence['status'] = 'PASS'
                    self.gameplay_check(label, evidence)
                    return
                # Pace only read-only terrain observations inside the existing deadline.
                with self.condition:
                    self.condition.wait(timeout=min(0.1, max(0, end - time.monotonic())))
            raise TimeoutError('Native terrain readiness deadline')
        except Exception as exc:
            evidence.update(status='FAIL', error=type(exc).__name__ + ': ' + str(exc))
            if isinstance(exc, TimeoutError):
                raise TimeoutError(label + ': ' + json.dumps(evidence)) from exc
            raise
        finally:
            save(self.run / 'gameplay-report.json', self.gameplay_report)

    def gameplay_setup_fill(self, box, block, label):
        self.gameplay_terrain_ready(box, label + '-terrain-loaded')
        command = 'fill ' + ' '.join(map(str, box)) + ' ' + block
        evidence = {'check': label, 'command': command, 'box': list(box), 'status': 'FAIL'}
        self.gameplay_report.setdefault('setup', []).append(evidence)
        start = self.serial
        try:
            lines = evidence['output'] = self.gameplay_exchange(command)
            outcomes = [match[1] for line in lines if (match := re.search(
                r'\[Server thread/INFO\]: (Successfully filled [1-9]\d* block\(s\)|No blocks were filled)\s*$', line))]
            if len(outcomes) != 1:
                raise RuntimeError(label + ': missing exact native fill outcome: ' + json.dumps(lines))
            evidence.update(status='PASS', outcome=outcomes[0])
            self.gameplay_check(label, evidence)
        except Exception as exc:
            evidence['error'] = type(exc).__name__ + ': ' + str(exc)
            raise
        finally:
            if 'output' not in evidence:
                with self.condition:
                    evidence['output'] = [line for serial, line in self.lines if serial > start]
            save(self.run / 'gameplay-report.json', self.gameplay_report)

    def gameplay_event(self, player, event, after, predicate=lambda record: True, tag=None):
        evidence = None
        def matches(line):
            nonlocal evidence
            record = self.native_record(line)
            if (record and record.get('player') == player and record.get('event') == event
                    and (tag is None or record.get('tag') == tag) and predicate(record)):
                evidence = record
                return True
            return False
        timeout = min(self.recipe['gameplay']['observation_timeout_seconds'],
                      self.gameplay_deadline - time.monotonic())
        if timeout <= 0:
            raise TimeoutError('Native gameplay campaign deadline')
        self.wait(matches, after, timeout)
        return evidence

    def gameplay_snapshot(self, player, predicate, label):
        end = min(self.gameplay_deadline, time.monotonic() +
                  self.recipe['gameplay']['observation_timeout_seconds'])
        last = None
        while time.monotonic() < end:
            for line in self.gameplay_exchange('parallelproof gameplay snapshot ' + player):
                record = self.native_record(line)
                if record and record.get('event') == 'snapshot' and record.get('player') == player:
                    last = record
                    if record.get('present') is True and predicate(record):
                        self.gameplay_check(label, record)
                        return record
            # Pace read-only observations, never gate or delay native execution.
            with self.condition:
                self.condition.wait(timeout=min(0.1, max(0, end - time.monotonic())))
        raise TimeoutError(label + ': last native snapshot=' + json.dumps(last))

    def gameplay_arm(self, player, tag):
        self.gameplay_exchange('parallelproof gameplay arm ' + player + ' ' + tag)
        return self.serial

    @staticmethod
    def gameplay_at(record, position):
        return all(abs(record[axis] - value) < 0.00001 for axis, value in zip(('x', 'y', 'z'), position))

    def gameplay_teleport(self, wire, position):
        cursor = wire.serial
        self.gameplay_exchange('tp ' + wire.name + ' ' + ' '.join(map(str, position)) + ' 0 0')
        wire.wait(lambda event: event['event'] == 'ready' and
                  self.gameplay_at(event['native_position'], position), after=cursor)
        self.gameplay_snapshot(wire.name,
            lambda record: self.gameplay_at(record, position) and not record['awaitingTeleport']
            and record['connected'] and record['clientLoaded'],
            wire.name + '-native-teleport-confirmed')

    def gameplay_require(self, player, tag, kind, count):
        lines = self.gameplay_exchange(
            'parallelproof gameplay require ' + player + ' ' + kind + ' ' + str(count))
        records = [record for line in lines if (record := self.native_record(line))
                   and record.get('event') == 'require' and record.get('player') == player
                   and record.get('tag') == tag and record.get('kind') == kind]
        if (len(records) != 1 or records[0].get('pass') is not True
                or records[0].get('count') != count or records[0].get('actual') != count):
            raise RuntimeError(tag + ': missing or failed exact native callback count: ' + json.dumps(records))
        self.gameplay_check(tag + '-exact-native-callback-count', records[0])

    def gameplay_move(self, wire, tag, position, kind='Pos', yaw=0, pitch=0):
        cursor = self.gameplay_arm(wire.name, tag)
        wire.send({'cmd': 'move', 'kind': kind, 'x': position[0], 'y': position[1],
                   'z': position[2], 'yaw': yaw, 'pitch': pitch,
                   'onGround': True, 'horizontalCollision': False})
        wire.send({'cmd': 'tick-end'})
        result = self.gameplay_event(wire.name, 'completion', cursor,
            lambda record: record['kind'] == kind, tag=tag)
        if not result['success'] or not self.gameplay_at(result['after'], position):
            raise RuntimeError(tag + ': movement native callback rejected or corrected: ' + json.dumps(result))
        if kind in ('Rot', 'PosRot') and (
                abs(result['after']['yaw'] - yaw) > 0.00001
                or abs(result['after']['pitch'] - pitch) > 0.00001):
            raise RuntimeError(tag + ': native rotation was not retained: ' + json.dumps(result))
        self.gameplay_check(tag + '-native-position', result)
        self.gameplay_require(wire.name, tag, kind, 1)
        self.gameplay_snapshot(wire.name, lambda record: self.gameplay_at(record, position),
                               tag + '-native-position-retained')
        return result

    def gameplay_beacon_late(self, wire):
        # A real BE applies its first effect after this island's player E.
        # The owed listener physics must move to Main without replaying E.
        tag = 'beacon-main-late'
        cursor = self.gameplay_arm(wire.name, tag)
        self.gameplay_setup_fill([300012, 298, 300009, 300014, 298, 300011],
                                 'minecraft:iron_block', 'beacon-native-base-fill')
        self.gameplay_exchange('setblock 300013 299 300010 minecraft:beacon{primary_effect:"minecraft:haste"}')
        entry = self.gameplay_event(wire.name, 'listener_physics_entry', cursor,
            lambda record: record['hasHaste'] and record['exclusive'] and not record['worker']
            and record['phase'] == 'LATE_RUNNING' and record.get('listedUnitClaim')
            and record['clockContextId'] == record['listedIsland']
            and record['clockContextTime'] == record['clock'], tag=tag)
        self.gameplay_check('beacon-effect-forces-original-main-late-with-source-clock', entry)
        completed = self.gameplay_event(wire.name, 'listener_physics_completion', cursor,
            lambda record: record['turnToken'] == entry['turnToken']
            and record['clockContextId'] == entry['clockContextId']
            and record['clockContextTime'] == entry['clockContextTime'], tag=tag)
        self.gameplay_check('same-native-listener-physics-completed', completed)
        self.gameplay_snapshot(wire.name,
            lambda record: record.get('listedIsland') == entry['listedIsland']
            and record.get('listedLogicalTick', -1) >= entry['clockContextTime'],
            'main-late-returned-and-source-unit-published')
        self.gameplay_exchange('setblock 300013 299 300010 minecraft:stone')
        self.gameplay_exchange('effect clear ' + wire.name)

    def gameplay_use(self, wire, tag, sequence):
        cursor = self.gameplay_arm(wire.name, tag)
        wire.send({'cmd': 'use-item', 'hand': 'MAIN_HAND', 'sequence': sequence, 'yaw': 0, 'pitch': 0})
        wire.send({'cmd': 'tick-end'})
        result = self.gameplay_event(wire.name, 'completion', cursor,
            lambda record: record['kind'] == 'UseItem', tag=tag)
        if not result['success'] or not result['after']['using']:
            raise RuntimeError(tag + ': native use did not start: ' + json.dumps(result))
        self.gameplay_check(tag + '-native-use-callback', result)
        start = self.gameplay_event(wire.name, 'item_start', cursor,
                                   lambda record: record['using'], tag=tag)
        self.gameplay_check(tag + '-native-item-start', start)
        progress = self.gameplay_event(wire.name, 'item_tick', cursor,
            lambda record: record['using'] and record['remaining'] < start['remaining']
            and record['ticksUsing'] >= self.recipe['gameplay']['minimum_use_progress_ticks'], tag=tag)
        self.gameplay_check(tag + '-native-item-progress', progress)
        self.gameplay_require(wire.name, tag, 'UseItem', 1)
        return progress

    def gameplay_release(self, wire, tag, sequence):
        cursor = self.gameplay_arm(wire.name, tag)
        wire.send({'cmd': 'release-use-item', 'sequence': sequence})
        wire.send({'cmd': 'tick-end'})
        result = self.gameplay_event(wire.name, 'completion', cursor,
            lambda record: record['kind'] == 'PlayerAction', tag=tag)
        if not result['success'] or result['after']['using']:
            raise RuntimeError(tag + ': native release failed: ' + json.dumps(result))
        self.gameplay_check(tag + '-native-release-callback', result)
        stop = self.gameplay_event(wire.name, 'item_stop', cursor,
                                  lambda record: not record['using'], tag=tag)
        self.gameplay_check(tag + '-native-item-stop', stop)
        self.gameplay_require(wire.name, tag, 'PlayerAction', 1)

    @staticmethod
    def gameplay_committed(record):
        return (record.get('listedIsland', -1) >= 0 and record.get('listedCommitted')
                and record.get('ownerMatchesListed') and record.get('asyncActive'))

    @staticmethod
    def gameplay_same_player(record, reference):
        return all(record.get(key) == reference.get(key)
                   for key in ('uuid', 'playerIdentity', 'listenerIdentity'))

    def gameplay_island_handoff(self, wire, fake):
        destination = self.recipe['gameplay']['handoff_anchor']
        original = self.gameplay_snapshot(wire.name, self.gameplay_committed,
                                          'teleport-island-handoff-original-committed-owner')
        if sum((original[axis] - value) ** 2
               for axis, value in zip(('x', 'z'), (destination[0], destination[2]))) < 4096 ** 2:
            raise RuntimeError('Remote island anchor must be at least 4096 blocks away')
        self.gameplay_exchange('tp ' + fake + ' ' + ' '.join(map(str, destination)) + ' 0 0')
        x, y, z = map(int, destination)
        self.gameplay_setup_fill([x - 3, y - 1, z - 3, x + 5, y - 1, z + 3],
                                 'minecraft:stone', 'handoff-native-support-fill')
        self.gameplay_setup_fill([x - 3, y, z - 3, x + 5, y + 4, z + 3],
                                 'minecraft:air', 'handoff-native-air-clear')
        # The first teleport supplies tickets, not a promise of supported admission.
        self.gameplay_exchange('tp ' + fake + ' ' + ' '.join(map(str, destination)) + ' 0 0')
        anchor = self.gameplay_snapshot(fake,
            lambda record: self.gameplay_committed(record) and self.gameplay_at(record, destination)
            and record['onGround'] and record['connected'] and record['alive']
            and record['listedIsland'] != original['listedIsland'],
            'teleport-island-handoff-distinct-committed-anchor')
        original = self.gameplay_snapshot(wire.name,
            lambda record: self.gameplay_committed(record)
            and self.gameplay_same_player(record, original)
            and record['listedIsland'] == original['listedIsland']
            and record['listedIsland'] != anchor['listedIsland'],
            'teleport-island-handoff-original-still-distinct')
        self.gameplay_teleport(wire, destination)
        arrived = self.gameplay_snapshot(wire.name,
            lambda record: self.gameplay_committed(record) and self.gameplay_at(record, destination)
            and record['listedIsland'] == anchor['listedIsland']
            and record['listedIsland'] != original['listedIsland']
            and self.gameplay_same_player(record, original) and not record['awaitingTeleport'],
            'teleport-island-handoff-native-destination-owner')
        cursor = self.serial
        position = [destination[0] + 0.5, destination[1], destination[2]]
        movement = self.gameplay_move(wire, 'teleport-island-handoff-wire-movement', position)
        if (not self.gameplay_committed(movement['after'])
                or movement['after']['listedIsland'] != arrived['listedIsland']
                or not self.gameplay_same_player(movement['after'], original)):
            raise RuntimeError('Native movement did not execute for the transferred owner: ' + json.dumps(movement))
        late = self.gameplay_event(wire.name, 'listener_physics_completion', cursor,
            lambda record: record['sequence'] > movement['sequence']
            and self.gameplay_committed(record) and self.gameplay_same_player(record, original)
            and record['listedIsland'] == arrived['listedIsland'] and record['phase'] == 'LATE_RUNNING'
            and record['clockContextId'] == arrived['listedIsland']
            and record['clockContextTime'] == record['clock'],
            tag='teleport-island-handoff-wire-movement')
        advanced = self.gameplay_snapshot(wire.name,
            lambda record: self.gameplay_committed(record)
            and record['listedIsland'] == arrived['listedIsland']
            and record['listedLogicalTick'] > late['clockContextTime']
            and record['listedUnitsCompleted'] > arrived['listedUnitsCompleted'],
            'teleport-island-handoff-later-unit-progress')
        self.gameplay_check('native-teleport-island-handoff', {
            'original': original, 'anchor': anchor, 'arrived': arrived,
            'movement': movement, 'laterListenerPhysics': late, 'laterUnit': advanced})

    def gameplay_death_between_e_and_l(self, wire, fake):
        self.gameplay_arm(wire.name, 'native-death-preparation')
        before = self.gameplay_snapshot(wire.name,
            lambda record: self.gameplay_committed(record) and record['alive']
            and record['connected'] and record.get('ownerKind') == 'REAL',
            'native-death-before-alive-real-player')
        anchor = self.gameplay_snapshot(fake,
            lambda record: self.gameplay_committed(record)
            and record['listedIsland'] == before['listedIsland'],
            'native-death-live-progress-anchor')
        x, y, z = (int(before[axis]) for axis in ('x', 'y', 'z'))
        cart_tag = 'parallelproof_native_death_cart'
        cart_selector = '@e[type=minecraft:command_block_minecart,tag=' + cart_tag + ']'
        command = 'execute as @a[name=' + wire.name + ',nbt=!{Health:0.0f}] run kill @s'
        cleanup = [
            'kill ' + cart_selector,
            f'setblock {x + 3} {y} {z} minecraft:air',
            f'setblock {x + 3} {y - 1} {z} minecraft:stone',
            'gamerule minecraft:command_blocks_work ' + str(before['commandBlocksEnabled']).lower()]
        failure = None
        try:
            # 26.2 ServerLevel.isCommandBlockEnabled reads this native gamerule.
            # No server property, measured setup command, or pre-window world is changed.
            self.gameplay_exchange('gamerule minecraft:command_blocks_work true')
            self.gameplay_snapshot(wire.name, lambda record: record['commandBlocksEnabled'],
                                   'native-death-command-block-gamerule-enabled')
            self.gameplay_exchange(f'setblock {x + 3} {y - 1} {z} minecraft:redstone_block')
            self.gameplay_exchange(f'setblock {x + 3} {y} {z} minecraft:activator_rail[powered=true,shape=north_south]')
            cursor = self.gameplay_arm(wire.name, 'native-death-between-e-and-l')
            # Registration appends the real native cart after the already registered player.
            # Native activation runs its own command; there are no worker pauses or hooks.
            self.gameplay_exchange(f'summon minecraft:command_block_minecart {x + 3.5} {y + 0.0625} {z + 0.5} '
                + '{Tags:["' + cart_tag + '"],Command:' + json.dumps(command) + ',TrackOutput:0b}')
            entry = self.gameplay_event(wire.name, 'death_entry', cursor, tag='native-death-between-e-and-l')
            if not (self.gameplay_same_player(entry, before) and self.gameplay_committed(entry)
                    and entry['listedIsland'] == before['listedIsland']
                    and entry['physicalMain'] and not entry['worker'] and entry['exclusive']
                    and entry['ownsPosition'] and entry['listedUnitClaim']
                    and entry['listedWaitingOnRoutedCommand']
                    and entry['phase'] == 'EARLY_DONE' and entry['turnToken'] != 0
                    and entry['hasEarly'] and entry['lastEarlyToken'] == entry['turnToken']
                    and (not entry['hasLate'] or entry['lastLateToken'] != entry['turnToken'])
                    and entry['clockContextId'] == entry['listedIsland']
                    and entry['clockContextTime'] == entry['clock']):
                raise RuntimeError('Native death missed the real parked after-E/before-L window: ' + json.dumps(entry))

            def same_turn(record):
                return (self.gameplay_same_player(record, entry)
                        and all(record.get(key) == entry.get(key) for key in
                                ('turnToken', 'clockContextId', 'clockContextTime', 'clock',
                                 'listedIsland', 'listedGeneration'))
                        and record['physicalMain'] and not record['worker']
                        and record['exclusive'] and record['listedUnitClaim'])

            completed = self.gameplay_event(wire.name, 'death_completion', cursor,
                lambda record: record.get('deathEpisode') == entry['sequence'],
                tag='native-death-between-e-and-l')
            if not (same_turn(completed) and completed['success'] and not completed['alive']
                    and completed['health'] == 0 and completed['phase'] == 'EARLY_DONE'):
                raise RuntimeError('Native death did not complete on the same owed turn: ' + json.dumps(completed))
            late = self.gameplay_event(wire.name, 'listener_physics_entry', cursor,
                lambda record: record['sequence'] > completed['sequence']
                and same_turn(record) and record['phase'] == 'LATE_RUNNING' and not record['alive'],
                tag='native-death-between-e-and-l')
            late_done = self.gameplay_event(wire.name, 'listener_physics_completion', cursor,
                lambda record: record['sequence'] > late['sequence']
                and same_turn(record) and record['phase'] == 'LATE_RUNNING' and not record['alive'],
                tag='native-death-between-e-and-l')
            advanced = self.gameplay_snapshot(fake,
                lambda record: self.gameplay_committed(record)
                and record['listedIsland'] == entry['listedIsland']
                and record['listedLogicalTick'] > entry['clockContextTime']
                and record['listedUnitsCompleted'] > entry['listedUnitsCompleted'],
                'native-death-later-source-unit-progress')
            dead = self.gameplay_snapshot(wire.name,
                lambda record: self.gameplay_same_player(record, before)
                and not record['alive'] and record['health'] == 0 and record['connected'],
                'native-death-real-client-remains-connected')
            self.gameplay_require(wire.name, 'native-death-between-e-and-l', 'Death', 1)
            self.gameplay_check('native-death-between-e-and-l', {
                'before': before, 'anchor': anchor, 'cartPosition': [x + 3.5, y + 0.0625, z + 0.5],
                'cartCommand': command, 'deathEntry': entry, 'deathCompletion': completed,
                'owedMainListenerEntry': late, 'owedMainListenerCompletion': late_done,
                'laterSourceUnit': advanced, 'deadConnectedPlayer': dead})
        except Exception as exc:
            failure = exc
            raise
        finally:
            cleanup_errors = []
            for command in cleanup:
                try:
                    self.gameplay_exchange(command)
                except Exception as exc:
                    cleanup_errors.append(command + ': ' + str(exc))
            if cleanup_errors:
                detail = 'Native death fixture cleanup failed: ' + '; '.join(cleanup_errors)
                if failure is not None:
                    failure.add_note(detail)
                    self.gameplay_report['death_cleanup_errors'] = cleanup_errors
                else:
                    raise RuntimeError(detail)
        self.gameplay_arm(wire.name, 'native-death-awaiting-healthy-close')

    def gameplay(self):
        self.phase = 'gameplay'
        settings = self.recipe['gameplay']
        self.gameplay_deadline = time.monotonic() + settings['timeout_seconds']
        self.gameplay_report.update(status='RUNNING', recipe=settings, started_unix=time.time(),
                                    evidence_contract='native probe callbacks/state only; wire output is transport only')
        save(self.run / 'gameplay-report.json', self.gameplay_report)
        try:
            self.gameplay_exchange('parallelproof gameplay reset')
            sensor = self.gameplay_exchange('parallelproof sensor')
            sensor_pass = ('NATIVE_SENSOR_STATE_PASS A64=true B16=false retainedWithoutDueScan=true '
                           'refreshedOnDueScan=true physicalWorkerMigration=false')
            if not any(sensor_pass in line for line in sensor):
                raise RuntimeError('Missing native sensor state assertions: ' + json.dumps(sensor))
            self.gameplay_check('native-sensor-state-without-physical-worker-migration', sensor)
            wire = WireChild(self, settings['real_player'])
            wire.wait(lambda event: event['event'] == 'ready',
                      timeout=settings['client_join_timeout_seconds'])
            self.gameplay_arm(wire.name, 'real-joined')
            joined = self.gameplay_snapshot(wire.name, lambda record: not record['awaitingTeleport']
                                   and record['connected'] and record['clientLoaded'],
                                   'real-player-native-joined')
            # Move the native player first so its tickets load the disposable platform chunks.
            self.gameplay_teleport(wire, settings['interior'])
            platform = settings['platform']
            self.gameplay_setup_fill(platform, 'minecraft:stone', 'initial-native-platform-fill')
            self.gameplay_setup_fill(
                [platform[0], 300, platform[2], platform[3], 304, platform[5]],
                'minecraft:air', 'initial-native-air-clear')
            self.gameplay_teleport(wire, settings['interior'])
            supported = self.gameplay_snapshot(wire.name,
                lambda record: self.gameplay_committed(record)
                and self.gameplay_same_player(record, joined)
                and self.gameplay_at(record, settings['interior']) and record['onGround']
                and record['connected'] and record['clientLoaded'] and record['alive']
                and not record['awaitingTeleport'] and not record['hasHaste'],
                'initial-native-supported-committed-owner')
            self.gameplay_snapshot(wire.name,
                lambda record: self.gameplay_committed(record)
                and self.gameplay_same_player(record, supported)
                and record['listedIsland'] == supported['listedIsland']
                and record['listedGeneration'] == supported['listedGeneration']
                and self.gameplay_at(record, settings['interior']) and record['onGround']
                and record['connected'] and record['clientLoaded'] and record['alive']
                and not record['awaitingTeleport'] and not record['hasHaste'],
                'initial-native-supported-owner-retained')
            interior = list(settings['interior'])
            interior[0] += 0.5
            self.gameplay_move(wire, 'interior-movement', interior)
            self.gameplay_move(wire, 'interior-rotation', interior, kind='Rot', yaw=30, pitch=10)
            self.gameplay_move(wire, 'interior-status', interior, kind='StatusOnly')
            interior[0] += 0.25
            self.gameplay_move(wire, 'interior-position-rotation', interior,
                               kind='PosRot', yaw=-20, pitch=5)
            self.gameplay_teleport(wire, settings['boundary_from'])
            self.gameplay_move(wire, 'chunk-boundary-movement', settings['boundary_to'])
            self.gameplay_beacon_late(wire)
            self.gameplay_exchange('item replace entity ' + wire.name + ' weapon.mainhand with ' + settings['item'])
            self.gameplay_use(wire, 'real-item-use', 1)
            self.gameplay_release(wire, 'real-item-release', 2)

            fake = settings['carpet_player']
            self.gameplay_exchange('player ' + fake + ' spawn at ' +
                ' '.join(map(str, settings['interior'])) + ' facing 0 0 in minecraft:overworld in creative')
            self.gameplay_arm(fake, 'carpet-joined')
            self.gameplay_snapshot(fake, lambda record: record['connected'] and record['alive'],
                                   'carpet-native-joined')
            self.gameplay_exchange('item replace entity ' + fake + ' weapon.mainhand with ' + settings['item'])
            cursor = self.gameplay_arm(fake, 'carpet-use')
            self.gameplay_exchange('player ' + fake + ' use continuous')
            start = self.gameplay_event(fake, 'item_start', cursor, lambda record: record['using'], tag='carpet-use')
            self.gameplay_check('carpet-native-action-item-start', start)
            progress = self.gameplay_event(fake, 'item_tick', cursor,
                lambda record: record['using'] and record['remaining'] < start['remaining']
                and record['ticksUsing'] >= settings['minimum_use_progress_ticks'], tag='carpet-use')
            self.gameplay_check('carpet-native-action-item-progress', progress)
            cursor = self.gameplay_arm(fake, 'carpet-stop')
            self.gameplay_exchange('player ' + fake + ' stop')
            stop = self.gameplay_event(fake, 'item_stop', cursor,
                                      lambda record: not record['using'], tag='carpet-stop')
            self.gameplay_check('carpet-native-action-item-stop', stop)
            self.gameplay_snapshot(fake, lambda record: not record['using'], 'carpet-native-stopped-state')

            progress = self.gameplay_use(wire, 'mode-item-use', 3)
            for mode in settings['async_transitions']:
                cursor = self.gameplay_arm(wire.name, 'mode-' + mode)
                self.gameplay_exchange('fabrolia regions async ' + mode)
                active = mode == 'on'
                self.gameplay_snapshot(wire.name,
                    lambda record: record['configuredAsync'] == active and record['asyncActive'] == active,
                    'native-mode-' + mode)
                progress = self.gameplay_event(wire.name, 'item_tick', cursor,
                    lambda record: record['configuredAsync'] == active and record['asyncActive'] == active
                    and record['using'] and record['remaining'] < progress['remaining'], tag='mode-' + mode)
                self.gameplay_check('native-item-progress-across-mode-' + mode, progress)
            self.gameplay_release(wire, 'mode-item-release', 4)
            self.gameplay_exchange('fabrolia regions async ' + self.recipe['async_mode'])
            self.gameplay_island_handoff(wire, fake)
            self.gameplay_death_between_e_and_l(wire, fake)

            malformed = WireChild(self, settings['malformed_player'])
            malformed.wait(lambda event: event['event'] == 'ready',
                           timeout=settings['client_join_timeout_seconds'])
            self.gameplay_arm(malformed.name, 'malformed-joined')
            self.gameplay_snapshot(malformed.name, lambda record: not record['awaitingTeleport']
                                   and record['connected'] and record['clientLoaded'],
                                   'malformed-player-native-joined')
            cursor = self.gameplay_arm(malformed.name, 'invalid-movement')
            transport_cursor = malformed.serial
            malformed.send({'cmd': 'move', 'kind': 'Pos', 'x': 'NaN', 'y': 300, 'z': 300008,
                            'onGround': True, 'horizontalCollision': False, 'malformed': True})
            entry = self.gameplay_event(malformed.name, 'entry', cursor,
                                       lambda record: record['kind'] == 'Pos', tag='invalid-movement')
            self.gameplay_check('invalid-movement-native-entry', entry)
            disconnected = self.gameplay_event(malformed.name, 'disconnect', cursor,
                lambda record: 'invalid' in record['reason'].lower() and 'mov' in record['reason'].lower(),
                tag='invalid-movement')
            self.gameplay_check('invalid-movement-native-disconnect', disconnected)
            malformed.wait(lambda event: event['event'] == 'disconnect', after=transport_cursor, disconnect=True)
            malformed.expected_disconnect = True
            malformed.proc.wait(timeout=10)
            self.gameplay_exchange('player ' + fake + ' kill')
            cursor = self.gameplay_arm(wire.name, 'healthy-wire-close')
            wire.send({'cmd': 'close'})
            closed = self.gameplay_event(wire.name, 'disconnect', cursor, tag='healthy-wire-close')
            self.gameplay_check('healthy-wire-native-disconnect', closed)
            wire.proc.wait(timeout=10)
            if wire.proc.returncode != 0:
                raise RuntimeError('Healthy wire close returned ' + str(wire.proc.returncode))
            self.gameplay_report['status'] = 'PASS'
        except Exception as exc:
            self.gameplay_report.update(status='FAIL', error=type(exc).__name__ + ': ' + str(exc),
                                        phase=self.phase)
            raise
        finally:
            self.gameplay_report['finished_unix'] = time.time()
            save(self.run / 'gameplay-report.json', self.gameplay_report)

    def nearby_query(self):
        self.native_cow_proof('nearby-query', self.nearby_query_report, 'NATIVE_NEARBY_QUERY')

    def panic_callback(self):
        self.native_cow_proof('panic-callback', self.panic_callback_report, 'NATIVE_PANIC_CALLBACK')

    def native_cow_proof(self, kind, report, prefix):
        settings = self.recipe[kind.replace('-', '_')]
        panic = kind == 'panic-callback'
        proof_path = self.run / ('native-' + kind + '-proof.json')
        report.update(status='RUNNING', recipe=settings, started_unix=time.time(),
                      mass_progress='NOT_RUN', mass_performance='NOT_RUN', gameplay='NOT_RUN')
        deadline = time.monotonic() + settings['timeout_seconds']

        def exchange(command, evidence, end=deadline):
            left = min(deadline, end) - time.monotonic()
            if left <= 0:
                raise TimeoutError('Native ' + kind + ' deadline in ' + self.phase)
            lines = self.exchange(command, timeout=min(left, self.recipe['command_timeout_seconds']))
            evidence.append({'command': command, 'output': lines, 'monotonic': time.monotonic()})
            save(proof_path, report)
            if panic and self.panic_callback_errors:
                raise RuntimeError('Native panic-callback observer errors: ' + '; '.join(self.panic_callback_errors))
            if time.monotonic() > min(deadline, end):
                raise TimeoutError('Native ' + kind + ' deadline in ' + self.phase)
            return lines

        def status(lines, phase):
            ending = r'\s*$' if panic else r'(?:\s|$)'
            statuses = [match for line in lines if (match := re.search(
                r'\b' + prefix + r'_STATUS phase=(\w+) ready=(true|false) errors=(\d+)' + ending, line))]
            if len(statuses) != 1 or statuses[0][1] != phase['phase']:
                raise RuntimeError('Missing exact native ' + kind + ' status for ' + phase['phase'])
            if int(statuses[0][3]) != 0:
                raise RuntimeError('Native ' + kind + ' observation errors: ' + statuses[0][0])
            return statuses[0][2] == 'true'

        def require(phase, evidence, end=deadline):
            lines = exchange(phase['require_command'], evidence, end)
            marker = r'\b' + prefix + '_PASS phase=' + re.escape(phase['phase']) + (
                r'\s*$' if panic else r'(?:\s|$)')
            if sum(bool(re.search(marker, line)) for line in lines) != 1:
                raise RuntimeError('Missing exact native ' + kind + ' proof for ' + phase['phase'])
            if panic:
                if not status(lines, phase):
                    raise RuntimeError('Native panic-callback retained proof is not ready: ' + phase['phase'])
                marker = r'\b' + prefix + '_EVIDENCE phase=' + re.escape(phase['phase']) + r'(?:\s|$)'
                if sum(bool(re.search(marker, line)) for line in lines) != 1:
                    raise RuntimeError('Missing exact native panic-callback evidence for ' + phase['phase'])

        try:
            self.phase = kind + '-setup'
            for step in settings['arena_commands']:
                lines = exchange(step['command'], report['setup'])
                if not any(re.search(step['response'], line) for line in lines):
                    raise RuntimeError('Missing native setup success: ' + step['command'])
            self.phase = kind + '-ownership'
            ownership_end = min(deadline, time.monotonic() + settings['ownership_timeout_seconds'])
            last = {}
            while True:
                for player in self.recipe['players']:
                    name = player['name']
                    lines = exchange(settings['snapshot_commands'][name], report['snapshots'], ownership_end)
                    records = [record for line in lines if (record := self.native_record(line))
                               and record.get('event') == 'snapshot' and record.get('player') == name]
                    if len(records) != 1:
                        raise RuntimeError('Missing exact native ownership snapshot for ' + name)
                    last[name] = records[0]
                if (all(last[p['name']].get('present') is True
                        and self.gameplay_committed(last[p['name']])
                        and last[p['name']].get('listedGeneration', -1) >= 0
                        and self.gameplay_at(last[p['name']], [p['x'], p['y'], p['z']])
                        for p in self.recipe['players'])
                        and len({r['listedIsland'] for r in last.values()}) == 2):
                    report['distinct_committed_islands'] = last
                    break
                left = ownership_end - time.monotonic()
                if left <= 0:
                    raise TimeoutError('Two distinct committed native islands not established: ' + json.dumps(last))
                time.sleep(min(settings['poll_interval_seconds'], left))
            self.phase = kind + '-census'
            query_proof = exchange(settings['census_command'], report['setup'])
            if not any('NATIVE_PLAYER_QUERY_PASS ' in line for line in query_proof):
                raise RuntimeError('Missing native live player query callback proof')
            save(self.run / 'native-player-query-proof.json', query_proof)
            # Arm only after the arena and ownership setup; never consume the window during world setup.
            exchange(settings['observer_command'], report['setup'])
            for phase in settings['phases']:
                self.phase = kind + '-' + phase['phase']
                phase_end = min(deadline, time.monotonic() + phase['timeout_seconds'])
                evidence = {'phase': phase['phase'], 'status': 'RUNNING', 'commands': []}
                report['phases'].append(evidence)
                exchange(phase['arm_command'], evidence['commands'], phase_end)
                if 'item_command' in phase:
                    lines = exchange(phase['item_command'], evidence['commands'], phase_end)
                    if not any(phase['item_response'] in line for line in lines):
                        raise RuntimeError('Missing native hand-item replacement for ' + phase['phase'])
                while True:
                    lines = exchange(settings['status_command'], evidence['commands'], phase_end)
                    if status(lines, phase):
                        break
                    left = phase_end - time.monotonic()
                    if left <= 0:
                        raise TimeoutError('Native ' + kind + ' proof not ready for ' + phase['phase'])
                    time.sleep(min(settings['poll_interval_seconds'], left))
                require(phase, evidence['commands'], phase_end)
                evidence['status'] = 'PASS'
            self.phase = kind + '-final'
            # Require every retained proof again; an earlier pass cannot hide later observer faults.
            for phase in settings['phases']:
                require(phase, report['final_requirements'])
            roster = self.player_list(timeout=min(self.recipe['command_timeout_seconds'],
                                                  max(0, deadline - time.monotonic())))
            report['final_native_player_list'] = roster
            if not self.complete_list(roster):
                raise RuntimeError('Native ' + kind + ' roster did not remain exactly two players')
            exchange(settings['disable_command'], report['final_requirements'])
            report['status'] = 'AWAITING_NATIVE_STOP' if panic else 'PASS'
        except Exception as exc:
            report.update(status='FAIL', error=type(exc).__name__ + ': ' + str(exc), phase=self.phase)
            raise
        finally:
            report['finished_unix'] = time.time()
            save(proof_path, report)

    def exercise(self):
        self.launch()
        self.wait(lambda line: re.search(r'Done \([0-9.]+s\)! For help, type', line), 0, self.recipe['boot_timeout_seconds'])
        if self.recipe.get('native_parallel_probe'):
            self.wait(lambda line: 'PARALLEL_NATIVE_PROBE_READY observer=off' in line,
                      0, self.recipe['boot_timeout_seconds'])
        print('NATIVE_BURST_SERVER_READY run=' + self.run.name, flush=True)
        self.phase = 'setup'
        for command in self.recipe['setup_commands']:
            self.exchange(command)
        self.diagnostics_now()
        self.phase = 'joins'
        deadline = time.monotonic() + self.recipe['join_timeout_seconds']
        for command in self.recipe['spawn_commands']:
            self.send(command)
            time.sleep(self.recipe['cadence_seconds'])
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                raise TimeoutError('All native replay players not reached')
            if self.complete_list(self.player_list(min(left, self.recipe['command_timeout_seconds']))):
                break
            time.sleep(2)
        if self.recipe.get('nearby_query'):
            self.nearby_query()
            self.phase = 'complete'
            return
        if self.recipe.get('panic_callback'):
            self.panic_callback()
            self.phase = 'complete'
            return
        if self.recipe.get('native_parallel_probe'):
            self.phase = 'native-player-query'
            query_proof = self.exchange('parallelproof playerquery')
            if not any('NATIVE_PLAYER_QUERY_PASS ' in line for line in query_proof):
                raise RuntimeError('Missing native live player query callback proof')
            save(self.run / 'native-player-query-proof.json', query_proof)
        self.phase = 'progress'
        self.mass_progress_report['status'] = 'RUNNING'
        settled_start = time.monotonic()
        save(self.run / 'settled-window.json', {'pid': self.proc.pid, 'started_unix': time.time(),
             'minimum_seconds': self.recipe['settled_observation_seconds'], 'native_player_list': self.last_list})
        self.diagnostics_now()
        deadline = time.monotonic() + self.recipe['progress_timeout_seconds']
        self.capture_handoff_status('before', 'before-first-measured-W-L')
        anchor = self.query_time()
        self.query_local()
        intervals = 0
        while (intervals < self.recipe['progress_intervals']
               or time.monotonic() - settled_start < self.recipe['settled_observation_seconds']):
            left = deadline - time.monotonic()
            if left <= self.recipe['query_interval_seconds']:
                raise TimeoutError('Native progress observation exceeded its independent deadline')
            time.sleep(self.recipe['query_interval_seconds'])
            current = self.query_time(min(deadline - time.monotonic(), self.recipe['command_timeout_seconds']))
            self.query_local()
            if current < anchor:
                raise RuntimeError('Native gameTime regressed')
            if current - anchor >= self.recipe['minimum_tick_delta']:
                intervals += 1
                anchor = current
        self.capture_handoff_status('after', 'after-final-measured-W-L')
        if not self.complete_list(self.player_list()):
            raise RuntimeError('Native replay players did not remain joined through progress window')
        self.diagnostics_now()
        if self.recipe.get('native_parallel_probe'):
            self.phase = 'native-proof'
            proof = self.exchange('parallelproof require')
            if not any('PARALLEL_NATIVE_ADMISSION_PASS ' in line for line in proof):
                raise RuntimeError('Missing native parallel admission witness')
            save(self.run / 'native-parallel-proof.json', proof)
            self.exchange('parallelproof disable')
        if self.recipe['async_mode'] == 'on':
            first, last = self.local_reports[0], self.local_reports[-1]
            elapsed = last['monotonic'] - first['monotonic']
            rates = {key:(last['clocks'][key]-value)/elapsed for key,value in first['clocks'].items()
                     if key in last['clocks']}
            save(self.run / 'local-rates.json', {'elapsed':elapsed,'rates':rates})
            self.mass_progress_report.update(
                stable_identities=len(rates), required_stable_identities=90,
                minimum_ticks_per_second=min(rates.values()) if rates else None,
                required_ticks_per_second=10, elapsed_seconds=elapsed,
                rates_artifact='local-rates.json')
            if len(rates) < 90:
                raise RuntimeError('Fewer than 90 stable island clocks observed')
            if min(rates.values()) < 10:
                error = 'Local island progress below 10 ticks/s: ' + str(min(rates.values()))
                self.mass_progress_report.update(status='FAIL', error=error)
                save(self.run / 'mass-progress-report.json', self.mass_progress_report)
                if not self.recipe.get('gameplay'):
                    raise RuntimeError(error)
        if self.mass_progress_report['status'] != 'FAIL':
            self.mass_progress_report['status'] = 'PASS'
        save(self.run / 'mass-progress-report.json', self.mass_progress_report)
        if self.recipe.get('gameplay'):
            self.gameplay()
        self.phase = 'complete'

    def stop(self):
        for wire in self.wires:
            try:
                self.gameplay_report['clients'].append(wire.stop())
            except Exception as exc:
                self.gameplay_report['clients'].append({'name': wire.name, 'cleanup_error': repr(exc)})
        if self.recipe.get('gameplay'):
            save(self.run / 'gameplay-report.json', self.gameplay_report)
        if self.proc is None:
            return
        self.phase = 'shutdown'
        stop_cursor = self.serial
        if self.proc.poll() is None:
            try:
                self.send(self.recipe['stop_command'], timeout=2)
                self.proc.wait(timeout=self.recipe['graceful_stop_seconds'])
                self.cleanup['method'] = 'native-stop'
            except (OSError, RuntimeError, TimeoutError, subprocess.TimeoutExpired):
                if self.proc.poll() is None:
                    self.proc.terminate()
                try:
                    self.proc.wait(timeout=self.recipe['terminate_seconds'])
                    self.cleanup['method'] = 'owned-child-sigterm'
                except subprocess.TimeoutExpired:
                    self.proc.kill()
                    self.proc.wait(timeout=5)
                    self.cleanup['method'] = 'owned-child-sigkill'
        else:
            self.cleanup['method'] = 'already-exited'
        self.cleanup['returncode'] = self.proc.returncode
        if self.thread:
            self.thread.join(timeout=5)
            self.cleanup['output_reader_finished'] = not self.thread.is_alive()
        if self.recipe.get('native_parallel_probe'):
            self.cleanup['native_probe_stopped'] = any(
                'PARALLEL_NATIVE_SERVER_STOPPED' in line for _, line in self.lines)
            if not self.cleanup['native_probe_stopped']:
                raise RuntimeError('Missing native probe server-stopped callback')
            self.cleanup['native_probe_stop_pass'] = any(
                'PARALLEL_NATIVE_PROBE_STOP_PASS' in line for _, line in self.lines)
            if not self.cleanup['native_probe_stop_pass']:
                raise RuntimeError('Native probe terminal verification failed')
            if self.recipe.get('campaign_kind') == 'nearby-query':
                self.cleanup['nearby_query_stop_pass'] = any(
                    'NATIVE_NEARBY_QUERY_STOP_PASS' in line for _, line in self.lines)
                if not self.cleanup['nearby_query_stop_pass']:
                    raise RuntimeError('Missing completed nearby-query proof at native stop')
            if self.recipe.get('campaign_kind') == 'panic-callback':
                receipts = [line for serial, line in self.lines if serial > stop_cursor
                            and re.search(r'\bNATIVE_PANIC_CALLBACK_STOP_PASS\s*$', line)]
                self.cleanup['panic_callback_stop_pass'] = len(receipts) == 1
                self.panic_callback_report['native_stop_receipts'] = receipts
                if not self.cleanup.get('output_reader_finished'):
                    raise RuntimeError('Native panic-callback output reader did not finish')
                if not self.cleanup['panic_callback_stop_pass']:
                    raise RuntimeError('Missing exact completed panic-callback proof at native stop')
                if self.panic_callback_errors:
                    raise RuntimeError('Native panic-callback observer errors at native stop')


def control(run):
    if os.environ.get('FABROLIA_NATIVE_BURST_ISOLATED') != 'loopback-only':
        raise RuntimeError('Invoke through run mode and its systemd sandbox')
    selected = json.loads((run / 'selected.json').read_text())
    if inventory(run / 'server') != selected['input_sha256']:
        raise RuntimeError('Server is not an exact fresh prepared input copy')
    recipe = json.loads((run / 'commands.json').read_text())
    campaign = Campaign(run, recipe)
    report = {'status': 'FAIL', 'run_id': selected['run_id'], 'fabrolia_sha256': selected['fabrolia_sha256'],
              'comparison_sha256': selected['comparison_sha256'], 'started_unix': time.time()}
    def interrupted(signum, frame):
        raise InterruptedError('Supervisor signal ' + str(signum))
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        campaign.exercise()
        if campaign.mass_progress_report['status'] == 'FAIL':
            report['error'] = 'RuntimeError: ' + campaign.mass_progress_report['error']
        else:
            report['status'] = 'PASS'
    except Exception as exc:
        report['error'] = type(exc).__name__ + ': ' + str(exc)
        if campaign.mass_progress_report['status'] == 'RUNNING':
            campaign.mass_progress_report.update(status='FAIL', error=report['error'])
    finally:
        report['phase_at_result'] = campaign.phase
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        campaign.capture_profile()
        try:
            campaign.stop()
        except Exception as exc:
            report['status'] = 'FAIL'
            report['cleanup_error'] = repr(exc)
        if campaign.cleanup.get('method') != 'native-stop' or campaign.cleanup.get('returncode') != 0:
            report['status'] = 'FAIL'
            report.setdefault('error', 'Native server did not stop cleanly')
        if any(not result.get('clean', False) for result in campaign.gameplay_report['clients']):
            report['status'] = 'FAIL'
            report.setdefault('error', 'Gameplay wire fixture did not stop cleanly')
        campaign.commands.close()
        campaign.console.flush()
        report.update({'finished_unix': time.time(), 'joined_log_names': sorted(campaign.joined),
                       'last_native_player_list': campaign.last_list, 'native_time_reports': campaign.time_reports,
                       'local_clock_reports': campaign.local_reports,
                       'mass_progress': campaign.mass_progress_report,
                       'gameplay': campaign.gameplay_report,
                       'cleanup': campaign.cleanup, 'commands_sha256': digest(run / 'commands.jsonl'),
                       'console_sha256': digest(run / 'console.log'), 'diagnostics': campaign.diagnostics})
        if campaign.mass_progress_report['status'] != 'NOT_RUN':
            save(run / 'mass-progress-report.json', campaign.mass_progress_report)
        if recipe.get('handoff_profile'):
            report.update(measurement_kind='diagnostic', performance_comparable=False,
                          handoff_profile={'settings': recipe['handoff_profile'],
                                           'captures': campaign.handoff_status})
        if recipe.get('nearby_query'):
            report.update(campaign_kind=recipe['campaign_kind'], nearby_query=campaign.nearby_query_report,
                          mass_progress={'status': 'NOT_RUN', 'reason': 'Separate two-player correctness campaign'},
                          mass_performance={'status': 'NOT_RUN', 'reason': 'No measured mass-player window'})
        if recipe.get('panic_callback'):
            proof = campaign.panic_callback_report
            proof.update(status=report['status'], finished_unix=time.time(),
                         observer_errors=campaign.panic_callback_errors, cleanup=campaign.cleanup,
                         source_receipts={'candidate_source': selected['candidate_source'],
                                          'fabrolia_sha256': selected['fabrolia_sha256'],
                                          'comparison_sha256': selected['comparison_sha256'],
                                          'controller_sha256': selected['comparison_inputs']['controller'],
                                          'recipe_sha256': selected['comparison_inputs']['commands'],
                                          'probe_sha256': selected['input_sha256']['mods/fabrolia-parallel-native-proof.jar']},
                         output_receipts={'commands_sha256': report['commands_sha256'],
                                          'console_sha256': report['console_sha256']})
            if report['status'] != 'PASS':
                proof.setdefault('error', report.get('error') or report.get('cleanup_error') or 'Campaign failed')
            proof_path = run / 'native-panic-callback-proof.json'
            save(proof_path, proof)
            report.update(campaign_kind='panic-callback', measurement_kind='diagnostic',
                          performance_comparable=False, panic_callback=proof,
                          panic_callback_proof_sha256=digest(proof_path),
                          mass_progress={'status': 'NOT_RUN', 'reason': 'Separate two-player correctness campaign'},
                          mass_performance={'status': 'NOT_RUN', 'reason': 'No measured mass-player window'})
        save(run / 'report.json', report)
        print(json.dumps({'status': report['status'], 'report': str(run / 'report.json'), 'error': report.get('error')}), flush=True)
    return 0 if report['status'] == 'PASS' else 1


def supervise(a):
    run = owned(a.run_id)
    selected = json.loads((run / 'selected.json').read_text())
    expected = selected['comparison_inputs']
    if (digest(run / 'controller.py') != expected['controller'] or digest(run / 'commands.json') != expected['commands']
            or digest(JAVA) != expected['java'] or digest(JAVA.parent.parent / 'release') != expected['java_release']):
        raise RuntimeError('Pinned harness, commands or Java changed since prepare')
    if 'gameplay_python' in expected and digest(Path('/usr/bin/python3')) != expected['gameplay_python']:
        raise RuntimeError('Pinned gameplay Python interpreter changed since prepare')
    unit = 'fabrolia-native-burst-' + a.run_id + '-' + os.urandom(4).hex()
    command = ['/usr/bin/sudo', '-n', '/usr/bin/systemd-run', '--unit=' + unit,
               '--collect', '--wait', '--pipe', f'--uid={os.getuid()}', f'--gid={os.getgid()}',
               '--working-directory=' + str(run), '--property=ProtectSystem=strict',
               '--property=ProtectHome=read-only', '--property=ReadWritePaths=' + str(run),
               '--property=InaccessiblePaths=' + str(ORIGINAL), '--property=PrivateTmp=yes',
               '--property=PrivateNetwork=yes', '--property=NoNewPrivileges=yes',
               '--property=IPAddressDeny=any', '--property=IPAddressAllow=localhost',
               '--property=RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6',
               '--property=MemoryMax=32G',
               '--property=RuntimeMaxSec=' + ('2400' if any(
                   json.loads((run / 'commands.json').read_text()).get(key)
                   for key in ('gameplay', 'nearby_query', 'panic_callback')) else '1500'),
               '--property=TimeoutStopSec=650',
               '--property=KillMode=control-group', '--setenv=FABROLIA_NATIVE_BURST_ISOLATED=loopback-only',
               '--setenv=HOME=' + str(run), '/usr/bin/python3', str(run / 'controller.py'), '_control', a.run_id]
    with (run / 'execution.json').open('x') as f:
        json.dump({'argv': command, 'unit': unit, 'argv_sha256': fingerprint(command), 'started_unix': time.time()}, f, indent=2)
    print('NATIVE_BURST_SUPERVISOR ' + json.dumps(command), flush=True)
    # systemd owns all native descendants and enforces the outer wall-clock bound.
    def interrupted(signum, frame):
        raise InterruptedError('Supervisor interrupted by signal ' + str(signum))
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    with (run / 'supervisor.log').open('x', buffering=1) as log:
        launcher = None
        try:
            launcher = subprocess.Popen(command, cwd=run, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                        start_new_session=True, text=True)
            for line in launcher.stdout:
                log.write(line)
                print(line, end='', flush=True)
            result = launcher.wait()
        except (OSError, InterruptedError, KeyboardInterrupt) as exc:
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            signal.signal(signal.SIGINT, signal.SIG_IGN)
            log.write('SUPERVISOR_INTERRUPTED ' + repr(exc) + '\n')
            result = 130
            if launcher is not None:
                # Random per-invocation unit name: never stop a reused/unrelated service.
                try:
                    subprocess.run(['/usr/bin/sudo', '-n', '/usr/bin/systemctl', 'stop', unit],
                                   stdout=log, stderr=subprocess.STDOUT, timeout=45, check=False)
                except subprocess.TimeoutExpired:
                    log.write('Unit stop exceeded 45s; systemd RuntimeMaxSec remains enforced\\n')
                try:
                    launcher.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    launcher.kill()
                    launcher.wait(timeout=5)
    if not (run / 'report.json').is_file():
        save(run / 'report.json', {'status': 'FAIL', 'error': 'Supervisor/controller failed before report',
             'run_id': a.run_id, 'supervisor_returncode': result, 'fabrolia_sha256': selected['fabrolia_sha256'],
             'comparison_sha256': selected['comparison_sha256']})
    save(run / 'supervisor-result.json', {'returncode': result, 'log_sha256': digest(run / 'supervisor.log')})
    report = json.loads((run / 'report.json').read_text())
    print(json.dumps({'status': report['status'], 'report': str(run / 'report.json'), 'supervisor_returncode': result}), flush=True)
    return 0 if result == 0 and report['status'] == 'PASS' else 1


def compare(a):
    old, new = (owned(run_id) for run_id in (a.old_run, a.new_run))
    reports = [json.loads((run / 'report.json').read_text()) for run in (old, new)]
    selected = [json.loads((run / 'selected.json').read_text()) for run in (old, new)]
    matched = selected[0]['comparison_sha256'] == selected[1]['comparison_sha256']
    result = {'old': reports[0], 'new': reports[1], 'matched_inputs_except_fabrolia': matched,
              'old_failed_new_passed': matched and reports[0]['status'] == 'FAIL' and reports[1]['status'] == 'PASS',
              'limitation': 'FAIL is not itself proof of the same scheduler root cause; inspect phase, native output and counters. PASS proves only this finite fresh normal-terrain native fake-player replay.'}
    print(json.dumps(result, indent=2))
    return 0 if matched and reports[1]['status'] == 'PASS' else (1 if matched else 2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    prep = sub.add_parser('prepare')
    prep.add_argument('--run-id', required=True)
    prep.add_argument('--candidate', type=Path, required=True)
    prep.add_argument('--match-run', help='Use a prepared prior run immutable code/config snapshot, replacing only Fabrolia')
    prep.add_argument('--async-mode', choices=['on','off'], default='on')
    prep.add_argument('--seed', type=int, default=20260915)
    prep.add_argument('--replay-chunk', type=int, nargs=2)
    prep.add_argument('--settled-seconds', type=int, default=60)
    prep.add_argument('--probe', type=Path)
    prep.add_argument('--fabric-probe', type=Path)
    prep.add_argument('--gameplay', action='store_true',
                      help='Run native real-player/Carpet gameplay assertions after the unchanged measured window')
    prep.add_argument('--nearby-query-proof', action='store_true',
                      help='Run only a fresh two-player native Cow Tempt/uncached LookAt correctness proof')
    prep.add_argument('--panic-callback-proof', action='store_true',
                      help='Run only a fresh two-player native exact PanicGoal default/custom/opaque callback proof')
    prep.add_argument('--handoff-profile', action='store_true',
                      help='Profile a fresh mass campaign diagnostically; requires --fabric-probe, excludes matching/nearby proof')
    prep.add_argument('--supervise', action='store_true',
                      help='Immediately run the prepared fixture through its isolated supervisor')
    runner = sub.add_parser('run')
    runner.add_argument('--run-id', required=True)
    internal = sub.add_parser('_control', help=argparse.SUPPRESS)
    internal.add_argument('run_id')
    diff = sub.add_parser('compare')
    diff.add_argument('old_run')
    diff.add_argument('new_run')
    a = parser.parse_args()
    if a.mode == 'prepare':
        prepare(a)
        return supervise(a) if a.supervise else 0
    if a.mode == 'run':
        return supervise(a)
    if a.mode == '_control':
        return control(owned(a.run_id))
    return compare(a)


if __name__ == '__main__':
    raise SystemExit(main())
