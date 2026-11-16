"""Give a rig face overlays for its eye expressions (the way Migamigos' Ganyu does it): in each of the groups
eyes_open, eyes_half, eyes_closed and eyes_happy, one flat 8x8 sheet a tenth of a pixel in front of the face,
with its own 8x8 patch of the texture. Each patch starts as a copy of the face as it is now, so nothing changes
until you paint the eyes differently in each. The game shows one at a time (blinking, dozing, sleeping, happy).

usage: add_eye_overlays.py in.bbmodel out.bbmodel
"""
import base64, io, json, math, sys, uuid
from PIL import Image

src, dst = sys.argv[1:3]
m = json.load(open(src))
uid = lambda: str(uuid.uuid4())
groups = {g['uuid']: g for g in m['groups']}
nodes = {}


def walk(n):
    nodes[groups[n['uuid']]['name']] = n
    for c in n['children']:
        if isinstance(c, dict):
            walk(c)


for n in m['outliner']:
    if isinstance(n, dict):
        walk(n)
els = {e['uuid']: e for e in m['elements']}
head = next(els[c] for c in nodes['head']['children'] if isinstance(c, str) and els[c]['name'] == 'head')
x0, y0, z0 = head['from']
x1, y1, _ = head['to']
face = head['faces']['north']['uv']
fu, fv = int(min(face[0], face[2])), int(min(face[1], face[3]))
fw, fh = int(abs(face[2] - face[0])), int(abs(face[3] - face[1]))

NAMES = ['eyes_open', 'eyes_half', 'eyes_closed', 'eyes_happy']
eyes = nodes['eyes']
eyes_group = groups[eyes['uuid']]
for name in NAMES:
    if name not in nodes:
        g = dict(eyes_group, name=name, uuid=uid())
        m['groups'].append(g)
        groups[g['uuid']] = g
        nodes[name] = {'uuid': g['uuid'], 'isOpen': False, 'children': []}
        eyes['children'].append(nodes[name])
    if any(isinstance(c, str) for c in nodes[name]['children']):
        raise SystemExit(f'{name} already has cubes; not adding another sheet')

# free space for four patches in a row (one pixel apart)
tex = Image.open(io.BytesIO(base64.b64decode(m['textures'][0]['source'].split(',', 1)[1]))).convert('RGBA')
W, H = tex.size
taken = [[False] * W for _ in range(H)]
for e in m['elements']:
    for f in e['faces'].values():
        if f.get('texture') is None:
            continue
        a, b, c, d = f['uv']
        for y in range(int(math.floor(min(b, d))), int(math.ceil(max(b, d)))):
            for x in range(int(math.floor(min(a, c))), int(math.ceil(max(a, c)))):
                if 0 <= x < W and 0 <= y < H:
                    taken[y][x] = True
    if e.get('box_uv'):
        w, h, dd = (math.ceil(e['to'][i] - e['from'][i]) for i in range(3))
        u, v = e.get('uv_offset', [0, 0])
        for y in range(v, min(H, v + dd + h)):
            for x in range(u, min(W, u + 2 * (dd + w))):
                taken[y][x] = True
alpha = tex.getchannel('A').load()
bw, bh = 4 * fw + 3, fh
spot = next(((x, y) for y in range(64, H - bh + 1) for x in range(0, W - bw + 1)
             if all(not taken[yy][xx] and not alpha[xx, yy] for yy in range(y, y + bh) for xx in range(x, x + bw))), None)
if spot is None:
    raise SystemExit('no free space for the eye patches')
current_face = tex.crop((fu, fv, fu + fw, fv + fh))
for i, name in enumerate(NAMES):
    u, v = spot[0] + i * (fw + 1), spot[1]
    tex.paste(current_face, (u, v))
    faces = {k: {'uv': [0, 0, 0, 0], 'texture': None} for k in ('east', 'south', 'west', 'up', 'down')}
    faces['north'] = {'uv': [u, v, u + fw, v + fh], 'texture': 0}
    e = {'name': name, 'box_uv': False, 'render_order': 'default', 'locked': False, 'export': True,
         'allow_mirror_modeling': True, 'from': [x0, y0, z0 - 0.1], 'to': [x1, y1, z0 - 0.1], 'autouv': 0, 'color': 4,
         'origin': [0, (y0 + y1) / 2, z0 - 0.1], 'rotation': [0, 0, 0], 'faces': faces, 'type': 'cube', 'uuid': uid()}
    m['elements'].append(e)
    nodes[name]['children'].append(e['uuid'])
    print(f'{name}: patch at ({u}, {v})')

buf = io.BytesIO()
tex.save(buf, 'PNG')
m['textures'][0]['source'] = 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()
json.dump(m, open(dst, 'w'))
