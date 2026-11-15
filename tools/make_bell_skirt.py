"""A bell-shaped two-tier skirt for the base rig (my own build of the technique): a HIP tier flaring out steeply from
the waist, and a HEM tier that starts tucked just inside and above the hip tier's outer edge and hangs down more
steeply, so the two overlap with no gap and the outline is a strict bell. Each ring's panel width comes from that
ring's own circumference (wider rings get wider panels), with overlap, so the panels never open up. An inner layer,
a slightly smaller copy turned half a panel, closes anything left and gives a lining color.
usage: make_bell_skirt.py base_rig.bbmodel out.bbmodel"""
import base64, io, json, math, sys, uuid
from PIL import Image, ImageDraw

WAIST_Y, WAIST_RX, WAIST_RZ = 12.8, 4.35, 2.55
HIP = dict(n=14, length=4.0, tilt=48)       # out from the waist, nearly a shelf
HEM = dict(n=18, length=5.0, tilt=16)       # hangs from under the hip edge
TUCK_IN, TUCK_UP = 0.6, 0.6                 # hem top sits this far inside / above the hip tier's bottom edge
OVERLAP = 1.3
INNER_SCALE, INNER_UP = 0.86, 0.4           # lining layer
FRONT_DEG = BACK_DEG = 40
SLICE, VS = 5, 2                            # texture: every panel 5 px wide, 2 px per model pixel down
CW, CH = 128, 256

def ring(top_y, rx, rz, tier, layer, offset):
    """One ring of panels; returns panels and the ring's bottom edge (y, rx, rz)."""
    t = math.radians(tier['tilt'])
    out, down = tier['length'] * math.sin(t), tier['length'] * math.cos(t)
    mid_rx, mid_rz = rx + out / 2, rz + out / 2
    perim = 2 * math.pi * math.sqrt((mid_rx ** 2 + mid_rz ** 2) / 2)
    width = perim / tier['n'] * OVERLAP
    panels = []
    for i in range(tier['n']):
        phi = (i + offset) * 360.0 / tier['n']
        panels.append(dict(phi=phi, rx=rx, rz=rz, top=top_y, width=width, **tier, layer=layer))
    return panels, (top_y - down, rx + out, rz + out)

def build_layer(layer, scale, up, offset):
    rx, rz, top = WAIST_RX * scale, WAIST_RZ * scale, WAIST_Y + up
    hip, (by, brx, brz) = ring(top, rx, rz, dict(HIP, length=HIP['length'] * scale), layer + '_hip', offset)
    hem, _ = ring(by + TUCK_UP, brx - TUCK_IN, brz - TUCK_IN, dict(HEM, length=HEM['length'] * scale), layer + '_hem', offset)
    return hip + hem

panels = build_layer('outer', 1.0, 0.0, 0.0) + build_layer('inner', INNER_SCALE, INNER_UP, 0.5)

def part_of(phi):
    d = (phi + 180) % 360 - 180
    return 'front' if abs(d) <= FRONT_DEG else 'back' if abs(abs(d) - 180) <= BACK_DEG else 'side'

base = json.load(open(sys.argv[1]))
src = Image.open(io.BytesIO(base64.b64decode(base['textures'][0]['source'].split(',', 1)[1]))).convert('RGBA')
tex = Image.new('RGBA', (CW, CH), (0, 0, 0, 0))
tex.paste(src, (0, 0))
draw = ImageDraw.Draw(tex)

# texture: outer layer gets real strips (one per tier and part), the inner layer one flat lining swatch
strips, y = {}, 64
for tier in ('outer_hip', 'outer_hem'):
    h = (HIP if tier.endswith('hip') else HEM)['length'] * VS
    x = 0
    for part in ('side', 'front', 'back'):
        ps = [p for p in panels if p['layer'] == tier and part_of(p['phi']) == part]
        w = SLICE * len(ps)
        col = (205, 205, 212) if tier.endswith('hip') else (180, 180, 190)
        if part == 'front':
            col = (col[0] + 10, col[1] + 4, col[2] - 12)
        draw.rectangle([x, y, x + w - 1, y + h - 1], fill=col + (255,))
        for k in range(len(ps)):
            draw.line([x + k * SLICE, y, x + k * SLICE, y + h - 1], fill=tuple(c - 25 for c in col) + (255,))
        strips[(tier, part)] = [x, y]
        x += w + 1
    assert x <= CW + 1, x
    y += int(h) + 1
LINING = (y, (120, 116, 128))
draw.rectangle([0, y, 3, y + 3], fill=LINING[1] + (255,))
assert y + 4 <= CH

groups, elements = base['groups'], base['elements']
uid = lambda: str(uuid.uuid4())
cursor = {}

def panel(p):
    phi = math.radians(p['phi'])
    px, pz = p['rx'] * math.sin(phi), -p['rz'] * math.cos(phi)
    nx, nz = math.sin(phi) / p['rx'], -math.cos(phi) / p['rz']
    yaw = math.degrees(math.atan2(-nx, -nz))
    w, L, top = p['width'], p['length'], p['top']
    faces = {k: {'uv': [0, 0, 0, 0], 'texture': None} for k in ('north', 'east', 'south', 'west', 'up', 'down')}
    part = part_of(p['phi'])
    if p['layer'].startswith('outer'):
        u0, v0 = strips[(p['layer'], part)]
        u = u0 + cursor.get((p['layer'], part), 0)
        cursor[(p['layer'], part)] = cursor.get((p['layer'], part), 0) + SLICE
        faces['north'] = {'uv': [u, v0, u + SLICE, v0 + L * VS], 'texture': 0}
        faces['south'] = {'uv': [0, LINING[0], 4, LINING[0] + 4], 'texture': 0}   # inside: lining color
    else:
        for f in ('north', 'south'):
            faces[f] = {'uv': [0, LINING[0], 4, LINING[0] + 4], 'texture': 0}
    e = {'name': f"{p['layer']}_{int(round(p['phi']))}", 'box_uv': False, 'render_order': 'default', 'locked': False,
         'export': True, 'allow_mirror_modeling': True, 'from': [round(px - w / 2, 4), round(top - L, 4), round(pz, 4)],
         'to': [round(px + w / 2, 4), round(top, 4), round(pz, 4)], 'autouv': 0, 'color': 3,
         'origin': [round(px, 4), round(top, 4), round(pz, 4)], 'rotation': [p['tilt'], round(yaw, 2), 0],
         'faces': faces, 'type': 'cube', 'uuid': uid()}
    elements.append(e)
    return e['uuid'], part

def group(name, origin, children):
    g = {'name': name, 'uuid': uid(), 'export': True, 'locked': False, 'scope': 0, 'selected': False, 'visibility': True,
         'origin': origin, 'rotation': [0, 0, 0], 'color': 3, 'reset': False, 'shade': True, 'mirror_uv': False,
         'autouv': 0, 'isOpen': False}
    groups.append(g)
    return {'uuid': g['uuid'], 'isOpen': False, 'children': children}

kids = {'front': [], 'back': [], 'outer': [], 'inner': []}
for p in panels:
    u, part = panel(p)
    kids[part if part != 'side' else p['layer'].split('_')[0]].append(u)
nodes = [group('skirt_inner', [0, WAIST_Y, 0], kids['inner']), group('skirt_outer', [0, WAIST_Y, 0], kids['outer']),
         group('skirt_front', [0, WAIST_Y, -WAIST_RZ], kids['front']), group('skirt_back', [0, WAIST_Y, WAIST_RZ], kids['back'])]
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
buf = io.BytesIO(); tex.save(buf, 'PNG')
t = base['textures'][0]
t.update({'name': 'skirt_bell.png', 'width': CW, 'height': CH, 'uv_width': CW, 'uv_height': CH,
          'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()})
base['resolution'] = {'width': CW, 'height': CH}
base['meta']['box_uv'] = False
json.dump(base, open(sys.argv[2], 'w'))
print({k: len(v) for k, v in kids.items()})
