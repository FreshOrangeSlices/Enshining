"""Generic A-line skirt template on the blank girl rig.

- Narrow 2-px panels in two tiers; the lower tier starts tucked under the upper one, so there are no gaps.
- High-low hem: just above the knee in front, halfway down the shin at the back, blended smoothly around the sides.
- A short frill ring along the hem; every frill panel shares one small patch, so one painted frill repeats all round.
- The waist is a rounded-box curve (not an ellipse), so the corners of her hips and legs stay inside the skirt.
- The skirt has its own texture (skirt.png, second texture in the project), separate from the body texture.

usage: make_skirt_aline.py base_rig.bbmodel out.bbmodel [slim]
"""
import base64, io, json, math, sys, uuid
from PIL import Image, ImageDraw

base_path, out_path = sys.argv[1:3]
slim = len(sys.argv) > 3 and sys.argv[3] == 'slim'

WAIST_Y = 12.8
WAIST_A, WAIST_B, WAIST_N = 4.5, 2.7, 4.0     # rounded box: |x/a|^n + |z/b|^n = 1 holds the body's corners (+-4, +-2)
FRONT_HEM_Y, BACK_HEM_Y = 7.0, 3.5            # front: 1 px above the knee (knee = 6); back: halfway down the shin
WIDTH, OVERLAP = 2.0, float(__import__('os').environ.get('OVERLAP', 1.25))  # 2-px panels, 25% overlap (room to swing without gaps)
TUCK_IN, TUCK_UP = 0.35, 0.5                  # the lower tier starts this far inside / above the upper tier's edge
TIERS = [(21, 0.5), (16, 0.5)] if slim else [(24, 0.5), (20, 0.5)]   # (tilt degrees, share of the drop)
FRILL = dict(length=1.8, extra_tilt=28, tuck_up=0.9, tuck_in=0.15)
PX = 2                                        # texture pixels per model pixel (accessories at double detail)
CW, CH = 128, 256
FRONT_DEG = BACK_DEG = 40                     # how wide the skirt_front / skirt_back groups are


def spow(v, p):
    return math.copysign(abs(v) ** p, v)


def waist(t):
    """Point on the waist curve and its outward unit normal; t = 0 is the front (-z), pi the back."""
    x = WAIST_A * spow(math.sin(t), 2 / WAIST_N)
    z = -WAIST_B * spow(math.cos(t), 2 / WAIST_N)
    gx = spow(x / WAIST_A, WAIST_N - 1) / WAIST_A
    gz = spow(z / WAIST_B, WAIST_N - 1) / WAIST_B
    g = math.hypot(gx, gz)
    return (x, z), (gx / g, gz / g)


def at(t, off):
    (x, z), (nx, nz) = waist(t)
    return x + off * nx, z + off * nz


def hem_y(t):
    return FRONT_HEM_Y + (BACK_HEM_Y - FRONT_HEM_Y) * (1 - math.cos(t)) / 2


def chain(t):
    """Each tier at t as (top offset, top y, length, tilt), then where the hem ends (offset, y)."""
    out, y = 0.0, WAIST_Y
    drop = WAIST_Y - hem_y(t)
    tiers = []
    for i, (tilt, share) in enumerate(TIERS):
        if i:
            out, y = out - TUCK_IN, y + TUCK_UP
        d = share * drop + (TUCK_UP if i else 0)
        length = d / math.cos(math.radians(tilt))
        tiers.append((out, y, length, tilt))
        out, y = out + length * math.sin(math.radians(tilt)), y - d
    return tiers, (out, y)


def spaced(n, bottom_off, shift):
    """n parameters spread evenly by arc length along the curve where this ring's panels end."""
    ts = [2 * math.pi * k / 720 for k in range(721)]
    pts = [at(t, bottom_off(t)) for t in ts]
    cum = [0.0]
    for a, b in zip(pts, pts[1:]):
        cum.append(cum[-1] + math.dist(a, b))
    total, out, j = cum[-1], [], 0
    for k in range(n):
        s = (k + shift) * total / n
        while cum[j + 1] < s:
            j += 1
        f = (s - cum[j]) / (cum[j + 1] - cum[j])
        out.append(ts[j] + f * (ts[j + 1] - ts[j]))
    return out, total


panels = []
for i in range(len(TIERS)):
    def bottom(t, i=i):
        tiers, end = chain(t)
        off, _, length, tilt = tiers[i]
        return off + length * math.sin(math.radians(tilt))
    _, total = spaced(8, bottom, 0)
    n = math.ceil(total * OVERLAP / WIDTH)
    for t in spaced(n, bottom, 0.5 if i % 2 else 0)[0]:
        off, top, length, tilt = chain(t)[0][i]
        panels.append(dict(kind=f't{i}', t=t, off=off, top=top, length=length, tilt=tilt))
frill_bottom = lambda t: chain(t)[1][0] - FRILL['tuck_in'] + FRILL['length'] * math.sin(math.radians(chain(t)[0][-1][3] + FRILL['extra_tilt']))
_, total = spaced(8, frill_bottom, 0)
for t in spaced(math.ceil(total * OVERLAP / WIDTH), frill_bottom, 0.25)[0]:
    tiers, (off, y) = chain(t)
    panels.append(dict(kind='frill', t=t, off=off - FRILL['tuck_in'], top=y + FRILL['tuck_up'],
                       length=FRILL['length'], tilt=tiers[-1][3] + FRILL['extra_tilt']))


def part_of(t):
    x, z = at(t, 0)
    d = math.degrees(math.atan2(x, -z))
    return 'front' if abs(d) <= FRONT_DEG else 'back' if abs(d) >= 180 - BACK_DEG else 'side'


# ---- skirt.png: one strip per tier (each panel its own 2x-density column), the shared frill patch, the lining -------
tex = Image.new('RGBA', (CW, CH), (0, 0, 0, 0))
draw = ImageDraw.Draw(tex)
uv, y0 = {}, 0
for i in range(len(TIERS)):
    ps = sorted([p for p in panels if p['kind'] == f't{i}'], key=lambda p: (math.degrees(p['t']) + 180) % 360)
    h = math.ceil(max(p['length'] for p in ps) * PX)
    shade = 215 - 18 * i
    for k, p in enumerate(ps):
        x = k * WIDTH * PX
        draw.rectangle([x, y0, x + WIDTH * PX - 1, y0 + h - 1], fill=(shade, shade, shade + 6, 255))
        draw.line([x, y0, x, y0 + h - 1], fill=(shade - 25, shade - 25, shade - 20, 255))
        uv[id(p)] = (x, y0)
    assert len(ps) * WIDTH * PX <= CW, len(ps)
    y0 += h + 2
FRILL_UV = (0, y0)
fh = round(FRILL['length'] * PX)
draw.rectangle([0, y0, WIDTH * PX - 1, y0 + fh - 1], fill=(244, 244, 244, 255))
draw.line([0, y0 + fh - 1, WIDTH * PX - 1, y0 + fh - 1], fill=(205, 205, 210, 255))
LINING = (WIDTH * PX + 2, y0)
draw.rectangle([LINING[0], y0, LINING[0] + 3, y0 + 3], fill=(110, 108, 118, 255))

# ---- the project: body texture as it was (on the same canvas size), skirt.png as texture 1 -------------------------
base = json.load(open(base_path))
src = Image.open(io.BytesIO(base64.b64decode(base['textures'][0]['source'].split(',', 1)[1]))).convert('RGBA')
body = Image.new('RGBA', (CW, CH), (0, 0, 0, 0))
body.paste(src, (0, 0))


def png(img):
    buf = io.BytesIO()
    img.save(buf, 'PNG')
    return 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()


t0 = base['textures'][0]
t0.update({'width': CW, 'height': CH, 'uv_width': CW, 'uv_height': CH, 'source': png(body)})
t1 = dict(t0, name='skirt.png', path='', id='1', uuid=str(uuid.uuid4()), use_as_default=False, source=png(tex))
base['textures'] = [t0, t1]
base['resolution'] = {'width': CW, 'height': CH}
base['meta']['box_uv'] = False
groups, elements = base['groups'], base['elements']
uid = lambda: str(uuid.uuid4())


def make(p):
    px, pz = at(p['t'], p['off'])
    _, (nx, nz) = waist(p['t'])
    yaw = math.degrees(math.atan2(-nx, -nz))
    L, top, w = p['length'], p['top'], WIDTH
    faces = {k: {'uv': [0, 0, 0, 0], 'texture': None} for k in ('north', 'east', 'south', 'west', 'up', 'down')}
    if p['kind'] == 'frill':
        u, v = FRILL_UV
        faces['north'] = faces['south'] = {'uv': [u, v, u + w * PX, v + fh], 'texture': 1}
    else:
        u, v = uv[id(p)]
        faces['north'] = {'uv': [u, v, u + w * PX, v + round(L * PX, 3)], 'texture': 1}
        faces['south'] = {'uv': [LINING[0], LINING[1], LINING[0] + 4, LINING[1] + 4], 'texture': 1}
    e = {'name': f"{p['kind']}_{round(math.degrees(p['t']))}", 'box_uv': False, 'render_order': 'default',
         'locked': False, 'export': True, 'allow_mirror_modeling': True,
         'from': [round(px - w / 2, 4), round(top - L, 4), round(pz, 4)], 'to': [round(px + w / 2, 4), round(top, 4), round(pz, 4)],
         'autouv': 0, 'color': 3, 'origin': [round(px, 4), round(top, 4), round(pz, 4)],
         'rotation': [round(p['tilt'], 2), round(yaw, 2), 0], 'faces': faces, 'type': 'cube', 'uuid': uid()}
    elements.append(e)
    return e['uuid']


def group(name, origin, children):
    g = {'name': name, 'uuid': uid(), 'export': True, 'locked': False, 'scope': 0, 'selected': False, 'visibility': True,
         'origin': origin, 'rotation': [0, 0, 0], 'color': 3, 'reset': False, 'shade': True, 'mirror_uv': False,
         'autouv': 0, 'isOpen': False}
    groups.append(g)
    return {'uuid': g['uuid'], 'isOpen': False, 'children': children}


kids = {'front': [], 'back': [], 'side': [], 'frill': []}
for p in panels:
    part = part_of(p['t'])
    kids['frill' if p['kind'] == 'frill' and part == 'side' else part].append(make(p))
nodes = [group('skirt_outer', [0, WAIST_Y, 0], kids['side']), group('skirt_frill', [0, WAIST_Y, 0], kids['frill']),
         group('skirt_front', [0, WAIST_Y, -WAIST_B], kids['front']), group('skirt_back', [0, WAIST_Y, WAIST_B], kids['back'])]
skirt = next(g['uuid'] for g in groups if g['name'] == 'skirt')
for name in ('skirt_front', 'skirt_back'):
    old = [g for g in groups if g['name'] == name]
    if len(old) > 1:
        groups.remove(old[0])


def place(ns):
    for n in ns:
        if isinstance(n, dict):
            if n['uuid'] == skirt:
                n['children'] = nodes
                return True
            if place(n.get('children', [])):
                return True


assert place(base['outliner'])
json.dump(base, open(out_path, 'w'))
counts = {k: sum(1 for p in panels if p['kind'] == k) for k in sorted({p['kind'] for p in panels})}
print(out_path, 'tilts', TIERS, counts, 'hem offset front/back', round(chain(0)[1][0], 2), round(chain(math.pi)[1][0], 2))
