#!/usr/bin/env python3
"""Fresh full-modpack boot that exercises every class the mixin-export verifier
reads (players, entity tracking, items, commands, RCON), then stops natively.
Run with -Dmixin.debug.export=true in commands.json java_args, inside an
isolated loopback-only unit: python3 mixin-export-driver.py RUN
"""
import importlib.util, json, os, re, socket, struct, sys, time
from pathlib import Path

run = Path(sys.argv[1]).resolve()
if os.environ.get('FABROLIA_NATIVE_BURST_ISOLATED') != 'loopback-only':
    raise RuntimeError('Requires loopback-only isolation')
spec = importlib.util.spec_from_file_location('native_transport', run / 'native-transport.py')
transport = importlib.util.module_from_spec(spec); spec.loader.exec_module(transport)
selected = json.loads((run / 'selected.json').read_text())
if transport.inventory(run / 'server') != selected['input_sha256']:
    raise RuntimeError('Fresh input manifest does not match')
recipe = json.loads((run / 'commands.json').read_text())
props = transport.Path(run / 'server/server.properties').read_text()
port = int(re.search(r'^rcon\.port=(\d+)$', props, re.M)[1])
password = re.search(r'^rcon\.password=(.+)$', props, re.M)[1]
campaign = transport.Campaign(run, recipe)
report = {'status': 'RUNNING', 'started_unix': time.time()}


def rcon(command):
    with socket.create_connection(('127.0.0.1', port), timeout=10) as s:
        def packet(request_id, kind, body):
            payload = struct.pack('<ii', request_id, kind) + body.encode() + b'\x00\x00'
            s.sendall(struct.pack('<i', len(payload)) + payload)
            size = struct.unpack('<i', s.recv(4))[0]
            data = b''
            while len(data) < size:
                data += s.recv(size - len(data))
            return struct.unpack('<ii', data[:8]), data[8:-2].decode(errors='replace')
        (rid, _), _ = packet(1, 3, password)
        if rid != 1:
            raise RuntimeError('RCON login refused')
        return packet(2, 2, command)[1]


try:
    campaign.launch()
    campaign.wait(lambda line: re.search(r'Done \([0-9.]+s\)! For help, type', line), 0, recipe['boot_timeout_seconds'])
    for c in recipe['setup_commands']:
        campaign.exchange(c)
    for name, x in (('ExportA', 0), ('ExportB', 3)):
        campaign.exchange(f'player {name} spawn at {x} 100 0 facing 0 0 in minecraft:overworld in survival')
        # Case-insensitive: without the sandbox Carpet may resolve the name to a real
        # Mojang profile with different casing.
        campaign.wait(lambda line, n=name.lower(): n + ' joined the game' in line.lower(), 0, 60)
    campaign.exchange('summon minecraft:item 1 100 1 {Item:{id:"minecraft:stone",count:1}}')
    campaign.exchange('summon minecraft:zombie 2 100 2 {NoAI:1b,PersistenceRequired:1b}')
    report['rcon_list'] = rcon('list')
    campaign.exchange('player ExportA move forward')
    time.sleep(20)
    campaign.exchange('player ExportA stop')
    report['status'] = 'PASS'
except Exception as error:
    import traceback
    report.update(status='FAIL', error=repr(error), traceback=traceback.format_exc())
finally:
    try:
        campaign.stop()
    except Exception as error:
        report.update(status='FAIL', stop_error=repr(error))
    report['cleanup'] = campaign.cleanup
    with campaign.condition:
        lines = [line for serial, line in campaign.lines]
    report['mixin_errors'] = [l for l in lines if 'Mixin apply' in l or 'InvalidInjection' in l or 'MixinTransformerError' in l][:20]
    if report['mixin_errors'] or campaign.cleanup.get('returncode') != 0:
        report['status'] = 'FAIL'
    (run / 'export-report.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print('EXPORT_RESULT ' + json.dumps({k: report.get(k) for k in ('status', 'error', 'rcon_list', 'cleanup')}), flush=True)
sys.exit(0 if report['status'] == 'PASS' else 1)
