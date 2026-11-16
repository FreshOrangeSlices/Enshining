"""Fold the skirt's own texture (the project's second texture) into the first one, for Blockbench formats that show
only one texture per model (the GeckoLib format does). Each block of the skirt's layout (a tier strip, the frill
and lining patches) is moved as a whole into free space on the main texture, and its faces' UVs follow.
The skirt's faces are the faces of every cube under the "skirt" group (Blockbench may have switched them to the
first texture on saving; their UVs still point into the skirt's own texture).

usage: merge_skirt_texture.py in.bbmodel out.bbmodel
"""
import base64, io, json, math, sys
from PIL import Image

src, dst = sys.argv[1:3]
m = json.load(open(src))
assert len(m['textures']) == 2, 'expects the main texture and the skirt texture'


def image(t):
    return Image.open(io.BytesIO(base64.b64decode(t['source'].split(',', 1)[1]))).convert('RGBA')


main, skirt = image(m['textures'][0]), image(m['textures'][1])
groups = {g['uuid']: g for g in m['groups']}
in_skirt = set()


def walk(node, inside):
    inside = inside or groups[node['uuid']]['name'] == 'skirt'
    for c in node['children']:
        if isinstance(c, str):
            if inside:
                in_skirt.add(c)
        else:
            walk(c, inside)


for n in m['outliner']:
    if isinstance(n, dict):
        walk(n, False)
W, H = main.size
taken = [[False] * W for _ in range(H)]


def take(u0, v0, u1, v1, margin=0):
    for y in range(max(0, v0 - margin), min(H, v1 + margin)):
        for x in range(max(0, u0 - margin), min(W, u1 + margin)):
            taken[y][x] = True


def box(uv):
    u0, v0, u1, v1 = uv
    return (int(math.floor(min(u0, u1))), int(math.floor(min(v0, v1))), int(math.ceil(max(u0, u1))), int(math.ceil(max(v0, v1))))


# Everything the main texture already uses: the skin area, every face reading it, and any painted pixel.
take(0, 0, 64, 64)
for e in m['elements']:
    if e['uuid'] in in_skirt:
        continue
    if e.get('box_uv'):
        w, h, d = (math.ceil(e['to'][i] - e['from'][i]) for i in range(3))
        u, v = e.get('uv_offset', [0, 0])
        take(u, v, u + 2 * (d + w), v + d + h)
        continue
    for f in e['faces'].values():
        if f.get('texture') == 0:
            take(*box(f['uv']))
alpha = main.getchannel('A').load()
for y in range(H):
    for x in range(W):
        if alpha[x, y]:
            taken[y][x] = True

# The skirt's layout as blocks: face rectangles that share rows belong to one block.
faces = [f for e in m['elements'] if e['uuid'] in in_skirt for f in e['faces'].values() if f.get('texture') is not None]
rects = sorted({box(f['uv']) for f in faces}, key=lambda r: r[1])
blocks = []
for r in rects:
    if blocks and r[1] < blocks[-1][3]:
        b = blocks[-1]
        blocks[-1] = (min(b[0], r[0]), b[1], max(b[2], r[2]), max(b[3], r[3]))
    else:
        blocks.append(r)


def free(x, y, w, h):
    return all(not taken[yy][xx] for yy in range(y, y + h) for xx in range(x, x + w))


moves = []
for b in sorted(blocks, key=lambda b: -(b[2] - b[0]) * (b[3] - b[1])):
    w, h = b[2] - b[0], b[3] - b[1]
    spot = next(((x, y) for y in range(64, H - h + 1) for x in range(0, W - w + 1) if free(x, y, w, h)), None)
    if spot is None:
        raise SystemExit(f'no free {w}x{h} space on the main texture for a skirt block')
    take(spot[0], spot[1], spot[0] + w, spot[1] + h, margin=1)
    main.paste(skirt.crop(b), spot)
    moves.append((b, spot[0] - b[0], spot[1] - b[1]))
    print(f'skirt block {w}x{h} at {b[:2]} -> {spot}')

for f in faces:
    r = box(f['uv'])
    b, dx, dy = next(mv for mv in moves if mv[0][0] <= r[0] and r[2] <= mv[0][2] and mv[0][1] <= r[1] and r[3] <= mv[0][3])
    u0, v0, u1, v1 = f['uv']
    f['uv'] = [u0 + dx, v0 + dy, u1 + dx, v1 + dy]
    f['texture'] = 0

buf = io.BytesIO()
main.save(buf, 'PNG')
m['textures'][0]['source'] = 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()
m['textures'] = m['textures'][:1]
json.dump(m, open(dst, 'w'))
