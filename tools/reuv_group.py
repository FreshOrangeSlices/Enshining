"""Give every cube under one group its own spot on the texture, at more texture pixels per model pixel (finer
detail), laid out like Minecraft's box layout for each cube:

        [up][down]
    [east][north][west][south]

Each new face starts with what that face showed before (scaled up), so nothing changes in-game until you paint it.
Groups inside it can be left where they are with --keep (e.g. something that already has its own pixels).

usage: reuv_group.py in.bbmodel out.bbmodel GROUP DENSITY [--row=N] [--keep=GROUP,GROUP]
  DENSITY  texture pixels per model pixel (3 = three times the detail of the 1:1 skin)
  --row    the first texture row to look for free space from (default 0)
"""
import math, sys

from PIL import Image

from bbtools import Project

args = [a for a in sys.argv[1:] if not a.startswith('--')]
opts = dict(a[2:].split('=', 1) for a in sys.argv[1:] if a.startswith('--'))
src, dst, group, density = args[0], args[1], args[2], float(args[3])
row = int(opts.get('row', 0))
keep = set(filter(None, opts.get('keep', '').split(',')))

P = Project(src)
old = P.tex.copy()


def cubes(node, skip):
    out = []
    for c in node['children']:
        if isinstance(c, str):
            out.append(P.els[c])
        elif P.groups[c['uuid']]['name'] not in skip:
            out.extend(cubes(c, skip))
    return out


def patch(face):
    """What a face shows now, the right way round."""
    a0, b0, a1, b1 = face['uv']
    x0, x1 = sorted((a0, a1))
    y0, y1 = sorted((b0, b1))
    box = (int(math.floor(x0)), int(math.floor(y0)), max(int(math.floor(x0)) + 1, math.ceil(x1)),
           max(int(math.floor(y0)) + 1, math.ceil(y1)))
    img = old.crop(box)
    if a1 < a0:
        img = img.transpose(Image.FLIP_LEFT_RIGHT)
    if b1 < b0:
        img = img.transpose(Image.FLIP_TOP_BOTTOM)
    return img


report = []
for e in cubes(P.nodes[group], keep):
    w, h, d = (round(abs(e['to'][i] - e['from'][i]) * density) for i in range(3))
    W, H = 2 * (d + w), d + h
    U, V = P.free_spot(W, H, start_row=row)
    layout = {'up': (U + d, V, w, d), 'down': (U + d + w, V, w, d), 'east': (U, V + d, d, h),
              'north': (U + d, V + d, w, h), 'west': (U + d + w, V + d, d, h), 'south': (U + d + w + d, V + d, w, h)}
    for name, (x, y, fw, fh) in layout.items():
        face = e['faces'].get(name)
        if not face or face.get('texture') is None:
            continue
        if face.get('rotation') in (90, 270):
            fw, fh = fh, fw
        if fw and fh:
            P.tex.paste(patch(face).resize((fw, fh), Image.NEAREST), (x, y))
        face['uv'] = [x, y, x + fw, y + fh]
    e['box_uv'] = False   # its own faces from now on (a box-UV cube's faces above are what it showed)
    report.append(f"{e['name']:16s} {w}x{h}x{d} at ({U},{V}) size {W}x{H}")

P.save(dst)
print('\n'.join(report))
