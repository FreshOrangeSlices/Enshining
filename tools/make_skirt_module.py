"""A plain, generic two-layer skirt rig on the girl base rig: flat flared panels (inner layer, outer layer), with the
front panels in skirt_front and the back panels in skirt_back (so they follow the legs in code), sides in
skirt_inner / skirt_outer. Placeholder neutral textures in clearly separated regions of ONE texture."""
import base64, io, json, sys, uuid
from PIL import Image, ImageDraw

base = json.load(open(sys.argv[1]))
out_path = sys.argv[2]
uid = lambda: str(uuid.uuid4())

# --- texture: keep the base rig's 64x64 on top, add a 64x64 skirt area below (64x128 total) -------------------------
src = Image.open(io.BytesIO(base64.b64decode(base['textures'][0]['source'].split(',', 1)[1]))).convert('RGBA')
tex = Image.new('RGBA', (64, 128), (0, 0, 0, 0))
tex.paste(src, (0, 0))
draw = ImageDraw.Draw(tex)
REGIONS = {}  # name -> (u, v, w, h)

def region(name, u, v, w, h, color):
    REGIONS[name] = (u, v, w, h)
    draw.rectangle([u, v, u + w - 1, v + h - 1], fill=color)
    # faint seam lines every 2 px so folds are easy to paint over
    for x in range(u, u + w, 2):
        draw.line([x, v, x, v + h - 1], fill=tuple(max(0, c - 12) for c in color[:3]) + (255,))

# Inner layer (light), outer layer (mid), front panels (warm): outside faces, then darker inside faces.
region('inner_side', 0, 64, 5, 6, (205, 205, 205, 255)); region('inner_side_in', 5, 64, 5, 6, (150, 150, 150, 255))
region('inner_back', 10, 64, 9, 6, (205, 205, 205, 255)); region('inner_back_in', 19, 64, 9, 6, (150, 150, 150, 255))
region('outer_side', 0, 72, 7, 8, (170, 170, 175, 255)); region('outer_side_in', 7, 72, 7, 8, (120, 120, 125, 255))
region('outer_back', 14, 72, 11, 8, (170, 170, 175, 255)); region('outer_back_in', 25, 72, 11, 8, (120, 120, 125, 255))
region('inner_front', 0, 84, 9, 6, (215, 200, 185, 255)); region('inner_front_in', 9, 84, 9, 6, (160, 148, 136, 255))
region('outer_front', 18, 84, 11, 8, (200, 185, 170, 255)); region('outer_front_in', 29, 84, 11, 8, (148, 136, 124, 255))

groups, elements = base['groups'], base['elements']

def group(name, origin, children=()):
    g = {'name': name, 'uuid': uid(), 'export': True, 'locked': False, 'scope': 0, 'selected': False, 'visibility': True,
         'origin': origin, 'rotation': [0, 0, 0], 'color': 3, 'reset': False, 'shade': True, 'mirror_uv': False,
         'autouv': 0, 'isOpen': True}
    groups.append(g)
    return {'uuid': g['uuid'], 'isOpen': True, 'children': list(children)}

def panel(name, frm, to, origin, rot, outside, inside, facing):
    """A flat plane (one size 0). 'facing' = the face pointing away from her body: north/south/east/west."""
    opposite = {'north': 'south', 'south': 'north', 'east': 'west', 'west': 'east'}[facing]
    faces = {k: {'uv': [0, 0, 0, 0], 'texture': None} for k in ('north', 'east', 'south', 'west', 'up', 'down')}
    u, v, w, h = REGIONS[outside]
    faces[facing] = {'uv': [u, v, u + w, v + h], 'texture': 0}
    u, v, w, h = REGIONS[inside]
    faces[opposite] = {'uv': [u + w, v, u, v + h], 'texture': 0}  # mirrored so it reads right from inside
    e = {'name': name, 'box_uv': False, 'render_order': 'default', 'locked': False, 'export': True,
         'allow_mirror_modeling': True, 'from': frm, 'to': to, 'autouv': 0, 'color': 3, 'origin': origin,
         'rotation': rot, 'faces': faces, 'type': 'cube', 'uuid': uid()}
    elements.append(e)
    return e['uuid']

TOP_I, LEN_I, Z_I, X_I, FLARE_I = 13.0, 6.0, 2.3, 4.3, 10   # inner: hugs the hips, mid-thigh
TOP_O, LEN_O, Z_O, X_O, FLARE_O = 13.5, 8.0, 3.0, 5.0, 18   # outer: a bit out, longer, more flare
bi, bo = TOP_I - LEN_I, TOP_O - LEN_O

front = group('skirt_front', [0, TOP_I, -Z_I], [
    panel('inner_front', [-X_I, bi, -Z_I], [X_I, TOP_I, -Z_I], [0, TOP_I, -Z_I], [FLARE_I, 0, 0], 'inner_front', 'inner_front_in', 'north'),
    panel('outer_front', [-X_O - 0.2, bo, -Z_O], [X_O + 0.2, TOP_O, -Z_O], [0, TOP_O, -Z_O], [FLARE_O, 0, 0], 'outer_front', 'outer_front_in', 'north'),
])
back = group('skirt_back', [0, TOP_I, Z_I], [
    panel('inner_back', [-X_I, bi, Z_I], [X_I, TOP_I, Z_I], [0, TOP_I, Z_I], [-FLARE_I, 0, 0], 'inner_back', 'inner_back_in', 'south'),
    panel('outer_back', [-X_O - 0.2, bo, Z_O], [X_O + 0.2, TOP_O, Z_O], [0, TOP_O, Z_O], [-FLARE_O, 0, 0], 'outer_back', 'outer_back_in', 'south'),
])
inner = group('skirt_inner', [0, TOP_I, 0], [
    panel('inner_right', [X_I, bi, -Z_I], [X_I, TOP_I, Z_I], [X_I, TOP_I, 0], [0, 0, FLARE_I], 'inner_side', 'inner_side_in', 'east'),
    panel('inner_left', [-X_I, bi, -Z_I], [-X_I, TOP_I, Z_I], [-X_I, TOP_I, 0], [0, 0, -FLARE_I], 'inner_side', 'inner_side_in', 'west'),
])
outer = group('skirt_outer', [0, TOP_O, 0], [
    panel('outer_right', [X_O, bo, -Z_O - 0.2], [X_O, TOP_O, Z_O + 0.2], [X_O, TOP_O, 0], [0, 0, FLARE_O], 'outer_side', 'outer_side_in', 'east'),
    panel('outer_left', [-X_O, bo, -Z_O - 0.2], [-X_O, TOP_O, Z_O + 0.2], [-X_O, TOP_O, 0], [0, 0, -FLARE_O], 'outer_side', 'outer_side_in', 'west'),
])

# Put the module inside the rig's (empty) skirt group.
skirt_uuid = next(g['uuid'] for g in groups if g['name'] == 'skirt')
def place(nodes):
    for n in nodes:
        if isinstance(n, dict):
            if n['uuid'] == skirt_uuid:
                n['children'] = [inner, outer, front, back]
                return True
            if place(n.get('children', [])):
                return True
    return False
# the rig already had empty skirt_front / skirt_back groups: drop them
for name in ('skirt_front', 'skirt_back'):
    old = [g for g in groups if g['name'] == name]
    if len(old) > 1:
        groups.remove(old[0])
assert place(base['outliner'])

buf = io.BytesIO(); tex.save(buf, 'PNG')
t = base['textures'][0]
t.update({'name': 'skirt_module.png', 'height': 128, 'uv_height': 128,
          'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()})
base['resolution'] = {'width': 64, 'height': 128}
base['name'] = 'skirt_module_girl'
json.dump(base, open(out_path, 'w'))
print('panels', sum(1 for e in elements if e['name'].startswith(('inner', 'outer'))), 'groups', [g['name'] for g in groups if 'skirt' in g['name']])
