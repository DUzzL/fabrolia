#!/usr/bin/env python3
"""Disposable 26.3/protocol 777 TCP client. Standard library; no physics engine.

Only launch as a child of native-controller.py's isolated _control process.
Requires FABROLIA_NATIVE_BURST_ISOLATED=loopback-only, literal localhost or
127.0.0.1, port 25935. Always connects numerically to 127.0.0.1 (no DNS).

stdin: one JSON object per line, with cmd and optional correlation id.
  {"cmd":"ready"} -> current received teleport position, or not_ready.
  {"cmd":"move","kind":"PosRot","x":0,"y":80,"z":0,"yaw":0,
   "pitch":0,"onGround":false,"horizontalCollision":false}
  kind is exactly Pos, Rot, PosRot, or StatusOnly. Only relevant numeric fields
  are required. Both booleans are always required; no hidden movement defaults.
  {"cmd":"input","forward":true} (other six input bits default false)
  {"cmd":"use-item","hand":"MAIN_HAND","sequence":1,"yaw":0,"pitch":0}
  {"cmd":"release-use-item","sequence":2} emits native PLAYER_ACTION RELEASE_USE_ITEM.
  {"cmd":"use-on-block","hand":"MAIN_HAND","sequence":2,
   "blockX":0,"blockY":79,"blockZ":0,"face":"UP",
   "hitX":0.5,"hitY":1.0,"hitZ":0.5,"inside":false,"worldBorderHit":false}
  hitXYZ are block-relative offsets, exactly the native wire float fields.
  {"cmd":"tick-end"} emits the native unit packet, not a simulated tick.
  {"cmd":"close"} closes transport; stdin EOF also closes transport.

Explicit malformed movement/use float test: add "malformed":true and encode
"NaN", "Infinity", or "-Infinity" as JSON strings. Bare nonstandard JSON
NaN/Infinity is rejected. Finite fields are never clamped/normalized.

Every sent record means ONLY sendall completed, never gameplay success.
ready means play LOGIN + PLAYER_POSITION received and corresponding teleport
confirmation (with the resulting pose) and PLAYER_LOADED sent. There is no server teleport-confirm
ACK packet and no movement ACK. native_position is the latest server teleport,
NOT a claim that subsequent movement was accepted. Relative teleports resolve
against client_reference (last finite commanded pose), with raw values retained.
Probe callbacks/server receipts, not this client, prove gameplay and scheduling.

Exact primary evidence and complete registration map: native-wire-*.javap.txt
and native-wire-map.json alongside this file (not required at runtime).
"""
import argparse
import base64
import hashlib
import json
import math
import os
import re
import select
import socket
import struct
import sys
import time
import uuid
import zlib

PROTOCOL = 777  # 26.3 server jar version.json protocol_version, id 26.3
# Indices from registration order, not external version tables. withBundlePacket
# contributes the single delimiter entry at play clientbound index 0.
SB = {
    'handshake': {'CLIENT_INTENTION': 0},
    'login': {'HELLO': 0, 'CUSTOM_QUERY_ANSWER': 2, 'LOGIN_ACKNOWLEDGED': 3,
              'COOKIE_RESPONSE': 4},
    'configuration': {'CLIENT_INFORMATION': 0, 'COOKIE_RESPONSE': 1,
                      'FINISH_CONFIGURATION': 3, 'KEEP_ALIVE': 4, 'PONG': 5,
                      'SELECT_KNOWN_PACKS': 7, 'ACCEPT_CODE_OF_CONDUCT': 9},
    'play': {'ACCEPT_TELEPORTATION': 0, 'CHUNK_BATCH_RECEIVED': 11,
             'CLIENT_TICK_END': 13, 'CONFIGURATION_ACKNOWLEDGED': 16,
             'COOKIE_RESPONSE': 21, 'KEEP_ALIVE': 28, 'MOVE_PLAYER_POS': 30,
             'MOVE_PLAYER_POS_ROT': 31, 'MOVE_PLAYER_ROT': 32,
             'MOVE_PLAYER_STATUS_ONLY': 33, 'PLAYER_ACTION': 41, 'PLAYER_INPUT': 43,
             'PLAYER_LOADED': 44, 'PONG': 45, 'USE_ITEM_ON': 66, 'USE_ITEM': 67},
}
CB = {
    'login': {0: 'LOGIN_DISCONNECT', 1: 'HELLO', 2: 'LOGIN_FINISHED',
              3: 'LOGIN_COMPRESSION', 4: 'CUSTOM_QUERY', 5: 'COOKIE_REQUEST'},
    'configuration': {0: 'COOKIE_REQUEST', 1: 'CUSTOM_PAYLOAD', 2: 'DISCONNECT',
                      3: 'FINISH_CONFIGURATION', 4: 'KEEP_ALIVE', 5: 'PING',
                      7: 'REGISTRY_DATA', 9: 'RESOURCE_PACK_PUSH', 11: 'STORE_COOKIE',
                      12: 'TRANSFER', 15: 'SELECT_KNOWN_PACKS', 20: 'CODE_OF_CONDUCT'},
    'play': {0: 'BUNDLE_DELIMITER', 4: 'BLOCK_CHANGED_ACK', 11: 'CHUNK_BATCH_FINISHED',
             12: 'CHUNK_BATCH_START', 21: 'COOKIE_REQUEST', 24: 'CUSTOM_PAYLOAD',
             32: 'DISCONNECT', 45: 'KEEP_ALIVE', 46: 'LEVEL_CHUNK_WITH_LIGHT',
             50: 'LOGIN', 62: 'PING', 73: 'PLAYER_POSITION', 74: 'PLAYER_ROTATION',
             82: 'RESOURCE_PACK_PUSH', 84: 'RESPAWN', 120: 'START_CONFIGURATION',
             122: 'STORE_COOKIE', 132: 'TRANSFER'},
}
MAX_FRAME = (1 << 21) - 1
MAX_INFLATED = 8388608


def safe_json(value):
    if isinstance(value, float) and not math.isfinite(value):
        return 'NaN' if math.isnan(value) else ('Infinity' if value > 0 else '-Infinity')
    if isinstance(value, dict):
        return {k: safe_json(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [safe_json(v) for v in value]
    return value


QUIET_EVENTS = {'received', 'sent'}
# 26.3 play ids: 6 BLOCK_ENTITY_DATA, 7 BLOCK_EVENT, 8 BLOCK_UPDATE, 86 SECTION_BLOCKS_UPDATE
BLOCK_PACKET_IDS = (6, 7, 8, 86)
BLOCK_COUNTS = {}
BLOCK_LAST = [0.0]  # perf stand: per-packet events would flood stdout


def emit(event, **fields):
    if event in QUIET_EVENTS:
        return
    print(json.dumps(safe_json({'event': event, 'unix_ns': time.time_ns(),
                                'monotonic_ns': time.monotonic_ns(), **fields}),
                     separators=(',', ':'), allow_nan=False), flush=True)


def varint(value):
    if type(value) is not int or not -(1 << 31) <= value < (1 << 31):
        raise ValueError('VarInt requires signed int32')
    value &= 0xffffffff
    out = bytearray()
    while value >= 128:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


def utf(value):
    raw = value.encode('utf-8')
    return varint(len(raw)) + raw


class Reader:
    def __init__(self, data):
        self.data = data
        self.offset = 0

    def take(self, size):
        if size < 0 or self.offset + size > len(self.data):
            raise ValueError('Truncated native packet')
        result = self.data[self.offset:self.offset + size]
        self.offset += size
        return result

    def unpack(self, fmt):
        return struct.unpack('>' + fmt, self.take(struct.calcsize('>' + fmt)))

    def varint(self):
        value = 0
        for index in range(5):
            byte = self.take(1)[0]
            if index == 4 and byte & 0xf0:
                raise ValueError('Invalid int32 VarInt')
            value |= (byte & 127) << (7 * index)
            if not byte & 128:
                return value if value < (1 << 31) else value - (1 << 32)
        raise ValueError('VarInt wider than int32')

    def count(self, maximum):
        value = self.varint()
        if not 0 <= value <= maximum:
            raise ValueError('Native collection/string count out of bounds')
        return value

    def string(self, maximum=32767):
        raw = self.take(self.count(maximum * 3))
        value = raw.decode('utf-8')
        if len(value.encode('utf-16-be')) // 2 > maximum:
            raise ValueError('Native string character limit exceeded')
        return value

    def boolean(self):
        return self.take(1)[0] != 0

    def done(self):
        if self.offset != len(self.data):
            raise ValueError('Unconsumed bytes in implemented native codec')


def boolean(command, field, default=None):
    value = command.get(field, default)
    if type(value) is not bool:
        raise ValueError(field + ' must be a JSON boolean')
    return value


def number(command, field):
    value = command[field]
    if isinstance(value, str) and value in ('NaN', 'Infinity', '-Infinity'):
        if command.get('malformed') is not True:
            raise ValueError('Nonfinite encoding requires malformed:true')
        return float(value)
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError(field + ' must be finite or an explicitly guarded nonfinite string')
    return float(value)


def integer(command, field, low, high):
    value = command[field]
    if type(value) is not int or not low <= value <= high:
        raise ValueError(field + ' integer outside exact native range')
    return value


def reject_constant(value):
    raise ValueError('Use guarded string for malformed ' + value)


class Client:
    def __init__(self, args):
        self.args = args
        self.sock = None
        self.state = 'handshake'
        self.compression = None
        self.buffer = bytearray()
        self.stdin_buffer = bytearray()
        self.sent_serial = 0
        self.received_serial = 0
        self.native_position = None
        self.client_reference = dict(x=0.0, y=0.0, z=0.0, yaw=0.0, pitch=0.0)
        self.login_received = False
        self.loaded = False
        self.cookies = {}
        self.closed = False
        self.started = time.monotonic()
        self.last_received = self.started
        self.join_started = self.started

    def send(self, name, payload=b'', command_id=None, **details):
        packet_id = SB[self.state][name]
        packet = varint(packet_id) + payload
        frame = packet
        if self.compression is not None:
            frame = (varint(len(packet)) + zlib.compress(packet)
                     if len(packet) >= self.compression else b'\x00' + packet)
        if len(frame) > MAX_FRAME:
            raise ValueError('Frame exceeds native 21-bit limit')
        self.sock.sendall(varint(len(frame)) + frame)
        self.sent_serial += 1
        emit('sent', serial=self.sent_serial, state=self.state, packet=name,
             packet_id=packet_id, command_id=command_id, payload_hex=payload.hex(),
             transport_only=True, **details)
        return self.sent_serial

    def transition(self, state):
        old = self.state
        self.state = state
        emit('state', previous=old, state=state, received_serial=self.received_serial)

    def configure(self):
        # ClientInformation.write: language, signed byte distance, enum chat,
        # bool colors, skin byte, enum main arm, bool filtering/listing, particles.
        self.send('CLIENT_INFORMATION', utf('en_us') + b'\x02\x00\x01\x7f\x01\x00\x01\x00')

    def ready(self, command_id=None):
        event = 'ready' if self.loaded and self.native_position is not None else 'not_ready'
        emit(event, command_id=command_id, state=self.state,
             native_position=self.native_position, client_reference=self.client_reference,
             sent_serial=self.sent_serial, received_serial=self.received_serial,
             position_basis='latest_received_server_teleport_not_movement_ack',
             native_gameplay_asserted=False)

    def packet(self, packet):
        reader = Reader(packet)
        packet_id = reader.varint()
        payload = reader.take(len(packet) - reader.offset)
        r = Reader(payload)
        self.received_serial += 1
        name = CB[self.state].get(packet_id, 'UNINTERPRETED')
        emit('received', serial=self.received_serial, state=self.state, packet=name,
             packet_id=packet_id, payload_bytes=len(payload),
             payload_sha256=hashlib.sha256(payload).hexdigest(),
             after_sent_serial=self.sent_serial)
        if self.state == 'play' and (b'has completed the' in payload or b'has made the advancement' in payload
                                     or b'has reached the goal' in payload or b'chat.type.advancement' in payload):
            emit('chat_probe', packet_id=packet_id, amethyst=b'Amethyst Miner' in payload,
                 text=payload[:240].decode('utf-8', 'replace'))
        if self.state == 'play' and packet_id in BLOCK_PACKET_IDS:
            BLOCK_COUNTS[packet_id] = BLOCK_COUNTS.get(packet_id, 0) + 1
            now = time.monotonic()
            if now - BLOCK_LAST[0] > 10:
                BLOCK_LAST[0] = now
                emit('block_packets', counts={str(k): v for k, v in BLOCK_COUNTS.items()})
        if name in ('LOGIN_DISCONNECT', 'DISCONNECT'):
            reason = r.string(262144) if name == 'LOGIN_DISCONNECT' else None
            emit('disconnect', state=self.state, received_serial=self.received_serial,
                 reason_json=reason, reason_wire_base64=base64.b64encode(payload).decode(),
                 reason_encoding='json_utf8' if reason is not None else 'native_component_nbt')
            raise ConnectionAbortedError('Native server sent ' + name)
        if name == 'TRANSFER':
            emit('transfer_refused', payload_base64=base64.b64encode(payload).decode())
            raise PermissionError('Server transfer forbidden by localhost sandbox contract')
        if name == 'RESOURCE_PACK_PUSH':
            emit('unsupported_required_negotiation', packet=name,
                 payload_base64=base64.b64encode(payload).decode())
            raise RuntimeError('Resource pack loading not implemented; disposable recipe must not require packs')
        if name == 'COOKIE_REQUEST':
            key = r.string()
            r.done()
            value = self.cookies.get(key)
            self.send('COOKIE_RESPONSE', utf(key) + (b'\x00' if value is None else
                      b'\x01' + varint(len(value)) + value), cookie_key=key)
        elif name == 'STORE_COOKIE':
            key = r.string()
            self.cookies[key] = r.take(r.count(5120))
            r.done()
            emit('cookie_stored', key=key, persistent=False)
        elif name == 'KEEP_ALIVE':
            token, = r.unpack('q')
            r.done()
            self.send('KEEP_ALIVE', struct.pack('>q', token), token=token)
        elif name == 'PING':
            token, = r.unpack('i')
            r.done()
            self.send('PONG', struct.pack('>i', token), token=token)
        elif self.state == 'login':
            if name == 'HELLO':
                raise PermissionError('Encryption requested: this client is offline-only')
            if name == 'LOGIN_COMPRESSION':
                threshold = r.varint()
                r.done()
                self.compression = threshold if threshold >= 0 else None
                emit('compression', threshold=threshold)
            elif name == 'CUSTOM_QUERY':
                transaction = r.varint()
                channel = r.string()
                emit('login_query', transaction=transaction, channel=channel,
                     payload_base64=base64.b64encode(r.take(len(payload)-r.offset)).decode())
                self.send('CUSTOM_QUERY_ANSWER', varint(transaction) + b'\x00',
                          transaction=transaction, understood=False)
            elif name == 'LOGIN_FINISHED':
                profile_id = str(uuid.UUID(bytes=r.take(16)))
                profile_name = r.string(16)
                properties = []
                for _ in range(r.count(16)):
                    item = {'name': r.string(64), 'value': r.string()}
                    item['signature'] = r.string(1024) if r.boolean() else None
                    properties.append(item)
                session_id = str(uuid.UUID(bytes=r.take(16)))
                r.done()
                emit('login_finished', profile_id=profile_id, name=profile_name,
                     session_id=session_id, property_count=len(properties))
                self.send('LOGIN_ACKNOWLEDGED')
                self.transition('configuration')
                self.configure()
            else:
                raise ValueError('Unimplemented login packet ' + str(packet_id))
        elif self.state == 'configuration':
            if name == 'SELECT_KNOWN_PACKS':
                packs = [dict(namespace=r.string(), id=r.string(), version=r.string())
                         for _ in range(r.count(1024))]
                r.done()
                # We have no local registries: truthfully select no cached packs.
                emit('known_packs', offered=packs, selected=[])
                self.send('SELECT_KNOWN_PACKS', varint(0))
            elif name == 'CODE_OF_CONDUCT':
                text = r.string()
                r.done()
                emit('code_of_conduct', text=text, accepted=True)
                self.send('ACCEPT_CODE_OF_CONDUCT')
            elif name == 'FINISH_CONFIGURATION':
                r.done()
                self.send('FINISH_CONFIGURATION')
                self.transition('play')
        elif self.state == 'play':
            if name == 'LOGIN':
                entity_id, = r.unpack('i')
                self.login_received = True
                emit('play_login', entity_id=entity_id,
                     unparsed_spawn_info_base64=base64.b64encode(r.take(len(payload)-r.offset)).decode())
            elif name == 'PLAYER_POSITION':
                teleport_id = r.varint()
                x, y, z, dx, dy, dz, yaw, pitch, flags = r.unpack('ddddddffI')
                r.done()
                if flags & ~511:
                    raise ValueError('Unknown native relative-position flag')
                if not self.login_received:
                    raise ValueError('Teleport received before native play login')
                if self.native_position is None and flags & 7:
                    raise ValueError('Initial relative teleport has no proven client pose baseline')
                raw = dict(x=x, y=y, z=z, yaw=yaw, pitch=pitch)
                if not all(math.isfinite(v) for v in raw.values()):
                    raise ValueError('Native server emitted nonfinite teleport')
                absolute = {key: value + (self.client_reference[key] if flags & (1 << bit) else 0.0)
                            for bit, (key, value) in enumerate(raw.items())}
                # Native calculateAbsolute performs float addition and clamps pitch.
                absolute['yaw'] = struct.unpack('>f', struct.pack('>f', absolute['yaw']))[0]
                absolute['pitch'] = max(-90.0, min(90.0, struct.unpack(
                    '>f', struct.pack('>f', absolute['pitch']))[0]))
                self.client_reference = absolute.copy()
                self.native_position = dict(**absolute, teleport_id=teleport_id,
                                            received_serial=self.received_serial)
                emit('teleport', teleport_id=teleport_id, raw=raw, relative_flags=flags,
                     delta_movement=[dx, dy, dz], native_position=self.native_position,
                     velocity_applied=False)
                # 26.3 ClientPacketListener.handleMovePlayer sends only
                # ServerboundAcceptTeleportationPacket(id, x, y, z, yRot, xRot);
                # the 26.2 MovePlayer echo is gone (a second position packet
                # before ClientTickEnd disconnects in 26.3).
                self.send('ACCEPT_TELEPORTATION', varint(teleport_id) +
                          struct.pack('>dddff', *absolute.values()), teleport_id=teleport_id)
                if not self.loaded:
                    self.send('PLAYER_LOADED')
                    self.loaded = True
                self.ready()
            elif name == 'PLAYER_ROTATION':
                yaw, relative_y, pitch, relative_x = r.unpack('f?f?')
                r.done()
                self.client_reference['yaw'] = yaw + (self.client_reference['yaw'] if relative_y else 0)
                self.client_reference['pitch'] = pitch + (self.client_reference['pitch'] if relative_x else 0)
                emit('rotation', yaw=yaw, pitch=pitch, relative_y=relative_y,
                     relative_x=relative_x, client_reference=self.client_reference)
            elif name == 'CHUNK_BATCH_FINISHED':
                count = r.varint()
                r.done()
                self.send('CHUNK_BATCH_RECEIVED', struct.pack('>f', 8.0),
                          received_chunks=count, desired_chunks_per_tick=8.0)
            elif name == 'BLOCK_CHANGED_ACK':
                sequence = r.varint()
                r.done()
                emit('block_changed_ack', sequence=sequence,
                     received_serial=self.received_serial, native_outcome_asserted=False)
            elif name == 'START_CONFIGURATION':
                r.done()
                self.send('CONFIGURATION_ACKNOWLEDGED')
                self.loaded = False
                self.native_position = None
                self.join_started = time.monotonic()
                self.transition('configuration')
                self.configure()
            elif name == 'RESPAWN':
                self.loaded = False
                self.native_position = None
                self.join_started = time.monotonic()
                # Last field: dataToKeep. ClientPacketListener.handleRespawn keeps
                # the old rotation with KEEP_ENTITY_DATA (2), else yRot=-180 on a
                # fresh player (xRot 0). Portal transitions rotate relatively to it.
                if not payload[-1] & 2:
                    self.client_reference['yaw'] = -180.0
                    self.client_reference['pitch'] = 0.0
                emit('respawn', payload_base64=base64.b64encode(payload).decode(),
                     data_to_keep=payload[-1])

    def receive(self):
        chunk = self.sock.recv(65536)
        if not chunk:
            emit('eof', state=self.state, sent_serial=self.sent_serial,
                 received_serial=self.received_serial, partial_bytes=len(self.buffer))
            raise EOFError('Native server closed TCP transport')
        self.last_received = time.monotonic()
        self.buffer.extend(chunk)
        while self.buffer:
            length = 0
            prefix = None
            for index in range(min(3, len(self.buffer))):
                byte = self.buffer[index]
                length |= (byte & 127) << (7 * index)
                if not byte & 128:
                    prefix = index + 1
                    break
            if prefix is None:
                if len(self.buffer) >= 3:
                    raise ValueError('Native frame length wider than 21 bits')
                return
            if length == 0:
                raise ValueError('Native frame length zero')
            if len(self.buffer) < prefix + length:
                return
            packet = bytes(self.buffer[prefix:prefix + length])
            del self.buffer[:prefix + length]
            if self.compression is not None:
                r = Reader(packet)
                inflated_length = r.count(MAX_INFLATED)
                encoded = r.take(len(packet) - r.offset)
                if inflated_length:
                    if inflated_length < self.compression:
                        raise ValueError('Compressed packet below negotiated threshold')
                    inflater = zlib.decompressobj()
                    packet = inflater.decompress(encoded, inflated_length + 1)
                    if (len(packet) != inflated_length or not inflater.eof or
                            inflater.unused_data or inflater.unconsumed_tail):
                        raise ValueError('Native compression length/stream mismatch')
                else:
                    packet = encoded
            self.packet(packet)

    def command(self, command):
        if not isinstance(command, dict):
            raise ValueError('Command must be a JSON object')
        cmd = command['cmd']
        command_id = command.get('id')
        if 'malformed' in command:
            boolean(command, 'malformed')
        if cmd == 'close':
            self.closed = True
            emit('close_requested', command_id=command_id, native_removal_asserted=False)
            return
        if cmd == 'ready':
            self.ready(command_id)
            return
        if self.state != 'play' or not self.loaded:
            raise ValueError('Gameplay command before received native play login/teleport')
        if cmd == 'move':
            kinds = {'Pos': ('MOVE_PLAYER_POS', 'ddd', ('x', 'y', 'z')),
                     'Rot': ('MOVE_PLAYER_ROT', 'ff', ('yaw', 'pitch')),
                     'PosRot': ('MOVE_PLAYER_POS_ROT', 'dddff', ('x', 'y', 'z', 'yaw', 'pitch')),
                     'StatusOnly': ('MOVE_PLAYER_STATUS_ONLY', '', ())}
            name, fmt, fields = kinds[command['kind']]
            values = [number(command, field) for field in fields]
            flags = int(boolean(command, 'onGround')) | (int(boolean(command, 'horizontalCollision')) << 1)
            payload = struct.pack('>' + fmt + 'B', *values, flags)
            # Track the wire-rounded finite client reference, not accepted state.
            wire_values = struct.unpack('>' + fmt + 'B', payload)[:-1]
            self.send(name, payload, command_id, fields=dict(zip(fields, wire_values)),
                      onGround=bool(flags & 1), horizontalCollision=bool(flags & 2),
                      malformed=command.get('malformed', False))
            for field, value in zip(fields, wire_values):
                if math.isfinite(value):
                    self.client_reference[field] = value
        elif cmd == 'input':
            fields = ('forward', 'backward', 'left', 'right', 'jump', 'shift', 'sprint')
            bits = sum(int(boolean(command, field, False)) << bit for bit, field in enumerate(fields))
            self.send('PLAYER_INPUT', bytes([bits]), command_id, input_bits=bits)
        elif cmd in ('use-item', 'use-on-block'):
            hand = {'MAIN_HAND': 0, 'OFF_HAND': 1}[command['hand']]
            sequence = integer(command, 'sequence', 0, (1 << 31) - 1)
            payload = varint(hand)
            if cmd == 'use-item':
                payload += varint(sequence) + struct.pack('>ff', number(command, 'yaw'), number(command, 'pitch'))
                name = 'USE_ITEM'
            else:
                x = integer(command, 'blockX', -(1 << 25), (1 << 25) - 1)
                y = integer(command, 'blockY', -(1 << 11), (1 << 11) - 1)
                z = integer(command, 'blockZ', -(1 << 25), (1 << 25) - 1)
                packed = ((x & 0x3ffffff) << 38) | ((z & 0x3ffffff) << 12) | (y & 0xfff)
                face = {'DOWN': 0, 'UP': 1, 'NORTH': 2, 'SOUTH': 3, 'WEST': 4, 'EAST': 5}[command['face']]
                payload += struct.pack('>Q', packed) + varint(face)
                payload += struct.pack('>fff??', number(command, 'hitX'), number(command, 'hitY'),
                                       number(command, 'hitZ'), boolean(command, 'inside'),
                                       boolean(command, 'worldBorderHit')) + varint(sequence)
                name = 'USE_ITEM_ON'
            self.send(name, payload, command_id, sequence=sequence,
                      malformed=command.get('malformed', False))
        elif cmd == 'release-use-item':
            sequence = integer(command, 'sequence', 0, (1 << 31) - 1)
            # Native enum ordinal 6 (26.3 inserted CHANGE_DESTROY_DIRECTION at 1),
            # BlockPos.ZERO, Direction.DOWN, then VarInt sequence.
            # handlePlayerAction RELEASE_USE_ITEM does not inspect the block position.
            payload = varint(6) + struct.pack('>QB', 0, 0) + varint(sequence)
            self.send('PLAYER_ACTION', payload, command_id, action='RELEASE_USE_ITEM',
                      sequence=sequence)
        elif cmd == 'tick-end':
            self.send('CLIENT_TICK_END', command_id=command_id)
        else:
            raise ValueError('Unknown command: ' + str(cmd))

    def stdin(self):
        data = os.read(sys.stdin.fileno(), 65536)
        if not data:
            if self.stdin_buffer:
                raise ValueError('stdin EOF before command newline')
            self.closed = True
            emit('stdin_eof', native_removal_asserted=False)
            return
        self.stdin_buffer.extend(data)
        if len(self.stdin_buffer) > 1048576:
            raise ValueError('stdin command buffer exceeds 1 MiB')
        while b'\n' in self.stdin_buffer and not self.closed:
            line, _, remaining = self.stdin_buffer.partition(b'\n')
            self.stdin_buffer = bytearray(remaining)
            command = None
            try:
                command = json.loads(line.decode('utf-8'), parse_constant=reject_constant)
                self.command(command)
            except (ValueError, KeyError, TypeError, OverflowError, struct.error) as exc:
                emit('command_error', command_id=command.get('id') if isinstance(command, dict) else None,
                     error=type(exc).__name__ + ': ' + str(exc), sent_serial=self.sent_serial)

    def run(self):
        if (os.environ.get('FABROLIA_NATIVE_BURST_ISOLATED') != 'loopback-only' or
                self.args.host not in ('localhost', '127.0.0.1') or self.args.port != 25935):
            raise PermissionError('Refusing outside isolated literal localhost:25935 contract')
        if not re.fullmatch(r'[A-Za-z0-9_]{1,16}', self.args.name):
            raise ValueError('Offline player name must be 1..16 ASCII word characters')
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            self.sock.settimeout(10)
            self.sock.connect(('127.0.0.1', 25935))
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            emit('connected', endpoint=['127.0.0.1', 25935], protocol=PROTOCOL,
                 name=self.args.name, transport_only=True)
            self.send('CLIENT_INTENTION', varint(PROTOCOL) + utf('localhost') + struct.pack('>H', 25935) + varint(2))
            self.transition('login')
            offline = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + self.args.name).encode()).digest(), version=3)
            self.send('HELLO', utf(self.args.name) + offline.bytes, profile_id=str(offline))
            while not self.closed:
                now = time.monotonic()
                if now - self.started > self.args.max_seconds:
                    raise TimeoutError('Client sandbox lifetime exceeded')
                if self.native_position is None and now - self.join_started > self.args.join_timeout:
                    raise TimeoutError('No native spawn/teleport before join timeout')
                if now - self.last_received > self.args.idle_timeout:
                    raise TimeoutError('No native transport traffic before idle timeout')
                readable, _, _ = select.select([self.sock, sys.stdin.fileno()], [], [], 0.25)
                # Process received teleports before an already-buffered user move.
                if self.sock in readable:
                    self.receive()
                if sys.stdin.fileno() in readable:
                    self.stdin()
            return 0
        finally:
            self.sock.close()
            emit('local_closed', requested=self.closed, state=self.state,
                 sent_serial=self.sent_serial, received_serial=self.received_serial,
                 native_removal_asserted=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--host', default='localhost')
    parser.add_argument('--port', type=int, default=25935)
    parser.add_argument('--name', default='NativeWire')
    parser.add_argument('--join-timeout', type=float, default=240)
    parser.add_argument('--idle-timeout', type=float, default=90)
    parser.add_argument('--max-seconds', type=float, default=1200)
    args = parser.parse_args()
    try:
        if any(not math.isfinite(value) or value <= 0 for value in
               (args.join_timeout, args.idle_timeout, args.max_seconds)):
            raise ValueError('Timeouts must be finite positive seconds')
        return Client(args).run()
    except (Exception, KeyboardInterrupt) as exc:
        emit('error', error=type(exc).__name__ + ': ' + str(exc), native_gameplay_asserted=False)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
