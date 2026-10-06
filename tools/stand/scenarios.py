"""Extra load scenarios for the perf stand, written as functions into the run's perfstand pack.

Both sit next to the static (non-walking) client Far05 at (2600, -60, 2600), inside its island.
  items    3000 unmergeable items in a 48x48 flowing-water basin (item physics, chunk lookups)
  pistons  225 observer-clock sticky pistons (block events, block-entity churn, block updates)
"""
from pathlib import Path

Y = -60  # classic flat: grass at -61, players stand at -60


def _items():
    x0, z0, w, y = 2640, 2576, 48, Y - 1
    tools = [f'{m}_{t}' for t in ('sword', 'pickaxe', 'axe', 'shovel')
             for m in ('wooden', 'stone', 'iron', 'golden')]
    cmds = [f'fill {x0 - 1} {y} {z0 - 1} {x0 + w} {y + 3} {z0 + w} minecraft:stone',
            f'fill {x0} {y + 1} {z0} {x0 + w - 1} {y + 3} {z0 + w - 1} minecraft:air']
    cmds += [f'setblock {x0 + 3 + 8 * i} {y + 1} {z0 + 3 + 8 * j} minecraft:water'
             for i in range(6) for j in range(6)]
    for n in range(3000):
        cmds.append(f'summon item {x0 + 0.5 + n % 47} {y + 1.5} {z0 + 0.5 + (n // 47) % 47} '
                    f'{{Age:-32768s,PickupDelay:32767s,Item:{{id:"minecraft:{tools[n % len(tools)]}",count:1}}}}')
    return cmds


def _pistons():
    cmds = []
    for i in range(15):
        for j in range(15):
            x, z = 2565 + 5 * i, 2585 + 2 * j
            cmds += [f'setblock {x - 2} {Y} {z} minecraft:stone',
                     f'setblock {x - 1} {Y} {z} minecraft:sticky_piston[facing=west]',
                     f'setblock {x} {Y} {z} minecraft:observer[facing=east]',
                     f'setblock {x + 1} {Y} {z} minecraft:observer[facing=west]']
    return cmds


SCENARIOS = {'items': _items, 'pistons': _pistons}


def write(pack: Path, name: str) -> str:
    """Write perfstand:<name> into the pack and return the function id."""
    fn = pack / 'data' / 'perfstand' / 'function'
    fn.mkdir(parents=True, exist_ok=True)
    (fn / f'{name}.mcfunction').write_text('\n'.join(SCENARIOS[name]()) + '\n')
    return f'perfstand:{name}'
