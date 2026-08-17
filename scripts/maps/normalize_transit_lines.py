#!/usr/bin/env python3
"""normalize_transit_lines.py — build the `transit_lines` PMTiles layer from OSM.

The maps app's transit-line highlight (P22) reads transit geometry from a vector
source-layer named `transit_lines` and styles each feature by its `kind` and, when
present, the route `colour`. We bake that into v5.pmtiles so the app can highlight
rail/subway/tram/etc. lines with no runtime Overpass query.

Two OSM sources feed this layer, both arriving here as GeoJSON(Seq):

  1. railway WAYS  — from `osmium export -f geojsonseq` of ways tagged
     railway ∈ {rail, subway, light_rail, tram, monorail, narrow_gauge}.
     Properties are the raw OSM tags (flat), e.g. {"railway":"subway", ...}.

  2. route RELATIONS — from `ogr2ogr -f GeoJSONSeq … multilinestrings` (GDAL OSM
     driver), which assembles relation geometry as MultiLineString and carries the
     relation's tags. GDAL exposes a few named fields (name, type, osm_id) and
     folds the rest into an `other_tags` HSTORE string
     (e.g. "\"route\"=>\"subway\",\"colour\"=>\"#DA291C\""). We parse that back to
     tags so `route` and `colour` are recovered. type=route relations only.

Emitted schema (newline-delimited GeoJSON, LineString/MultiLineString only):

    { kind, name?, ref?, colour?, osm_id }
    kind ∈ { rail, subway, light_rail, tram, monorail, train }

Usage:
    cat ways.geojsonseq relations.geojsonseq | python3 normalize_transit_lines.py > transit_lines.geojsonseq
    python3 normalize_transit_lines.py < features.geojson > transit_lines.geojsonseq

Pure stdlib so it can run (and be unit-tested) without pyosmium, GDAL, or
tippecanoe installed.
"""
from __future__ import annotations

import json
import re
import sys
from typing import Any, Iterable, Iterator, Optional

LINE_TYPES = ("LineString", "MultiLineString")

# railway=<value> on a way  ->  transit_lines `kind`
RAILWAY_KIND = {
    "rail": "rail",
    "subway": "subway",
    "light_rail": "light_rail",
    "tram": "tram",
    "monorail": "monorail",
    "narrow_gauge": "rail",
}

# route=<value> on a type=route relation  ->  transit_lines `kind`
ROUTE_KIND = {
    "subway": "subway",
    "tram": "tram",
    "light_rail": "light_rail",
    "train": "train",
    "monorail": "monorail",
}

# GDAL HSTORE: "key"=>"value","key2"=>"value2"  (values may contain \" and \\)
_HSTORE_RE = re.compile(r'"((?:[^"\\]|\\.)*)"\s*=>\s*"((?:[^"\\]|\\.)*)"')


def _truthy(v: Any) -> bool:
    return v not in (None, "", "no", "false", "0")


def _unescape(s: str) -> str:
    return s.replace('\\"', '"').replace("\\\\", "\\")


def parse_hstore(s: str) -> dict[str, str]:
    """Parse a GDAL `other_tags` HSTORE string into a plain dict."""
    out: dict[str, str] = {}
    for k, v in _HSTORE_RE.findall(s):
        out[_unescape(k)] = _unescape(v)
    return out


def merged_tags(props: dict[str, Any]) -> dict[str, Any]:
    """Flatten a feature's properties, expanding a GDAL `other_tags` HSTORE.

    Named fields win over HSTORE entries only where the named field is set;
    otherwise HSTORE values (which hold `route`, `colour`, …) fill in.
    """
    other = props.get("other_tags")
    if not isinstance(other, str) or "=>" not in other:
        return props
    tags = dict(parse_hstore(other))
    for k, v in props.items():
        if k == "other_tags":
            continue
        if _truthy(v):
            tags[k] = v
    return tags


def classify(tags: dict[str, Any]) -> Optional[str]:
    """Return the `transit_lines` `kind` for a set of OSM tags, or None to drop.

    Ways are matched first on `railway`; relations on `route` (only when the
    relation is type=route, which is what GDAL's multilinestrings layer yields).
    """
    railway = tags.get("railway")
    if railway in RAILWAY_KIND:
        return RAILWAY_KIND[railway]
    if tags.get("type") in (None, "route") and tags.get("route") in ROUTE_KIND:
        return ROUTE_KIND[tags["route"]]
    return None


def _colour(tags: dict[str, Any]) -> Optional[str]:
    for key in ("colour", "color"):
        val = tags.get(key)
        if _truthy(val):
            return str(val)
    return None


def build_properties(kind: str, tags: dict[str, Any], feature: dict[str, Any]) -> dict[str, Any]:
    props: dict[str, Any] = {"kind": kind}
    name = tags.get("name")
    if _truthy(name):
        props["name"] = name
    ref = tags.get("ref")
    if _truthy(ref):
        props["ref"] = ref
    colour = _colour(tags)
    if colour is not None:
        props["colour"] = colour
    osm_id = (
        feature.get("id")
        or tags.get("osm_id")
        or tags.get("@id")
        or tags.get("id")
    )
    if osm_id is not None:
        props["osm_id"] = str(osm_id)
    return props


def iter_features(raw: str) -> Iterator[dict[str, Any]]:
    """Yield GeoJSON features from either a FeatureCollection or geojsonseq text."""
    stripped = raw.lstrip()
    if stripped.startswith("{") and '"FeatureCollection"' in stripped[:200]:
        try:
            obj = json.loads(raw)
            if obj.get("type") == "FeatureCollection":
                yield from obj.get("features", [])
                return
        except json.JSONDecodeError:
            pass  # fall through to line mode
    for line in raw.splitlines():
        line = line.strip().lstrip("\x1e").strip()  # drop RS separator + ws
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
        tags = merged_tags(feature.get("properties") or {})
        kind = classify(tags)
        if kind is None:
            continue
        yield {
            "type": "Feature",
            "geometry": geom,
            "properties": build_properties(kind, tags, feature),
        }


def main(argv: list[str]) -> int:
    raw = sys.stdin.read()
    count = 0
    out = sys.stdout
    for feature in normalize(iter_features(raw)):
        out.write(json.dumps(feature, separators=(",", ":"), ensure_ascii=False))
        out.write("\n")
        count += 1
    sys.stderr.write(f"normalize_transit_lines: emitted {count} feature(s)\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
