"""Put a standard 64x64 Minecraft skin (slim arms) onto the base rig: the skin goes in the texture's top-left corner,
every body cube reads its part of the skin (12-tall limbs and torso split at the joints: top 6 rows upper, bottom 6
lower), and an outer-layer cube is added for each (hat, jacket, sleeves, pants) reading the skin's second layer.
usage: map_skin.py rig.bbmodel skin.png out.bbmodel"""
import base64, io, json, sys, uuid
from PIL import Image

rig_path, skin_path, out_path = sys.argv[1:4]
m = json.load(open(rig_path))
skin = Image.open(skin_path).convert('RGBA')
assert skin.size == (64, 64), skin.size
t = m['textures'][0]
tex = Image.open(io.BytesIO(base64.b64decode(t['source'].split(',', 1)[1]))).convert('RGBA')
tex.paste(Image.new('RGBA', (64, 64), (0, 0, 0, 0)), (0, 0))  # clear the placeholder template
tex.paste(skin, (0, 0))
buf = io.BytesIO(); tex.save(buf, 'PNG')
t['source'] = 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()

def faces(u, v, w, h, d, rows=None):
    """Box-UV faces of a w*h*d cube at (u, v), optionally only rows [r0, r1) of its height (for split limbs)."""
    r0, r1 = rows or (0, h)
    side = lambda x0, x1: [x0, v + d + r0, x1, v + d + r1]
    f = {
        'north': {'uv': side(u + d, u + d + w)},
        'east': {'uv': side(u, u + d)},
        'south': {'uv': side(u + 2 * d + w, u + 2 * d + 2 * w)},
        'west': {'uv': side(u + d + w, u + 2 * d + w)},
    }
    # the cube's own top/bottom, or a 1-row strip of the front at a joint (so a bent elbow/knee shows clothing)
    f['up'] = {'uv': [u + d + w, v + d, u + d, v]} if r0 == 0 else {'uv': [u + d, v + d + r0, u + d + w, v + d + r0 + 1]}
    f['down'] = {'uv': [u + d + 2 * w, v, u + d + w, v + d]} if r1 == h else {'uv': [u + d, v + d + r1 - 1, u + d + w, v + d + r1]}
    for x in f.values():
        x['texture'] = 0
    return f

# part: (base layer offset, outer layer offset, size w h d, rows, outer inflate)
TOP, LOW = (0, 6), (6, 12)
PARTS = {
    'head': ((0, 0), (32, 0), (8, 8, 8), None, 0.5),
    'chest': ((16, 16), (16, 32), (8, 12, 4), TOP, 0.25),
    'body': ((16, 16), (16, 32), (8, 12, 4), LOW, 0.25),
    'right_arm': ((40, 16), (40, 32), (3, 12, 4), TOP, 0.25),
    'right_forearm': ((40, 16), (40, 32), (3, 12, 4), LOW, 0.25),
    'left_arm': ((32, 48), (48, 48), (3, 12, 4), TOP, 0.25),
    'left_forearm': ((32, 48), (48, 48), (3, 12, 4), LOW, 0.25),
    'right_leg': ((0, 16), (0, 32), (4, 12, 4), TOP, 0.25),
    'right_shin': ((0, 16), (0, 32), (4, 12, 4), LOW, 0.25),
    'left_leg': ((16, 48), (0, 48), (4, 12, 4), TOP, 0.25),
    'left_shin': ((16, 48), (0, 48), (4, 12, 4), LOW, 0.25),
}
groups = {g['uuid']: g for g in m['groups']}
elements = {e['uuid']: e for e in m['elements']}
added = 0

def walk(nodes):
    global added
    for n in nodes:
        if not isinstance(n, dict):
            continue
        g = groups[n['uuid']]
        part = PARTS.get(g['name'])
        if part:
            (bu, bv), (ou, ov), (w, h, d), rows, inflate = part
            cube = next(elements[c] for c in n['children'] if isinstance(c, str) and elements[c]['name'] == g['name'])
            cube['box_uv'] = False
            cube.pop('uv_offset', None)
            cube['faces'] = faces(bu, bv, w, h, d, rows)
            layer = json.loads(json.dumps(cube))
            layer.update({'name': g['name'] + '_layer', 'uuid': str(uuid.uuid4()), 'inflate': inflate,
                          'faces': faces(ou, ov, w, h, d, rows)})
            m['elements'].append(layer)
            n['children'].insert(n['children'].index(cube['uuid']) + 1, layer['uuid'])
            added += 1
        walk(n.get('children', []))

walk(m['outliner'])
m['meta']['box_uv'] = False
json.dump(m, open(out_path, 'w'))
print('parts mapped', added)
