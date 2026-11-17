"""Small helpers shared by the model tools: reading a .bbmodel's tree and texture, finding free texture space,
and turning a rotation matrix into Blockbench's angles."""
import base64, io, json, math, uuid

import numpy as np
from PIL import Image

uid = lambda: str(uuid.uuid4())


class Project:
    def __init__(self, path):
        self.m = json.load(open(path))
        self.groups = {g['uuid']: g for g in self.m['groups']}
        self.els = {e['uuid']: e for e in self.m['elements']}
        self.nodes = {}

        def walk(n):
            self.nodes[self.groups[n['uuid']]['name']] = n
            for c in n['children']:
                if isinstance(c, dict):
                    walk(c)
        for n in self.m['outliner']:
            if isinstance(n, dict):
                walk(n)
        t = self.m['textures'][0]
        self.tex = Image.open(io.BytesIO(base64.b64decode(t['source'].split(',', 1)[1]))).convert('RGBA')

    def group(self, name, parent, origin=(0, 0, 0)):
        """The group NAME (made under PARENT if missing)."""
        if name in self.nodes:
            return self.nodes[name]
        g = {'name': name, 'uuid': uid(), 'export': True, 'locked': False, 'scope': 0, 'selected': False,
             'visibility': True, 'origin': list(origin), 'rotation': [0, 0, 0], 'color': 0, 'reset': False, 'shade': True,
             'mirror_uv': False, 'autouv': 0, 'isOpen': False}
        self.m['groups'].append(g)
        self.groups[g['uuid']] = g
        node = {'uuid': g['uuid'], 'isOpen': False, 'children': []}
        self.nodes[parent]['children'].append(node)
        self.nodes[name] = node
        return node

    def cube(self, node, name, frm, to, origin, rotation=(0, 0, 0), faces=None, inflate=0):
        e = {'name': name, 'box_uv': False, 'render_order': 'default', 'locked': False, 'export': True,
             'allow_mirror_modeling': True, 'from': [round(v, 4) for v in frm], 'to': [round(v, 4) for v in to],
             'autouv': 0, 'color': 0, 'origin': [round(v, 4) for v in origin],
             'rotation': [round(v, 3) for v in rotation], 'type': 'cube', 'uuid': uid(),
             'faces': {k: {'uv': [0, 0, 0, 0], 'texture': None} for k in ('north', 'east', 'south', 'west', 'up', 'down')}}
        if inflate:
            e['inflate'] = inflate
        for k, uv in (faces or {}).items():
            e['faces'][k] = {'uv': list(uv), 'texture': 0}
        self.m['elements'].append(e)
        self.els[e['uuid']] = e
        node['children'].append(e['uuid'])
        return e

    def cubes_under(self, name, direct=False):
        out = []

        def walk(n):
            for c in n['children']:
                if isinstance(c, str):
                    out.append(self.els[c])
                elif not direct:
                    walk(c)
        walk(self.nodes[name])
        return out

    def free_spot(self, w, h, start_row=64):
        """Top-left of a free W x H block on the texture: no face reads it and nothing is painted there."""
        W, H = self.tex.size
        taken = np.zeros((H, W), bool)
        for e in self.m['elements']:
            if e.get('box_uv'):
                cw, ch, cd = (math.ceil(e['to'][i] - e['from'][i]) for i in range(3))
                u, v = e.get('uv_offset', [0, 0])
                taken[v:v + cd + ch, u:u + 2 * (cd + cw)] = True
                continue
            for f in e['faces'].values():
                if f.get('texture') is None:
                    continue
                a, b, c, d = f['uv']
                taken[int(math.floor(min(b, d))):int(math.ceil(max(b, d))), int(math.floor(min(a, c))):int(math.ceil(max(a, c)))] = True
        taken |= np.array(self.tex.getchannel('A')) > 0
        for y in range(start_row, H - h + 1):
            for x in range(0, W - w + 1):
                if not taken[y:y + h + 1, x:x + w + 1].any():   # with a pixel to spare right and below
                    return x, y
        raise SystemExit(f'no free {w}x{h} space on the texture')

    def save(self, path):
        buf = io.BytesIO()
        self.tex.save(buf, 'PNG')
        self.m['textures'][0]['source'] = 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()
        json.dump(self.m, open(path, 'w'))


def rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def blockbench_angles(R):
    """Degrees (x, y, z) with Rz(z) @ Ry(y) @ Rx(x) == R (Blockbench's order)."""
    y = math.asin(max(-1.0, min(1.0, -R[2, 0])))
    if abs(math.cos(y)) > 1e-6:
        x = math.atan2(R[2, 1], R[2, 2])
        z = math.atan2(R[1, 0], R[0, 0])
    else:
        x, z = math.atan2(-R[1, 2], R[1, 1]), 0.0
    return tuple(math.degrees(v) for v in (x, y, z))
