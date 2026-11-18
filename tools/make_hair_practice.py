"""A practice file for hair on the blank girl rig: the four techniques side by side, each in its own group under
"hair", to open in Blockbench, take apart and copy.

  hair_1_base         a shell just over the head (like a skin's hat layer): the hair's main mass, painted
  hair_2_cube_locks   side locks made of 3 cubes each, thinner toward the tip, each turned a little
  hair_3_plane_locks  bangs as flat "hair cards": one plane each, the lock's shape cut by see-through pixels
  flow_strand_1..3    a strand down the back as a chain of flow_ bones (sways on its own in the game)

usage: make_hair_practice.py base_rig.bbmodel out.bbmodel
"""
import sys

from PIL import Image, ImageDraw

from bbtools import Project

src, dst = sys.argv[1:3]
p = Project(src)
old = p.tex
p.tex = Image.new('RGBA', (128, 128), (0, 0, 0, 0))
p.tex.paste(old, (0, 0))
p.m['resolution'] = {'width': 128, 'height': 128}
t = p.m['textures'][0]
t.update({'width': 128, 'height': 128, 'uv_width': 128, 'uv_height': 128})
d = ImageDraw.Draw(p.tex)

DARK, MID, LIGHT, SHINE = (58, 40, 52, 255), (92, 64, 84, 255), (128, 92, 118, 255), (176, 140, 168, 255)


def shaded(x, y, w, h, top_light=True):
    """A patch shaded top (light) to bottom (dark), with a shine band a third of the way down."""
    for row in range(h):
        f = row / max(1, h - 1)
        col = LIGHT if f < 0.3 else MID if f < 0.7 else DARK
        if top_light and abs(f - 0.3) < 0.5 / max(1, h - 1):
            col = SHINE
        d.line([x, y + row, x + w - 1, y + row], fill=col)


# ---- 1: the base shell -------------------------------------------------------------------------------------------
# faces 8x8 each, laid out from (64, 0): top, front (hairline only), right, back, left
U, V = 64, 0
shaded(U, V, 8, 8)                                  # top
for x in range(8):                                  # front: just the hairline, a little uneven, the rest see-through
    depth = 2 if x % 3 else 1
    d.line([U + 8 + x, V, U + 8 + x, V + depth], fill=MID)
for i in (2, 3, 4):                                 # sides and back: full hair
    shaded(U + 8 * i, V, 8, 8)
for i in (2, 4):                                    # sides: cut a little around the ear/face edge
    d.line([U + 8 * i, V + 5, U + 8 * i, V + 7], fill=(0, 0, 0, 0))
hair = p.group('hair_1_base', 'hair', origin=(0, 28, 0))
p.cube(hair, 'hair_shell', (-4, 24, -4), (4, 32, 4), (0, 28, 0), faces={
    'up': (U, V, U + 8, V + 8), 'north': (U + 8, V, U + 16, V + 8), 'east': (U + 16, V, U + 24, V + 8),
    'south': (U + 24, V, U + 32, V + 8), 'west': (U + 32, V, U + 40, V + 8)}, inflate=0.45)

# ---- 2: cube locks -----------------------------------------------------------------------------------------------
U, V = 64, 10
shaded(U, V, 4, 6, top_light=False)
locks = p.group('hair_2_cube_locks', 'hair', origin=(0, 28, 0))
for side, sx in (('right', 1), ('left', -1)):   # her right is +x
    g = p.group(f'lock_{side}', 'hair_2_cube_locks', origin=(sx * 4.3, 28, -2.6))
    top, segs = 28.0, [(1.8, 3.0, 1.8, 6), (1.4, 3.0, 1.4, 10), (1.0, 2.4, 1.0, 14)]
    for i, (w, h, dpt, tilt) in enumerate(segs):
        cx = sx * (4.3 + 0.25 * i)
        uv = (U, V, U + 4, V + 6)
        p.cube(g, f'lock_{side}_{i + 1}', (cx - w / 2, top - h, -2.6 - dpt / 2), (cx + w / 2, top, -2.6 + dpt / 2),
               (cx, top, -2.6), (0, 0, -sx * tilt), faces={k: uv for k in ('north', 'east', 'south', 'west', 'down')})
        top -= h - 0.35   # each segment starts tucked into the one above

# ---- 3: plane locks ("hair cards") -------------------------------------------------------------------------------
U, V = 70, 10
W, H = 6, 10   # 3 x 5 model pixels at double detail
for row in range(H):
    f = row / (H - 1)
    width = W if f < 0.55 else max(1, round(W * (1 - (f - 0.55) / 0.45)))   # tapers to a point
    left = (W - width) // 2 + (1 if f > 0.8 else 0)
    col = LIGHT if f < 0.25 else MID if f < 0.7 else DARK
    d.line([U + left, V + row, U + left + width - 1, V + row], fill=col)
d.point((U + 2, V + 2), fill=SHINE)
cards = p.group('hair_3_plane_locks', 'hair', origin=(0, 31, -4.5))
for i, (x, roll, length) in enumerate(((-2.2, 14, 2.9), (0.0, -4, 3.2), (2.2, -16, 2.7))):   # end above the eyes
    uv = (U, V, U + W, V + H)
    p.cube(cards, f'bang_{i + 1}', (x - 1.5, 31.5 - length, -4.6), (x + 1.5, 31.5, -4.6), (x, 31.5, -4.6),
           (12, 0, roll), faces={'north': uv, 'south': (uv[2], uv[1], uv[0], uv[3])})   # tips lean out from the face

# ---- 4: a flowing strand down the back ----------------------------------------------------------------------------
U, V = 78, 10
shaded(U, V, 4, 6, top_light=False)
parent, top, z = 'hair', 27.0, 4.6
for i, (w, h) in enumerate(((2.2, 4.0), (1.8, 4.0), (1.3, 3.4))):
    name = f'flow_strand_{i + 1}'
    g = p.group(name, parent, origin=(0, top, z))
    uv = (U, V, U + 4, V + 6)
    p.cube(g, f'strand_{i + 1}', (-w / 2, top - h, z - w / 2), (w / 2, top, z + w / 2), (0, top, z),
           faces={k: uv for k in ('north', 'east', 'south', 'west', 'down')})
    parent, top, z = name, top - h + 0.3, z + 0.35
p.save(dst)
print('hair practice saved:', dst)
