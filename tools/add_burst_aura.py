"""A starting burst overlay: a bigger copy of the claymore (every cube in "weapon", scaled up around the grip and a
little inflated) in "weapon_aura", all its faces reading one small soft-gold see-through patch. The game draws
"weapon_aura" glowing and see-through only while her burst is on. Reshape or repaint freely.

usage: add_burst_aura.py in.bbmodel out.bbmodel [scale]
"""
import sys
from PIL import Image, ImageDraw
from bbtools import Project

src, dst = sys.argv[1:3]
S = float(sys.argv[3]) if len(sys.argv) > 3 else 1.3
p = Project(src)
if p.cubes_under('weapon_aura', direct=True):
    raise SystemExit('weapon_aura already has cubes')
weapon = p.groups[p.nodes['weapon']['uuid']]
grip = weapon['origin']
claymore = p.cubes_under('weapon', direct=True)

# the glow patch: 4x4, brighter and more solid at the top, fading toward the bottom
u, v = p.free_spot(4, 4)
d = ImageDraw.Draw(p.tex)
for row in range(4):
    a = 150 - row * 25
    d.line([u, v + row, u + 3, v + row], fill=(255, 226 - row * 8, 140 - row * 15, a))
aura = p.nodes['weapon_aura']
for e in claymore:
    scale = lambda pt: [grip[i] + (pt[i] - grip[i]) * S for i in range(3)]
    faces = {k: (u, v, u + 4, v + 4) for k, f in e['faces'].items() if f.get('texture') is not None}
    p.cube(aura, e['name'] + '_aura', scale(e['from']), scale(e['to']), scale(e['origin']), e.get('rotation', [0, 0, 0]),
           faces, inflate=(e.get('inflate') or 0) + 0.2)
p.save(dst)
print(f'{len(claymore)} cubes copied at {S}x into weapon_aura; glow patch at ({u}, {v})')
