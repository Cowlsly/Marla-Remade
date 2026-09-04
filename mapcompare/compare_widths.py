import json, pathlib
flat=json.loads(open(r'c:\Users\Vayun\Documents\code\Modern-Apps\library\map\src\main\rust\style\basemap.flat.json').read())
def at_ramp(ramp, zoom):
    if ramp is None: return 0.0
    if isinstance(ramp, (int,float)): return float(ramp)
    base=ramp.get('base',1.6) if isinstance(ramp, dict) else 1.0
    stops=ramp.get('stops') if isinstance(ramp, dict) else None
    if stops is None: return float(ramp) if isinstance(ramp,(int,float)) else 0.0
    if zoom <= stops[0][0]: return stops[0][1]
    if zoom >= stops[-1][0]: return stops[-1][1]
    for i in range(len(stops)-1):
        if zoom <= stops[i+1][0]:
            lo, hi = stops[i], stops[i+1]
            span=hi[0]-lo[0]
            if base==1.0: t=(zoom-lo[0])/span
            else: t=(base** (zoom-lo[0])-1)/(base**span-1)
            return lo[1]+(hi[1]-lo[1])*t
    return stops[-1][1]
def flat_ramp(layer, key): return layer.get(key)
for z in [6,10,14,16,17,18]:
    print(f"--- z{z} ---")
    for id_ in ['roads-minor','roads-major','roads-highway','roads-highway-casing','roads-major-casing','roads-minor-casing']:
        fl = next(l for l in flat['layers'] if l['id']==id_)
        print(f"{id_:22} w={at_ramp(flat_ramp(fl,'width'),z):5.2f} gap={at_ramp(flat_ramp(fl,'gap_width'),z):5.2f}")
