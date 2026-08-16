#!/usr/bin/env python3
"""normalize_safety.py — map OSM road-furniture/safety tags to the `safety` PMTiles layer.

Reads GeoJSON features (a FeatureCollection on stdin, or newline-delimited
GeoJSON a.k.a. geojsonseq, optionally RS-prefixed as produced by
`osmium export -f geojsonseq -r`) and writes newline-delimited GeoJSON
containing only the features that belong in the baked `safety` source-layer.

Every emitted feature carries a normalized `kind` attribute so the app can
style them without any runtime Overpass query:

    speed_camera     highway=speed_camera | enforcement=maxspeed
    alpr             man_made=surveillance + ALPR signals (DeFlock / Flock Safety)
    surveillance     man_made=surveillance (any other camera)
    stop_sign        highway=stop
    traffic_signals  highway=traffic_signals

Usage:
    osmium export -f geojsonseq safety_raw.osm.pbf | python3 normalize_safety.py > safety.geojsonseq
    python3 normalize_safety.py < features.geojson > safety.geojsonseq

The script is pure stdlib so it can run (and be unit-tested) without pyosmium,
GDAL, or tippecanoe installed.
"""
from __future__ import annotations

import json
import sys
from typing import Any, Iterable, Iterator, Optional


def _truthy(v: Optional[str]) -> bool:
    return v not in (None, "", "no", "false", "0")


def classify(tags: dict[str, Any]) -> Optional[str]:
    """Return the `safety` layer `kind` for a set of OSM tags, or None to drop."""
    highway = tags.get("highway")
    man_made = tags.get("man_made")

    if highway == "speed_camera":
        return "speed_camera"
    # A node on an enforcement=maxspeed relation is often tagged directly.
    if tags.get("enforcement") == "maxspeed":
        return "speed_camera"

    if man_made == "surveillance":
        # DeFlock (deflock.me) tags automated license-plate readers as
        # man_made=surveillance with surveillance:type=ALPR (or camera:type=alpr).
        # Flock Safety hardware is the dominant operator/manufacturer.
        surv_type = str(tags.get("surveillance:type", "")).lower()
        cam_type = str(tags.get("camera:type", "")).lower()
        operator = str(tags.get("operator", "")).lower()
        manufacturer = str(tags.get("manufacturer", "")).lower()
        if (
            "alpr" in surv_type
            or "anpr" in surv_type
            or cam_type in ("alpr", "anpr")
            or "flock" in operator
            or "flock" in manufacturer
        ):
            return "alpr"
        return "surveillance"

    if highway == "stop":
        return "stop_sign"
    if highway == "traffic_signals":
        return "traffic_signals"

    return None


def build_properties(kind: str, tags: dict[str, Any], feature: dict[str, Any]) -> dict[str, Any]:
    props: dict[str, Any] = {"kind": kind}
    # Carry a small, stable set of attributes useful for display / dedup.
    for src, dst in (
        ("name", "name"),
        ("direction", "direction"),
        ("operator", "operator"),
        ("ref", "ref"),
        ("surveillance:type", "surveillance_type"),
    ):
        val = tags.get(src)
        if _truthy(val):
            props[dst] = val
    osm_id = feature.get("id") or tags.get("@id") or tags.get("id") or tags.get("osm_id")
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
        # Safety furniture is node-based; keep only points.
        if geom.get("type") != "Point":
            continue
        tags = feature.get("properties") or {}
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
    sys.stderr.write(f"normalize_safety: emitted {count} feature(s)\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
