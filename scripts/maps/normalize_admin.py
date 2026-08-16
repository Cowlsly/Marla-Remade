#!/usr/bin/env python3
"""normalize_admin.py — normalize admin-boundary polygons for the admin PMTiles layers.

Produces the country / region / city border layers that REPLACE the bundled
FlatGeobuf assets (maps/src/main/assets/admin0.fgb, admin1.fgb). The current
app (data/CountryMap.kt) queries those files by `ISO_A2` (countries) and
`iso_3166_2` (states/provinces) to draw a dimming inverted mask; we preserve
those exact attribute keys so P13 can swap the data source with no schema change.

Source of truth (documented choice — see README.md):
  * country (admin_level=2)  Natural Earth  ne_10m_admin_0_countries
  * region  (admin_level=4)  Natural Earth  ne_10m_admin_1_states_provinces
  * city    (admin_level=8)  OpenStreetMap  boundary=administrative admin_level=8
Rationale: the vendored .fgb are Natural-Earth-derived and keyed by ISO_A2 /
iso_3166_2, so NE gives byte-for-byte attribute compatibility + clean
generalized geometry ideal for the mask. City polygons don't exist in NE, so
those come from OSM admin_level=8.

Reads GeoJSON (FeatureCollection or geojsonseq) on stdin; writes geojsonseq.

Usage:
    ogr2ogr -f GeoJSON /vsistdout/ ne_10m_admin_0_countries.shp \
        | python3 normalize_admin.py --level country > admin_country.geojsonseq
    ogr2ogr -f GeoJSON /vsistdout/ ne_10m_admin_1_states_provinces.shp \
        | python3 normalize_admin.py --level region  > admin_region.geojsonseq
    osmium export -f geojsonseq city_raw.osm.pbf \
        | python3 normalize_admin.py --level city    > admin_city.geojsonseq

Pure stdlib — no GDAL/pyosmium needed to run or test.
"""
from __future__ import annotations

import argparse
import json
import sys
from typing import Any, Iterable, Iterator, Optional

LEVELS = {"country": 2, "region": 4, "city": 8}


def _get(tags: dict[str, Any], *keys: str) -> Optional[Any]:
    for k in keys:
        v = tags.get(k)
        if v not in (None, ""):
            return v
    return None


def build_country(tags: dict[str, Any]) -> dict[str, Any]:
    props: dict[str, Any] = {"admin_level": 2}
    name = _get(tags, "NAME_EN", "name:en", "NAME", "name")
    if name:
        props["name"] = name
    name_en = _get(tags, "NAME_EN", "name:en")
    if name_en:
        props["name_en"] = name_en
    iso_a2 = _get(tags, "ISO_A2", "ISO_A2_EH", "iso_a2", "ISO3166-1:alpha2")
    if iso_a2:
        props["ISO_A2"] = str(iso_a2).upper()
    iso_a3 = _get(tags, "ISO_A3", "ISO_A3_EH", "iso_a3")
    if iso_a3:
        props["iso_a3"] = str(iso_a3).upper()
    return props


def build_region(tags: dict[str, Any]) -> dict[str, Any]:
    props: dict[str, Any] = {"admin_level": 4}
    name = _get(tags, "name_en", "NAME_EN", "name", "NAME")
    if name:
        props["name"] = name
    name_en = _get(tags, "name_en", "NAME_EN", "name:en")
    if name_en:
        props["name_en"] = name_en
    # Natural Earth admin_1 exposes ISO 3166-2 as `iso_3166_2`.
    iso = _get(tags, "iso_3166_2", "ISO_3166_2", "iso3166_2")
    if iso:
        props["iso_3166_2"] = str(iso)
    parent = _get(tags, "iso_a2", "ISO_A2", "adm0_a3")
    if parent:
        props["country_iso"] = str(parent).upper()
    return props


def build_city(tags: dict[str, Any]) -> dict[str, Any]:
    props: dict[str, Any] = {"admin_level": 8}
    name = _get(tags, "name:en", "name", "NAME_EN", "NAME")
    if name:
        props["name"] = name
    name_en = _get(tags, "name:en", "NAME_EN")
    if name_en:
        props["name_en"] = name_en
    return props


BUILDERS = {"country": build_country, "region": build_region, "city": build_city}


def keep_geometry(geom: dict[str, Any]) -> bool:
    return geom.get("type") in ("Polygon", "MultiPolygon")


def is_admin_level(tags: dict[str, Any], level: int) -> bool:
    """For OSM city input, filter to boundary=administrative + matching admin_level."""
    if "admin_level" not in tags and "boundary" not in tags:
        return True  # Natural Earth rows: no OSM admin tagging, accept.
    if str(tags.get("boundary", "administrative")) != "administrative":
        return False
    try:
        return int(str(tags.get("admin_level", level))) == level
    except ValueError:
        return False


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


def normalize(features: Iterable[dict[str, Any]], level_name: str) -> Iterator[dict[str, Any]]:
    level = LEVELS[level_name]
    build = BUILDERS[level_name]
    for feature in features:
        geom = feature.get("geometry") or {}
        if not keep_geometry(geom):
            continue
        tags = feature.get("properties") or {}
        if level_name == "city" and not is_admin_level(tags, level):
            continue
        props = build(tags)
        if "name" not in props:
            continue  # unnamed borders are useless for the mask/labels
        yield {"type": "Feature", "geometry": geom, "properties": props}


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--level", required=True, choices=sorted(LEVELS))
    args = ap.parse_args(argv)

    raw = sys.stdin.read()
    count = 0
    out = sys.stdout
    for feature in normalize(iter_features(raw), args.level):
        out.write(json.dumps(feature, separators=(",", ":"), ensure_ascii=False))
        out.write("\n")
        count += 1
    sys.stderr.write(f"normalize_admin[{args.level}]: emitted {count} feature(s)\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
