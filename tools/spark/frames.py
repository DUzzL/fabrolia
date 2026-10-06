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
          'com.axalotl.fabrolia.common.regions.IslandTickUnit.awaitEntityBarrier']
MAIN = ['net.minecraft.server.MinecraftServer.tickServer',
        'net.minecraft.server.level.ServerChunkCache.trackerOnMain',
        'com.axalotl.fabrolia.common.regions.IslandCommands.quiesceAllExcept',
        'com.axalotl.fabrolia.common.regions.IslandCommands.mainClaim',
        'eventFdWrite']


def match(label, t):
    name = label.split('(')[0]
    return name == t if t.count('.') > 2 else t in label


for path in sys.argv[1:]:
    a = core.Analysis(core.load(path), path)
    secs = 60.0 * len(a.window_ids)
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
        ticks = a.norm_ticks or 1
        print(f'== {Path(path).parts[-5] if len(Path(path).parts) > 5 else path} [{group}] windows={len(a.window_ids)} '
              f'running {running / secs:.1f} ms/s ({running / ticks:.2f} ms/tick)')
        for t in targets:
            print(f'{incl[t] / secs:9.1f} ms/s {incl[t] / ticks:8.2f} ms/tick  {t}')
