#!/usr/bin/env python3
"""Compare a generated `.mamaps` archive against the upstream `.pmtiles` it replaces, per kind.

The measurable answer to the dominant risk of the `.mamaps` project. Reproducing the Protomaps
tag->kind mapping from `.osm.pbf` is weeks of work, and "does it look right" is not a way to track
weeks of work. This turns it into a per-kind recall table: for every (layer, kind) upstream draws,
how many features do we draw, and how much total geometry.

**The bar is visually equivalent, not count-identical.** `landcover` and `landuse` will never match:
upstream applies minimum-area thresholds and zoom rules accumulated over years, and a different but
reasonable threshold changes a count by tens of percent without changing what a person sees. What
this catches is the failure that matters -- a kind we emit *none* of, or ten times too many of.

Usage:
  diff_kinds.py --ours OUT.mamaps --theirs UPSTREAM.pmtiles [--layer NAME] [--max-zoom N]
                [--fail-under 0.5]

Both archives are read through their own dump tools, which is the point: the comparison is of the
decoded model, not of bytes. `mamaps_dump --mode summary` and `pmtiles_dump --mode summary` both emit
one tab-separated line per (zoom, layer) with a sorted kind list, so this is a join rather than a
parse.

Exit status is 1 when a kind upstream draws is missing from ours entirely, or when the ratio for a
kind falls below `--fail-under`. Everything else is reported and does not fail, because a difference
is expected and only an absence is a bug.
"""

import argparse
import os
import shutil
import subprocess
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
TILE_BUILD = os.path.join(HERE, "..", "tile_build")


def dump_tool(name):
    """The built binary, or a `cargo run` that builds it.

    Same fallback `diff_pmtiles.py` uses: a release binary if one is lying around, otherwise cargo,
    so the harness works in a fresh checkout without a separate build step.
    """
    exe = os.path.join(TILE_BUILD, "target", "release", name + (".exe" if os.name == "nt" else ""))
    if os.path.exists(exe):
        return [exe]
    if shutil.which("cargo") is None:
        sys.exit(f"diff_kinds: {name} is not built and cargo is not on PATH")
    return [
        "cargo",
        "run",
        "--release",
        "--quiet",
        "--manifest-path",
        os.path.join(TILE_BUILD, "Cargo.toml"),
        "--bin",
        name,
        "--",
    ]


def summarise(tool, archive, layer):
    """`(zoom, layer, kind) -> (features, points)` from a dump's summary mode.

    `points` is absent from `pmtiles_dump`'s output, so it comes back as `None` there and the
    geometry-length column is simply not compared. Counting features is the part that catches a
    missing kind.
    """
    argv = dump_tool(tool) + [archive, "--mode", "summary"]
    if layer:
        argv += ["--layer", layer]
    try:
        out = subprocess.run(argv, capture_output=True, text=True, check=True).stdout
    except subprocess.CalledProcessError as e:
        sys.exit(f"diff_kinds: {tool} failed on {archive}:\n{e.stderr}")

    rows = {}
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) < 3 or not parts[0].startswith("z"):
            continue
        zoom = int(parts[0][1:])
        name = parts[1]
        fields = {}
        for field in parts[2:]:
            if "=" in field:
                key, value = field.split("=", 1)
                fields[key] = value
        features = int(fields.get("features", 0))
        points = int(fields["points"]) if "points" in fields else None
        kinds = [k for k in fields.get("kinds", "").split(",") if k and k != "-"]
        if not kinds:
            kinds = ["-"]
        # A summary line gives one feature count for the whole (zoom, layer), not per kind, so the
        # count is spread evenly across the kinds present. That is crude, and it is enough for what
        # this measures: whether a kind appears at all, and whether a layer's volume is in the right
        # order of magnitude. A per-kind count would need `--mode tiles` over the whole archive.
        share = features / len(kinds)
        point_share = points / len(kinds) if points is not None else None
        for kind in kinds:
            # Ours writes `kind:detail`; upstream writes only `kind`. Compared on the kind alone, so
            # a road's OSM class does not make every road look like a mismatch.
            rows[(zoom, name, kind.split(":", 1)[0])] = (share, point_share)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ours", required=True, help="a .mamaps archive")
    parser.add_argument("--theirs", required=True, help="the upstream .pmtiles archive")
    parser.add_argument("--layer", help="compare one layer only")
    parser.add_argument("--max-zoom", type=int, default=14)
    parser.add_argument(
        "--fail-under",
        type=float,
        default=0.0,
        help="fail when a kind's feature ratio falls below this (0 disables, only absence fails)",
    )
    args = parser.parse_args()

    ours = summarise("mamaps_dump", args.ours, args.layer)
    theirs = summarise("pmtiles_dump", args.theirs, args.layer)

    # Aggregated over zooms, because a one-level difference in a kind's minimum zoom moves every
    # count in a way that says nothing about whether the mapping is right.
    def fold(rows):
        out = defaultdict(lambda: [0.0, 0.0])
        for (zoom, layer, kind), (features, points) in rows.items():
            if zoom > args.max_zoom:
                continue
            out[(layer, kind)][0] += features
            out[(layer, kind)][1] += points or 0.0
        return out

    ours_folded, theirs_folded = fold(ours), fold(theirs)
    keys = sorted(set(ours_folded) | set(theirs_folded))

    print(f"{'layer':<12}{'kind':<24}{'upstream':>12}{'ours':>12}{'ratio':>9}  note")
    missing, thin = [], []
    for layer, kind in keys:
        up = theirs_folded.get((layer, kind), [0.0, 0.0])[0]
        us = ours_folded.get((layer, kind), [0.0, 0.0])[0]
        note = ""
        if up > 0 and us == 0:
            note = "MISSING -- upstream draws this and we draw none"
            missing.append((layer, kind))
        elif up == 0 and us > 0:
            note = "extra -- we draw this and upstream does not"
        ratio = us / up if up > 0 else float("inf") if us > 0 else 1.0
        if up > 0 and us > 0 and args.fail_under > 0 and ratio < args.fail_under:
            note = note or f"THIN -- below the {args.fail_under} floor"
            thin.append((layer, kind))
        print(f"{layer:<12}{kind:<24}{up:>12.0f}{us:>12.0f}{ratio:>9.2f}  {note}")

    print()
    print(f"{len(keys)} (layer, kind) pair(s) compared, {len(missing)} missing, {len(thin)} thin")
    if missing:
        print("missing: " + ", ".join(f"{l}/{k}" for l, k in missing))
    if thin:
        print("thin:    " + ", ".join(f"{l}/{k}" for l, k in thin))
    # A difference is expected; an absence is a bug. Only the second fails.
    return 1 if missing or thin else 0


if __name__ == "__main__":
    sys.exit(main())
