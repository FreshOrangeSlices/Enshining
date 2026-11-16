"""Give the cubes of one group a proper 1-pixel-per-pixel unwrap: every face its own pixels (sides and bottom
included), laid out like Minecraft's box layout:

        [up][down]
    [east][north][west][south]

The first cube's front stays where it was painted; the other faces start filled with what they showed before
(stretched), so nothing changes until you paint them. A cube that is the left-right mirror of the first one
shares its pixels, mirrored (paint one side, both change).

usage: unwrap_cube_group.py in.bbmodel out.bbmodel GROUP
"""
import base64, io, json, math, sys
from PIL import Image

src, dst, group_name = sys.argv[1:4]
m = json.load(open(src))
groups = {g['uuid']: g for g in m['groups']}
els = {e['uuid']: e for e in m['elements']}


def cubes_of(name):
    out = []

    def walk(n, inside):
        inside = inside or groups[n['uuid']]['name'] == name
        for c in n['children']:
            if isinstance(c, str):
                if inside:
                    out.append(els[c])
            else:
                walk(c, inside)
    for n in m['outliner']:
        if isinstance(n, dict):
            walk(n, False)
    return out


cubes = cubes_of(group_name)
tex = Image.open(io.BytesIO(base64.b64decode(m['textures'][0]['source'].split(',', 1)[1]))).convert('RGBA')
old = tex.copy()
first = cubes[0]
w, h, d = (max(1, round(first['to'][i] - first['from'][i])) for i in range(3))
n = first['faces']['north']['uv']
U, V = int(min(n[0], n[2])) - d, int(min(n[1], n[3])) - d
layout = {'up': (U + d, V, w, d), 'down': (U + d + w, V, w, d), 'east': (U, V + d, d, h),
          'north': (U + d, V + d, w, h), 'west': (U + d + w, V + d, d, h), 'south': (U + d + w + d, V + d, w, h)}

# The new pixels must not land on anything else's.
mine = {id(f) for c in cubes for f in c['faces'].values()}
for e in m['elements']:
    for f in e['faces'].values():
        if f.get('texture') is None or id(f) in mine or e.get('box_uv'):
            continue
        a0, b0, a1, b1 = f['uv']
        for (x, y, fw, fh) in layout.values():
            if min(a0, a1) < x + fw and max(a0, a1) > x and min(b0, b1) < y + fh and max(b0, b1) > y:
                raise SystemExit(f"new {group_name} pixels would overlap {e['name']}")


def mirrored(c):
    return all(abs(c['from'][0] + first['to'][0]) < 0.01 and abs(c['to'][0] + first['from'][0]) < 0.01 for _ in [0]) and \
        all(abs(c[k][i] - first[k][i]) < 0.01 for k in ('from', 'to') for i in (1, 2))


# Pre-fill each new face with what that face showed before.
for face, (x, y, fw, fh) in layout.items():
    if face == 'north':
        continue
    a0, b0, a1, b1 = first['faces'][face]['uv']
    patch = old.crop((int(min(a0, a1)), int(min(b0, b1)), max(int(min(a0, a1)) + 1, math.ceil(max(a0, a1))),
                      max(int(min(b0, b1)) + 1, math.ceil(max(b0, b1)))))
    tex.paste(patch.resize((fw, fh), Image.NEAREST), (x, y))

for c in cubes:
    flip = c is not first and mirrored(c)
    if c is not first and not flip:
        raise SystemExit(f"{c['name']} is neither the first cube nor its mirror; unwrap it on its own")
    for face, (x, y, fw, fh) in layout.items():
        src_face = {'east': 'west', 'west': 'east'}.get(face, face) if flip else face
        x, y, fw, fh = layout[src_face]
        uv = [x, y, x + fw, y + fh]
        if flip:
            uv = [uv[2], uv[1], uv[0], uv[3]]
        c['faces'][face] = {'uv': uv, 'texture': 0}

buf = io.BytesIO()
tex.save(buf, 'PNG')
m['textures'][0]['source'] = 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()
json.dump(m, open(dst, 'w'))
print(group_name, 'cube', (w, h, d), 'unwrapped at', (U, V), 'size', (2 * (d + w), d + h), '| cubes', len(cubes))
