"""Build the world template of one bench2 scenario on plain vanilla 26.3.

Usage: python prep2.py chunks|hoppers|redstone|worldgen|scaling [--size N]

chunks    natural world, default game rules; 32 players visit their spots (view 16) so all
          chunks exist; observer clock above every spot.
hoppers   32 bases, each SIZE stacked rings of 24 x 24 hoppers (92 per ring) carrying
          cobblestone around in a loop; no natural spawning, fixed time.
redstone  32 bases, each SIZE layers of 8 x 32 piston clocks (observer pair driving a piston
          on either side); no natural spawning, fixed time.
worldgen  only the spawn area exists; 32 players are saved at far spots as spectators (which
          do not generate chunks), so the runs generate their terrain.
scaling   the mob pens after the full ramp (11,520 mobs), from runs/world-template.
Writes runs/<scenario>-template with bench-meta.json.
"""
import argparse
import json
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import chunks_scenario as cs  # noqa: E402
import mc  # noqa: E402
import scenario as mob  # noqa: E402

ROOT = mc.HOME
PORT = 26600
ap = argparse.ArgumentParser()
ap.add_argument('scenario', choices=['chunks', 'hoppers', 'redstone', 'worldgen', 'scaling'])
ap.add_argument('--size', type=int, default=0)
ap.add_argument('--quick', action='store_true', help='smoke test: 2 spots, separate template')
a = ap.parse_args()

GRID = [((i - 4) * 1024 + 512, (j - 2) * 1024 + 512) for j in range(4) for i in range(8)]
FIXED_RULES = ('spawn_mobs false', 'spawn_monsters false', 'spawn_patrols false', 'spawn_phantoms false',
               'spawn_wandering_traders false', 'advance_weather false', 'advance_time false',
               'max_entity_cramming 0', 'raids false', 'mob_griefing false')

TAG = a.scenario + ('-quick' if a.quick else '')
run = mc.copy_template(ROOT / 'tpl' / 'fabric', ROOT / 'runs' / ('prep-' + TAG))
view = {'chunks': cs.VIEW_DISTANCE, 'worldgen': 12}.get(a.scenario, 10)
srv = mc.Server(run, 'vanilla', PORT, xmx='12G')
seed = {'chunks': cs.SEED, 'worldgen': 'fabrolia-worldgen', 'hoppers': 'fabrolia-bases',
        'redstone': 'fabrolia-bases'}.get(a.scenario, mob.SEED)
srv.write_properties(level_seed=seed, gamemode='spectator' if a.scenario == 'worldgen' else 'creative',
                     force_gamemode='true', view_distance=view, simulation_distance=10, difficulty='easy')
if a.scenario == 'scaling':
    shutil.copytree(ROOT / 'runs' / 'world-template', run / 'world')
meta = {'scenario': a.scenario, 'seed': seed, 'view': view}


def rate(bot):
    t = bot.clock_times
    return round(2 * (len(t) - 1) / (t[-1] - t[0]), 2) if len(t) > 1 else 0.0


def clock(x, z, y=300):
    srv.rcon('setblock %d %d %d minecraft:observer[facing=east]' % (x, y, z))
    srv.rcon('setblock %d %d %d minecraft:observer[facing=west]' % (x + 1, y, z))


def build_hoppers(x, z, rings):
    w = 24
    x0, z0 = x - w // 2, z - w // 2
    x1, z1 = x0 + w - 1, z0 + w - 1
    nbt = '{Items:[{Slot:0b,id:"minecraft:cobblestone",count:64}]}'
    for k in range(rings):
        y = 200 + 2 * k
        for cmd in ('fill %d %d %d %d %d %d minecraft:hopper[facing=east]%s' % (x0, y, z0, x1 - 1, y, z0, nbt),
                    'fill %d %d %d %d %d %d minecraft:hopper[facing=south]%s' % (x1, y, z0, x1, y, z1 - 1, nbt),
                    'fill %d %d %d %d %d %d minecraft:hopper[facing=west]%s' % (x0 + 1, y, z1, x1, y, z1, nbt),
                    'fill %d %d %d %d %d %d minecraft:hopper[facing=north]%s' % (x0, y, z0 + 1, x0, y, z1, nbt)):
            out = srv.rcon(cmd)
            if 'Successfully' not in out:
                raise RuntimeError(cmd + ' -> ' + out)


def build_redstone(x, z, layers):
    groups, length = 8, 32
    z0 = z - length // 2
    for k in range(layers):
        y = 200 + 3 * k
        for g in range(groups):
            bx = x - 20 + 5 * g
            for dx, block in ((0, 'piston[facing=up]'), (3, 'piston[facing=up]'),
                              (1, 'observer[facing=east]'), (2, 'observer[facing=west]')):
                out = srv.rcon('fill %d %d %d %d %d %d minecraft:%s' % (bx + dx, y, z0, bx + dx, y, z0 + length - 1, block))
                if 'Successfully' not in out:
                    raise RuntimeError(block + ' -> ' + out)


srv.start()
try:
    srv.wait_ready()
    if a.scenario in ('hoppers', 'redstone'):
        for rule in FIXED_RULES:
            srv.rcon('gamerule ' + rule)
        srv.rcon('time set 6000')
    if a.scenario == 'worldgen':
        srv.rcon('gamerule spectators_generate_chunks false')

    if a.scenario == 'scaling':
        bots = [mc.Bot(n, PORT) for n in mob.BOTS]
        for b in bots:
            b.connect()
        mc.wait_until(lambda: all(b.loaded or b.error for b in bots), 300, what='bots')
        time.sleep(10)
        base = int(srv.rcon('execute if entity @e[type=!minecraft:player,type=!minecraft:item]').rsplit(' ', 1)[-1])
        for b in bots:
            for slot, (_, item) in enumerate(mob.EGGS):
                b.set_creative_slot(36 + slot, item)
        order = []
        for kind, n in mob.MIX.items():
            order += [kind] * n
        slots = {kind: i for i, (kind, _) in enumerate(mob.EGGS)}
        offsets = mob.spawn_offsets()
        total = 11520 // 8
        for i in range(total):
            for b, (cx, cz) in zip(bots, mob.CENTERS):
                kind = order[i % len(order)]
                b.select_hotbar(slots[kind])
                dx, dz = offsets[i % len(offsets)]
                b.use_item_on(cx + dx, mob.FLOOR_Y, cz + dz)
            time.sleep(0.02)
        target = base + 11520
        for _ in range(120):
            time.sleep(10)
            out = srv.rcon('execute if entity @e[type=!minecraft:player,type=!minecraft:item]')
            n = int(out.rsplit(' ', 1)[-1]) if 'Count' in out else 0
            print('entities', n, flush=True)
            if n >= target:
                break
        meta['spots'] = mob.CENTERS
        meta['bots'] = mob.BOTS
        meta['entities'] = int(srv.rcon('execute if entity @e[type=!minecraft:player]').rsplit(' ', 1)[-1])
    else:
        names = {'chunks': cs.BOTS}.get(a.scenario, ['Base%02d' % (i + 1) for i in range(32)])
        if a.scenario == 'worldgen':
            names = ['Gen%02d' % (i + 1) for i in range(32)]
        spots = cs.SPOTS if a.scenario == 'chunks' else GRID
        if a.scenario == 'worldgen':
            spots = [(x * 3, z * 3) for x, z in GRID]
        if a.quick:
            names, spots = names[:2], spots[:2]
        meta['spots'] = spots
        meta['bots'] = names
        bots = [mc.Bot(n, PORT, view_distance=view) for n in names]
        if a.scenario == 'worldgen':
            anchor = mc.Bot('Anchor', PORT, view_distance=view)
            bots_all = bots + [anchor]
        else:
            bots_all = bots
        for b in bots_all:
            b.connect()
        mc.wait_until(lambda: all(b.loaded or b.error for b in bots_all), 300, what='bots')
        hover = 240 if a.scenario in ('hoppers', 'redstone') else cs.HOVER_Y
        for b, (x, z) in zip(bots, spots):
            srv.rcon('tp %s %.1f %d %.1f' % (b.name, x + 0.5, hover, z + 0.5))
        if a.scenario == 'worldgen':
            srv.rcon('gamemode creative Anchor')
            srv.rcon('tp Anchor 0.5 %d 0.5' % cs.HOVER_Y)
            time.sleep(20)
            clock(0, 0)
            anchor.watch_clock([(0, 300, 0), (1, 300, 0)])
            meta['anchor'] = (0, 0)
        else:
            t0 = time.monotonic()
            need = 1000 if a.scenario == 'chunks' else 420
            while time.monotonic() - t0 < 900:
                time.sleep(15)
                got = sorted(b.chunks for b in bots)
                print('%4.0fs chunks min %d total %d' % (time.monotonic() - t0, got[0], sum(got)), flush=True)
                if got[0] >= need:
                    break
            size = a.size or {'hoppers': 20, 'redstone': 3}.get(a.scenario, 0)
            meta['size'] = size
            for b, (x, z) in zip(bots, spots):
                if a.scenario == 'hoppers':
                    build_hoppers(x, z, size)
                elif a.scenario == 'redstone':
                    build_redstone(x, z, size)
                clock(x, z)
                b.watch_clock([(x, 300, z), (x + 1, 300, z)])
        time.sleep(20)
        watched = [anchor] if a.scenario == 'worldgen' else bots
        rates = [rate(b) for b in watched]
        meta['prep_tps_all_present'] = round(sum(rates) / len(rates), 2)
        print('clock rates with every player present', rates, flush=True)
    srv.rcon('save-all flush')
    for b in (bots_all if a.scenario not in ('scaling',) else bots):
        b.close()
    time.sleep(3)
    print(srv.rcon('execute if entity @e[type=!minecraft:player]').strip())
finally:
    print('stop', srv.stop(timeout=600))
target = ROOT / 'runs' / (TAG + '-template')
if target.exists():
    shutil.rmtree(target)
shutil.copytree(run / 'world', target)
(target / 'session.lock').unlink(missing_ok=True)
(target / 'bench-meta.json').write_text(json.dumps(meta, indent=1))
print('template', target, json.dumps({k: v for k, v in meta.items() if k not in ('spots', 'bots')}))
