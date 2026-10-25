"""Generate the benchmark world once on plain vanilla 26.3 and save it as runs/world-template.

Eight glass pens with a grass floor, one observer clock above each, all chunks around the
pens generated, gamerules for a deterministic load (no natural spawning, fixed day and
weather, no entity cramming), and every bot's saved position inside its pen.
"""
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mc  # noqa: E402
import scenario as sc  # noqa: E402

ROOT = mc.HOME
run = mc.copy_template(ROOT / 'tpl' / 'fabric', ROOT / 'runs' / 'prep')
srv = mc.Server(run, 'vanilla', sc.PORT, xmx='8G')
srv.write_properties(level_seed=sc.SEED, gamemode='creative', force_gamemode='true', view_distance=10,
                     simulation_distance=10, difficulty='easy')
srv.start()
try:
    srv.wait_ready()
    for rule in ('spawn_mobs false', 'spawn_monsters false', 'spawn_patrols false', 'spawn_phantoms false',
                 'spawn_wandering_traders false', 'advance_weather false', 'advance_time false',
                 'max_entity_cramming 0', 'raids false', 'mob_griefing false'):
        print(rule, srv.rcon('gamerule ' + rule).strip())
    srv.rcon('time set 6000')
    srv.rcon('weather clear')
    bots = [mc.Bot(n, sc.PORT) for n in sc.BOTS]
    for b in bots:
        b.connect()
    mc.wait_until(lambda: all(b.loaded or b.error for b in bots), 180, what='bots')
    for n, (cx, cz) in zip(sc.BOTS, sc.CENTERS):
        srv.rcon('tp %s %d 200 %d' % (n, cx, cz))
    time.sleep(40)  # chunk generation around every pen
    for (cx, cz), b in zip(sc.CENTERS, bots):
        y, h = sc.FLOOR_Y, sc.HALF
        print(srv.rcon('fill %d %d %d %d %d %d minecraft:glass hollow' % (cx - h - 1, y, cz - h - 1, cx + h, y + 4, cz + h - 0)).strip())
        print(srv.rcon('fill %d %d %d %d %d %d minecraft:grass_block' % (cx - h, y, cz - h, cx + h - 1, y, cz + h - 1)).strip())
        (a, cy, c), (a2, _, c2) = sc.clock_blocks(cx, cz)
        srv.rcon('setblock %d %d %d minecraft:observer[facing=east]' % (a, cy, c))
        srv.rcon('setblock %d %d %d minecraft:observer[facing=west]' % (a2, cy, c2))
        b.watch_clock(sc.clock_blocks(cx, cz))
    for n, (cx, cz) in zip(sc.BOTS, sc.CENTERS):
        srv.rcon('tp %s %.1f %d %.1f' % (n, cx + 0.5, sc.FLOOR_Y + 1, cz + 0.5))
    time.sleep(10)
    for b in bots:
        t = b.clock_times
        print(b.name, 'chunks', b.chunks, 'clock rate', round(2 * (len(t) - 1) / (t[-1] - t[0]), 2) if len(t) > 1 else 0,
              'pos', b.pos, 'err', b.error)
    print(srv.rcon('execute if entity @e[type=!minecraft:player]').strip())
    srv.rcon('save-all flush')
    for b in bots:
        b.close()
    time.sleep(3)
finally:
    print('stop', srv.stop())
target = ROOT / 'runs' / 'world-template'
if target.exists():
    shutil.rmtree(target)
shutil.copytree(run / 'world', target)
(target / 'session.lock').unlink(missing_ok=True)
print('template', target, sum(1 for _ in target.rglob('*')), 'files')
