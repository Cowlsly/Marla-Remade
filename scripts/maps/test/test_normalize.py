#!/usr/bin/env python3
"""test_normalize.py — dry-run / unit test for the safety + admin normalizers.

Runs the pure-Python schema-mapping core of the PMTiles pipeline against tiny
fixtures and asserts the emitted feature schema (source-layer attributes, `kind`
values, admin_level, ISO keys). This proves the safety + border layers are
generated correctly WITHOUT needing tippecanoe / osmium / GDAL installed.

Run:
    python3 scripts/maps/test/test_normalize.py
Exit code 0 = all assertions passed.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
FIX = HERE / "fixtures"
sys.path.insert(0, str(ROOT))

import normalize_admin  # noqa: E402
import normalize_maxspeed  # noqa: E402
import normalize_safety  # noqa: E402
import normalize_transit_lines  # noqa: E402


def _load(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _features(raw: str) -> list[dict]:
    return list(normalize_safety.iter_features(raw))


passed = 0
failed = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS {name}")
    else:
        failed += 1
        print(f"  FAIL {name} {detail}")


def test_safety() -> None:
    print("safety layer:")
    feats = list(normalize_safety.normalize(_features(_load(FIX / "safety_sample.geojsonseq"))))
    kinds = [f["properties"]["kind"] for f in feats]
    # 7 point safety features; cafe + LineString stop dropped.
    check("emits 7 features", len(feats) == 7, f"got {len(feats)}")
    check("speed_camera from highway", "speed_camera" in kinds)
    check("speed_camera from enforcement", kinds.count("speed_camera") == 2, f"kinds={kinds}")
    check("alpr (surveillance:type=ALPR)", "alpr" in kinds)
    check("alpr count == 2 (ALPR + camera:type)", kinds.count("alpr") == 2, f"kinds={kinds}")
    check("generic surveillance", "surveillance" in kinds)
    check("stop_sign", "stop_sign" in kinds)
    check("traffic_signals", "traffic_signals" in kinds)
    check("no non-safety leaked", "cafe" not in kinds and None not in kinds)
    check("all geometry Point", all(f["geometry"]["type"] == "Point" for f in feats))
    alpr = next(f for f in feats if f["properties"]["kind"] == "alpr" and f["properties"].get("operator"))
    check("alpr keeps operator", alpr["properties"]["operator"] == "Flock Safety")
    check("osm_id carried", all("osm_id" in f["properties"] for f in feats))


def test_maxspeed() -> None:
    print("maxspeed layer:")
    raw = _load(FIX / "maxspeed_sample.geojsonseq")
    feats = list(normalize_maxspeed.normalize(normalize_maxspeed.iter_features(raw)))
    # 7 line features with maxspeed; service (no maxspeed) + Point node dropped.
    check("emits 7 line features", len(feats) == 7, f"got {len(feats)}")
    check("all line geometry", all(f["geometry"]["type"] in ("LineString", "MultiLineString") for f in feats))
    values = [f["properties"]["maxspeed"] for f in feats]
    check("keeps raw mph value", "25 mph" in values)
    check("keeps bare number value", "50" in values)
    check("keeps km/h value", "100 km/h" in values)
    check("falls back to maxspeed:forward", "30 mph" in values)
    # Non-numeric OSM values are passed through raw; the app parser handles them.
    check("passes through 'none' raw", "none" in values)
    check("passes through 'walk' raw", "walk" in values)
    check("passes through 'signals' raw", "signals" in values)
    check("every feature has maxspeed property", all(f["properties"].get("maxspeed") for f in feats))
    check("no point node leaked", all(f["geometry"]["type"] != "Point" for f in feats))


def test_transit_lines() -> None:
    print("transit_lines layer:")
    raw = _load(FIX / "transit_lines_sample.geojsonseq")
    feats = list(normalize_transit_lines.normalize(normalize_transit_lines.iter_features(raw)))
    kinds = sorted(f["properties"]["kind"] for f in feats)
    # 6 railway ways + 2 route relations; platform way, highway way, bus route
    # relation, and the station point are all dropped.
    check("emits 8 features", len(feats) == 8, f"got {len(feats)}")
    check("all line geometry", all(f["geometry"]["type"] in ("LineString", "MultiLineString") for f in feats))
    check("railway subway + route subway -> subway", kinds.count("subway") == 2, f"kinds={kinds}")
    check("railway tram + route tram -> tram", kinds.count("tram") == 2, f"kinds={kinds}")
    check("light_rail kind", "light_rail" in kinds)
    check("monorail kind", "monorail" in kinds)
    check("narrow_gauge folds into rail", kinds.count("rail") == 2, f"kinds={kinds}")
    check("platform/highway/station dropped", "platform" not in kinds and None not in kinds)
    # Route-relation colour recovered from the GDAL other_tags HSTORE string.
    red = next(f for f in feats if f["properties"].get("name") == "Red Line")
    check("relation colour parsed from HSTORE", red["properties"].get("colour") == "#DA291C")
    check("relation ref parsed from HSTORE", red["properties"].get("ref") == "Red")
    check("relation kind from route tag", red["properties"]["kind"] == "subway")
    njudah = next(f for f in feats if f["properties"].get("name") == "N Judah")
    check("US 'color' spelling accepted", njudah["properties"].get("colour") == "blue")
    check("bus route relation dropped", all(f["properties"].get("ref") != "38" for f in feats))
    check("way ref carried", any(f["properties"].get("ref") == "F" for f in feats))
    check("osm_id carried", all("osm_id" in f["properties"] for f in feats))


def test_admin_country() -> None:
    print("admin_country layer:")
    raw = _load(FIX / "admin_country_sample.geojson")
    feats = list(normalize_admin.normalize(normalize_admin.iter_features(raw), "country"))
    check("only polygon kept (point dropped)", len(feats) == 1, f"got {len(feats)}")
    p = feats[0]["properties"]
    check("admin_level == 2", p["admin_level"] == 2)
    check("name present", p.get("name") == "United States of America")
    check("ISO_A2 preserved (matches admin0.fgb key)", p.get("ISO_A2") == "US")
    check("iso_a3 present", p.get("iso_a3") == "USA")


def test_admin_region() -> None:
    print("admin_region layer:")
    raw = _load(FIX / "admin_region_sample.geojsonseq")
    feats = list(normalize_admin.normalize(normalize_admin.iter_features(raw), "region"))
    check("2 regions", len(feats) == 2, f"got {len(feats)}")
    p = feats[0]["properties"]
    check("admin_level == 4", p["admin_level"] == 4)
    check("iso_3166_2 preserved (matches admin1.fgb key)", p.get("iso_3166_2") == "US-CA")
    check("name present", p.get("name") == "California")
    check("country_iso derived", p.get("country_iso") == "US")
    check("MultiPolygon kept", feats[1]["geometry"]["type"] == "MultiPolygon")


def test_admin_city() -> None:
    print("admin_city layer:")
    raw = _load(FIX / "admin_city_sample.geojsonseq")
    feats = list(normalize_admin.normalize(normalize_admin.iter_features(raw), "city"))
    names = sorted(f["properties"]["name"] for f in feats)
    check("only admin_level=8 kept (county dropped)", names == ["Oakland", "San Francisco"], f"got {names}")
    check("admin_level == 8", all(f["properties"]["admin_level"] == 8 for f in feats))


def test_valid_geojson_output() -> None:
    """Every emitted line must be independently valid JSON (tippecanoe input)."""
    print("output validity:")
    raw = _load(FIX / "safety_sample.geojsonseq")
    lines = []
    for f in normalize_safety.normalize(_features(raw)):
        lines.append(json.dumps(f, separators=(",", ":")))
    ok = all(json.loads(l)["type"] == "Feature" for l in lines)
    check("geojsonseq lines re-parse as Features", ok)


def main() -> int:
    test_safety()
    test_maxspeed()
    test_transit_lines()
    test_admin_country()
    test_admin_region()
    test_admin_city()
    test_valid_geojson_output()
    print(f"\n{passed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
