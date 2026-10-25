"""Minimal local Minecraft 26.3 server harness: process control, RCON and lean protocol bots.

Standard library + psutil. Bots are offline-mode protocol 777 clients that log in, keep
alive, accept teleports, acknowledge chunk batches and time-stamp block updates of one
"clock" section. Nothing here simulates physics.
"""
import hashlib
import os
import re
import shutil
import socket
import struct
import subprocess
import threading
import time
import uuid
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
# Work directory for downloads, server templates and runs (large; not part of the repository).
HOME = Path(os.environ.get('FABROLIA_BENCH_HOME', REPO / 'build' / 'bench')).resolve()
JAVA = os.environ.get('FABROLIA_BENCH_JAVA') or shutil.which('java') or 'java'
PROTOCOL = 777  # 26.3

# Aikar's G1 flags, the usual recommendation for Minecraft servers.
AIKAR = ['-XX:+UseG1GC', '-XX:+ParallelRefProcEnabled', '-XX:MaxGCPauseMillis=200',
         '-XX:+UnlockExperimentalVMOptions', '-XX:+DisableExplicitGC', '-XX:+AlwaysPreTouch',
         '-XX:G1NewSizePercent=30', '-XX:G1MaxNewSizePercent=40', '-XX:G1HeapRegionSize=8M',
         '-XX:G1ReservePercent=20', '-XX:G1HeapWastePercent=5', '-XX:G1MixedGCCountTarget=4',
         '-XX:InitiatingHeapOccupancyPercent=15', '-XX:G1MixedGCLiveThresholdPercent=90',
         '-XX:G1RSetUpdatingPauseTimePercent=5', '-XX:SurvivorRatio=32',
         '-XX:+PerfDisableSharedMem', '-XX:MaxTenuringThreshold=1']


# ---------------------------------------------------------------- wire helpers
def varint(value):
    value &= 0xffffffff
    out = bytearray()
    while value >= 0x80:
        out.append((value & 0x7f) | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


def read_varint(buf, pos):
    result = shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7f) << shift
        if not b & 0x80:
            break
        shift += 7
    if result & 0x80000000:
        result -= 1 << 32
    return result, pos


def utf(text):
    raw = text.encode('utf-8')
    return varint(len(raw)) + raw


def block_pos_key(x, y, z):
    return struct.pack('>Q', ((x & 0x3ffffff) << 38) | ((z & 0x3ffffff) << 12) | (y & 0xfff))


def section_key(x, y, z):
    sx, sy, sz = x >> 4, y >> 4, z >> 4
    return struct.pack('>Q', ((sx & 0x3fffff) << 42) | ((sz & 0x3fffff) << 20) | (sy & 0xfffff))


SB = {
    'login': {'HELLO': 0, 'CUSTOM_QUERY_ANSWER': 2, 'LOGIN_ACKNOWLEDGED': 3, 'COOKIE_RESPONSE': 4},
    'configuration': {'CLIENT_INFORMATION': 0, 'COOKIE_RESPONSE': 1, 'FINISH_CONFIGURATION': 3,
                      'KEEP_ALIVE': 4, 'PONG': 5, 'SELECT_KNOWN_PACKS': 7, 'ACCEPT_CODE_OF_CONDUCT': 9},
    'play': {'ACCEPT_TELEPORTATION': 0, 'CHUNK_BATCH_RECEIVED': 11, 'CLIENT_TICK_END': 13,
             'CONFIGURATION_ACKNOWLEDGED': 16, 'COOKIE_RESPONSE': 21, 'KEEP_ALIVE': 28,
             'MOVE_PLAYER_POS': 30, 'MOVE_PLAYER_STATUS_ONLY': 33, 'PLAYER_LOADED': 44, 'PONG': 45,
             'USE_ITEM_ON': 66, 'SET_CARRIED_ITEM': 54, 'SET_CREATIVE_MODE_SLOT': 57},
}


class Bot:
    """One offline-mode player. A reader thread handles the protocol; a ticker thread sends
    CLIENT_TICK_END every 50 ms and the held position once a second, like an idle client."""

    def __init__(self, name, port, view_distance=10):
        self.name = name
        self.port = port
        self.view_distance = view_distance
        self.state = 'login'
        self.compression = -1
        self.lock = threading.Lock()
        self.sock = None
        self.closed = False
        self.error = None
        self.disconnect_reason = None
        self.loaded = False
        self.pos = None  # (x, y, z, yaw, pitch)
        self.teleports = 0
        self.packets = 0
        self.bytes = 0
        self.chunks = 0
        self.clock_keys = set()
        self.clock_section = None
        self.clock_times = []

    # -- connection
    def connect(self):
        self.sock = socket.create_connection(('127.0.0.1', self.port), timeout=30)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 << 20)
        self.sock.settimeout(None)
        self._raw(varint(0) + varint(PROTOCOL) + utf('localhost') + struct.pack('>H', self.port) + varint(2))
        offline = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + self.name).encode()).digest(), version=3)
        self.send('HELLO', utf(self.name) + offline.bytes)
        threading.Thread(target=self._reader, name='bot-' + self.name, daemon=True).start()
        threading.Thread(target=self._ticker, name='tick-' + self.name, daemon=True).start()

    def close(self):
        self.closed = True
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass

    def _raw(self, packet):
        frame = packet
        if self.compression >= 0:
            if len(packet) >= self.compression:
                frame = varint(len(packet)) + zlib.compress(packet, 1)
            else:
                frame = b'\x00' + packet
        with self.lock:
            self.sock.sendall(varint(len(frame)) + frame)

    def send(self, name, payload=b''):
        self._raw(varint(SB[self.state][name]) + payload)

    def use_item_on(self, x, y, z, face=1, hand=0, hit=(0.5, 1.0, 0.5)):
        """Right click on a block face (1 = UP), like a client placing the held block."""
        self.sequence = getattr(self, 'sequence', 0) + 1
        packed = ((x & 0x3ffffff) << 38) | ((z & 0x3ffffff) << 12) | (y & 0xfff)
        self.send('USE_ITEM_ON', varint(hand) + struct.pack('>Q', packed) + varint(face)
                  + struct.pack('>fff??', hit[0], hit[1], hit[2], False, False) + varint(self.sequence))

    def set_creative_slot(self, slot, item_id, count=1):
        """Creative inventory: put an item without components into a menu slot (hotbar = 36..44)."""
        self.send('SET_CREATIVE_MODE_SLOT', struct.pack('>h', slot) + varint(count) + varint(item_id) + varint(0) + varint(0))

    def select_hotbar(self, slot):
        self.send('SET_CARRIED_ITEM', struct.pack('>h', slot))

    def watch_clock(self, positions):
        """Record arrival times of updates touching the section that holds these blocks."""
        self.clock_keys = {block_pos_key(*p) for p in positions}
        self.clock_section = section_key(*positions[0])
        self.clock_times = []

    # -- threads
    def _ticker(self):
        n = 0
        nxt = time.monotonic()
        while not self.closed:
            try:
                if self.state == 'play' and self.loaded:
                    self.send('CLIENT_TICK_END')
                    if n % 20 == 0 and self.pos:
                        x, y, z = self.pos[:3]
                        self.send('MOVE_PLAYER_POS', struct.pack('>dddB', x, y, z, 1))
                    n += 1
            except OSError:
                if not self.closed:
                    self.error = 'send failed'
                return
            nxt += 0.05
            d = nxt - time.monotonic()
            if d > 0:
                time.sleep(d)
            else:
                nxt = time.monotonic()

    def _reader(self):
        buf = bytearray()
        sock = self.sock
        try:
            while not self.closed:
                chunk = sock.recv(1 << 20)
                if not chunk:
                    raise EOFError('server closed connection')
                self.bytes += len(chunk)
                buf += chunk
                pos = 0
                n = len(buf)
                while True:
                    # frame length
                    length = shift = 0
                    p = pos
                    ok = False
                    while p < n and p - pos < 3:
                        b = buf[p]
                        p += 1
                        length |= (b & 0x7f) << shift
                        if not b & 0x80:
                            ok = True
                            break
                        shift += 7
                    if not ok or p + length > n:
                        break
                    frame = memoryview(buf)[p:p + length]
                    pos = p + length
                    if self.compression >= 0:
                        data_len, q = read_varint(frame, 0)
                        packet = zlib.decompress(frame[q:]) if data_len else bytes(frame[q:])
                    else:
                        packet = bytes(frame)
                    frame.release()
                    self._packet(packet)
                del buf[:pos]
        except Exception as e:  # surfaced to the driver
            if not self.closed:
                self.error = repr(e)

    def _packet(self, pk):
        self.packets += 1
        pid, p = read_varint(pk, 0)
        st = self.state
        if st == 'play':
            if pid == 8:  # BLOCK_UPDATE
                if self.clock_keys and pk[p:p + 8] in self.clock_keys:
                    self.clock_times.append(time.monotonic())
            elif pid == 86:  # SECTION_BLOCKS_UPDATE
                if self.clock_section is not None and pk[p:p + 8] == self.clock_section:
                    self.clock_times.append(time.monotonic())
            elif pid == 45:  # KEEP_ALIVE
                self.send('KEEP_ALIVE', pk[p:p + 8])
            elif pid == 62:  # PING
                self.send('PONG', pk[p:p + 4])
            elif pid == 73:  # PLAYER_POSITION
                tid, p = read_varint(pk, p)
                x, y, z, dx, dy, dz, yaw, pitch, flags = struct.unpack_from('>ddddddffI', pk, p)
                if self.pos is not None and flags & 31:
                    ref = self.pos
                    vals = [x, y, z, yaw, pitch]
                    for bit in range(5):
                        if flags & (1 << bit):
                            vals[bit] += ref[bit]
                    x, y, z, yaw, pitch = vals
                self.pos = (x, y, z, yaw, pitch)
                self.teleports += 1
                self.send('ACCEPT_TELEPORTATION', varint(tid) + struct.pack('>dddff', x, y, z, yaw, pitch))
                if not self.loaded:
                    self.send('PLAYER_LOADED')
                    self.loaded = True
            elif pid == 46:  # LEVEL_CHUNK_WITH_LIGHT (Paper sends chunks outside batches too)
                self.chunks += 1
            elif pid == 11:  # CHUNK_BATCH_FINISHED
                self.send('CHUNK_BATCH_RECEIVED', struct.pack('>f', 64.0))
            elif pid == 32:  # DISCONNECT (NBT component)
                self.disconnect_reason = pk[p:p + 400].decode('utf-8', 'replace')
                raise ConnectionAbortedError('disconnected: ' + self.disconnect_reason)
            elif pid == 120:  # START_CONFIGURATION
                self.send('CONFIGURATION_ACKNOWLEDGED')
                self.loaded = False
                self.state = 'configuration'
                self._client_information()
            elif pid == 84:  # RESPAWN
                self.loaded = False
        elif st == 'configuration':
            if pid == 15:  # SELECT_KNOWN_PACKS: claim the vanilla core pack we were offered
                cnt, q = read_varint(pk, p)
                packs = []
                for _ in range(cnt):
                    parts = []
                    for _f in range(3):
                        ln, q = read_varint(pk, q)
                        parts.append(bytes(pk[q:q + ln]).decode())
                        q += ln
                    packs.append(parts)
                body = varint(len(packs)) + b''.join(utf(a) + utf(b) + utf(c) for a, b, c in packs)
                self.send('SELECT_KNOWN_PACKS', body)
            elif pid == 20:  # CODE_OF_CONDUCT
                self.send('ACCEPT_CODE_OF_CONDUCT')
            elif pid == 3:  # FINISH_CONFIGURATION
                self.send('FINISH_CONFIGURATION')
                self.state = 'play'
            elif pid == 4:  # KEEP_ALIVE
                self.send('KEEP_ALIVE', pk[p:p + 8])
            elif pid == 5:  # PING
                self.send('PONG', pk[p:p + 4])
            elif pid == 0:  # COOKIE_REQUEST
                ln, q = read_varint(pk, p)
                self.send('COOKIE_RESPONSE', pk[p:q + ln] + b'\x00')
            elif pid == 2:  # DISCONNECT
                self.disconnect_reason = pk[p:p + 400].decode('utf-8', 'replace')
                raise ConnectionAbortedError('config disconnect: ' + self.disconnect_reason)
        else:  # login
            if pid == 3:  # LOGIN_COMPRESSION
                self.compression, _ = read_varint(pk, p)
            elif pid == 2:  # LOGIN_FINISHED
                self.send('LOGIN_ACKNOWLEDGED')
                self.state = 'configuration'
                self._client_information()
            elif pid == 4:  # CUSTOM_QUERY: not understood
                txn, _ = read_varint(pk, p)
                self.send('CUSTOM_QUERY_ANSWER', varint(txn) + b'\x00')
            elif pid == 5:  # COOKIE_REQUEST
                ln, q = read_varint(pk, p)
                self.send('COOKIE_RESPONSE', pk[p:q + ln] + b'\x00')
            elif pid == 0:  # LOGIN_DISCONNECT
                self.disconnect_reason = pk[p:p + 400].decode('utf-8', 'replace')
                raise ConnectionAbortedError('login refused: ' + self.disconnect_reason)
            elif pid == 1:
                raise PermissionError('server requested encryption (online mode)')

    def _client_information(self):
        # language, view distance, chat visibility, colors, skin parts, main arm,
        # text filtering, allow listing, particle status
        self.send('CLIENT_INFORMATION', utf('en_us') + bytes([self.view_distance]) + b'\x00\x01\x7f\x01\x00\x01\x00')


# ---------------------------------------------------------------- RCON
class Rcon:
    def __init__(self, port, password):
        self.port = port
        self.password = password
        self.sock = None
        self.lock = threading.Lock()
        self.ident = 0

    def _read(self, n):
        b = b''
        while len(b) < n:
            piece = self.sock.recv(n - len(b))
            if not piece:
                raise EOFError('RCON closed')
            b += piece
        return b

    def _packet(self, kind, body):
        self.ident += 1
        data = struct.pack('<ii', self.ident, kind) + body.encode('utf-8') + b'\0\0'
        self.sock.sendall(struct.pack('<i', len(data)) + data)
        size = struct.unpack('<i', self._read(4))[0]
        data = self._read(size)
        ident, _ = struct.unpack('<ii', data[:8])
        return ident, data[8:-2].decode('utf-8', 'replace')

    def connect(self, timeout=600):
        self.sock = socket.create_connection(('127.0.0.1', self.port), timeout=timeout)
        ident, _ = self._packet(3, self.password)
        if ident == -1:
            raise RuntimeError('RCON auth refused')

    def __call__(self, command):
        with self.lock:
            if self.sock is None:
                self.connect()
            try:
                return self._packet(2, command)[1]
            except (OSError, EOFError):
                self.sock = None
                self.connect()
                return self._packet(2, command)[1]

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None


# ---------------------------------------------------------------- server
class Server:
    def __init__(self, directory, loader, port, xmx='4G', xms=None, jvm_extra=(), rcon_password='local-bench'):
        self.dir = Path(directory)
        self.loader = loader
        self.port = port
        self.xmx = xmx
        self.xms = xms or xmx
        self.jvm_extra = list(jvm_extra)
        self.rcon = Rcon(port + 1, rcon_password)
        self.rcon_password = rcon_password
        self.proc = None
        self.console = None

    def write_properties(self, **extra):
        props = {
            'server-ip': '127.0.0.1', 'server-port': self.port, 'online-mode': 'false',
            'enable-rcon': 'true', 'rcon.port': self.port + 1, 'rcon.password': self.rcon_password,
            'level-name': 'world', 'spawn-protection': 0, 'max-tick-time': -1,
            'enable-command-block': 'true', 'network-compression-threshold': 256,
            'max-players': 64, 'sync-chunk-writes': 'false', 'white-list': 'false',
            'enforce-whitelist': 'false',
        }
        props.update({k.replace('_', '-'): v for k, v in extra.items()})
        (self.dir / 'server.properties').write_text(''.join('%s=%s\n' % kv for kv in props.items()))
        (self.dir / 'eula.txt').write_text('eula=true\n')

    def command_line(self):
        jvm = [JAVA, '-Xms' + self.xms, '-Xmx' + self.xmx] + self.jvm_extra
        if self.loader == 'neoforge':
            args = next((self.dir / 'libraries/net/neoforged/neoforge').glob('*/win_args.txt'))
            return jvm + ['@' + str(args.relative_to(self.dir)).replace('\\', '/'), 'nogui']
        if self.loader == 'fabric':
            return jvm + ['-jar', 'fabric-server-launch.jar', 'nogui']
        return jvm + ['-jar', 'server.jar', 'nogui']

    def start(self):
        self.console = open(self.dir / 'console.log', 'w', encoding='utf-8', errors='replace')
        self.proc = subprocess.Popen(self.command_line(), cwd=self.dir, stdin=subprocess.PIPE,
                                     stdout=self.console, stderr=subprocess.STDOUT,
                                     creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        return self.proc

    def log_text(self):
        try:
            return (self.dir / 'console.log').read_text(encoding='utf-8', errors='replace')
        except OSError:
            return ''

    def wait_ready(self, timeout=900):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if self.proc.poll() is not None:
                raise RuntimeError('server exited with %s during start' % self.proc.returncode)
            if 'Done (' in self.log_text():
                for _ in range(200):
                    try:
                        self.rcon.connect()
                        return
                    except OSError:
                        time.sleep(0.5)
            time.sleep(0.5)
        raise TimeoutError('server did not start')

    def stop(self, timeout=300):
        if self.proc is None or self.proc.poll() is not None:
            return self.proc.returncode if self.proc else None
        try:
            self.rcon('stop')
        except Exception:
            try:
                self.proc.stdin.write(b'stop\n')
                self.proc.stdin.flush()
            except Exception:
                pass
        try:
            self.proc.wait(timeout)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait()
        self.rcon.close()
        if self.console:
            self.console.close()
        return self.proc.returncode


def copy_template(template, target):
    target = Path(target)
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(template, target)
    return target


def wait_until(fn, timeout, step=0.25, what='condition'):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if fn():
            return True
        time.sleep(step)
    raise TimeoutError(what)


# ---------------------------------------------------------------- log scan
# Log events at ERROR/FATAL: "[thread/ERROR]" lines and log4j status lines
# ("2026-...Z Server thread ERROR ...").
LOG_ERROR = re.compile(r'/(?:ERROR|FATAL)\]|^\d{4}-\d\d-\d\dT\S+Z .*? (?:ERROR|FATAL) ')
# NeoForge on Windows: log4j's DebugFile appender fails on netty's epoll/kqueue
# probes. A vanilla NeoForge run without Fabrolia logs the same lines.
APPENDER_NOISE = re.compile(r'ERROR An exception occurred processing Appender DebugFile')
NETTY_NATIVE = re.compile(r'io\.netty\.channel\.(?:epoll|kqueue)\.Native')


def unexpected_log_errors(text):
    """ERROR/FATAL log lines other than the proven NeoForge appender noise."""
    lines = text.splitlines()
    found = []
    for i, line in enumerate(lines):
        if not LOG_ERROR.search(line):
            continue
        if APPENDER_NOISE.search(line) and any(NETTY_NATIVE.search(l) for l in lines[i + 1:i + 4]):
            continue
        found.append(line)
    return found
