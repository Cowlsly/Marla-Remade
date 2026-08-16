#!/usr/bin/env python3
"""normalize_maxspeed.py — build the `maxspeed` PMTiles layer from OSM ways.

P5b's MaxspeedSource (maps app) reads the posted speed limit by calling
queryRenderedFeatures on a vector source-layer named `maxspeed`, reading the
`maxspeed` property — the raw OSM maxspeed tag value, e.g. "35 mph", "50",
"50 km/h". We bake that into v5.pmtiles so the posted-speed-limit feature works
from the same file (no separate maxspeed.pmtiles host).

Reads GeoJSON (FeatureCollection or geojsonseq, e.g. from `osmium export`) of
highway ways carrying a `maxspeed` tag; writes geojsonseq with a minimal
schema: { maxspeed, highway?, name?, osm_id }. Line geometry only.

Usage:
    osmium tags-filter planet.osm.pbf w/maxspeed -o ms.osm.pbf
    osmium export -f geojsonseq ms.osm.pbf | python3 normalize_maxspeed.py > maxspeed.geojsonseq

Pure stdlib so it can run and be unit-tested without pyosmium/tippecanoe.
"""
from __future__ import annotations

import json
import sys
from typing import Any, Iterable, Iterator, Optional

LINE_TYPES = ("LineString", "MultiLineString")


def extract_maxspeed(tags: dict[str, Any]) -> Optional[str]:
    """Return the maxspeed value to bake, or None to drop the feature.

    Keeps the raw OSM string (MaxspeedSource parses "mph"/"km/h"/bare number).
    Prefers the forward/backward-agnostic `maxspeed`; falls back to directional
    tags if only those exist.
    """
    for key in ("maxspeed", "maxspeed:forward", "maxspeed:backward"):
        val = tags.get(key)
        if val not in (None, ""):
            return str(val)
    return None


def build_properties(maxspeed: str, tags: dict[str, Any], feature: dict[str, Any]) -> dict[str, Any]:
    props: dict[str, Any] = {"maxspeed": maxspeed}
    highway = tags.get("highway")
    if highway:
        props["highway"] = highway
    name = tags.get("name")
    if name:
        props["name"] = name
    osm_id = feature.get("id") or tags.get("@id") or tags.get("id") or tags.get("osm_id")
    if osm_id is not None:
        props["osm_id"] = str(osm_id)
    return props


def iter_features(raw: str) -> Iterator[dict[str, Any]]:
    stripped = raw.lstrip()
    if stripped.startswith("{") and '"FeatureCollection"' in stripped[:200]:
        try:
            obj = json.loads(raw)
            if obj.get("type") == "FeatureCollection":
                yield from obj.get("features", [])
                return
        except json.JSONDecodeError:
            pass
    for line in raw.splitlines():
        line = line.strip().lstrip("\x1e").strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if obj.get("type") == "Feature":
            yield obj
        elif obj.get("type") == "FeatureCollection":
            yield from obj.get("features", [])


def normalize(features: Iterable[dict[str, Any]]) -> Iterator[dict[str, Any]]:
    for feature in features:
        geom = feature.get("geometry") or {}
        if geom.get("type") not in LINE_TYPES:
            continue
        tags = feature.get("properties") or {}
        maxspeed = extract_maxspeed(tags)
        if maxspeed is None:
            continue
        yield {
            "type": "Feature",
            "geometry": geom,
            "properties": build_properties(maxspeed, tags, feature),
        }


def main(argv: list[str]) -> int:
    raw = sys.stdin.read()
    count = 0
    out = sys.stdout
    for feature in normalize(iter_features(raw)):
        out.write(json.dumps(feature, separators=(",", ":"), ensure_ascii=False))
        out.write("\n")
        count += 1
    sys.stderr.write(f"normalize_maxspeed: emitted {count} feature(s)\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
