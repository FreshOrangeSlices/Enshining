"""Blank shared base rigs (girl / man / small) for GeckoLib: the skeleton every character shares, empty groups for
each character's own pieces, empty attachment bones, and invisible armor reference cubes. Plain cubes on a 64x64
box-UV template (each part a different hue; front faces lighter). x+ = her right, front = -z (north)."""
import base64, io, json, sys, uuid
from PIL import Image, ImageDraw

def uid():
    return str(uuid.uuid4())

def box_faces(u, v, w, h, d, textured=True):
    t = 0 if textured else None
    return {
        'north': {'uv': [u + d, v + d, u + d + w, v + d + h], 'texture': t},
        'east': {'uv': [u, v + d, u + d, v + d + h], 'texture': t},
        'south': {'uv': [u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h], 'texture': t},
        'west': {'uv': [u + d + w, v + d, u + 2 * d + w, v + d + h], 'texture': t},
        'up': {'uv': [u + d + w, v + d, u + d, v], 'texture': t},
        'down': {'uv': [u + d + 2 * w, v, u + d + w, v + d], 'texture': t},
    }

def build(kind, p):
    elements, groups = [], []
    tex = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(tex)

    def paint(u, v, w, h, d, hue):
        r, g, b = hue
        shade = lambda k: (int(r * k), int(g * k), int(b * k), 255)
        draw.rectangle([u + d, v + d, u + d + w - 1, v + d + h - 1], fill=shade(1.0))            # front
        draw.rectangle([u, v + d, u + d - 1, v + d + h - 1], fill=shade(0.75))                   # her right side
        draw.rectangle([u + d + w, v + d, u + 2 * d + w - 1, v + d + h - 1], fill=shade(0.75))   # her left side
        draw.rectangle([u + 2 * d + w, v + d, u + 2 * d + 2 * w - 1, v + d + h - 1], fill=shade(0.55))  # back
        draw.rectangle([u + d, v, u + d + w - 1, v + d - 1], fill=shade(0.9))                    # top
        draw.rectangle([u + d + w, v, u + d + 2 * w - 1, v + d - 1], fill=shade(0.45))           # bottom

    def cube(name, frm, size, uvo=None, hue=(200, 200, 200), visible=True):
        to = [frm[i] + size[i] for i in range(3)]
        e = {'name': name, 'box_uv': True, 'render_order': 'default', 'locked': False, 'export': True,
             'allow_mirror_modeling': True, 'from': frm, 'to': to, 'autouv': 0, 'color': 0,
             'origin': [0, 0, 0], 'uv_offset': uvo or [0, 0], 'type': 'cube', 'uuid': uid(),
             'faces': box_faces(*(uvo or [0, 0]), size[0], size[1], size[2], visible)}
        if visible and uvo:
            paint(*uvo, size[0], size[1], size[2], hue)
        elements.append(e)
        return e['uuid']

    def group(name, origin, children=()):
        g = {'name': name, 'uuid': uid(), 'export': True, 'locked': False, 'scope': 0, 'selected': False,
             'visibility': True, 'origin': origin, 'rotation': [0, 0, 0], 'color': 0, 'reset': False,
             'shade': True, 'mirror_uv': False, 'autouv': 0, 'isOpen': True}
        groups.append(g)
        return {'uuid': g['uuid'], 'isOpen': True, 'children': list(children)}

    W, D, AW, LW = 8, 4, p['arm_w'], 4
    shin, thigh, body_h, chest_h, head_h = p['shin'], p['thigh'], p['body'], p['chest'], p['head']
    upper, fore = p['upper'], p['fore']
    parts = [('head', 8, head_h, 8), ('chest', W, chest_h, D), ('body', W, body_h, D),
             ('right_arm', AW, upper, D), ('right_forearm', AW, fore, D), ('left_arm', AW, upper, D), ('left_forearm', AW, fore, D),
             ('right_leg', LW, thigh, D), ('right_shin', LW, shin, D), ('left_leg', LW, thigh, D), ('left_shin', LW, shin, D)]
    UV, x, y, row = {}, 0, 0, 0
    for name, w, h, d in parts:  # shelf packing, 64 wide
        bw, bh = 2 * (w + d), h + d
        if x + bw > 64:
            x, y, row = 0, y + row, 0
        UV[name] = [x, y]
        x, row = x + bw, max(row, bh)
    assert y + row <= 64, (kind, y + row)
    leg_top = shin + thigh
    waist = leg_top + body_h
    neck = waist + chest_h
    ax = 4 + AW / 2  # arm centre (x)
    # The skeleton (animations move only these) with each character's pieces in the empty groups.
    head = group('head', [0, neck, 0], [
        cube('head', [-4, neck, -4], [8, head_h, 8], UV['head'], (230, 200, 170)),
        group('eyes', [0, neck + 4, -4], [group('eyes_open', [0, neck + 4, -4]), group('eyes_half', [0, neck + 4, -4]),
                                          group('eyes_closed', [0, neck + 4, -4])]),
        group('hair', [0, neck + head_h, 0]),
        group('head_extras', [0, neck + head_h, 0]),
        group('armor_head', [0, neck, 0], [cube('armor_head', [-4, neck, -4], [8, head_h, 8], visible=False)]),
    ])
    def arm(side):
        s = 1 if side == 'right' else -1
        x0 = 4 if s > 0 else -4 - AW
        c = ax * s
        hand_y = neck - upper - fore + 0.5
        hue = (120, 150, 220) if s > 0 else (110, 190, 140)
        uv_up, uv_lo = UV[f'{side}_arm'], UV[f'{side}_forearm']
        hand_kids = [group('weapon', [c, hand_y, 0], [group('weapon_aura', [c, hand_y, 0])])] if s > 0 else []
        return group(f'{side}_arm', [c, neck - 1.5, 0], [
            cube(f'{side}_arm', [x0, neck - upper, -2], [AW, upper, D], list(uv_up), hue),
            group(f'{side}_forearm', [c, neck - upper, 0], [
                cube(f'{side}_forearm', [x0, neck - upper - fore, -2], [AW, fore, D], list(uv_lo), tuple(int(v * 0.85) for v in hue)),
                group(f'{side}_hand', [c, hand_y, 0], hand_kids),
            ]),
            group(f'{side}_arm_extras', [c, neck - 1.5, 0]),  # shoulder pieces etc.: move with the arm, hidden under a chestplate
            group(f'armor_{side}_arm', [c, neck - 1.5, 0],
                  [cube(f'armor_{side}_arm', [x0, neck - upper - fore, -2], [AW, upper + fore, D], visible=False)]),
        ])
    chest = group('chest', [0, waist, 0], [
        cube('chest', [-4, waist, -2], [W, chest_h, D], UV['chest'], (210, 120, 120)),
        head, arm('right'), arm('left'),
        group('chest_extras', [0, waist, 0]),
        group('weapon_back', [0, neck - 3, 3.5]),
        group('armor_body', [0, waist, 0], [cube('armor_body', [-4, leg_top, -2], [W, body_h + chest_h, D], visible=False)]),
    ])
    body = group('body', [0, leg_top, 0], [
        cube('body', [-4, leg_top, -2], [W, body_h, D], UV['body'], (190, 100, 100)),
        chest,
        group('waist_extras', [0, leg_top, 0]),
        group('skirt', [0, leg_top + 0.5, 0], [group('skirt_front', [0, leg_top + 0.5, -2.5]), group('skirt_back', [0, leg_top + 0.5, 2.5])]),
        group('armor_waist', [0, leg_top, 0], [cube('armor_waist', [-4, leg_top, -2], [W, body_h, D], visible=False)]),
    ])
    def leg(side):
        s = 1 if side == 'right' else -1
        x0 = 0 if s > 0 else -4
        c = 2 * s
        hue = (150, 120, 200) if s > 0 else (200, 170, 90)
        uv_hi, uv_lo = UV[f'{side}_leg'], UV[f'{side}_shin']
        return group(f'{side}_leg', [c, leg_top, 0], [
            cube(f'{side}_leg', [x0, shin, -2], [LW, thigh, D], list(uv_hi), hue),
            group(f'{side}_shin', [c, shin, 0], [
                cube(f'{side}_shin', [x0, 0, -2], [LW, shin, D], list(uv_lo), tuple(int(v * 0.85) for v in hue)),
                group(f'armor_{side}_foot', [c, shin, 0], [cube(f'armor_{side}_foot', [x0, 0, -2], [LW, shin, D], visible=False)]),
            ]),
            group(f'armor_{side}_leg', [c, leg_top, 0], [cube(f'armor_{side}_leg', [x0, 0, -2], [LW, leg_top, D], visible=False)]),
        ])
    root = group('root', [0, 0, 0], [body, leg('right'), leg('left')])
    buf = io.BytesIO(); tex.save(buf, 'PNG')
    model = {
        'meta': {'format_version': '5.0', 'model_format': 'geckolib_model', 'box_uv': True},
        'name': f'base_rig_{kind}', 'model_identifier': f'enshining.base_{kind}', 'visible_box': [4, 5, 2.5],
        'variable_placeholders': '', 'variable_placeholder_buttons': [], 'timeline_setups': [], 'unhandled_root_fields': {},
        'geckolib_modid': '', 'geckolib_filepath_cache': {}, 'geckolib_model_type': 'Entity',
        'resolution': {'width': 64, 'height': 64},
        'elements': elements, 'groups': groups, 'outliner': [root],
        'textures': [{'name': f'base_rig_{kind}.png', 'path': '', 'folder': '', 'namespace': '', 'id': '0', 'group': '',
                      'scope': 0, 'width': 64, 'height': 64, 'uv_width': 64, 'uv_height': 64, 'particle': False,
                      'use_as_default': False, 'layers_enabled': False, 'render_mode': 'default', 'render_sides': 'auto',
                      'visible': True, 'internal': True, 'saved': False, 'uuid': uid(),
                      'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()}],
        'animations': [],
    }
    return model, leg_top + body_h + chest_h + head_h

RIGS = {
    'girl':  dict(shin=6, thigh=6, body=6, chest=6, head=8, upper=6, fore=6, arm_w=3),
    'man':   dict(shin=6, thigh=6, body=6, chest=7, head=9, upper=7, fore=6, arm_w=3),   # +1 torso, +1 head
    'small': dict(shin=4, thigh=5, body=4, chest=5, head=8, upper=5, fore=4, arm_w=3),
}
for kind, p in RIGS.items():
    m, height = build(kind, p)
    json.dump(m, open(f'{sys.argv[1]}/base_rig_{kind}.bbmodel', 'w'))
    print(kind, 'height px', height, 'groups', len(m['groups']), 'cubes', len(m['elements']))
