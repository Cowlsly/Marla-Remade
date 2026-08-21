#!/usr/bin/env python3
"""diff_pmtiles.py — the tiling half of the differential harness.

Compares a tippecanoe-built archive against one of ours by decoding both with
`tile_build`'s `pmtiles_dump` and diffing the decoded model.

WHY NOT A BYTE COMPARISON. Three reasons, all documented in the plan and none of
them fixable:

  * `--drop-densest-as-needed` is a lossy per-tile heuristic. We deliberately
    implement a different, deterministic drop policy instead, so per-tile feature
    counts will not match. --max-feature-delta is the budget for that difference,
    and the point of the flag is to make the size of the divergence explicit
    rather than unbounded.
  * `--extend-zooms-if-still-dropping` can push tippecanoe's archive past its own
    --maximum-zoom. We do not reproduce it, so tippecanoe may carry zooms we
    have no opinion about; those are reported, not failed, unless --strict-zooms.
  * An MVT re-encode rebuilds each layer's key/value dictionaries in first-use
    order, so even an untouched tile comes back a few bytes different.

WHAT IS HELD TO EXACTNESS:
  * Which layers appear at which zooms (modulo the extended-zoom caveat above).
  * The property key set per (zoom, layer). A missing or extra attribute is a
    schema break, and the app reads these by name.
  * The tile extent.
  * Geometry type mix per (zoom, layer) -- a line layer must not start emitting
    polygons.

Usage:
  ./diff_pmtiles.py legacy.pmtiles ours.pmtiles
  ./diff_pmtiles.py legacy.pmtiles ours.pmtiles --layer maxspeed --max-feature-delta 10
  ./diff_pmtiles.py a.pmtiles b.pmtiles --strict-zooms

Exit code 0 when they agree within the stated budget, 1 otherwise.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CRATE = ROOT / "tile_build"


def dump_command(explicit: Path | None) -> list[str]:
    if explicit:
        return [str(explicit)]
    for name in ("pmtiles_dump", "pmtiles_dump.exe"):
        built = CRATE / "target" / "release" / name
        if built.exists():
            return [str(built)]
    # Falling back to `cargo run` keeps this usable on a clean checkout, at the
    # cost of a build on first use.
    return [
        "cargo",
        "run",
        "--release",
        "--quiet",
        "--manifest-path",
        str(CRATE / "Cargo.toml"),
        "--bin",
        "pmtiles_dump",
        "--",
    ]


def run_dump(cmd: list[str], archive: Path, layer: str | None) -> dict[tuple[str, str], dict[str, str]]:
    argv = cmd + [str(archive)]
    if layer:
        argv += ["--layer", layer]
    proc = subprocess.run(argv, capture_output=True, text=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise SystemExit(f"pmtiles_dump failed on {archive}")
    if proc.stderr.strip():
        sys.stderr.write(proc.stderr)

    out: dict[tuple[str, str], dict[str, str]] = {}
    for line in proc.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        zoom, layer_name = parts[0], parts[1]
        fields = {}
        for p in parts[2:]:
            k, _, v = p.partition("=")
            fields[k] = v
        out[(zoom, layer_name)] = fields
    return out


def zoom_num(z: str) -> int:
    return int(z.lstrip("z"))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("legacy", type=Path, help="archive built by tippecanoe")
    ap.add_argument("ours", type=Path, help="archive built by tile_build")
    ap.add_argument("--layer", help="restrict the comparison to one layer")
    ap.add_argument(
        "--max-feature-delta",
        type=float,
        default=5.0,
        help="permitted per-(zoom,layer) feature-count difference, in percent of "
        "the legacy count (default 5). This is the documented drop delta.",
    )
    ap.add_argument(
        "--dump",
        type=Path,
        help="path to a pmtiles_dump binary (default: the crate's release build, "
        "else `cargo run`)",
    )
    ap.add_argument(
        "--strict-zooms",
        action="store_true",
        help="treat a zoom present in only one archive as a failure. Off by "
        "default because --extend-zooms-if-still-dropping is not reproduced.",
    )
    args = ap.parse_args()

    cmd = dump_command(args.dump)
    legacy = run_dump(cmd, args.legacy, args.layer)
    ours = run_dump(cmd, args.ours, args.layer)

    print(f"legacy: {len(legacy)} (zoom, layer) pair(s)  {args.legacy}")
    print(f"ours  : {len(ours)} (zoom, layer) pair(s)  {args.ours}")

    only_legacy = sorted(set(legacy) - set(ours), key=lambda p: (zoom_num(p[0]), p[1]))
    only_ours = sorted(set(ours) - set(legacy), key=lambda p: (zoom_num(p[0]), p[1]))

    failures: list[str] = []
    warnings: list[str] = []

    # A layer missing from one side at a zoom BOTH sides cover is a real hole. The
    # same thing at a zoom only one side reaches is the extended-zoom caveat.
    legacy_zooms = {z for z, _ in legacy}
    our_zooms = {z for z, _ in ours}
    for pair in only_legacy:
        msg = f"{pair[0]} {pair[1]}: in legacy, absent from ours"
        (failures if (pair[0] in our_zooms or args.strict_zooms) else warnings).append(msg)
    for pair in only_ours:
        msg = f"{pair[0]} {pair[1]}: in ours, absent from legacy"
        (failures if (pair[0] in legacy_zooms or args.strict_zooms) else warnings).append(msg)

    for pair in sorted(set(legacy) & set(ours), key=lambda p: (zoom_num(p[0]), p[1])):
        a, b = legacy[pair], ours[pair]
        label = f"{pair[0]} {pair[1]}"

        akeys = set(filter(None, a.get("keys", "").split(",")))
        bkeys = set(filter(None, b.get("keys", "").split(",")))
        if akeys != bkeys:
            missing = sorted(akeys - bkeys)
            extra = sorted(bkeys - akeys)
            failures.append(f"{label}: property keys differ (missing {missing}, extra {extra})")

        if a.get("extent") != b.get("extent"):
            failures.append(f"{label}: extent {a.get('extent')} vs {b.get('extent')}")

        ageoms = {p.split(":")[0] for p in a.get("geom", "").split(",") if p}
        bgeoms = {p.split(":")[0] for p in b.get("geom", "").split(",") if p}
        if ageoms != bgeoms:
            failures.append(f"{label}: geometry types {sorted(ageoms)} vs {sorted(bgeoms)}")

        af = int(a.get("features", 0))
        bf = int(b.get("features", 0))
        if af == 0:
            if bf != 0:
                failures.append(f"{label}: legacy has 0 features, ours has {bf}")
        else:
            pct = abs(bf - af) / af * 100.0
            if pct > args.max_feature_delta:
                failures.append(
                    f"{label}: features {af} vs {bf} ({pct:.1f}% > "
                    f"{args.max_feature_delta:.1f}% budget)"
                )
            elif bf != af:
                warnings.append(f"{label}: features {af} vs {bf} ({pct:.1f}%, within budget)")

    if warnings:
        print(f"\nwithin budget / expected ({len(warnings)}):")
        for w in warnings:
            print(f"  {w}")

    print()
    if not failures:
        print(f"OK: {len(set(legacy) & set(ours))} (zoom, layer) pair(s) agree")
        return 0
    print(f"FAIL ({len(failures)}):")
    for f in failures:
        print(f"  {f}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
