"""A painting map for a model's texture: where every cube face reads its pixels, coloured by body part, with pixel
coordinates (the same numbers Blockbench's UV panel shows) and a cube unfolding diagram. No art from the texture
is shown, only the layout.

usage: make_uv_guide.py model.bbmodel guide.png template.png
  guide.png    big and labelled, to read
  template.png same size as the texture, the colours only, to lay under / over your painting as a guide layer
"""
import json, math, sys
from PIL import Image, ImageDraw, ImageFont

src, guide_path, template_path = sys.argv[1:4]
m = json.load(open(src))
W, H = m['resolution']['width'], m['resolution']['height']
S = 6                       # guide pixels per texture pixel
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
BOLD = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
f_small, f_mid, f_big = ImageFont.truetype(BOLD, 11), ImageFont.truetype(FONT, 15), ImageFont.truetype(BOLD, 19)
f_num = ImageFont.truetype(BOLD, 14)

groups = {g['uuid']: g for g in m['groups']}
els = {e['uuid']: e for e in m['elements']}
owner = {}


def walk(n, path):
    path = path + [groups[n['uuid']]['name']]
    for c in n['children']:
        if isinstance(c, str):
            owner[c] = path
        else:
            walk(c, path)


for n in m['outliner']:
    if isinstance(n, dict):
        walk(n, [])

# ---- which part each cube belongs to (the first group on its path that names a part) -----------------------------
PARTS = [  # (key, label, colour, note)
    ('head', 'Head', (236, 112, 99), 'the skin\'s head; hat layer = the outer layer'),
    ('chest', 'Chest (top half of torso)', (245, 176, 65), 'skin torso, rows 1-6'),
    ('body', 'Waist (bottom half of torso)', (244, 208, 63), 'skin torso, rows 7-12'),
    ('right_arm', 'Her right arm, upper', (93, 173, 226), 'skin right arm, rows 1-6'),
    ('right_forearm', 'Her right arm, lower', (52, 152, 219), 'rows 7-12'),
    ('left_arm', 'Her left arm, upper', (165, 105, 189), 'skin left arm, rows 1-6'),
    ('left_forearm', 'Her left arm, lower', (142, 68, 173), 'rows 7-12'),
    ('right_leg', 'Her right leg, upper', (88, 214, 141), 'skin right leg, rows 1-6'),
    ('right_shin', 'Her right leg, lower', (40, 180, 99), 'rows 7-12'),
    ('left_leg', 'Her left leg, upper', (72, 201, 176), 'skin left leg, rows 1-6'),
    ('left_shin', 'Her left leg, lower', (23, 165, 137), 'rows 7-12'),
    ('skirt', 'Skirt', (250, 140, 190), 'see the strip notes below'),
    ('eyes', 'Eye expressions', (255, 214, 102), 'whole-face sheets in front of her face, one shown at a time'),
    ('weapon', 'Claymore', (149, 165, 166), 'in her hand and hovering behind her: same pixels'),
    ('aura', 'Burst glow', (255, 236, 150), 'the see-through overlay on the claymore during her burst'),
    ('weapon_back', 'Claymore', (149, 165, 166), ''),
]
EXTRA = [(170, 110, 80), (205, 97, 85), (125, 206, 160), (133, 193, 233), (187, 143, 206), (230, 126, 34),
         (93, 109, 126), (220, 118, 51), (46, 134, 193), (171, 235, 198)]
part_of = {}
extras = {}
for uid, path in owner.items():
    e = els[uid]
    if any(p.startswith('armor') for p in path):   # armor pieces get their own textures later
        continue
    hit = None
    for name in reversed(path):   # the innermost accessory group wins, else the body part
        if name.startswith('eyes'):
            hit = 'eyes'
            break
        if name == 'weapon_aura':
            hit = 'aura'
            break
        if name in ('skirt', 'weapon', 'weapon_back'):
            hit = name
            break
    if hit is None:
        acc = [p for p in path if p not in {k for k, *_ in PARTS} and p not in ('root',) and not p.endswith('_extras')
               and not p.startswith('armor') and not p.startswith('eyes') and p != 'hair']
        if acc:
            hit = 'acc:' + acc[0]
        else:
            hit = next((p for p in reversed(path) if p in {k for k, *_ in PARTS}), path[-1])
    part_of[uid] = hit
    if hit.startswith('acc:') and hit not in extras:
        extras[hit] = EXTRA[len(extras) % len(EXTRA)]

info = {k: (label, col, note) for k, label, col, note in PARTS}
for k, col in extras.items():
    info[k] = (k[4:].replace('_', ' ').capitalize(), col, '')

FACE = {'north': 'F', 'south': 'B', 'up': 'T', 'down': 'U', 'east': 'R', 'west': 'L'}


def rects():
    """Every face as (part, face letter, x0, y0, x1, y1, layer?) in texture pixels."""
    out = []
    for e in m['elements']:
        part = part_of.get(e['uuid'])
        if part is None or part.startswith('armor'):
            continue
        layer = e['name'].endswith('_layer')
        if e.get('box_uv'):
            w, h, d = (math.ceil(e['to'][i] - e['from'][i]) for i in range(3))
            u, v = e.get('uv_offset', [0, 0])
            box = {'up': (u + d, v, w, d), 'down': (u + d + w, v, w, d), 'east': (u, v + d, d, h),
                   'north': (u + d, v + d, w, h), 'west': (u + d + w, v + d, d, h), 'south': (u + d + w + d, v + d, w, h)}
            for face, (x, y, fw, fh) in box.items():
                out.append((part, FACE[face], x, y, x + fw, y + fh, layer))
            continue
        for face, f in e['faces'].items():
            if f.get('texture') is None:
                continue
            u0, v0, u1, v1 = f['uv']
            x0, x1 = sorted((u0, u1))
            y0, y1 = sorted((v0, v1))
            if x1 - x0 < 1e-6 or y1 - y0 < 1e-6:
                continue
            out.append((part, FACE[face], x0, y0, x1, y1, layer))
    # one entry per pixel area (a mirrored copy or the hovering claymore reads the same pixels: the first one names
    # it), big faces first so small ones sit on top
    seen, unique = set(), []
    for r in out:
        key = tuple(round(v, 3) for v in r[2:6])
        if key not in seen:
            seen.add(key)
            unique.append(r)
    return sorted(unique, key=lambda r: -(r[4] - r[2]) * (r[5] - r[3]))


faces = rects()

# ---- the real-size template ------------------------------------------------------------------------------------
tpl = Image.new('RGBA', (W, H), (0, 0, 0, 0))
td = ImageDraw.Draw(tpl)
for part, letter, x0, y0, x1, y1, layer in faces:
    col = info[part][1]
    td.rectangle([math.floor(x0), math.floor(y0), math.ceil(x1) - 1, math.ceil(y1) - 1], fill=col + (90 if layer else 150,))
tpl.save(template_path)

# ---- the big labelled guide --------------------------------------------------------------------------------------
LEGEND = 620
MARGIN = 34
img = Image.new('RGB', (MARGIN + W * S + 20 + LEGEND, MARGIN + H * S + 20), (250, 250, 247))
d = ImageDraw.Draw(img, 'RGBA')
ox, oy = MARGIN, MARGIN
# pixel grid, stronger every 8 pixels, with the coordinates Blockbench shows
for x in range(W + 1):
    d.line([ox + x * S, oy, ox + x * S, oy + H * S], fill=(0, 0, 0, 60 if x % 8 == 0 else 14))
for y in range(H + 1):
    d.line([ox, oy + y * S, ox + W * S, oy + y * S], fill=(0, 0, 0, 60 if y % 8 == 0 else 14))
for x in range(0, W + 1, 16):
    d.text((ox + x * S, oy - 4), str(x), fill=(60, 60, 60), font=f_small, anchor='mb')
for y in range(0, H + 1, 16):
    d.text((ox - 5, oy + y * S), str(y), fill=(60, 60, 60), font=f_small, anchor='rm')
d.text((ox + W * S / 2, 4), 'u (across)  - the first UV number in Blockbench', fill=(90, 90, 90), font=f_small, anchor='mt')
d.text((4, oy + H * S + 6), 'v (down) - the second UV number', fill=(90, 90, 90), font=f_small)

centers = {}
for part, letter, x0, y0, x1, y1, layer in faces:
    col = info[part][1]
    box = [ox + x0 * S, oy + y0 * S, ox + x1 * S - 1, oy + y1 * S - 1]
    d.rectangle(box, fill=col + (110 if layer else 200,), outline=tuple(int(c * 0.55) for c in col) + (255,))
    if (x1 - x0) * S >= 11 and (y1 - y0) * S >= 12 and part != 'skirt':
        d.text(((box[0] + box[2]) / 2, (box[1] + box[3]) / 2), letter.lower() if layer else letter,
               fill=(30, 30, 30), font=f_small, anchor='mm')
    a = (x1 - x0) * (y1 - y0) * (0.2 if layer else 1)   # number the inner layer, not the outer one
    if part not in centers or a > centers[part][0]:
        centers[part] = (a, (box[0] + box[2]) / 2, (box[1] + box[3]) / 2)

# eye expressions: name each sheet under its patch
for e in m['elements']:
    if part_of.get(e['uuid']) == 'eyes':
        u0, v0, u1, v1 = e['faces']['north']['uv']
        d.text((ox + (u0 + u1) / 2 * S, oy + max(v0, v1) * S + 2), e['name'].replace('eyes_', ''), fill=(120, 90, 0),
               font=f_small, anchor='ma')

# skirt strips: say which way round they go
sk = [r for r in faces if r[0] == 'skirt']
strips = {}
for part, letter, x0, y0, x1, y1, layer in sk:
    if y1 - y0 <= 5:   # the frill and lining patches, not panel columns
        continue
    key = round(y0)
    s = strips.setdefault(key, [x0, y0, x1, y1, 0])
    s[0], s[1], s[2], s[3], s[4] = min(s[0], x0), min(s[1], y0), max(s[2], x1), max(s[3], y1), s[4] + 1

order = [k for k, *_ in PARTS] + list(extras)
numbers = {}
for part in order:
    if part in centers and info[part][0] not in [info[p][0] for p in numbers]:
        numbers[part] = len(numbers) + 1
for part, (a, cx, cy) in centers.items():
    num = numbers.get(part) or next(n for p, n in numbers.items() if info[p][0] == info[part][0])
    d.ellipse([cx - 11, cy - 11, cx + 11, cy + 11], fill=(255, 255, 255, 235), outline=(30, 30, 30))
    d.text((cx, cy), str(num), fill=(20, 20, 20), font=f_num, anchor='mm')

# ---- legend ------------------------------------------------------------------------------------------------------
lx, ly = MARGIN + W * S + 30, MARGIN
d.text((lx, ly), 'What paints where', fill=(20, 20, 20), font=f_big)
ly += 34
for part, num in numbers.items():
    label, col, note = info[part]
    d.rectangle([lx, ly + 2, lx + 18, ly + 18], fill=col, outline=(60, 60, 60))
    d.text((lx + 26, ly), f'{num}  {label}', fill=(20, 20, 20), font=f_mid)
    if note:
        d.text((lx + 46, ly + 19), note, fill=(100, 100, 100), font=f_small)
        ly += 14
    ly += 26

ly += 10
d.text((lx, ly), 'Letters on a face', fill=(20, 20, 20), font=f_big)
ly += 30
for line in ['F front    B back', 'T top    U underside', 'R her right side    L her left side',
             'lower-case = the outer layer (like a skin\'s hat/jacket layer),',
             'drawn a little bigger over the inner one; leave it see-through',
             'where you want the inner layer to show']:
    d.text((lx, ly), line, fill=(40, 40, 40), font=f_mid)
    ly += 22

# how a cube unfolds
ly += 14
d.text((lx, ly), 'How one cube unfolds (looking at her front)', fill=(20, 20, 20), font=f_big)
ly += 32
c, wide = 38, 48   # depth squares and width squares
cube = [('T', c, 0, wide), ('U', c + wide, 0, wide), ('R', 0, 1, c), ('F', c, 1, wide), ('L', c + wide, 1, c),
        ('B', 2 * c + wide, 1, wide)]
for letter, x, row, w in cube:
    x, y = lx + 20 + x, ly + row * c
    d.rectangle([x, y, x + w, y + c], fill=(255, 236, 200), outline=(60, 60, 60))
    d.text((x + w / 2, y + c / 2), letter, fill=(30, 30, 30), font=f_mid, anchor='mm')
ly += 2 * c + 10
for line in ['The front sits in the middle with her right side on its left (as',
             'you look at her) and her left side on its right; the top folds up,',
             'the underside sits beside the top, the back goes on the far right.',
             'Each square is exactly as many pixels as the cube is wide/tall/deep.']:
    d.text((lx, ly), line, fill=(60, 60, 60), font=f_small)
    ly += 16

ly += 18
d.text((lx, ly), 'Skirt strips', fill=(20, 20, 20), font=f_big)
ly += 30
for line in ['Two long strips, one 4-pixel column per panel (2 px wide, double',
             'detail). Left to right each strip goes round her: back centre,',
             'her left side, FRONT CENTRE (the middle), her right side, back.',
             'Hip tier = the shorter strip; hem tier = the longer one.',
             'The small patch: left = the frill (every frill panel shares it,',
             'so a pattern repeats all round), right = the lining (inside).']:
    d.text((lx, ly), line, fill=(60, 60, 60), font=f_small)
    ly += 16
for key, (x0, y0, x1, y1, n) in strips.items():
    if x1 - x0 > 40:
        y = oy + y0 * S + 3
        d.text((ox + x0 * S + 2, y), 'back', fill=(150, 40, 90), font=f_small)
        d.text((ox + (x0 + x1) / 2 * S, y), 'front', fill=(150, 40, 90), font=f_small, anchor='ma')
        d.text((ox + x1 * S - 2, y), 'back', fill=(150, 40, 90), font=f_small, anchor='ra')

ly += 18
d.text((lx, ly), 'Shared pixels', fill=(20, 20, 20), font=f_big)
ly += 30
for line in ['Some pieces read the same pixels on purpose: both halves of a',
             'mirrored piece (paint one, both change), the claymore in hand and',
             'the one hovering behind her, all frill panels.',
             'Blank (white) areas are free: new pieces can go there.']:
    d.text((lx, ly), line, fill=(60, 60, 60), font=f_small)
    ly += 16
img.save(guide_path)
print('guide', img.size, '| template', tpl.size, '| parts', len(numbers))
