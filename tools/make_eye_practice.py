"""Moving eyes for the experiments file (or any rig with an "eyes" group), the way the Linky Playz short does them: each eye is a
white plane on the face with a pupil plane just in front of it. The pupil is its own bone, so it slides around
inside the white and grows or shrinks; the whole eye squashes flat to blink. The planes are geometry, not face
pixels, so they can sit between pixels and move smoothly, and they get their own finer texture (4 per pixel).

Three short animations show it off in Blockbench's Animate tab: eyes.look_around, eyes.blink, eyes.surprised.

usage: make_eye_practice.py base_rig.bbmodel out.bbmodel
"""
import sys

from PIL import Image, ImageDraw

from bbtools import Project, uid

src, dst = sys.argv[1:3]
p = Project(src)
if p.tex.size[0] < 128:   # room for the eyes' own patches
    old = p.tex
    p.tex = Image.new('RGBA', (128, 128), (0, 0, 0, 0))
    p.tex.paste(old, (0, 0))
    p.m['resolution'] = {'width': 128, 'height': 128}
    p.m['textures'][0].update({'width': 128, 'height': 128, 'uv_width': 128, 'uv_height': 128})
d = ImageDraw.Draw(p.tex)

FACE_Z, EYE_Y, EYE_X = -4.0, 27.0, 1.9      # front of the face, eye line, how far each eye sits from the middle
WHITE_W, WHITE_H = 2.6, 2.0                  # the white, in pixels
PUPIL_W, PUPIL_H = 1.0, 1.4                  # the pupil: taller than wide

# white: 12 x 8, pale blue along the top (the shadow of the upper lid), white below
WU, WV = p.free_spot(12, 8, start_row=0)
d.rectangle([WU, WV, WU + 11, WV + 7], fill=(250, 252, 255, 255))
d.rectangle([WU, WV, WU + 11, WV + 1], fill=(196, 214, 240, 255))
# pupil: 4 x 6, dark at the top fading lighter toward the bottom (no shine)
PU, PV = p.free_spot(4, 6, start_row=0)
for row, col in enumerate([(22, 48, 30), (30, 62, 38), (40, 80, 48), (52, 100, 60), (64, 120, 72), (70, 132, 80)]):
    d.line([PU, PV + row, PU + 3, PV + row], fill=col + (255,))

eyes = p.nodes['eyes']
made = {}
for side, sx in (('right', 1), ('left', -1)):   # her right is +x
    cx = sx * EYE_X
    eye = p.group(f'eye_{side}', 'eyes', origin=(cx, EYE_Y, FACE_Z - 0.05))
    p.cube(eye, f'white_{side}', (cx - WHITE_W / 2, EYE_Y - WHITE_H / 2, FACE_Z - 0.05),
           (cx + WHITE_W / 2, EYE_Y + WHITE_H / 2, FACE_Z - 0.05), (cx, EYE_Y, FACE_Z - 0.05),
           faces={'north': (WU, WV, WU + 12, WV + 8)})
    pupil = p.group(f'pupil_{side}', f'eye_{side}', origin=(cx, EYE_Y, FACE_Z - 0.1))
    p.cube(pupil, f'pupil_{side}', (cx - PUPIL_W / 2, EYE_Y - PUPIL_H / 2, FACE_Z - 0.1),
           (cx + PUPIL_W / 2, EYE_Y + PUPIL_H / 2, FACE_Z - 0.1), (cx, EYE_Y, FACE_Z - 0.1),
           faces={'north': (PU, PV, PU + 4, PV + 6)})
    made[side] = (eye, pupil)


def anim(name, length, tracks, loop='once'):
    """tracks: {group name: {channel: [(time, (x, y, z)), ...]}}, values as Blockbench shows them."""
    animators = {}
    for group, chans in tracks.items():
        g = p.groups[p.nodes[group]['uuid']]
        keys = []
        for ch, ks in chans.items():
            for t, (x, y, z) in ks:
                keys.append({'channel': ch, 'data_points': [{'x': x, 'y': y, 'z': z}], 'uuid': uid(), 'time': t,
                             'color': -1, 'interpolation': 'catmullrom'})
        animators[g['uuid']] = {'name': group, 'type': 'bone', 'keyframes': keys}
    p.m.setdefault('animations', []).append({
        'uuid': uid(), 'name': name, 'loop': loop, 'override': False, 'length': length, 'snapping': 24,
        'selected': False, 'anim_time_update': '', 'blend_weight': '', 'start_delay': '', 'loop_delay': '',
        'animators': animators})


# pupils can travel (white - pupil) / 2 each way: 0.8 across, 0.3 up and down
look = [(0.0, (0, 0, 0)), (0.4, (0.8, 0, 0)), (0.8, (0.8, 0, 0)), (1.2, (-0.7, 0.3, 0)), (1.6, (-0.7, 0.3, 0)),
        (2.0, (0.2, -0.3, 0)), (2.4, (0.2, -0.3, 0)), (2.8, (0, 0, 0))]
anim('animation.practice.eyes.look_around', 2.8, {f'pupil_{s}': {'position': look} for s in ('right', 'left')}, loop='loop')
blink = [(0.0, (1, 1, 1)), (0.08, (1, 0.08, 1)), (0.16, (1, 0.08, 1)), (0.28, (1, 1, 1))]
anim('animation.practice.eyes.blink', 1.0, {f'eye_{s}': {'scale': blink} for s in ('right', 'left')}, loop='loop')
size = [(0.0, (1, 1, 1)), (0.15, (0.55, 0.55, 1)), (0.9, (0.55, 0.55, 1)), (1.1, (1.25, 1.25, 1)), (1.8, (1.25, 1.25, 1)),
        (2.1, (1, 1, 1))]
anim('animation.practice.eyes.surprised', 2.1, {f'pupil_{s}': {'scale': size} for s in ('right', 'left')}, loop='loop')
p.save(dst)
print('eye practice saved:', dst)
