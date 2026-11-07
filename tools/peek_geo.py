"""Throwaway: summarize how a model in another mod's jar is built (bones, angles, cube sizes), for study.
Usage: peek_geo.py <jar> <name fragment>"""
import json, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
names = [n for n in z.namelist() if n.endswith('.geo.json') and sys.argv[2] in n.lower()]
out = []
for n in names:
    g = json.loads(z.read(n))
    for geo in g.get('minecraft:geometry', []):
        d = geo['description']
        out.append(f"== {n} texture {d.get('texture_width')}x{d.get('texture_height')}")
        for b in geo.get('bones', []):
            cubes = b.get('cubes', [])
            line = f"{b['name']} <- {b.get('parent')} pivot {b.get('pivot')} rot {b.get('rotation')} cubes {len(cubes)}"
            out.append(line)
            for c in cubes[:12]:
                uv = c.get('uv'); uvk = 'per-face' if isinstance(uv, dict) else 'box'
                out.append(f"   size {c.get('size')} origin {c.get('origin')} rot {c.get('rotation')} pivot {c.get('pivot')} inflate {c.get('inflate')} uv {uvk}")
anims = [n for n in z.namelist() if n.endswith('.animation.json') and sys.argv[2] in n.lower()]
for n in anims:
    a = json.loads(z.read(n))
    out.append(f"== {n}: " + ", ".join(sorted(a.get('animations', {}).keys())))
    for k, v in a.get('animations', {}).items():
        skirt = [b for b in v.get('bones', {}) if any(s in b.lower() for s in ('skirt', 'dress', 'cloth', 'apron'))]
        if skirt:
            out.append(f"  {k}: animates {skirt}")
text = "\n".join(out) or "nothing matched; geo files: " + ", ".join(n for n in z.namelist() if n.endswith('.geo.json'))
esc = lambda s: s.replace('%', '%25').replace('\n', '%0A')
for i in range(0, min(len(text), 3900 * 10), 3900):
    print(f"::notice title=peek-{i // 3900:02d}::{esc(text[i:i + 3900])}")
