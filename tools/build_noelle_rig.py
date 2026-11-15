"""Move Noelle onto the shared girl rig (one-off migration).

Takes the rig with the A-line skirt (skirt on its own texture), puts the user's new skin on it (map_skin.py), carries
over the user's pieces from the old model (claymore, headdress, roses, neck rose, chest plate, pauldron, belt, bow),
copies the claymore to "weapon_back" (hovering behind her), keeps the old joint pivots the animations were made on,
and embeds the current game animations so they can be edited in Blockbench.

usage: build_noelle_rig.py skirt_rig.bbmodel skin.png old_model.bbmodel game_animations.json out.bbmodel clean_anims.json
"""
import base64, copy, io, json, math, subprocess, sys, uuid
from PIL import Image

rig_path, skin_path, old_path, anim_path, out_path, clean_anim_path = sys.argv[1:7]
subprocess.run([sys.executable, 'map_skin.py', rig_path, skin_path, '/tmp/skinned.bbmodel'], check=True)
m = json.load(open('/tmp/skinned.bbmodel'))
old = json.load(open(old_path))
uid = lambda: str(uuid.uuid4())


def index(d):
    groups = {g['uuid']: g for g in d['groups']}
    nodes = {}

    def walk(ns):
        for n in ns:
            if isinstance(n, dict):
                nodes[groups[n['uuid']]['name']] = (n, groups[n['uuid']])
                walk(n['children'])
    walk(d['outliner'])
    return nodes, {e['uuid']: e for e in d['elements']}


new_nodes, new_el = index(m)
old_nodes, old_el = index(old)

# ---- old joint pivots (the animations were made on these; all within half a pixel of the rig's own) ----------------
for name in ('root', 'right_arm', 'left_arm', 'right_forearm', 'left_forearm', 'right_hand', 'right_leg', 'left_leg',
             'right_shin', 'left_shin', 'body', 'chest', 'head'):
    o = old_nodes[name][1]['origin']
    g = new_nodes[name][1]
    assert name == 'root' or all(abs(a - b) <= 1.0 for a, b in zip(o, g['origin'])), name
    g['origin'] = list(o)

used_rects = []


def take_cube(e, offset=(0, 0, 0)):
    c = copy.deepcopy(e)
    c['uuid'] = uid()
    for k in ('from', 'to', 'origin'):
        c[k] = [round(c[k][i] + offset[i], 4) for i in range(3)]
    for f in c['faces'].values():
        if f.get('texture') is not None:
            f['texture'] = 0
            if not c.get('box_uv'):
                u0, v0, u1, v1 = f['uv']
                used_rects.append((min(u0, u1), min(v0, v1), max(u0, u1), max(v0, v1)))
    if c.get('box_uv'):   # box-uv cube: reads a (2d+2w) x (d+h) block from its uv offset
        w, h, d = (math.ceil(c['to'][i] - c['from'][i]) for i in range(3))
        u, v = c.get('uv_offset', [0, 0])
        used_rects.append((u, v, u + 2 * (d + w), v + d + h))
    m['elements'].append(c)
    return c['uuid']


def take_group(old_name, rename=None):
    """A copy of an old group (its cubes and sub-groups), under new uuids."""
    on, og = old_nodes[old_name]
    g = copy.deepcopy(og)
    g['uuid'] = uid()
    g['name'] = (rename or {}).get(og['name'], og['name'])
    m['groups'].append(g)
    kids = []
    for c in on['children']:
        if isinstance(c, str):
            kids.append(take_cube(old_el[c]))
        else:
            kids.append(take_group(next(x['name'] for x in old['groups'] if x['uuid'] == c['uuid']), rename))
    return {'uuid': g['uuid'], 'isOpen': False, 'children': kids}


def put(parent, node):
    new_nodes[parent][0]['children'].append(node)


tails = {'bow_tail_left': 'flow_bow_tail_left', 'bow_tail_right': 'flow_bow_tail_right'}
for name, parent in (('headdress', 'head_extras'), ('roses', 'head_extras'), ('neck_rose', 'chest_extras'),
                     ('chest_plate', 'chest_extras'), ('pauldron', 'left_arm_extras'), ('belt', 'waist_extras'),
                     ('bow', 'waist_extras')):
    put(parent, take_group(name, tails))

# ---- the claymore in her hand: same place relative to the hand as before ------------------------------------------
wn, wg = new_nodes['weapon']
wg['origin'] = list(old_nodes['weapon'][1]['origin'])
claymore = [old_el[c] for c in old_nodes['weapon'][0]['children'] if isinstance(c, str)]
wn['children'] = [take_cube(e) for e in claymore] + [c for c in wn['children'] if isinstance(c, dict)]

# ---- a copy hovering behind her: hilt over her right shoulder, blade down across her back, flat side out ----------
GRIP = old_nodes['weapon'][1]['origin']          # the fist, in the claymore's own coordinates
HOVER_AT = [3.5, 27.5, 7.0]                       # where that grip floats: behind the right shoulder
TILT = -28                                          # degrees off vertical, tip toward her left
bn, bg = new_nodes['weapon_back']
off = [HOVER_AT[i] - GRIP[i] for i in range(3)]
model = {'name': 'weapon_back_model', 'uuid': uid(), 'export': True, 'locked': False, 'scope': 0, 'selected': False,
         'visibility': True, 'origin': list(HOVER_AT), 'rotation': [-90, 90, TILT], 'color': 0, 'reset': False,
         'shade': True, 'mirror_uv': False, 'autouv': 0, 'isOpen': False}
m['groups'].append(model)
bn['children'].append({'uuid': model['uuid'], 'isOpen': False, 'children': [take_cube(e, off) for e in claymore]})

# ---- texture: the new skin (top-left), the old pieces' pixels where their faces read them -------------------------
tex = m['textures'][0]
body = Image.open(io.BytesIO(base64.b64decode(tex['source'].split(',', 1)[1]))).convert('RGBA')
otex = Image.open(io.BytesIO(base64.b64decode(old['textures'][0]['source'].split(',', 1)[1]))).convert('RGBA')
assert otex.size == body.size == (128, 256)
for u0, v0, u1, v1 in used_rects:
    box = (int(math.floor(u0)), int(math.floor(v0)), int(math.ceil(u1)), int(math.ceil(v1)))
    if box[2] <= 64 and box[3] <= 64:
        raise SystemExit(f'old piece reads the skin area {box}')
    body.paste(otex.crop(box), box[:2])
buf = io.BytesIO()
body.save(buf, 'PNG')
tex['source'] = 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()
tex['name'] = 'noelle.png'
m['textures'][1]['name'] = 'noelle_skirt.png'

# ---- animations: the game's current ones, minus the old skirt bones; embedded for editing ------------------------
anims = json.load(open(anim_path))
for an in anims['animations'].values():
    for gone in ('SkirtFront', 'skirt', 'skirt_mid', 'skirt_hem'):
        an['bones'].pop(gone, None)
json.dump(anims, open(clean_anim_path, 'w'), indent='\t')


def vec(v):
    return v['vector'] if isinstance(v, dict) and 'vector' in v else v


def to_bb(ch, v):
    v = [float(x) for x in vec(v)]
    if ch in ('rotation', 'position'):
        v[0] = -v[0]
    if ch == 'rotation':
        v[1] = -v[1]
    return {'x': v[0], 'y': v[1], 'z': v[2]}


m['animations'] = []
for name, an in anims['animations'].items():
    animators = {}
    for bone, chans in an['bones'].items():
        g = new_nodes[bone][1]
        keys = []
        for ch, ks in chans.items():
            if not isinstance(ks, dict):
                ks = {'0': ks}
            times = sorted(ks, key=float)
            for i, t in enumerate(times):
                e = ks[t]
                interp = 'linear'
                if isinstance(e, dict) and 'post' in e:
                    if e.get('lerp_mode') == 'catmullrom':
                        interp = 'catmullrom'
                        assert 'pre' not in e or vec(e['pre']) == vec(e['post']), (name, bone, t)
                    elif 'pre' in e:      # a step: the key before holds until this one
                        keys[-1]['interpolation'] = 'step'
                    e = e['post']
                assert not (isinstance(e, dict) and 'easing' in e), 'easing keys not handled'
                keys.append({'channel': ch, 'data_points': [to_bb(ch, e)], 'uuid': uid(), 'time': float(t),
                             'color': -1, 'interpolation': interp})
        animators[g['uuid']] = {'name': bone, 'type': 'bone', 'keyframes': keys}
    loop = an.get('loop')
    m['animations'].append({'uuid': uid(), 'name': name,
                            'loop': 'loop' if loop is True else 'hold' if loop == 'hold_on_last_frame' else 'once',
                            'override': False, 'length': an['animation_length'], 'snapping': 24, 'selected': False,
                            'anim_time_update': '', 'blend_weight': '', 'start_delay': '', 'loop_delay': '',
                            'animators': animators})
m['name'] = 'noelle'
m['model_identifier'] = 'noelle'
json.dump(m, open(out_path, 'w'))
print('cubes', len(m['elements']), 'groups', len(m['groups']), 'animations', len(m['animations']))
