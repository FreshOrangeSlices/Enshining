"""A starting chain belt, worn at an angle: a leather strap round her back and hips that turns into a chain across
the front (the chain dips a little in the middle), with a metal ring where chain meets strap. Higher on one hip,
lower on the other. Goes in "chain_belt" under "waist_extras"; an existing "belt" group is switched off from
export (still in the file, so nothing is lost).

Chain links alternate like a real chain: a flat ring facing out (with a see-through middle), then a small bar
turned edge-on. Everything reads a few small patches (double detail): strap, ring link, bar, joint ring.

usage: make_chain_belt.py in.bbmodel out.bbmodel
"""
import math, sys

import numpy as np
from PIL import ImageDraw

from bbtools import Project, blockbench_angles, rx, ry, rz

src, dst = sys.argv[1:3]
# ---- shape (pixels) -------------------------------------------------------------------------------------------------
A, B, N = 4.7, 2.85, 4.0          # the ring round her waist: rounded box just outside the body
MID_Y, TILT = 13.9, 0.8           # middle height, and how much higher her right hip is than her left
STRAP_H, STRAP_W = 1.6, 2.0       # strap panel height and width
CHAIN_FROM = 70                    # the chain spans the front, -70..70 degrees from front centre
SAG = 1.1                          # how far the chain dips in the middle
LINK = 1.15                        # spacing between links along the chain
PX = 2                             # texture pixels per model pixel


def spow(v, e):
    return math.copysign(abs(v) ** e, v)


def ring(t, grow=0.0):
    """Point on the waist ring at angle t (0 = front, + = her right), its outward normal, and its height."""
    x = (A + grow) * spow(math.sin(t), 2 / N)
    z = -(B + grow) * spow(math.cos(t), 2 / N)
    gx, gz = spow(x / (A + grow), N - 1) / (A + grow), spow(z / (B + grow), N - 1) / (B + grow)
    g = math.hypot(gx, gz)
    return np.array([x, MID_Y + TILT * x / A, z]), np.array([gx / g, 0, gz / g])


p = Project(src)
if 'chain_belt' in p.nodes:
    raise SystemExit('chain_belt already exists')
if 'belt' in p.nodes:
    p.groups[p.nodes['belt']['uuid']]['export'] = False
belt = p.group('chain_belt', 'waist_extras', origin=(0, MID_Y, 0))
strap_g = p.group('belt_strap', 'chain_belt', origin=(0, MID_Y, 0))
chain_g = p.group('belt_chain', 'chain_belt', origin=(0, MID_Y, -B))

# ---- texture patches ----------------------------------------------------------------------------------------------
u, v = p.free_spot(20, 4)
d = ImageDraw.Draw(p.tex)
LEATHER, STITCH, METAL, SHINE, DARK = (52, 40, 36, 255), (120, 98, 80, 255), (150, 150, 160, 255), (215, 215, 225, 255), (95, 95, 105, 255)
d.rectangle([u, v, u + 3, v + 2], fill=LEATHER)                         # strap 4x3
d.line([u, v, u + 3, v], fill=STITCH)
link = (u + 5, v)                                                         # ring link 4x3, hollow
d.rectangle([link[0], v, link[0] + 3, v + 2], outline=METAL)
d.point((link[0] + 1, v), fill=SHINE)
bar = (u + 10, v)                                                         # bar 3x1
d.line([bar[0], v, bar[0] + 2, v], fill=METAL)
d.point((bar[0] + 1, v), fill=SHINE)
joint = (u + 14, v)                                                       # joint ring 4x4
d.rectangle([joint[0], v, joint[0] + 3, v + 3], fill=DARK, outline=METAL)
d.point((joint[0] + 1, v + 1), fill=SHINE)


def flat(node, name, centre, normal, along_angle, w, h, uv, thick=0.0):
    """A piece centred at CENTRE, facing NORMAL, its width turned ALONG_ANGLE (radians) from level, about its normal."""
    yaw = math.atan2(-normal[0], -normal[2])
    R = ry(yaw) @ rz(along_angle)   # the piece's width runs round the ring (toward her right at the front)
    x, y, z = blockbench_angles(R)
    c = centre
    frm = [c[0] - w / 2, c[1] - h / 2, c[2] - thick / 2]
    to = [c[0] + w / 2, c[1] + h / 2, c[2] + thick / 2]
    faces = {'north': uv, 'south': uv}
    if thick:
        faces.update({k: uv for k in ('east', 'west', 'up', 'down')})
    return p.cube(node, name, frm, to, c, (x, y, z), faces)


# strap: round her back, from one end of the chain to the other
t0, t1 = math.radians(CHAIN_FROM), math.radians(360 - CHAIN_FROM)
ts = np.linspace(t0, t1, 400)
pts = [ring(t)[0] for t in ts]
length = sum(np.linalg.norm(pts[i + 1] - pts[i]) for i in range(len(pts) - 1))
n = math.ceil(length * 1.35 / STRAP_W)
cum = np.concatenate([[0], np.cumsum([np.linalg.norm(pts[i + 1] - pts[i]) for i in range(len(pts) - 1)])])
def rise(f, t, dt=0.01):
    """How steeply a path f(t) climbs going round toward increasing t."""
    a, b = f(t - dt), f(t + dt)
    tang = b - a
    return math.atan2(tang[1], math.hypot(tang[0], tang[2]))

for k in range(n):
    t = float(np.interp(length * (k + 0.5) / n, cum, ts))   # evenly spaced along the strap, not by angle
    c, nrm = ring(t)
    flat(strap_g, f'strap_{k}', c, nrm, rise(lambda q: ring(q)[0], t), STRAP_W, STRAP_H, (u, v, u + 4, v + 3))

# chain: across the front, dipping in the middle
def chain_point(s):
    """s from -1 (her left end) to 1 (her right end)."""
    t = math.radians(CHAIN_FROM) * s
    c, nrm = ring(t, grow=0.15)
    c = c + np.array([0, -SAG * (1 - s * s), 0])
    return c, nrm

ss = np.linspace(-1, 1, 600)
cps = [chain_point(s)[0] for s in ss]
arc = np.concatenate([[0], np.cumsum([np.linalg.norm(cps[i + 1] - cps[i]) for i in range(len(cps) - 1)])])
count = int(arc[-1] / LINK)
for k in range(count + 1):
    target = arc[-1] * k / count
    i = min(len(ss) - 2, int(np.searchsorted(arc, target)))
    c, nrm = chain_point(ss[i])
    nxt, _ = chain_point(ss[min(len(ss) - 1, i + 3)])
    prv, _ = chain_point(ss[max(0, i - 3)])
    tang = nxt - prv
    angle = math.atan2(tang[1], math.hypot(tang[0], tang[2]))   # how steeply the chain runs here
    if k % 2 == 0:
        flat(chain_g, f'link_{k}', c, nrm, angle, 1.6, 1.15, (link[0], link[1], link[0] + 4, link[1] + 3))
    else:
        flat(chain_g, f'bar_{k}', c + nrm * 0.1, nrm, angle, 1.3, 0.35, (bar[0], bar[1], bar[0] + 3, bar[1] + 1), thick=0.35)

# joint rings where the chain meets the strap
for side, s in (('left', -1), ('right', 1)):
    c, nrm = chain_point(s)
    flat(belt, f'joint_{side}', c + nrm * 0.15, nrm, 0.0, 1.8, 1.8, (joint[0], joint[1], joint[0] + 4, joint[1] + 4), thick=0.4)

p.save(dst)
print(f'strap {n} panels, chain {count + 1} links, patches at ({u}, {v})')
