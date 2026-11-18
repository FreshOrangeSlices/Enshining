"""Like check_field, but the whole skirt bone also sways (CompanionModel.sway: up to 0.07 rad on X, 0.035 on Z, about
the skirt's pivot) and the ride follows that sway too (lerp(I, sway, weight) on top of the ring ride)."""
import sys, itertools, numpy as np
from skirtsim import *
from check_belt import samples, depth_under
from check_belt import skirt_tris as _st

def skirt_tris(ring, sk, pose, S):
    _,front,back,flare,tw=pose; A=[];B=[];C=[]
    for e,M,_ in sk:
        col=ring.cols.get(e['uuid'])
        X=ring.column_matrix(col,front,back,flare,tw) if col else np.eye(4)
        for k,q in face_quads(e, S@X@M):
            A+=[q[0],q[0]]; B+=[q[1],q[2]]; C+=[q[2],q[3]]
    return np.array(A),np.array(B),np.array(C)

def run(path, sway_on_belt=True, groups=('chain_belt','bow')):
    model=Model(path); sk=model.cubes('skirt'); ring=Ring(sk); F=Field(ring,sk,0.5,top=0.5,lift=1.2)
    piv=np.array([g for g in model.m['groups'] if g['name']=='skirt'][0]['origin'],float)
    items=[(g,e,M) for g in groups for e,M,_ in model.cubes(g)]
    ps=[p for p in poses() if p[0]=='rest' or ' fl+0.10' not in p[0]]
    worst=(-9,None)
    for sx,sz in [(0,0),(0.07,0.035),(-0.07,-0.035),(0.07,-0.035),(-0.07,0.035)]:
        S=T(piv)@H(rz(sz)@rx(sx))@T(-piv)
        for pose in ps:
            tris=skirt_tris(ring,sk,pose,S)
            for g,e,M in items:
                pts=np.array([p for k,q in face_quads(e,M) for p in samples(q)])
                if not len(pts): continue
                out=[]
                for p in pts:
                    R=F.matrix(p,*pose[1:])
                    w=min(1,max(0,(ring.y+0.5-p[1])/0.5))
                    Sw=np.eye(4)+w*(S-np.eye(4)) if sway_on_belt else np.eye(4)
                    out.append((Sw@R@np.array([*p,1]))[:3])
                d=depth_under(ring,tris,np.array(out)).max()
                if d>worst[0]: worst=(d,(sx,sz,pose[0],e['name']))
    return worst
if __name__=='__main__':
    print('ride + sway:', run(sys.argv[1]))
    print('ride without following the sway:', run(sys.argv[1], False))
