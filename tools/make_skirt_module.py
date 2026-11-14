"""A plain, generic two-layer skirt rig on the girl base rig, built the "ring of narrow fanned panels" way: each
layer is a ring of narrow flat planes around the hips, each turned to face outward and tilted out at the hem, slightly
overlapping, so together they read as a smooth bell that's narrow at the waist and wide at the hem. The inner layer is
shorter and offset by half a panel so it fills the outer layer's hem gaps. Front panels (both layers) are in
skirt_front, back panels in skirt_back (they follow the legs in code), the rest in skirt_inner / skirt_outer.
Placeholder neutral textures, one strip per group per layer (outside and a darker inside), in ONE texture."""
import base64, io, json, math, sys, uuid
from PIL import Image, ImageDraw

base = json.load(open(sys.argv[1]))
out_path = sys.argv[2]
uid = lambda: str(uuid.uuid4())

LAYERS = {
    # name: panels, hip radii (x, z), top y, length, hem flare (deg), width overlap, angle offset (fraction of a step)
    'inner': dict(n=12, rx=4.6, rz=2.8, top=13.0, length=6.0, flare=14, overlap=1.25, offset=0.5, short=0.0),
    'outer': dict(n=14, rx=5.1, rz=3.3, top=13.5, length=9.0, flare=24, overlap=1.3, offset=0.0, short=1.0),
}
import os
PX = int(os.environ.get('PX', '1'))           # texture pixels per UV unit (1 = classic skin pixels)
SLICE = float(os.environ.get('SLICE', '3'))   # UV width every panel gets in the texture (whole pixels)
FRONT_DEG, BACK_DEG = 40, 40  # panels within this many degrees of straight front / back go in skirt_front / skirt_back

def part_of(phi):
    d = (phi + 180) % 360 - 180
    if abs(d) <= FRONT_DEG:
        return 'front'
    if abs(abs(d) - 180) <= BACK_DEG:
        return 'back'
    return 'rest'

# panel list with widths first, to size the texture strips
panels = []
for layer, p in LAYERS.items():
    perim = 2 * math.pi * math.sqrt((p['rx'] ** 2 + p['rz'] ** 2) / 2)
    width = perim / p['n'] * p['overlap']
    for i in range(p['n']):
        phi = (i + p['offset']) * 360.0 / p['n']
        length = p['length'] - (p['short'] if i % 2 else 0.0)  # staggered hem on the outer layer
        panels.append(dict(layer=layer, i=i, phi=phi, width=round(width, 3), length=length, part=part_of(phi), **{k: p[k] for k in ('rx', 'rz', 'top', 'flare')}))

# --- texture: the base rig's 64x64 on top, skirt strips below (64x128) --------------------------------------------
src = Image.open(io.BytesIO(base64.b64decode(base['textures'][0]['source'].split(',', 1)[1]))).convert('RGBA')
tex = Image.new('RGBA', (64 * PX, 128 * PX), (0, 0, 0, 0))
tex.paste(src.resize((64 * PX, 64 * PX), Image.NEAREST), (0, 0))
draw = ImageDraw.Draw(tex)
COLORS = {('inner', 'out'): (205, 205, 210), ('inner', 'in'): (150, 150, 155),
          ('outer', 'out'): (170, 170, 178), ('outer', 'in'): (118, 118, 126)}
strips, y = {}, 64
for layer in ('inner', 'outer'):
    h = max(pp['length'] for pp in panels if pp['layer'] == layer)
    for side in ('out', 'in'):
        x = 0
        for part in ('rest', 'front', 'back'):
            ps = [pp for pp in panels if pp['layer'] == layer and pp['part'] == part]
            w = SLICE * len(ps)
            strips[(layer, part, side)] = [x, y]
            col = COLORS[(layer, side)]
            if part == 'front':
                col = tuple(min(255, c + 12) for c in col[:2]) + (col[2] - 10,)  # front a touch warmer
            draw.rectangle([x * PX, y * PX, (x + w) * PX - 1, (y + h) * PX - 1], fill=col + (255,))
            for k in range(len(ps)):  # a faint line at each panel edge, so seams are easy to see while painting
                u = (x + k * SLICE) * PX
                draw.line([u, y * PX, u, (y + h) * PX - 1], fill=tuple(c - 25 for c in col[:3]) + (255,))
            x += w + 1
        assert x <= 65, (layer, side, x)
        y += h + 1
assert y <= 128

groups, elements = base['groups'], base['elements']

def group(name, origin, children):
    g = {'name': name, 'uuid': uid(), 'export': True, 'locked': False, 'scope': 0, 'selected': False, 'visibility': True,
         'origin': origin, 'rotation': [0, 0, 0], 'color': 3, 'reset': False, 'shade': True, 'mirror_uv': False,
         'autouv': 0, 'isOpen': False}
    groups.append(g)
    return {'uuid': g['uuid'], 'isOpen': False, 'children': children}

cursor = {}
def panel(pp):
    """One flat plane: built facing front at its spot on the hip ellipse, tilted out at the hem (X) and turned to
    face outward (Y), both about its own top edge so it stays attached at the waist."""
    phi = math.radians(pp['phi'])
    px, pz = pp['rx'] * math.sin(phi), -pp['rz'] * math.cos(phi)
    nx, nz = math.sin(phi) / pp['rx'], -math.cos(phi) / pp['rz']
    yaw = math.degrees(math.atan2(-nx, -nz))
    w, L, top = pp['width'], pp['length'], pp['top']
    frm, to = [px - w / 2, top - L, pz], [px + w / 2, top, pz]
    key = (pp['layer'], pp['part'])
    faces = {k: {'uv': [0, 0, 0, 0], 'texture': None} for k in ('north', 'east', 'south', 'west', 'up', 'down')}
    for side, face in (('out', 'north'), ('in', 'south')):
        u0, v0 = strips[(pp['layer'], pp['part'], side)]
        u = u0 + cursor.get(key, 0.0)
        faces[face] = {'uv': [u, v0, u + SLICE, v0 + L] if side == 'out' else [u + SLICE, v0, u, v0 + L], 'texture': 0}
    cursor[key] = cursor.get(key, 0.0) + SLICE
    e = {'name': f"{pp['layer']}_{pp['part']}_{pp['i']}", 'box_uv': False, 'render_order': 'default', 'locked': False,
         'export': True, 'allow_mirror_modeling': True, 'from': [round(v, 4) for v in frm], 'to': [round(v, 4) for v in to],
         'autouv': 0, 'color': 3, 'origin': [round(px, 4), top, round(pz, 4)],
         'rotation': [pp['flare'], round(yaw, 2), 0], 'faces': faces, 'type': 'cube', 'uuid': uid()}
    elements.append(e)
    return e['uuid']

children = {(l, p): [] for l in LAYERS for p in ('front', 'back', 'rest')}
for pp in panels:
    children[(pp['layer'], pp['part'])].append(panel(pp))
inner = group('skirt_inner', [0, 13.0, 0], children[('inner', 'rest')])
outer = group('skirt_outer', [0, 13.5, 0], children[('outer', 'rest')])
front = group('skirt_front', [0, 13.0, -2.8], children[('inner', 'front')] + children[('outer', 'front')])
back = group('skirt_back', [0, 13.0, 2.8], children[('inner', 'back')] + children[('outer', 'back')])

skirt_uuid = next(g['uuid'] for g in groups if g['name'] == 'skirt')
for name in ('skirt_front', 'skirt_back'):  # the base rig's empty placeholders
    old = [g for g in groups if g['name'] == name]
    if len(old) > 1:
        groups.remove(old[0])
def place(nodes):
    for n in nodes:
        if isinstance(n, dict):
            if n['uuid'] == skirt_uuid:
                n['children'] = [inner, outer, front, back]
                return True
            if place(n.get('children', [])):
                return True
    return False
assert place(base['outliner'])

buf = io.BytesIO(); tex.save(buf, 'PNG')
t = base['textures'][0]
t.update({'name': 'skirt_module.png', 'width': 64 * PX, 'height': 128 * PX, 'uv_width': 64, 'uv_height': 128,
          'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()})
base['resolution'] = {'width': 64, 'height': 128}
base['name'] = 'skirt_module_girl'
json.dump(base, open(out_path, 'w'))
print({k: len(v) for k, v in children.items()}, 'strip rows end at', y)
