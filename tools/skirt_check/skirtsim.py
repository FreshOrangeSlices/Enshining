"""Exact Python port of CompanionRenderer's SkirtRing + renderSkirtCubes (Blockbench space, pixels),
plus world placement of any group's cubes, so the belt can be checked against the moving skirt."""
import json, math, numpy as np

def rx(a): c,s=math.cos(a),math.sin(a); return np.array([[1,0,0],[0,c,-s],[0,s,c]])
def ry(a): c,s=math.cos(a),math.sin(a); return np.array([[c,0,s],[0,1,0],[-s,0,c]])
def rz(a): c,s=math.cos(a),math.sin(a); return np.array([[c,-s,0],[s,c,0],[0,0,1]])
def eul(d): x,y,z=np.radians(d); return rz(z)@ry(y)@rx(x)
def axis_rot(ax, a):
    ax=np.asarray(ax,float); ax=ax/np.linalg.norm(ax); x,y,z=ax; c,s=math.cos(a),math.sin(a); C=1-c
    return np.array([[c+x*x*C, x*y*C-z*s, x*z*C+y*s],[y*x*C+z*s, c+y*y*C, y*z*C-x*s],[z*x*C-y*s, z*y*C+x*s, c+z*z*C]])
def H(R=np.eye(3), t=(0,0,0)):
    m=np.eye(4); m[:3,:3]=R; m[:3,3]=t; return m
def T(t): return H(np.eye(3), t)

class Model:
    def __init__(self, path_or_dict):
        self.m = json.load(open(path_or_dict)) if isinstance(path_or_dict, str) else path_or_dict
        self.groups={g['uuid']:g for g in self.m['groups']}; self.els={e['uuid']:e for e in self.m['elements']}
        self.root=[n for n in self.m['outliner'] if isinstance(n,dict)]
    def node(self, name):
        def f(n):
            if self.groups[n['uuid']]['name']==name: return n
            for c in n['children']:
                if isinstance(c,dict):
                    r=f(c)
                    if r: return r
        for n in self.root:
            r=f(n)
            if r: return r
    def cubes(self, name, base=None, extra=None):
        """(element, 4x4 matrix element-space -> frame of NAME's parent) for every cube under group NAME.
        extra(group_name) -> optional 4x4 local rotation added to a group (for sway tests)."""
        out=[]
        def walk(n, W):
            g=self.groups[n['uuid']]; o=np.array(g['origin'],float)
            R=eul(g.get('rotation',[0,0,0]))
            if extra and extra(g['name']) is not None: R=R@extra(g['name'])
            W=W@T(o)@H(R)@T(-o)
            for c in n['children']:
                if isinstance(c,str):
                    e=self.els[c]
                    eo=np.array(e.get('origin',[0,0,0]),float)
                    out.append((e, W@T(eo)@H(eul(e.get('rotation') or [0,0,0]))@T(-eo), g['name']))
                else: walk(c,W)
        walk(self.node(name), np.eye(4) if base is None else base)
        return out

def corners(e):
    f=np.array(e['from'],float); t=np.array(e['to'],float); inf=e.get('inflate') or 0
    f=f-inf; t=t+inf
    return f,t

def face_quads(e, M):
    f,t=corners(e); x0,y0,z0=f; x1,y1,z1=t
    Q={'north':[(x1,y1,z0),(x0,y1,z0),(x0,y0,z0),(x1,y0,z0)],'south':[(x0,y1,z1),(x1,y1,z1),(x1,y0,z1),(x0,y0,z1)],
       'east':[(x1,y1,z1),(x1,y1,z0),(x1,y0,z0),(x1,y0,z1)],'west':[(x0,y1,z0),(x0,y1,z1),(x0,y0,z1),(x0,y0,z0)],
       'up':[(x0,y1,z0),(x1,y1,z0),(x1,y1,z1),(x0,y1,z1)],'down':[(x0,y0,z1),(x1,y0,z1),(x1,y0,z0),(x0,y0,z0)]}
    out=[]
    for k,q in Q.items():
        fc=e['faces'].get(k)
        if not fc or fc.get('texture') is None: continue
        out.append((k,[(M@np.array([*p,1]))[:3] for p in q]))
    return out

class Ring:
    """SkirtRing.of: per skirt cube hinge (x,y,z), facing (dx,dz), front weight, back weight, hang."""
    def __init__(self, skirt_cubes):
        tops=[]
        for e,M,_ in skirt_cubes:
            f,t=corners(e); tops.append(np.array([(f[0]+t[0])/2, max(f[1],t[1]), (f[2]+t[2])/2]))
        topY=max(t[1] for t in tops)
        waist=[t for t in tops if t[1]>=topY-0.3]
        cx=np.mean([t[0] for t in waist]); cz=np.mean([t[2] for t in waist]); y=np.mean([t[1] for t in waist])
        ang=lambda p: math.atan2(p[0]-cx, p[2]-cz)
        waist.sort(key=ang); angles=[ang(p) for p in waist]
        depth=max(1e-4*16, max(abs(t[2]-cz) for t in waist))
        self.cx,self.cz,self.y=cx,cz,y; self.cols={}
        n=len(waist)
        def on(a):
            nx=0
            while nx<n and angles[nx]<=a: nx+=1
            if nx in (0,n):
                p,q,fr,to=waist[-1],waist[0],angles[-1],angles[0]+2*math.pi; at=a+2*math.pi if a<fr else a
            else: p,q,fr,to,at=waist[nx-1],waist[nx],angles[nx-1],angles[nx],a
            ff=0 if to-fr<1e-5 else min(1,max(0,(at-fr)/(to-fr)))
            return (p[0]+(q[0]-p[0])*ff, p[2]+(q[2]-p[2])*ff)
        self.on=on
        for (e,M,_),t in zip(skirt_cubes,tops):
            hx,hz=on(ang(t)); r=np.array([t[0]-cx,t[2]-cz]); L=np.linalg.norm(r)
            if L<1e-4: continue
            r/=L
            f,tt=corners(e); size=tt-f
            if abs(size[2])<1e-9: nrm=np.array([0,0,1.])
            elif abs(size[0])<1e-9: nrm=np.array([1.,0,0])
            else: nrm=None
            if nrm is not None:
                nrm=eul(e.get('rotation') or [0,0,0])@nrm; l=math.hypot(nrm[0],nrm[2])
                fac=r if l<0.2 else np.array([nrm[0]/l,nrm[2]/l])
                if fac@r<0: fac=-fac
            else: fac=r
            side=min(1,max(-1,(hz-cz)/depth)); hang=min(1,max(0,(y-t[1])/6))
            self.cols[e['uuid']]=(np.array([hx,y,hz]), fac, side*side if side<0 else 0, side*side if side>0 else 0, hang)
    def column_matrix(self, col, front, back, flare, twist):
        h,fac,fw,bw,hang=col; M=np.eye(4)
        if twist!=0 and hang>0:
            M=M@T((self.cx,0,self.cz))@H(ry(twist*hang))@T((-self.cx,0,-self.cz))
        sw=front*fw+back*bw
        if sw!=0 or flare!=0:
            M=M@T(h)@H(axis_rot((-fac[1],0,fac[0]),flare))@H(rx(sw))@T(-h)
        return M

def limits(flare):
    fl=max(0,flare); return max(0,0.349-1.9*fl), max(0,0.26-0.6*fl)

def poses():
    """Every extreme the skirt can reach, plus rest."""
    out=[('rest',0,0,0,0)]
    for flare in (-0.03,0,0.1,0.21):
        L,TL=limits(flare)
        for front in (-L,0,L):
            for back in (-L,0,L):
                for tw in (-TL,0,TL):
                    out.append((f'f{front:+.2f} b{back:+.2f} fl{flare:+.2f} tw{tw:+.2f}',front,back,flare,tw))
    return out

class Field:
    """What the ride-on-the-skirt code does to one point of something worn over the skirt (waist_extras):
    the skirt's motion at that spot around the waist, faded in over the first pixel below the waist line."""
    def __init__(self, ring, sk, fade=1.0, twist=False, top=0.0, lift=0.0):
        self.ring=ring; self.fade=fade; self.twist=twist; self.top=top; self.lift=lift
        cx,cz=ring.cx,ring.cz
        tops=[]
        for e,M,_ in sk:
            f,t=corners(e); tops.append((e,np.array([(f[0]+t[0])/2, max(f[1],t[1]), (f[2]+t[2])/2])))
        topY=max(t[1] for _,t in tops)
        w=[(math.atan2(t[0]-cx,t[2]-cz),t,ring.cols[e['uuid']][1]) for e,t in tops if t[1]>=topY-0.3]
        w.sort(key=lambda a:a[0]); self.w=w
        self.depth=max(abs(t[2]-cz) for _,t,_ in w)
    def facing(self, a):
        w=self.w; n=len(w); nx=0
        while nx<n and w[nx][0]<=a: nx+=1
        if nx in (0,n): p,q,fr,to=w[-1],w[0],w[-1][0],w[0][0]+2*math.pi; at=a+2*math.pi if a<fr else a
        else: p,q,fr,to,at=w[nx-1],w[nx],w[nx-1][0],w[nx][0],a
        ff=0 if to-fr<1e-5 else min(1,max(0,(at-fr)/(to-fr)))
        v=p[2]+(q[2]-p[2])*ff; return v/np.linalg.norm(v)
    def matrix(self, p, front, back, flare, twist):
        r=self.ring; a=math.atan2(p[0]-r.cx,p[2]-r.cz)
        hx,hz=r.on(a); fac=self.facing(a)
        side=min(1,max(-1,(hz-r.cz)/self.depth)); fw=side*side if side<0 else 0; bw=side*side if side>0 else 0
        wgt=min(1,max(0,(r.y+self.top-p[1])/self.fade))
        sw=(front*fw+back*bw)*wgt; h=np.array([hx,r.y,hz])
        M=T(h)@H(axis_rot((-fac[1],0,fac[0]),flare*wgt))@H(rx(sw))@T(-h)
        if self.lift:
            amt=self.lift*(max(abs(front),abs(back))+abs(flare)+0.5*abs(twist))*wgt
            d=np.array([p[0]-r.cx,0,p[2]-r.cz]); d/=max(1e-6,np.linalg.norm(d))
            M=T(amt*d)@M
        return M
