#!/usr/bin/env python3
"""diff_geojsonseq.py — the extraction half of the differential harness.

Runs a legacy (osmium/ogr2ogr + normalize_*.py) GeoJSONSeq and a Rust
(osm_extract) one through the same canonicaliser and diffs them. This is the real
safety net for the port: every layer that moves to Rust gets checked here against
the pipeline it replaces, on the same PBF, before its `--engine` default flips.

WHAT MUST MATCH, AND WHY THE TOLERANCES DIFFER:

  * Properties: exactly, always. Every attribute is either a raw tag passthrough
    or a documented classification, so any difference is a bug, not a rounding
    artefact. `maxspeed` is the sharp case -- "35 mph" must survive as the string
    "35 mph", so a normalising parse shows up here as a property diff.

  * Point geometry: exactly, to the 7 decimal places both sides emit. A node's
    coordinate is copied, not computed.

  * Line and polygon geometry: within --epsilon (default 1e-7 degrees, i.e. the
    last emitted digit). The two sides reach the same vertices by different
    routes -- libosmium assembles ways through its own node cache, we look them
    up in a sorted table -- so the final decimal can differ. Vertex COUNTS and
    nesting structure must still match exactly; a different number of vertices is
    a different shape, not a rounding difference.

Features are paired on --key-prop (default `osm_id`), which both pipelines carry
and which is stable across them. Features with no key are paired on rounded
geometry instead, and unpairable ones are reported rather than ignored.

Usage:
  ./diff_geojsonseq.py legacy.geojsonseq rust.geojsonseq
  ./diff_geojsonseq.py legacy.geojsonseq rust.geojsonseq --epsilon 1e-6
  ./diff_geojsonseq.py a.geojsonseq b.geojsonseq --ignore-prop timestamp

Exit code 0 when the two agree within tolerance, 1 otherwise.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

# Reuse the normalisers' own reader so the harness accepts exactly the inputs
# they do: geojsonseq, a FeatureCollection, and the \x1e record separator.
from normalize_safety import iter_features  # noqa: E402

POINT_TYPES = ("Point", "MultiPoint")


def load(path: Path) -> list[dict[str, Any]]:
    return list(iter_features(path.read_text(encoding="utf-8")))


def feature_key(feat: dict[str, Any], key_prop: str) -> str:
    props = feat.get("properties") or {}
    key = props.get(key_prop)
    if key not in (None, ""):
        return f"{key_prop}={key}"
    # No stable id: fall back to the geometry, rounded hard enough that the two
    # pipelines' last-digit disagreement cannot split a pair.
    return "geom=" + repr(round_coords(feat.get("geometry") or {}, 5))


def round_coords(geom: Any, places: int) -> Any:
    if isinstance(geom, dict):
        return {k: round_coords(v, places) for k, v in sorted(geom.items())}
    if isinstance(geom, list):
        return [round_coords(v, places) for v in geom]
    if isinstance(geom, float):
        return round(geom, places)
    return geom


def coord_shape(coords: Any) -> Any:
    """Nesting structure and vertex counts, with the numbers removed.

    A shape mismatch means a genuinely different geometry and is never excused by
    --epsilon.
    """
    if isinstance(coords, list):
        if coords and not isinstance(coords[0], list):
            return len(coords)
        return [coord_shape(c) for c in coords]
    return 0


def max_coord_delta(a: Any, b: Any) -> float:
    if isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            return float("inf")
        return max((max_coord_delta(x, y) for x, y in zip(a, b)), default=0.0)
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return abs(float(a) - float(b))
    return 0.0 if a == b else float("inf")


def compare_geometry(a: dict[str, Any], b: dict[str, Any], epsilon: float) -> str | None:
    at, bt = a.get("type"), b.get("type")
    if at != bt:
        return f"geometry type {at!r} vs {bt!r}"
    sa, sb = coord_shape(a.get("coordinates")), coord_shape(b.get("coordinates"))
    if sa != sb:
        return f"geometry shape {sa} vs {sb}"
    delta = max_coord_delta(a.get("coordinates"), b.get("coordinates"))
    # Points are copied coordinates, so they get no tolerance at all.
    tolerance = 0.0 if at in POINT_TYPES else epsilon
    if delta > tolerance:
        return f"geometry differs by {delta:.3e} (tolerance {tolerance:.3e})"
    return None


def compare_props(
    a: dict[str, Any], b: dict[str, Any], ignore: set[str]
) -> list[str]:
    out = []
    keys = (set(a) | set(b)) - ignore
    for k in sorted(keys):
        if k not in a:
            out.append(f"property {k!r} only in rust ({b[k]!r})")
        elif k not in b:
            out.append(f"property {k!r} only in legacy ({a[k]!r})")
        elif a[k] != b[k]:
            out.append(f"property {k!r}: legacy {a[k]!r} vs rust {b[k]!r}")
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("legacy", type=Path, help="GeoJSONSeq from the osmium/python path")
    ap.add_argument("rust", type=Path, help="GeoJSONSeq from osm_extract")
    ap.add_argument(
        "--epsilon",
        type=float,
        default=1e-7,
        help="per-coordinate tolerance in degrees for lines and polygons "
        "(default 1e-7, the last emitted digit). Points always require exactness.",
    )
    ap.add_argument(
        "--key-prop",
        default="osm_id",
        help="property both sides carry, used to pair features (default osm_id)",
    )
    ap.add_argument(
        "--ignore-prop",
        action="append",
        default=[],
        help="property to exclude from the comparison (repeatable)",
    )
    ap.add_argument(
        "--max-report",
        type=int,
        default=20,
        help="stop printing after this many differing features (default 20)",
    )
    args = ap.parse_args()

    legacy = load(args.legacy)
    rust = load(args.rust)
    ignore = set(args.ignore_prop)

    lmap: dict[str, dict[str, Any]] = {}
    rmap: dict[str, dict[str, Any]] = {}
    ldupes = rdupes = 0
    for feats, m in ((legacy, lmap), (rust, rmap)):
        for f in feats:
            k = feature_key(f, args.key_prop)
            if k in m:
                # Duplicate keys make a pairing ambiguous. Count them rather than
                # silently letting the last one win and calling the run clean.
                if m is lmap:
                    ldupes += 1
                else:
                    rdupes += 1
                continue
            m[k] = f

    print(f"legacy: {len(legacy)} feature(s), {len(lmap)} unique key(s)")
    print(f"rust  : {len(rust)} feature(s), {len(rmap)} unique key(s)")
    if ldupes or rdupes:
        print(f"WARNING: duplicate keys skipped -- legacy {ldupes}, rust {rdupes}")

    only_legacy = sorted(set(lmap) - set(rmap))
    only_rust = sorted(set(rmap) - set(lmap))
    prop_diffs: list[tuple[str, list[str]]] = []
    geom_diffs: list[tuple[str, str]] = []

    for k in sorted(set(lmap) & set(rmap)):
        a, b = lmap[k], rmap[k]
        pd = compare_props(a.get("properties") or {}, b.get("properties") or {}, ignore)
        if pd:
            prop_diffs.append((k, pd))
        gd = compare_geometry(a.get("geometry") or {}, b.get("geometry") or {}, args.epsilon)
        if gd:
            geom_diffs.append((k, gd))

    def report(title: str, items: list, fmt) -> None:
        if not items:
            return
        print(f"\n{title} ({len(items)}):")
        for item in items[: args.max_report]:
            print(fmt(item))
        if len(items) > args.max_report:
            print(f"  ... and {len(items) - args.max_report} more")

    report("only in legacy", only_legacy, lambda k: f"  {k}")
    report("only in rust", only_rust, lambda k: f"  {k}")
    report(
        "property differences",
        prop_diffs,
        lambda kv: "  " + kv[0] + "\n" + "\n".join(f"    {d}" for d in kv[1]),
    )
    report("geometry differences", geom_diffs, lambda kv: f"  {kv[0]}: {kv[1]}")

    failures = len(only_legacy) + len(only_rust) + len(prop_diffs) + len(geom_diffs)
    print()
    if failures == 0:
        print(f"OK: {len(lmap)} feature(s) agree (epsilon {args.epsilon:.3e})")
        return 0
    print(
        f"FAIL: {len(only_legacy)} legacy-only, {len(only_rust)} rust-only, "
        f"{len(prop_diffs)} property diff(s), {len(geom_diffs)} geometry diff(s)"
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
