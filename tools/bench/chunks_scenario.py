"""The 'many loaded chunks' scenario: players spread over a large pre-generated world."""

SEED = 'fabrolia-chunks'
PORT = 26600
VIEW_DISTANCE = 16        # 33 x 33 = 1089 chunks sent to every player
SIMULATION_DISTANCE = 12  # 25 x 25 = 625 ticking chunks around every player
PLAYERS_PER_STEP = 4
CLOCK_Y = 300             # observer clock, alone in section y 288..303 above any terrain
HOVER_Y = 200             # creative players hover here

# 32 spots on an 8 x 4 grid, 1024 blocks apart: every player's loaded area (radius 256 blocks)
# stays more than 384 blocks away from its neighbours', so each spot is its own island.
SPOTS = [((i - 4) * 1024 + 512, (j - 2) * 1024 + 512) for j in range(4) for i in range(8)]
# Join order spreads every step over the whole grid.
ORDER = [0, 31, 7, 24, 3, 28, 12, 19, 5, 26, 10, 21, 1, 30, 14, 17,
         6, 25, 9, 22, 2, 29, 13, 18, 4, 27, 11, 20, 15, 16, 8, 23]
BOTS = ['Chunk%02d' % (i + 1) for i in range(len(SPOTS))]


def clock_blocks(x, z):
    return [(x, CLOCK_Y, z), (x + 1, CLOCK_Y, z)]
