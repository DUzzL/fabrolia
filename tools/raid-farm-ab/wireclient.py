"""Thread-based 26.3 protocol client for the raid farm A/B (Windows: no select() on stdin).

Reuses the stand's perf-wire-client codec/handshake (tools/stand/perf-wire-client.py)
and adds the packets a real client sends while operating the farm:
ATTACK (1), INTERACT (26), PUNCH (46), plus entity tracking from ADD_ENTITY (1)
and REMOVE_ENTITIES (78).
"""
import importlib.util
import math
import socket
import struct
import threading
import time
import uuid
import hashlib
from pathlib import Path

_SRC = Path(__file__).resolve().parent.parent / 'stand' / 'perf-wire-client.py'
_spec = importlib.util.spec_from_file_location('perf_wire_client', _SRC)
pwc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pwc)

pwc.SB['play'].update({'ATTACK': 1, 'INTERACT': 26, 'PUNCH': 46})
pwc.CB['play'].update({1: 'ADD_ENTITY', 78: 'REMOVE_ENTITIES'})
pwc.QUIET_EVENTS.update({'received', 'sent', 'teleport', 'ready', 'block_packets', 'state',
                         'compression', 'login_finished', 'known_packs', 'play_login', 'rotation',
                         'block_changed_ack'})


def lpvec3(x, y, z):
    """net.minecraft.network.LpVec3.write"""
    m = max(abs(x), abs(y), abs(z))
    if m < 3.051944088384301e-5:
        return b'\x00'
    scale = math.ceil(m)
    partial = (scale & 3) != scale
    markers = (scale & 3) | 4 if partial else scale

    def pack(v):
        return int(round((v * 0.5 + 0.5) * 32766.0))
    buf = markers | (pack(x / scale) << 3) | (pack(y / scale) << 18) | (pack(z / scale) << 33)
    out = bytes([buf & 0xff, (buf >> 8) & 0xff]) + struct.pack('>I', (buf >> 16) & 0xffffffff)
    if partial:
        out += pwc.varint(scale >> 2)
    return out


def skip_lpvec3(r):
    lowest = r.take(1)[0]
    if lowest == 0:
        return
    r.take(1)
    r.take(4)
    if lowest & 4:
        r.varint()


class WireClient(pwc.Client):
    def __init__(self, name, port):
        class Args:
            pass
        args = Args()
        args.name = name
        args.host = '127.0.0.1'
        args.port = port
        args.max_seconds = 1e9
        args.join_timeout = 300
        args.idle_timeout = 120
        super().__init__(args)
        self.lock = threading.RLock()
        self.entities = {}
        self.error = None
        self.thread = None

    def send(self, name, payload=b'', command_id=None, **details):
        with self.lock:
            return super().send(name, payload, command_id, **details)

    def packet(self, packet):
        super().packet(packet)
        if self.state != 'play':
            return
        r = pwc.Reader(packet)
        packet_id = r.varint()
        if packet_id == 1:
            entity_id = r.varint()
            r.take(16)
            type_id = r.varint()
            x, y, z = r.unpack('ddd')
            self.entities[entity_id] = (type_id, x, y, z)
        elif packet_id == 78:
            for _ in range(r.varint()):
                self.entities.pop(r.varint(), None)

    def connect(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.settimeout(10)
        self.sock.connect(('127.0.0.1', self.args.port))
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.sock.settimeout(None)
        self.send('CLIENT_INTENTION', pwc.varint(pwc.PROTOCOL) + pwc.utf('localhost')
                  + struct.pack('>H', self.args.port) + pwc.varint(2))
        self.transition('login')
        offline = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + self.args.name).encode()).digest(), version=3)
        self.send('HELLO', pwc.utf(self.args.name) + offline.bytes)
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def _loop(self):
        try:
            while not self.closed:
                self.receive()
        except Exception as e:  # surfaced to the driver
            if not self.closed:
                self.error = e

    def close(self):
        self.closed = True
        try:
            self.sock.close()
        except Exception:
            pass

    # --- gameplay packets -------------------------------------------------
    def tick_end(self):
        self.send('CLIENT_TICK_END')

    def move(self, x, y, z, yaw, pitch, on_ground=True):
        self.send('MOVE_PLAYER_POS_ROT', struct.pack('>dddffB', x, y, z, yaw, pitch, 1 if on_ground else 0))

    def interact(self, entity_id, hand, rel):
        self.send('INTERACT', pwc.varint(entity_id) + pwc.varint(hand) + lpvec3(*rel) + b'\x00')

    def attack(self, entity_id):
        self.send('ATTACK', pwc.varint(entity_id))

    def punch(self):
        self.send('PUNCH')

    def use_item_on(self, hand, seq, x, y, z, face, hit):
        packed = ((x & 0x3ffffff) << 38) | ((z & 0x3ffffff) << 12) | (y & 0xfff)
        payload = pwc.varint(hand) + struct.pack('>Q', packed) + pwc.varint(face)
        payload += struct.pack('>fff??', hit[0], hit[1], hit[2], False, False) + pwc.varint(seq)
        self.send('USE_ITEM_ON', payload)

    def use_item(self, hand, seq, yaw, pitch):
        self.send('USE_ITEM', pwc.varint(hand) + pwc.varint(seq) + struct.pack('>ff', yaw, pitch))
