"""Generate a roads-like or POI-like GeoJSONSeq for benchmarking the tilers.

Not a fixture: nothing asserts against this. It exists so the per-phase
performance gate has an input with the shape that matters -- many short polylines
(or many points) spread over a wide area, which is what makes the tile count large
and the spill non-trivial. A handful of long lines would measure the wrong thing.

  python gen_roads.py 200000 bench_roads.geojsonseq lines
  python gen_roads.py 200000 bench_pois.geojsonseq  points
"""

import json
import random
import sys

count = int(sys.argv[1]) if len(sys.argv) > 1 else 200_000
out = sys.argv[2] if len(sys.argv) > 2 else "bench_roads.geojsonseq"
kind = sys.argv[3] if len(sys.argv) > 3 else "lines"
if kind not in ("lines", "points"):
    raise SystemExit(f"kind must be 'lines' or 'points', not {kind!r}")

rng = random.Random(20260826)

# Roughly California's box, so the zoom range the real roads layer uses is honest.
LON0, LON1 = -124.4, -114.1
LAT0, LAT1 = 32.5, 42.0

CLASSES = ["motorway", "trunk", "primary", "secondary", "tertiary", "residential"]

with open(out, "w", encoding="utf-8", newline="\n") as f:
    for i in range(count):
        lon = rng.uniform(LON0, LON1)
        lat = rng.uniform(LAT0, LAT1)
        if kind == "points":
            geometry = {"type": "Point", "coordinates": [round(lon, 7), round(lat, 7)]}
        else:
            # 2-8 vertices, each a short hop, so a feature covers a few tiles at z16
            # rather than thousands.
            n = rng.randint(2, 8)
            coords = []
            for _ in range(n):
                coords.append([round(lon, 7), round(lat, 7)])
                lon += rng.uniform(-0.004, 0.004)
                lat += rng.uniform(-0.004, 0.004)
            geometry = {"type": "LineString", "coordinates": coords}
        feature = {
            "type": "Feature",
            "geometry": geometry,
            "properties": {
                "class": CLASSES[i % len(CLASSES)],
                "name": f"Road {i}",
                "lanes": 1 + (i % 4),
                "maxspeed": 25 + 5 * (i % 13),
            },
        }
        f.write(json.dumps(feature, separators=(",", ":")))
        f.write("\n")

print(f"wrote {count} {kind} feature(s) to {out}")
