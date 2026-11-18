"""Nudge waist items that sit inside (or exactly on) the skirt at rest straight outward, just enough to clear it.
usage: nudge.py in.bbmodel out.bbmodel [clearance_px]"""
import sys, json, copy, math, numpy as np
from skirtsim import *
from check_belt import skirt_tris, samples, depth_under

args=[a for a in sys.argv[1:] if not a.startswith('--')]
src,dst=args[:2]; clear=float(args[2]) if len(args)>2 else 0.15
# pieces of these groups move together, straight out from the waist toward the group's own pivot (a bow's wings stay on its knot)
along=[a.split('=',1)[1] for a in sys.argv if a.startswith('--along=')]
m=json.load(open(src)); model=Model(m)
sk=model.cubes('skirt'); ring=Ring(sk); tris=skirt_tris(ring,sk,poses()[0])
moved=[]
for _ in range(6):
    model=Model(m); changed=False
    for e,M,gname in model.cubes('waist_extras'):
        fq=face_quads(e,M)
        if not fq: continue
        pts=np.array([p for k,q in fq for p in samples(q,6)])
        below=pts[:,1]<ring.y+0.3
        if not below.any(): continue
        dep=depth_under(ring,tris,pts[below]); d=float(dep.max())
        if d<=-clear: continue
        c=pts[below].mean(axis=0)
        if gname in along:
            g=[x for x in m['groups'] if x['name']==gname][0]; c=np.array(g['origin'],float)
        out=np.array([c[0]-ring.cx,0,c[2]-ring.cz]); out/=np.linalg.norm(out)
        step=(d+clear+0.01)*out
        local=np.linalg.inv(M[:3,:3])@step   # M includes the cube's own rotation: undo it, then move from/to/origin in the group's frame
        R_cube=eul(e.get('rotation') or [0,0,0]); local=R_cube@local
        for k in ('from','to','origin'): e[k]=[round(a+b,4) for a,b in zip(e[k],local)]
        moved.append((gname,e['name'],round(d,2),np.round(step,2))); changed=True
    tris=skirt_tris(ring,sk,poses()[0])
    if not changed: break
for g,n,d,s in moved: print(f'{g:20s} {n:14s} was {d:+.2f}px -> moved {s} (body frame)')
json.dump(m,open(dst,'w'))
