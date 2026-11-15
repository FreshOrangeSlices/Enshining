"""Export a Blockbench .bbmodel (GeckoLib format) to geo.json + animation.json + png, following Blockbench's own
Bedrock codec (js/formats/bedrock/bedrock.js and keyframe.js compileBedrockKeyframe), and optionally cut one long
animation into pieces.

usage: bb_export.py model.bbmodel outdir geo_id anim_prefix [--cut SRC=name1@a-b,name2@b-c,...] [--rename OLD=NEW]
"""
import base64, json, sys, os

def fmt_time(t):
    s = f"{t:.4f}".rstrip('0').rstrip('.')
    return s if s else "0"

def num(v):
    try:
        f = float(v)
    except (TypeError, ValueError):
        return v
    f = round(f, 4)
    return int(f) if f == int(f) else f

# ---------------------------------------------------------------- geometry

def export_geo(d, geo_id):
    groups = {g['uuid']: g for g in d.get('groups', [])}
    elements = {e['uuid']: e for e in d['elements']}
    tex = d['textures'][0]
    tw, th = tex.get('uv_width', tex.get('width')), tex.get('uv_height', tex.get('height'))
    res = d.get('resolution', {})
    tw, th = res.get('width', tw), res.get('height', th)
    project_box = d['meta'].get('box_uv', False)
    bones = []

    def cube_size(e):
        return [e['to'][i] - e['from'][i] for i in range(3)]

    def compile_cube(e, bone):
        size = cube_size(e)
        c = {'origin': [e['from'][0], e['from'][1], e['from'][2]], 'size': size}
        if e.get('inflate'):
            c['inflate'] = e['inflate']
        c['origin'][0] = -(c['origin'][0] + size[0])
        rot = e.get('rotation', [0, 0, 0])
        if any(r != 0 for r in rot):
            c['pivot'] = [-e['origin'][0], e['origin'][1], e['origin'][2]]
            c['rotation'] = [-rot[0], -rot[1], rot[2]]
        box = e.get('box_uv', project_box)
        if box:
            c['uv'] = e.get('uv_offset', [0, 0])
            mirror = bool(e.get('mirror_uv', False))
            if mirror == (not bone.get('mirror', False)):
                c['mirror'] = mirror
        else:
            uv = {}
            for key, f in e['faces'].items():
                if f.get('texture') is None:
                    continue
                u = [f['uv'][0], f['uv'][1]]
                us = [f['uv'][2] - f['uv'][0], f['uv'][3] - f['uv'][1]]
                fu = {'uv': u, 'uv_size': us}
                if f.get('rotation'):
                    fu['uv_rotation'] = f['rotation']
                if key in ('up', 'down'):
                    fu['uv'] = [u[0] + us[0], u[1] + us[1]]
                    fu['uv_size'] = [-us[0], -us[1]]
                uv[key] = fu
            c['uv'] = uv
        for k in ('origin', 'size', 'pivot', 'rotation'):
            if k in c:
                c[k] = [num(v) for v in c[k]]
        return c

    def walk(node, parent):
        g = groups.get(node['uuid'], node)
        if g.get('export') is False:
            return
        bone = {'name': g['name']}
        if parent:
            bone['parent'] = parent
        bone['pivot'] = [num(-g['origin'][0]), num(g['origin'][1]), num(g['origin'][2])]
        rot = g.get('rotation', [0, 0, 0])
        if any(r != 0 for r in rot):
            bone['rotation'] = [num(-rot[0]), num(-rot[1]), num(rot[2])]
        if g.get('mirror_uv') and project_box:
            bone['mirror'] = True
        cubes = []
        for ch in node.get('children', []):
            if isinstance(ch, str):
                e = elements.get(ch)
                if e and e.get('export', True) and e.get('type', 'cube') == 'cube':
                    cubes.append(compile_cube(e, bone))
        if cubes:
            bone['cubes'] = cubes
        bones.append(bone)
        for ch in node.get('children', []):
            if isinstance(ch, dict):
                walk(ch, g['name'])

    for node in d['outliner']:
        if isinstance(node, dict):
            walk(node, None)
    return {
        'format_version': '1.12.0',
        'minecraft:geometry': [{
            'description': {'identifier': geo_id, 'texture_width': tw, 'texture_height': th,
                            'visible_bounds_width': 4, 'visible_bounds_height': 4, 'visible_bounds_offset': [0, 1.5, 0]},
            'bones': bones,
        }],
    }

# ---------------------------------------------------------------- animation

def keys_of(animator):
    out = {}
    for k in animator.get('keyframes', []):
        if k['channel'] not in ('rotation', 'position', 'scale'):
            continue
        dp = k['data_points'][0]
        out.setdefault(k['channel'], []).append({
            'time': round(float(k['time']), 4),
            'v': [float(dp[a]) for a in 'xyz'],
            'interp': k.get('interpolation', 'linear'),
            'easing': k.get('easing'),
            'easing_args': k.get('easingArgs'),
        })
    for ks in out.values():
        ks.sort(key=lambda k: k['time'])
    return out

def catmull(p0, p1, p2, p3, t):
    return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3)

def sample(ks, t):
    """Value of a channel at time t, as Blockbench interpolates it (linear, step, catmullrom)."""
    if t <= ks[0]['time']:
        return list(ks[0]['v'])
    if t >= ks[-1]['time']:
        return list(ks[-1]['v'])
    for i in range(len(ks) - 1):
        a, b = ks[i], ks[i + 1]
        if a['time'] <= t <= b['time']:
            if t == a['time']:
                return list(a['v'])
            f = (t - a['time']) / (b['time'] - a['time'])
            if a['interp'] == 'step':
                return list(a['v'])
            if a['interp'] == 'catmullrom' or b['interp'] == 'catmullrom':
                p0 = ks[i - 1]['v'] if i > 0 else a['v']
                p3 = ks[i + 2]['v'] if i + 2 < len(ks) else b['v']
                return [catmull(p0[j], a['v'][j], b['v'][j], p3[j], f) for j in range(3)]
            return [a['v'][j] + (b['v'][j] - a['v'][j]) * f for j in range(3)]

def compile_channel(ch, ks):
    out = {}
    for i, k in enumerate(ks):
        v = list(k['v'])
        if ch in ('position', 'rotation'):
            v[0] = -v[0]
        if ch == 'rotation':
            v[1] = -v[1]
        v = [num(x) for x in v]
        prev = ks[i - 1] if i > 0 else None
        if k['interp'] == 'catmullrom':
            include_pre = (prev is None and k['time'] > 0) or (prev is not None and prev['interp'] != 'catmullrom')
            entry = {'post': v, 'lerp_mode': 'catmullrom'}
            if include_pre:
                entry = {'pre': v, 'post': v, 'lerp_mode': 'catmullrom'}
        elif prev is not None and prev['interp'] == 'step':
            pv = list(prev['v'])
            if ch in ('position', 'rotation'):
                pv[0] = -pv[0]
            if ch == 'rotation':
                pv[1] = -pv[1]
            entry = {'pre': [num(x) for x in pv], 'post': v}
        elif k.get('easing') and k['easing'] != 'linear':
            # GeckoLib easing (e.g. easeOutBack) on a linear key, as the GeckoLib Blockbench plugin writes it
            entry = {'vector': v, 'easing': k['easing']}
            if k.get('easing_args'):
                entry['easingArgs'] = k['easing_args']
        else:
            entry = v
        out[fmt_time(k['time'])] = entry
    return out

def export_anim(a, name, start=None, end=None, loop=None):
    length = a['length']
    bones = {}
    for an in a['animators'].values():
        chans = keys_of(an)
        if not chans:
            continue
        bc = {}
        for ch, ks in chans.items():
            if start is not None:
                inside = [k for k in ks if start < k['time'] < end]
                first = {'time': start, 'v': sample(ks, start), 'interp': next((k['interp'] for k in ks if k['time'] >= start), ks[-1]['interp'])}
                last = {'time': end, 'v': sample(ks, end), 'interp': next((k['interp'] for k in ks if k['time'] >= end), ks[-1]['interp'])}
                ex_first = next((k for k in ks if abs(k['time'] - start) < 1e-4), None)
                ex_last = next((k for k in ks if abs(k['time'] - end) < 1e-4), None)
                seq = [dict(ex_first) if ex_first else first] + [dict(k) for k in inside] + [dict(ex_last) if ex_last else last]
                for k in seq:
                    k['time'] = round(k['time'] - start, 4)
                snap = None
                if ch == 'rotation':
                    # Each piece starts within -180..180 (whole turns removed; same pose, so pieces still join up).
                    shift = [-360 * round(seq[0]['v'][j] / 360) for j in range(3)]
                    for k in seq:
                        k['v'] = [k['v'][j] + shift[j] for j in range(3)]
                    # And ends there too: if the piece spins past it, jump by whole turns on its very last frame
                    # (invisible), so holding or blending out to idle never twirls back.
                    end_v = seq[-1]['v']
                    norm = [end_v[j] - 360 * round(end_v[j] / 360) for j in range(3)]
                    if any(abs(norm[j] - end_v[j]) > 1e-6 for j in range(3)):
                        snap = norm
                ks = seq
                bc[ch] = compile_channel(ch, ks)
                if snap is not None:
                    last = fmt_time(ks[-1]['time'])
                    v = list(snap)
                    v[0], v[1] = -v[0], -v[1]
                    pre = ks[-1]['v']
                    pre = [-pre[0], -pre[1], pre[2]]
                    bc[ch][last] = {'pre': [num(x) for x in pre], 'post': [num(x) for x in v]}
                continue
            bc[ch] = compile_channel(ch, ks)
        bones[an['name']] = bc
    out = {'animation_length': num(end - start if start is not None else length), 'bones': bones}
    lp = loop if loop is not None else a.get('loop')
    if lp == 'loop':
        out = {'loop': True, **out}
    elif lp == 'hold':
        out = {'loop': 'hold_on_last_frame', **out}
    return name, out

def main():
    src, outdir, geo_id, prefix = sys.argv[1:5]
    cuts, renames, skip = {}, {}, set()
    args = sys.argv[5:]
    while args:
        flag, val = args[0], args[1]
        args = args[2:]
        if flag == '--cut':
            s, spec = val.split('=', 1)
            cuts[s] = []
            for part in spec.split(','):
                nm, rng = part.split('@')
                a, b = rng.split('-')
                cuts[s].append((nm, float(a), float(b)))
        elif flag == '--rename':
            o, n = val.split('=')
            renames[o] = n
        elif flag == '--skip':
            skip.add(val)
    d = json.load(open(src))
    os.makedirs(outdir, exist_ok=True)
    json.dump(export_geo(d, geo_id), open(os.path.join(outdir, f'{prefix}.geo.json'), 'w'), indent='\t')
    anims = {}
    for a in d['animations']:
        if a['name'] in skip:
            continue
        if a['name'] in cuts:
            for nm, s, e in cuts[a['name']]:
                k, v = export_anim(a, nm, s, e, loop='once')
                anims[k] = v
            continue
        k, v = export_anim(a, renames.get(a['name'], a['name']))
        anims[k] = v
    json.dump({'format_version': '1.8.0', 'animations': anims}, open(os.path.join(outdir, f'{prefix}.animation.json'), 'w'), indent='\t')
    src_tex = d['textures'][0]['source']
    png = base64.b64decode(src_tex.split(',', 1)[1])
    open(os.path.join(outdir, f'{prefix}.png'), 'wb').write(png)
    print('animations:', {k: v['animation_length'] for k, v in anims.items()})

if __name__ == '__main__':
    main()
