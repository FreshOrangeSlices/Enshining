"""Split a pose animation into a one-time start and a repeating rest, for poses that should settle in and then keep
going (the menu poses). For each NAME=SECONDS given, adds NAME_intro (0 to SECONDS, played once) and NAME_loop
(SECONDS to the end, repeating; it eases back to where it started over EASE seconds if the end doesn't already
match). The game plays NAME_intro then loops NAME_loop when both exist; NAME itself is kept as it was.

usage: pose_loops.py animations.json NAME=SECONDS [NAME=SECONDS ...] [--ease 0.5]
"""
import json, sys

path = sys.argv[1]
args = sys.argv[2:]
ease = 0.5
if '--ease' in args:
    i = args.index('--ease')
    ease = float(args[i + 1])
    del args[i:i + 2]
data = json.load(open(path))
anims = data['animations']


def keys(chan):
    """[(time, value, catmull)] sorted, from a channel in any of the exported shapes."""
    if not isinstance(chan, dict) or 'post' in chan or 'vector' in chan:
        chan = {'0': chan}
    out = []
    for t, v in chan.items():
        cat = False
        if isinstance(v, dict) and 'post' in v:
            cat = v.get('lerp_mode') == 'catmullrom'
            v = v['post']
        if isinstance(v, dict):
            v = v['vector']
        if not isinstance(v, list):
            v = [v, v, v]
        out.append((float(t), [float(x) for x in v], cat))
    return sorted(out)


def sample(ks, t):
    if t <= ks[0][0]:
        return ks[0][1]
    if t >= ks[-1][0]:
        return ks[-1][1]
    i = max(j for j in range(len(ks)) if ks[j][0] <= t)
    (t1, a, c1), (t2, b, c2) = ks[i], ks[i + 1]
    f = (t - t1) / (t2 - t1)
    if not (c1 or c2):
        return [a[k] + (b[k] - a[k]) * f for k in range(3)]
    p0 = ks[max(0, i - 1)][1]
    p3 = ks[min(len(ks) - 1, i + 2)][1]
    return [0.5 * (2 * a[k] + (-p0[k] + b[k]) * f + (2 * p0[k] - 5 * a[k] + 4 * b[k] - p3[k]) * f * f
                   + (-p0[k] + 3 * a[k] - 3 * b[k] + p3[k]) * f ** 3) for k in range(3)]


def write(ks):
    return {f'{t:.4f}'.rstrip('0').rstrip('.') or '0': ({'post': v, 'lerp_mode': 'catmullrom'} if c else v)
            for t, v, c in ks}


for arg in args:
    name, at = arg.split('=')
    full = name if name.startswith('animation.') else 'animation.companion.' + name
    an = anims[full]
    t0, end = float(at), float(an['animation_length'])
    intro, loop = {}, {}
    length = end - t0
    for bone, chans in an['bones'].items():
        for ch, chan in chans.items():
            ks = keys(chan)
            start = [round(x, 4) for x in sample(ks, t0)]
            last = [round(x, 4) for x in sample(ks, end)]
            ik = [k for k in ks if k[0] < t0 - 1e-4] + [(t0, start, True)]
            lk = [(0.0, start, True)] + [(k[0] - t0, k[1], k[2]) for k in ks if t0 + 1e-4 < k[0] <= end + 1e-4]
            if lk[-1][0] < length - 1e-4:
                lk.append((length, last, True))
            if any(abs(a - b) > 1e-3 for a, b in zip(start, last)):
                lk.append((length + ease, start, True))   # ease back to where the loop starts
            intro.setdefault(bone, {})[ch] = write(ik)
            loop.setdefault(bone, {})[ch] = write(lk)
    loop_len = max(float(t) for chans in loop.values() for chan in chans.values() for t in chan)
    anims[full + '_intro'] = {'animation_length': t0, 'bones': intro}
    anims[full + '_loop'] = {'loop': True, 'animation_length': max(length, loop_len), 'bones': loop}
    print(f'{full}: intro 0-{t0}s, then loops {t0}-{end}s ({max(length, loop_len):.2f}s)')
json.dump(data, open(path, 'w'), indent='\t')
