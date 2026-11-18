"""How deep under the skirt any part of the waist items goes, at rest and at every extreme of the skirt's motion.
usage: check_belt.py model.bbmodel [--ride]   (--ride: each waist cube follows the skirt panel it lies on)"""
import sys, math, numpy as np
from skirtsim import *

def tri_hits(O, D, A, B, C):
    """Möller–Trumbore, every ray (N) against every triangle (M): distance or inf, shape N x M."""
    e1=B-A; e2=C-A                                   # M x 3
    P=np.cross(D[:,None,:], e2[None,:,:])            # N x M x 3
    det=np.einsum('nmk,mk->nm',P,e1)
    ok=np.abs(det)>1e-9; inv=np.where(ok,1/np.where(ok,det,1),0)
    Tv=O[:,None,:]-A[None,:,:]
    u=np.einsum('nmk,nmk->nm',Tv,P)*inv
    Q=np.cross(Tv,e1[None,:,:])
    v=np.einsum('nk,nmk->nm',D,Q)*inv
    t=np.einsum('mk,nmk->nm',e2,Q)*inv
    hit=ok&(u>=-1e-6)&(v>=-1e-6)&(u+v<=1+1e-6)&(t>0)
    return np.where(hit,t,np.inf)

def samples(quad, k=3):
    p0,p1,p2,p3=[np.asarray(v) for v in quad]; out=[]
    for i in range(k+1):
        for j in range(k+1):
            a,b=i/k,j/k; out.append((1-a)*(1-b)*p0+a*(1-b)*p1+a*b*p2+(1-a)*b*p3)
    return out

def skirt_tris(ring, sk, pose):
    _,front,back,flare,tw=pose; A=[];B=[];C=[]
    for e,M,_ in sk:
        col=ring.cols.get(e['uuid'])
        X=ring.column_matrix(col,front,back,flare,tw) if col else np.eye(4)
        for k,q in face_quads(e, X@M):
            A+= [q[0],q[0]]; B+=[q[1],q[2]]; C+=[q[2],q[3]]
    return np.array(A),np.array(B),np.array(C)

def nearest_col(ring, sk, p):
    best=None
    for e,M,_ in sk:
        f,t=corners(e); c=(M@np.array([*(f+t)/2,1]))[:3]; d=np.linalg.norm(c-p)
        if e['uuid'] in ring.cols and (best is None or d<best[0]): best=(d,ring.cols[e['uuid']],e['name'])
    return best

def depth_under(ring, tris, pts):
    """For each point: how far the skirt is outside it along the ray from the waist's middle (>-0.05 = hidden/z-fight)."""
    O=np.column_stack([np.full(len(pts),ring.cx),pts[:,1],np.full(len(pts),ring.cz)])
    D=pts-O; D[:,1]=0; r=np.linalg.norm(D,axis=1); r[r<1e-6]=1e-6; D/=r[:,None]
    t=tri_hits(O,D,*tris)
    t=np.where(np.isfinite(t),t,-np.inf).max(axis=1)   # outermost skirt surface along that ray
    return t-r

def run(path, ride, groups=('chain_belt','bow'), poses_=None, verbose=True, assign=None):
    model=Model(path)
    sk=model.cubes('skirt'); ring=Ring(sk)
    items=[]
    for g in groups:
        for e,M,gname in model.cubes(g):
            f,t=corners(e); c=(M@np.array([*(f+t)/2,1]))[:3]
            col=(assign or nearest_col)(ring,sk,c)
            items.append((g,e,M,gname,col))
    worst={}
    for pose in (poses_ or poses()):
        tris=skirt_tris(ring,sk,pose)
        for g,e,M,gname,nc in items:
            X=ring.column_matrix(nc[1],*pose[1:]) if (ride and nc) else np.eye(4)
            pts=np.array([p for k,q in face_quads(e, X@M) for p in samples(q)])
            if not len(pts): continue
            dep=depth_under(ring,tris,pts); i=int(np.argmax(dep))
            key=(g,gname,e['name'],round(float(e['from'][0]),1),round(float(e['from'][1]),1))
            if key not in worst or dep[i]>worst[key][0]: worst[key]=(float(dep[i]),pose[0],np.round(pts[i],2))
    if verbose:
        for key,(dep,pn,p) in sorted(worst.items(), key=lambda kv:-kv[1][0]):
            flag='HIDDEN ' if dep>0 else ('touch  ' if dep>-0.05 else 'clear  ')
            print(f'{flag}{key[0]:10s} {key[1]:20s} {key[2]:14s} skirt {dep:+5.2f}px past it ({pn}) at {p}')
    return worst

if __name__=='__main__':
    run(sys.argv[1], '--ride' in sys.argv, poses_=[poses()[0]] if '--rest' in sys.argv else None)
