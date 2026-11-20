"""Pack some groups' cubes tightly into one area of the texture, biggest first, carrying over what's painted.

usage: repack.py in.bbmodel out.bbmodel GROUP=DENSITY [GROUP=DENSITY ...] [--col=N] [--row=N]
  DENSITY  texture pixels per model pixel for that group's cubes (1 = the skin's own)
  --col/--row  where the area starts (default 0, 0): e.g. --col=128 for the right half of a 256-wide texture

Their old spots are cleared first (where nothing else uses those pixels), so the pack can reuse them; each face
starts with what it showed before, scaled to its new size. Layout per cube as Minecraft's box layout:
        [up][down]
    [east][north][west][south]
"""
import math
import sys

from PIL import Image

from bbtools import Project

args = [a for a in sys.argv[1:] if not a.startswith('--')]
opts = dict(a[2:].split('=', 1) for a in sys.argv[1:] if a.startswith('--'))
src, dst = args[0], args[1]
wanted = dict((a.split('=')[0], float(a.split('=')[1])) for a in args[2:])
col, row = int(opts.get('col', 0)), int(opts.get('row', 0))

P = Project(src)
old = P.tex.copy()


def cubes(node):
    out = []
    for c in node['children']:
        out.extend([P.els[c]] if isinstance(c, str) else cubes(c))
    return out


def rect(face):
    a0, b0, a1, b1 = face['uv']
    x0, x1 = sorted((a0, a1))
    y0, y1 = sorted((b0, b1))
    return int(math.floor(x0)), int(math.floor(y0)), max(int(math.floor(x0)) + 1, math.ceil(x1)), max(int(math.floor(y0)) + 1, math.ceil(y1))


def patch(face):
    a0, b0, a1, b1 = face['uv']
    img = old.crop(rect(face))
    if a1 < a0:
        img = img.transpose(Image.FLIP_LEFT_RIGHT)
    if b1 < b0:
        img = img.transpose(Image.FLIP_TOP_BOTTOM)
    return img


todo = []
for group, density in wanted.items():
    for e in cubes(P.nodes[group]):
        todo.append((e, density))
moving = {id(e) for e, _ in todo}

# What each face shows now, then clear the old spots (keeping any pixel another cube still uses).
kept = set()
for e in P.m['elements']:
    if id(e) in moving:
        continue
    for f in e['faces'].values():
        if f.get('texture') is not None:
            x0, y0, x1, y1 = rect(f)
            kept.update((x, y) for x in range(x0, x1) for y in range(y0, y1))
patches = {}
for e, _ in todo:
    for name, f in e['faces'].items():
        if f.get('texture') is None:
            continue
        patches[(id(e), name)] = patch(f)
        x0, y0, x1, y1 = rect(f)
        for x in range(x0, min(x1, P.tex.width)):
            for y in range(y0, min(y1, P.tex.height)):
                if (x, y) not in kept:
                    P.tex.putpixel((x, y), (0, 0, 0, 0))
        f['uv'] = [0, 0, 0, 0]   # out of the way while packing

layouts = []
for e, density in todo:
    rotated = [f.get('rotation') in (90, 270) for f in e['faces'].values()]
    w, h, d = (round(abs(e['to'][i] - e['from'][i]) * density) for i in range(3))
    layouts.append((e, w, h, d, 2 * (d + w), d + h))
layouts.sort(key=lambda t: (-t[5], -t[4]))   # tallest first, then widest: a tight shelf pack

report = []
for e, w, h, d, W, H in layouts:
    if W == 0 or H == 0:
        U, V = col, row
    else:
        U, V = P.free_spot(W, H, start_row=row, start_col=col)
    spots = {'up': (U + d, V, w, d), 'down': (U + d + w, V, w, d), 'east': (U, V + d, d, h),
             'north': (U + d, V + d, w, h), 'west': (U + d + w, V + d, d, h), 'south': (U + d + w + d, V + d, w, h)}
    for name, (x, y, fw, fh) in spots.items():
        f = e['faces'].get(name)
        if not f or f.get('texture') is None:
            continue
        if f.get('rotation') in (90, 270):
            fw, fh = fh, fw
        if fw and fh:
            P.tex.paste(patches[(id(e), name)].resize((fw, fh), Image.NEAREST), (x, y))
        f['uv'] = [x, y, x + fw, y + fh]
    e['box_uv'] = False
    report.append(f"{e['name']:20s} {w}x{h}x{d} at ({U},{V})")

P.save(dst)
print('\n'.join(report))
