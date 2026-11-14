"""Make a rig use its texture 1:1 like her old file: UV grid = image size (128x256), every face keeps its painted
area (UVs doubled, box UV turned into per-face UV), so each model pixel is 2x2 texture pixels."""
import json, sys
m = json.load(open(sys.argv[1]))
k = 2
for e in m['elements']:
    for f in e['faces'].values():
        f['uv'] = [round(v * k, 4) for v in f['uv']]
    if e.get('box_uv'):
        e['box_uv'] = False
        e.pop('uv_offset', None)
    e['autouv'] = 0
m['meta']['box_uv'] = False
m['resolution'] = {'width': 64 * k, 'height': 128 * k}
t = m['textures'][0]
t.update({'uv_width': 64 * k, 'uv_height': 128 * k})
json.dump(m, open(sys.argv[2], 'w'))
print('resolution', m['resolution'], 'image', t['width'], t['height'])
