#!/usr/bin/env python3
"""diff_mamaps.py — is this archive the same PICTURE as that one?

Compares two `.mamaps` archives by decoding both with `tile_build`'s `mamaps_dump`
and diffing the decoded model, tile by tile and layer by layer.

WHY THIS EXISTS. Every earlier change to the tile pipeline was gated on a
byte-identical archive, which is the strongest check there is and needs no
judgement. `tile_build::subdivide` gave that up: it clips a feature down the tile
quadtree instead of once per tile, and Sutherland-Hodgman keeps its input's
rotation, so a ring comes out starting at a different vertex. Same edges, same
winding, same area, different bytes. A byte diff on such an archive reports
everything and means nothing, so this compares the measures instead.

WHAT IS HELD EXACT, per (tile, layer):
  * The set of (tile, layer) pairs. A tile in one archive and not the other is a
    hole in the map or a tile invented from nothing, and either is a failure.
  * Feature count.
  * Geometry-type mix -- a line layer must not start emitting polygons.

WHAT IS HELD TO A TOLERANCE, per (tile, layer):
  * Total absolute ring area, and total line length, relatively. This is the
    check that says the shape did not move. The tolerance is there for the
    last-bit drift a composed clip introduces when it interpolates a crossing off
    an already-interpolated vertex, which is around 1e-12 of a coordinate.
  * Point count, which may differ by a little: a concave ring's zero-area run
    along a clip boundary is shorter when the clip happens in stages. The budget
    is a percentage OR a small absolute slack, whichever is larger, because the
    effect is a couple of vertices per ring and not a proportion -- one vertex on
    an eleven-point tile is 9%, which a percentage-only budget would call a
    catastrophe. The SIGN of the net change is printed in the summary; a net
    increase means the archive is growing and is worth explaining.

Usage:
  ./diff_mamaps.py before.mamaps after.mamaps
  ./diff_mamaps.py a.mamaps b.mamaps --layer water
  ./diff_mamaps.py a.mamaps b.mamaps --max-measure-delta 1e-6 --max-point-delta 1.0

Exit code 0 when they hold the same picture within the stated budget, 1 otherwise.
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
    for name in ("mamaps_dump", "mamaps_dump.exe"):
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
        "mamaps_dump",
        "--",
    ]


class Row:
    """One (tile, layer)'s measures, as `mamaps_dump --mode geometry` prints them."""

    __slots__ = ("features", "points", "rings", "geom", "area", "net", "length")

    def __init__(self, fields: dict[str, str]) -> None:
        self.features = int(fields.get("features", 0))
        self.points = int(fields.get("points", 0))
        self.rings = int(fields.get("rings", 0))
        # Type mix as a dict, so "polygon:3" and "line:1" compare as a mapping
        # rather than as a string whose order might drift.
        self.geom = {}
        for p in filter(None, fields.get("geom", "").split(",")):
            k, _, v = p.partition(":")
            self.geom[k] = int(v or 0)
        self.area = float(fields.get("area", 0.0))
        self.net = float(fields.get("net", 0.0))
        self.length = float(fields.get("length", 0.0))


def run_dump(cmd: list[str], archive: Path, layer: str | None) -> dict[tuple[str, str], Row]:
    argv = cmd + [str(archive), "--mode", "geometry"]
    if layer:
        argv += ["--layer", layer]
    proc = subprocess.run(argv, capture_output=True, text=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise SystemExit(f"mamaps_dump failed on {archive}")
    if proc.stderr.strip():
        sys.stderr.write(proc.stderr)

    out: dict[tuple[str, str], Row] = {}
    for line in proc.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        tile, layer_name = parts[0], parts[1]
        fields = {}
        for p in parts[2:]:
            k, _, v = p.partition("=")
            fields[k] = v
        # A tile id can repeat across layers but never within one, so the pair is
        # the key. A duplicate would mean the dump itself is wrong.
        key = (tile, layer_name)
        if key in out:
            raise SystemExit(f"{archive}: {tile} {layer_name} appears twice in the dump")
        out[key] = Row(fields)
    return out


def tile_sort_key(tile: str) -> tuple[int, int, int]:
    z, x, y = tile.split("/")
    return int(z), int(x), int(y)


def relative_delta(a: float, b: float) -> float:
    """How far apart two measures are, scaled so a zero measure is comparable."""
    scale = max(abs(a), abs(b), 1.0)
    return abs(a - b) / scale


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("before", type=Path, help="the archive to compare against")
    ap.add_argument("after", type=Path, help="the archive under test")
    ap.add_argument("--layer", help="restrict the comparison to one layer")
    ap.add_argument(
        "--max-measure-delta",
        type=float,
        default=1e-6,
        help="permitted relative difference in ring area and line length per "
        "(tile, layer) (default 1e-6). Composed clipping drifts by about 1e-12, "
        "so this is six orders of margin and still far below one extent unit.",
    )
    ap.add_argument(
        "--max-point-delta",
        type=float,
        default=1.0,
        help="permitted per-(tile, layer) point-count difference, in percent of "
        "the `before` count (default 1). Zero-area collinear runs along a clip "
        "boundary are the only thing that should move this.",
    )
    ap.add_argument(
        "--point-slack",
        type=int,
        default=2,
        help="point-count difference always permitted regardless of percentage "
        "(default 2). A tile holding eleven points is 9%% different at one "
        "vertex, and one vertex is the size of the effect being tolerated.",
    )
    ap.add_argument(
        "--dump",
        type=Path,
        help="path to a mamaps_dump binary (default: the crate's release build, "
        "else `cargo run`)",
    )
    ap.add_argument(
        "--max-report",
        type=int,
        default=20,
        help="how many failures to print before summarising (default 20)",
    )
    args = ap.parse_args()

    cmd = dump_command(args.dump)
    before = run_dump(cmd, args.before, args.layer)
    after = run_dump(cmd, args.after, args.layer)

    print(f"before: {len(before):>9,} (tile, layer) pair(s)  {args.before}")
    print(f"after : {len(after):>9,} (tile, layer) pair(s)  {args.after}")

    failures: list[str] = []
    only_before = sorted(set(before) - set(after), key=lambda p: (tile_sort_key(p[0]), p[1]))
    only_after = sorted(set(after) - set(before), key=lambda p: (tile_sort_key(p[0]), p[1]))
    for tile, layer in only_before:
        failures.append(f"{tile} {layer}: in before, absent from after -- a hole in the map")
    for tile, layer in only_after:
        failures.append(f"{tile} {layer}: in after, absent from before -- a tile from nothing")

    # Totals, so the summary says something even when every pair agrees.
    shared = sorted(set(before) & set(after), key=lambda p: (tile_sort_key(p[0]), p[1]))
    totals = {
        "area": [0.0, 0.0],
        "net": [0.0, 0.0],
        "length": [0.0, 0.0],
        "points": [0, 0],
        "features": [0, 0],
        "rings": [0, 0],
    }
    worst = {"area": 0.0, "net": 0.0, "length": 0.0}
    offenders: dict[str, list[tuple[float, str, float, float]]] = {
        "area": [],
        "net": [],
        "length": [],
    }
    moved_points = 0
    lost_rings = 0

    for key in shared:
        a, b = before[key], after[key]
        label = f"{key[0]} {key[1]}"

        totals["features"][0] += a.features
        totals["features"][1] += b.features
        totals["rings"][0] += a.rings
        totals["rings"][1] += b.rings
        totals["points"][0] += a.points
        totals["points"][1] += b.points
        totals["area"][0] += a.area
        totals["area"][1] += b.area
        totals["net"][0] += a.net
        totals["net"][1] += b.net
        totals["length"][0] += a.length
        totals["length"][1] += b.length

        if a.features != b.features:
            failures.append(f"{label}: features {a.features} vs {b.features}")
        if a.geom != b.geom:
            failures.append(f"{label}: geometry mix {a.geom} vs {b.geom}")
        # Exact, and never budgeted. A lost ring is a lost hole, and a hole that
        # disappears renders as a lake filled in solid -- the one failure mode this
        # pipeline has always gated on. `geom=` counts features by TYPE and cannot
        # see it, which is why this column exists.
        if a.rings != b.rings:
            lost_rings += 1
            failures.append(
                f"{label}: rings {a.rings} vs {b.rings} "
                f"(net area {a.net:.2f} vs {b.net:.2f})"
            )

        for what, x, y in (
            ("area", a.area, b.area),
            ("net", a.net, b.net),
            ("length", a.length, b.length),
        ):
            d = relative_delta(x, y)
            worst[what] = max(worst[what], d)
            # Kept with its identity, not just its size: "the worst area delta is
            # 20%" is not actionable, and "it is this tile" is.
            offenders[what].append((d, label, x, y))
            if d > args.max_measure_delta:
                failures.append(
                    f"{label}: {what} {x:.2f} vs {y:.2f} "
                    f"(relative {d:.3e} > {args.max_measure_delta:.0e})"
                )

        if a.points != b.points:
            moved_points += 1
            off = abs(b.points - a.points)
            pct = off / max(a.points, 1) * 100.0
            if off > args.point_slack and pct > args.max_point_delta:
                failures.append(
                    f"{label}: points {a.points} vs {b.points} "
                    f"({pct:.2f}% > {args.max_point_delta:.2f}% budget, "
                    f"{off} > {args.point_slack} slack)"
                )

    print()
    print(f"shared pairs        : {len(shared):>9,}")
    print(f"features            : {totals['features'][0]:>9,} vs {totals['features'][1]:,}")
    print(f"rings               : {totals['rings'][0]:>9,} vs {totals['rings'][1]:,}")
    print(f"  pairs that lost or gained a ring: {lost_rings:,}")
    print(f"points              : {totals['points'][0]:>9,} vs {totals['points'][1]:,}")
    point_delta = totals["points"][1] - totals["points"][0]
    print(f"  net point change       : {point_delta:+,}")
    print(f"  pairs whose count moved: {moved_points:,}")
    print(f"ring area   (total) : {totals['area'][0]:.2f} vs {totals['area'][1]:.2f}")
    print(f"net  area   (total) : {totals['net'][0]:.2f} vs {totals['net'][1]:.2f}")
    print(f"line length (total) : {totals['length'][0]:.2f} vs {totals['length'][1]:.2f}")
    print(f"worst relative area delta   : {worst['area']:.3e}")
    print(f"worst relative net delta    : {worst['net']:.3e}")
    print(f"worst relative length delta : {worst['length']:.3e}")

    for what in ("area", "net", "length"):
        top = sorted(offenders[what], reverse=True)[:5]
        if top and top[0][0] > 0.0:
            print(f"\nlargest {what} differences:")
            for d, label, x, y in top:
                if d == 0.0:
                    break
                print(f"  {label}: {x:.2f} vs {y:.2f}  (relative {d:.3e})")

    print()
    if not failures:
        print(f"OK: {len(shared):,} (tile, layer) pair(s) hold the same picture")
        return 0
    print(f"FAIL ({len(failures):,}):")
    for f in failures[: args.max_report]:
        print(f"  {f}")
    if len(failures) > args.max_report:
        print(f"  ... and {len(failures) - args.max_report:,} more")
    return 1


if __name__ == "__main__":
    sys.exit(main())
