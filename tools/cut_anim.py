"""Cut a piece out of an exported animation into an animation of its own (the piece's start becomes 0 s):

    cut_anim.py NAME.animation.json SOURCE FROM TO NEW [--loop] [--add=BONE:CHANNEL:AXIS:AMOUNT ...]

Keys inside the window keep their shape; the values right at FROM and TO are sampled (straight or catmull-rom, as
the keys say) so the piece starts and ends exactly where the source was at those moments. --add shifts one axis
of one channel by a fixed amount (e.g. root:rotation:1:2160 takes six whole turns off a spin, so it starts near 0
and a short blend into it doesn't spin her backwards).
"""
import json
import sys

sys.argv, raw = sys.argv[:1], sys.argv[1:]
args = [a for a in raw if not a.startswith('--')]
flags = [a for a in raw if a.startswith('--')]
path, source, t0, t1, new = args[0], args[1], float(args[2]), float(args[3]), args[4]
loop = '--loop' in flags
adds = [a.split('=', 1)[1].split(':') for a in flags if a.startswith('--add=')]


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
    (ta, a, c1), (tb, b, c2) = ks[i], ks[i + 1]
    f = (t - ta) / (tb - ta)
    if not (c1 or c2):
        return [a[k] + (b[k] - a[k]) * f for k in range(3)]
    p0 = ks[max(0, i - 1)][1]
    p3 = ks[min(len(ks) - 1, i + 2)][1]
    return [0.5 * (2 * a[k] + (-p0[k] + b[k]) * f + (2 * p0[k] - 5 * a[k] + 4 * b[k] - p3[k]) * f * f
                   + (-p0[k] + 3 * a[k] - 3 * b[k] + p3[k]) * f ** 3) for k in range(3)]


def write(ks):
    return {f'{t:.4f}'.rstrip('0').rstrip('.') or '0': ({'post': v, 'lerp_mode': 'catmullrom'} if c else v)
            for t, v, c in ks}


data = json.load(open(path))
src = data['animations'][source]
bones = {}
for bone, channels in src.get('bones', {}).items():
    out = {}
    for channel, chan in channels.items():
        ks = keys(chan)
        piece = [(0.0, sample(ks, t0), False)]
        piece += [(t - t0, v, c) for t, v, c in ks if t0 < t < t1]
        piece.append((t1 - t0, sample(ks, t1), False))
        for b, ch, axis, amount in adds:
            if b == bone and ch == channel:
                piece = [(t, [x + (float(amount) if i == int(axis) else 0) for i, x in enumerate(v)], c) for t, v, c in piece]
        out[channel] = write(piece)
    bones[bone] = out
data['animations'][new] = {'loop': loop, 'animation_length': round(t1 - t0, 4), 'bones': bones}
json.dump(data, open(path, 'w'), indent='\t')
print(f'{new}: {source} {t0}-{t1} s ({t1 - t0:.4f} s){" looping" if loop else ""}')
