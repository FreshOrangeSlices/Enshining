import sys, numpy as np
from skirtsim import *
from check_belt import skirt_tris, samples, depth_under
def run(path, groups=('chain_belt','bow'), mode='field', rest_only=False, fade=1.0, quiet=False, top=0.0, lift=0.0):
    model=Model(path); sk=model.cubes('skirt'); ring=Ring(sk); F=Field(ring,sk,fade,top=top,lift=lift)
    worst={}
    for pose in ([poses()[0]] if rest_only else poses()):
        tris=skirt_tris(ring,sk,pose)
        for g in groups:
            for e,M,gname in model.cubes(g):
                pts=np.array([p for k,q in face_quads(e,M) for p in samples(q)])
                if not len(pts): continue
                if mode=='field': pts=np.array([(F.matrix(p,*pose[1:])@np.array([*p,1]))[:3] for p in pts])
                dep=depth_under(ring,tris,pts); i=int(np.argmax(dep))
                key=(g,gname,e['name'],round(float(e['from'][0]),1),round(float(e['from'][1]),1))
                if key not in worst or dep[i]>worst[key][0]: worst[key]=(float(dep[i]),pose[0],np.round(pts[i],2))
    if not quiet:
        for key,(dep,pn,p) in sorted(worst.items(), key=lambda kv:-kv[1][0]):
            if dep<=-0.5: continue
            flag='HIDDEN ' if dep>0 else ('touch  ' if dep>-0.1 else 'close  ')
            print(f'{flag}{key[0]:10s} {key[1]:20s} {key[2]:14s} {key[3]:5} {key[4]:5} skirt {dep:+5.2f}px ({pn}) at {p}')
    return worst
if __name__=='__main__':
    run(sys.argv[1], mode='static' if '--static' in sys.argv else 'field', rest_only='--rest' in sys.argv)
