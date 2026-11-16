"""Turn a GeckoLib / Bedrock model (geo.json + texture, optionally its animation.json) into one Blockbench project
(.bbmodel, GeckoLib format) to open and study. The inverse of bb_export.py.

usage: geo_to_bbmodel.py model.geo.json texture.png out.bbmodel [animations.json]
"""
import base64, json, sys, uuid

geo_path, tex_path, out_path = sys.argv[1:4]
anim_path = sys.argv[4] if len(sys.argv) > 4 else None
geo = json.load(open(geo_path))['minecraft:geometry'][0]
desc = geo['description']
TW, TH = desc.get('texture_width', 64), desc.get('texture_height', 64)
uid = lambda: str(uuid.uuid4())
FACES = ('north', 'east', 'south', 'west', 'up', 'down')

groups, elements, by_name, nodes = [], [], {}, {}
for b in geo['bones']:
    p = b.get('pivot', [0, 0, 0])
    r = b.get('rotation', [0, 0, 0])
    g = {'name': b['name'], 'uuid': uid(), 'export': True, 'locked': False, 'scope': 0, 'selected': False,
         'visibility': True, 'origin': [-p[0], p[1], p[2]], 'rotation': [-r[0], -r[1], r[2]], 'color': 0,
         'reset': False, 'shade': True, 'mirror_uv': bool(b.get('mirror')), 'autouv': 0, 'isOpen': False}
    groups.append(g)
    by_name[b['name']] = g
    node = {'uuid': g['uuid'], 'isOpen': False, 'children': []}
    nodes[b['name']] = node
    for c in b.get('cubes', []):
        o, s = c['origin'], c['size']
        frm = [-(o[0] + s[0]), o[1], o[2]]
        to = [frm[i] + s[i] for i in range(3)]
        cr = c.get('rotation', [0, 0, 0])
        cp = c.get('pivot', p)
        e = {'name': b['name'], 'box_uv': False, 'render_order': 'default', 'locked': False, 'export': True,
             'allow_mirror_modeling': True, 'from': frm, 'to': to, 'autouv': 0, 'color': 0,
             'origin': [-cp[0], cp[1], cp[2]], 'rotation': [-cr[0], -cr[1], cr[2]], 'type': 'cube', 'uuid': uid()}
        if c.get('inflate'):
            e['inflate'] = c['inflate']
        uv = c.get('uv', [0, 0])
        if isinstance(uv, list):
            e['box_uv'] = True
            e['uv_offset'] = uv
            e['mirror_uv'] = bool(c.get('mirror', b.get('mirror', False)))
            w, h, d = s
            u, v = uv
            box = {'north': (u + d, v + d, w, h), 'east': (u, v + d, d, h), 'south': (u + d + w + d, v + d, w, h),
                   'west': (u + d + w, v + d, d, h), 'up': (u + d, v, w, d), 'down': (u + d + w, v, w, d)}
            e['faces'] = {k: {'uv': [x, y, x + fw, y + fh], 'texture': 0} for k, (x, y, fw, fh) in box.items()}
        else:
            e['faces'] = {}
            for k in FACES:
                f = uv.get(k)
                if f is None:
                    e['faces'][k] = {'uv': [0, 0, 0, 0], 'texture': None}
                    continue
                (U, V), (SU, SV) = f['uv'], f['uv_size']
                face = {'uv': [U + SU, V + SV, U, V] if k in ('up', 'down') else [U, V, U + SU, V + SV], 'texture': 0}
                if f.get('uv_rotation'):
                    face['rotation'] = f['uv_rotation']
                e['faces'][k] = face
        elements.append(e)
        node['children'].append(e['uuid'])

outliner = []
for b in geo['bones']:
    parent = b.get('parent')
    (nodes[parent]['children'] if parent in nodes else outliner).append(nodes[b['name']])


def bb_value(ch, i, v):
    """A file value as Blockbench shows it (x of rotation/position and y of rotation are mirrored on export)."""
    flip = (ch in ('rotation', 'position') and i == 0) or (ch == 'rotation' and i == 1)
    if not flip:
        return v
    if isinstance(v, (int, float)):
        return -v
    return f'-({v})'


animations = []
if anim_path:
    for name, an in json.load(open(anim_path))['animations'].items():
        animators, longest = {}, 0.0
        for bone, chans in an.get('bones', {}).items():
            if bone not in by_name:
                continue
            keys = []
            for ch, ks in chans.items():
                if not isinstance(ks, dict) or 'vector' in ks or 'post' in ks:
                    ks = {'0': ks}
                for t, val in sorted(ks.items(), key=lambda kv: float(kv[0])):
                    interp, easing = 'linear', None
                    if isinstance(val, dict) and 'post' in val:
                        interp = 'catmullrom' if val.get('lerp_mode') == 'catmullrom' else 'linear'
                        val = val['post']
                    if isinstance(val, dict):
                        easing = val.get('easing')
                        val = val['vector']
                    if not isinstance(val, list):
                        val = [val, val, val]
                    k = {'channel': ch, 'data_points': [{a: bb_value(ch, i, val[i]) for i, a in enumerate('xyz')}],
                         'uuid': uid(), 'time': float(t), 'color': -1, 'interpolation': interp}
                    if easing:
                        k['easing'] = easing
                    keys.append(k)
                    longest = max(longest, float(t))
            animators[by_name[bone]['uuid']] = {'name': bone, 'type': 'bone', 'keyframes': keys}
        loop = an.get('loop')
        animations.append({'uuid': uid(), 'name': name,
                           'loop': 'loop' if loop is True else 'hold' if loop == 'hold_on_last_frame' else 'once',
                           'override': False, 'length': an.get('animation_length', longest), 'snapping': 24,
                           'selected': False, 'anim_time_update': '', 'blend_weight': '', 'start_delay': '',
                           'loop_delay': '', 'animators': animators})

tex = 'data:image/png;base64,' + base64.b64encode(open(tex_path, 'rb').read()).decode()
model = {
    'meta': {'format_version': '4.10', 'model_format': 'geckolib_model', 'box_uv': False},
    'name': out_path.rsplit('/', 1)[-1].replace('.bbmodel', ''), 'model_identifier': '',
    'visible_box': [desc.get('visible_bounds_width', 2), desc.get('visible_bounds_height', 2),
                    (desc.get('visible_bounds_offset') or [0, 0, 0])[1]],
    'variable_placeholders': '', 'variable_placeholder_buttons': [], 'timeline_setups': [], 'unhandled_root_fields': {},
    'resolution': {'width': TW, 'height': TH}, 'elements': elements, 'groups': groups, 'outliner': outliner,
    'textures': [{'path': '', 'name': tex_path.rsplit('/', 1)[-1], 'folder': '', 'namespace': '', 'id': '0',
                  'width': TW, 'height': TH, 'uv_width': TW, 'uv_height': TH, 'particle': False,
                  'render_mode': 'default', 'render_sides': 'auto', 'visible': True, 'mode': 'bitmap',
                  'saved': True, 'uuid': uid(), 'source': tex}],
    'animations': animations,
}
json.dump(model, open(out_path, 'w'))
print(out_path, len(groups), 'groups', len(elements), 'cubes', len(animations), 'animations')
