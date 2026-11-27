"""Mark 2 starter: the shared rig's skeleton at double size, a see-through stand-in body, red joint markers, and the
rig file's animations (positions doubled), with none of Mark 1's cubes. Usage: make_mark2_starter.py RIG.bbmodel OUT.bbmodel"""
import json, sys, uuid, base64, io, copy
from PIL import Image

SCALE = 2.0
KEEP = ['root', 'body', 'chest', 'head', 'right_arm', 'right_forearm', 'right_hand', 'left_arm', 'left_forearm',
        'left_hand', 'right_leg', 'right_shin', 'left_leg', 'left_shin']

src = json.load(open(sys.argv[1]))
G = {g['uuid']: g for g in src['groups']}
by_name = {g['name']: g for g in src['groups']}
keep_ids = {by_name[n]['uuid'] for n in KEEP}

# Texture: four see-through colours for the stand-in and solid red for the joints (4x4 swatches on 16x16).
SW = {'head': ((170, 190, 230, 80), 0, 0), 'torso': ((200, 170, 230, 80), 4, 0), 'arm': ((150, 215, 180, 80), 8, 0),
      'leg': ((235, 200, 150, 80), 12, 0), 'joint': ((235, 40, 40, 255), 0, 4)}
img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
for col, x, y in SW.values():
    for i in range(4):
        for j in range(4):
            img.putpixel((x + i, y + j), col)
buf = io.BytesIO(); img.save(buf, 'PNG')
tex = copy.deepcopy(src['textures'][0])
tex.update({'name': 'standin.png', 'id': '0', 'width': 16, 'height': 16, 'uv_width': 16, 'uv_height': 16,
            'uuid': str(uuid.uuid4()), 'force_translucent': True, 'path': '', 'relative_path': '', 'saved': False,
            'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()})
tex.pop('group', None)

def cube(name, frm, to, swatch, origin=(0, 0, 0)):
    _, u, v = SW[swatch]
    face = {'uv': [u + 0.5, v + 0.5, u + 3.5, v + 3.5], 'texture': 0}
    return {'name': name, 'box_uv': False, 'render_order': 'default', 'locked': False, 'export': True, 'scope': 0,
            'allow_mirror_modeling': True, 'from': list(frm), 'to': list(to), 'autouv': 0, 'color': 0,
            'origin': list(origin), 'type': 'cube', 'uuid': str(uuid.uuid4()),
            'faces': {f: dict(face) for f in ('north', 'east', 'south', 'west', 'up', 'down')}}

def joint(name, p):
    return cube('joint_' + name, (p[0] - 1, p[1] - 1, p[2] - 1), (p[0] + 1, p[1] + 1, p[2] + 1), 'joint')

# Stand-in body (Minecraft slim proportions, doubled) and a marker on each joint, in the bone that bends there.
parts = {
    'head': [cube('standin_head', (-8, 48, -8), (8, 64, 8), 'head'), joint('neck', (0, 48, 0))],
    'chest': [cube('standin_chest', (-8, 36, -4), (8, 48, 4), 'torso'), joint('chest', (0, 36, 0))],
    'body': [cube('standin_waist', (-8, 24, -4), (8, 36, 4), 'torso'), joint('waist', (0, 24, 0))],
    'right_arm': [cube('standin_right_upper_arm', (8, 36, -4), (14, 48, 4), 'arm'), joint('right_shoulder', (10, 44, 0))],
    'right_forearm': [cube('standin_right_forearm', (8, 24, -4), (14, 36, 4), 'arm'), joint('right_elbow', (11, 36, 0))],
    'right_hand': [joint('right_wrist', (11, 24, 0))],
    'left_arm': [cube('standin_left_upper_arm', (-14, 36, -4), (-8, 48, 4), 'arm'), joint('left_shoulder', (-10, 44, 0))],
    'left_forearm': [cube('standin_left_forearm', (-14, 24, -4), (-8, 36, 4), 'arm'), joint('left_elbow', (-11, 36, 0))],
    'left_hand': [joint('left_wrist', (-11, 25, 0))],
    'right_leg': [cube('standin_right_thigh', (0, 12, -4), (8, 24, 4), 'leg'), joint('right_hip', (3.8, 24, 0))],
    'right_shin': [cube('standin_right_shin', (0, 0, -4), (8, 12, 4), 'leg'), joint('right_knee', (3.8, 12, 0))],
    'left_leg': [cube('standin_left_thigh', (-8, 12, -4), (0, 24, 4), 'leg'), joint('left_hip', (-3.8, 24, 0))],
    'left_shin': [cube('standin_left_shin', (-8, 0, -4), (0, 12, 4), 'leg'), joint('left_knee', (-3.8, 12, 0))],
}

elements = []
groups = []
for g in src['groups']:
    if g['uuid'] in keep_ids:
        g = copy.deepcopy(g)
        g['origin'] = [c * SCALE for c in g['origin']]
        g['visibility'] = True
        g['export'] = True
        groups.append(g)

def rebuild(nodes):
    out = []
    for n in nodes:
        if isinstance(n, dict) and n['uuid'] in keep_ids:
            name = G[n['uuid']]['name']
            mine = parts.get(name, [])
            elements.extend(mine)
            out.append({'uuid': n['uuid'], 'isOpen': True,
                        'children': [e['uuid'] for e in mine] + rebuild(n.get('children', []))})
    return out

outliner = rebuild(src['outliner'])

def scaled(v):
    try:
        return str(round(float(v) * SCALE, 4))
    except (TypeError, ValueError):
        return f'({v})*{SCALE:g}' if str(v).strip() else v

animations = []
for a in src['animations']:
    a = copy.deepcopy(a)
    kept = {}
    for key, an in a['animators'].items():
        if key == 'effects' or key in keep_ids:
            for k in an.get('keyframes', []):
                if k['channel'] == 'position':
                    for dp in k['data_points']:
                        for c in 'xyz':
                            if c in dp:
                                dp[c] = scaled(dp[c])
            kept[key] = an
    a['animators'] = kept
    a['saved'] = False
    animations.append(a)

out = copy.deepcopy(src)
out.update({'name': 'mark2_starter', 'model_identifier': 'mark2', 'resolution': {'width': 16, 'height': 16},
            'visible_box': [c * SCALE for c in src['visible_box']], 'elements': elements, 'groups': groups,
            'outliner': outliner, 'textures': [tex], 'texture_groups': [], 'animations': animations,
            'geckolib_filepath_cache': {}})
json.dump(out, open(sys.argv[2], 'w'))
print('bones', len(groups), 'cubes', len(elements), 'animations', len(animations),
      'animators kept', sum(len(a['animators']) for a in animations))
