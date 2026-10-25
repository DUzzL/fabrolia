"""The benchmark scenario shared by world preparation and the runs."""
import math

SEED = 'fabrolia-bench'
PORT = 26600
BOTS = ['Bench%d' % (i + 1) for i in range(8)]

# Eight bases on a ring of radius 2000 (adjacent bases ~1500 blocks apart), centred on
# multiples of 16 so each clock sits alone in its section.
CENTERS = []
for i in range(8):
    a = i * math.pi / 4
    CENTERS.append((int(round(2000 * math.cos(a) / 16)) * 16, int(round(2000 * math.sin(a) / 16)) * 16))

FLOOR_Y = 180          # pen floor (grass), interior y 181..183, glass roof at 184
HALF = 24              # interior 48 x 48
CLOCK_Y = 250          # two observers facing each other, alone in section y 240..255


def clock_blocks(cx, cz):
    return [(cx, CLOCK_Y, cz), (cx + 1, CLOCK_Y, cz)]


def spawn_offsets():
    """Floor blocks within reach of the player standing at the pen centre."""
    out = []
    for dx in range(-4, 5):
        for dz in range(-4, 5):
            if (dx, dz) != (0, 0) and dx * dx + dz * dz <= 16:
                out.append((dx, dz))
    return out


# 26.3 item registry ids (data generator report) and the mix spawned per bot and step.
EGGS = [('cow', 1281), ('sheep', 1283), ('pig', 1282), ('chicken', 1280), ('villager', 1321)]
MIX = {'cow': 4, 'sheep': 2, 'pig': 2, 'chicken': 2, 'villager': 2}  # per 12 mobs
