#!/usr/bin/env python3
"""frames.py PROFILE... — per thread group, inclusive ms per wall second (all windows) of target frames."""
import sys
from collections import defaultdict
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import core  # noqa: E402

WORKER = ['net.minecraft.server.level.ServerChunkCache.getChunk',
          'net.minecraft.world.level.Level.getChunk',
          'shortcutGetChunk',
          'fabrolia$workerChunkCacheLookup',
          'net.minecraft.world.entity.item.ItemEntity.tick',
          'net.minecraft.world.level.Level.getBlockState',
          'com.axalotl.fabrolia.common.regions.IslandTickUnit.runCoordinated',
          'com.axalotl.fabrolia.common.regions.IslandEntityTracking.flush',
          'com.axalotl.fabrolia.common.regions.IslandEntityTracking.publishCurrent',
          'net.minecraft.server.level.ServerLevel.tickNonPassenger',
          'net.minecraft.server.level.ServerChunkCache.fabrolia$tickIslandChunks',
          'eventFdWrite',
          'net.minecraft.network.Connection.send',
          'com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap.get',
          'com.axalotl.fabrolia.common.regions.IslandTickUnit.awaitEntityBarrier',
          'com.axalotl.fabrolia.common.regions.RegionizedServer.tickOwned',
          'com.axalotl.fabrolia.common.regions.RegionizedServer.tickIslandEntitiesInVanillaOrder',
          'net.minecraft.world.level.entity.EntityTickList.fabrolia$snapshotEntities',
          'com.axalotl.fabrolia.common.regions.IslandBlockEvents.runIslandEventsEntered']
MAIN = ['net.minecraft.server.MinecraftServer.tickServer',
        'net.minecraft.server.level.ServerChunkCache.trackerOnMain',
        'com.axalotl.fabrolia.common.regions.IslandCommands.quiesceAllExcept',
        'com.axalotl.fabrolia.common.regions.IslandCommands.mainClaim',
        'eventFdWrite',
        'com.axalotl.fabrolia.common.regions.RegionizedServer.tickGlobalEntitiesInVanillaOrder',
        'net.minecraft.world.level.entity.EntityTickList.fabrolia$snapshotEntities']


def match(label, t):
    name = label.split('(')[0]
    return name == t if t.count('.') > 2 else t in label


if len(sys.argv) < 2 or sys.argv[1] in ('-h', '--help'):
    sys.exit(__doc__)

for path in sys.argv[1:]:
    a = core.Analysis(core.load(path), path)
    # Spark's first/last windows may cover only part of a minute, and long
    # profiles may retain only their tail. Normalize the samples by the
    # retained windows intersected with the actual capture, not 60 s/bucket.
    secs = sum(max(0, min(a.md.end_time, a.window_start(k) + a.window_duration(k))
                      - max(a.md.start_time, a.window_start(k)))
               for k in range(len(a.window_ids))) / 1000.0
    if secs <= 0:
        raise ValueError(f'{path}: no retained capture duration')
    for group, targets, pred in (('Region-Worker', WORKER, lambda n: 'Region-Worker' in n),
                                 ('Server thread', MAIN, lambda n: n == 'Server thread')):
        incl = defaultdict(float)
        running = 0.0
        for ta in a.threads:
            if not pred(ta.name):
                continue
            running += sum(a.state_windows(ta)[core.RUNNING])
            for e in a.method_inclusive(ta, with_windows=True):
                for t in targets:
                    if match(e['label'], t):
                        incl[t] += sum(e['w'])
        ticks = a.norm_ticks
        running_per_tick = f'{running / ticks:.2f} ms/tick' if ticks else 'ms/tick unavailable'
        print(f'== {Path(path).parts[-5] if len(Path(path).parts) > 5 else path} [{group}] windows={len(a.window_ids)} '
              f'seconds={secs:.3f} running {running / secs:.1f} ms/s ({running_per_tick})')
        for t in targets:
            per_tick = f'{incl[t] / ticks:8.2f} ms/tick' if ticks else 'ms/tick unavailable'
            print(f'{incl[t] / secs:9.1f} ms/s {per_tick}  {t}')
