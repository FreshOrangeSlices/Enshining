"""Even out a hand-reshaped skirt: take how far each panel was moved toward/away from the middle (compared with the
template it came from), smooth those amounts around the ring, and move every panel (both tiers and the frill) by
the smoothed amount at its angle. Keeps the reshaped silhouette without steps between neighbours, which is what
lets the ring swing without slits. UVs and rotations untouched.
usage: smooth_skirt_edit.py edited.bbmodel template.bbmodel out.bbmodel"""
import json, math, sys
import numpy as np
ed, tp, out = sys.argv[1:4]
m = json.load(open(ed)); t = {e['uuid']: e for e in json.load(open(tp))['elements']}
panels = [e for e in m['elements'] if e['name'].split('_')[0] in ('t0', 't1', 'frill') and e['uuid'] in t]
def ang(o): return math.atan2(o[0], -o[2])
samples = []
for e in panels:
    a = np.array(t[e['uuid']]['origin']); b = np.array(e['origin'])
    rad = np.array([a[0], 0, a[2]]); rad /= np.linalg.norm(rad)
    samples.append((ang(a), float((b - a) @ rad)))
A = np.array([s[0] for s in samples]); D = np.array([s[1] for s in samples])
def smooth(phi, width=math.radians(22)):
    d = np.angle(np.exp(1j * (A - phi)))
    w = np.exp(-0.5 * (d / width) ** 2)
    return float((w * D).sum() / w.sum())
for e in panels:
    a = np.array(t[e['uuid']]['origin'], float)
    rad = np.array([a[0], 0, a[2]]); rad /= np.linalg.norm(rad)
    shift = rad * smooth(ang(a))
    base = t[e['uuid']]
    for k in ('from', 'to', 'origin'):
        e[k] = [round(base[k][i] + shift[i], 4) for i in range(3)]
json.dump(m, open(out, 'w'))
for deg in range(0, 360, 30):
    print(f'{deg:3d} deg: {smooth(math.radians(deg)):+.2f} px', end='  ')
print()
