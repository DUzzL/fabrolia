"""Controlled temporary bell removal in a running local farm fixture.

Start run_farm_ab.py separately, then run this with its directory and --port.
Unlike the observational probe, this deliberately changes the copied farm:
After observing the first raid, wait 18 Main ticks, then remove the bell and the
fallback fletching table for six Main ticks and restore both. This creates the
village graph seen in the late failures. It tests raid stop rules, not villager AI.
"""
import argparse
import json
import re
import socket
import struct
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('run', type=Path)
parser.add_argument('--port', type=int, required=True, help='Game port; RCON is port + 1')
args = parser.parse_args()
report = {'commands': []}


def ex(command):
    with socket.create_connection(('127.0.0.1', args.port + 1), timeout=10) as sock:
        def read(n):
            data = b''
            while len(data) < n:
                part = sock.recv(n - len(data))
                if not part:
                    raise EOFError('RCON closed')
                data += part
            return data

        def packet(ident, kind, text):
            body = struct.pack('<ii', ident, kind) + text.encode() + b'\0\0'
            sock.sendall(struct.pack('<i', len(body)) + body)
            return read(struct.unpack('<i', read(4))[0])

        auth = packet(1, 3, 'raid-ab-local-only')
        if struct.unpack('<i', auth[:4])[0] != 1:
            raise RuntimeError('RCON authentication failed')
        answer = packet(2, 2, command)[8:-2].decode()
    if not command.startswith('time query'):
        report['commands'].append({'command': command, 'out': answer})
    return answer


def wait_until_tick(target):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        now = int(re.search(r'\d+', ex('time query gametime'))[0])
        if now >= target:
            return now
        time.sleep(0.025)
    raise TimeoutError('game clock did not reach target')


try:
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        log = args.run / 'raidprobe.log'
        if log.exists():
            first = re.search(r't=(\d+).*?RAID_NEW raid=(\w+)', log.read_text(encoding='utf-8'))
            if first:
                break
        time.sleep(0.05)
    else:
        raise TimeoutError('no raid created')
    report['raid'] = first[2]
    report['created_tick_context'] = int(first[1])
    # The probe stamps island L on workers; /time query on Main reads raw time.
    # Schedule a relative duration on the raw clock, never compare it with L.
    report['observed_tick_raw'] = int(re.search(r'\d+', ex('time query gametime'))[0])
    state = {}
    for prop, options in {'attachment': ('floor', 'ceiling', 'single_wall', 'double_wall'),
                          'facing': ('north', 'south', 'east', 'west'),
                          'powered': ('false', 'true')}.items():
        for value in options:
            if 'passed' in ex(f'execute if block -1 192 -1 minecraft:bell[{prop}={value}]'):
                state[prop] = value
                break
        else:
            raise RuntimeError('bell state not found: ' + prop)
    restore = 'minecraft:bell[' + ','.join(f'{key}={value}' for key, value in state.items()) + ']'
    if 'passed' not in ex('execute if block 0 143 18 minecraft:fletching_table'):
        raise RuntimeError('fallback workstation missing before intervention')
    target = report['observed_tick_raw'] + 18
    tick = wait_until_tick(target)
    if tick > target + 1:
        raise RuntimeError('missed intervention tick: ' + str(tick))
    report['remove_tick_raw'] = tick
    try:
        ex('setblock -1 192 -1 minecraft:air')
        ex('setblock 0 143 18 minecraft:air')
        report['restore_tick_raw'] = wait_until_tick(tick + 6)
    finally:
        ex('setblock -1 192 -1 ' + restore)
        ex('setblock 0 143 18 minecraft:fletching_table')
    report['status'] = 'DONE'
finally:
    (args.run / 'bell-gap-control.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report), flush=True)
