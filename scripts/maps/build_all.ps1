# build_all.ps1 — the Windows twin of build_all.sh.
#
# Same job: one command, from an .osm.pbf plus a GTFS source to the runtime
# artifacts, with every bytes-to-bytes transformation done by our own Rust crates
# and only network I/O and process orchestration here.
#
# WHAT THIS TWIN CAN AND CANNOT DO. Only `admin_country` and `admin_region` cannot
# be built here, and never will be from OSM: they come from Natural Earth
# shapefiles. Everything else is cargo-only, so the tiles stage builds safety,
# maxspeed, transit_lines, admin_city, ma_pois and transit_stops and merges them
# into an OVERLAY-ONLY archive -- no base. The app mounts that alongside the
# published base archive, which is also the only shape that fits in tile_join's
# memory at planet scale (see build_v5_pmtiles.sh --no-base). It says so loudly
# rather than quietly emitting an archive with layers missing. For the two
# Natural Earth admin levels, run build_all.sh under WSL. Stages graph, pois and
# transit are complete.
#
# THE 11 ARTIFACTS, all landing in -OutDir:
#   graph    metadata.bin road_names.bin nodes.bin edges.bin lanes.bin
#            intermediate.bin
#   pois     poi_names.bin poi_index.bin poi_attrs.bin poi_spatial.bin poi_name_index.bin
#   transit  world.transit
#   tiles    v5-overlay.pmtiles               (name from -Out)
# plus manifest.txt with each one's size and SHA-256, byte-comparable with the
# manifest.txt build_all.sh writes.
#
# Each stage stamps <Work>\stamps on success, so a re-run skips finished work;
# -Force ignores the stamps.
#
# Examples:
#   .\build_all.ps1 -Pbf california-latest.osm.pbf -GtfsRegion us-ca
#   .\build_all.ps1 -Geofabrik north-america/us/california -Only graph
#   .\build_all.ps1 -Pbf norcal.osm.pbf -DryRun
#
# Requirements: PowerShell 7, cargo, and `tar` (in-box on Windows 10+) for the
# transit registries. Nothing else.

[CmdletBinding()]
param(
    # Inputs
    [string] $Pbf = "",
    # Geofabrik region path, e.g. north-america/us/california ('-latest.osm.pbf'
    # is appended). Cached in -Work.
    [string] $Geofabrik = "",
    # feeds.manifest ('name=dir[=motis_prefix]' per line) for world.transit and
    # the transit_stops tile layer.
    [string] $GtfsManifest = "",
    # Transitous registry file to resolve instead, e.g. 'us-ca'.
    [string] $GtfsRegion = "",

    # Outputs
    [string] $OutDir = "build_all_out",
    [string] $Out = "",
    [string] $Work = "",

    # Stage control
    [switch] $SkipGraph,
    [switch] $SkipPois,
    [switch] $SkipTransit,
    [switch] $SkipTiles,
    [ValidateSet("", "graph", "pois", "transit", "tiles")]
    [string] $Only = "",
    [switch] $Force,
    [switch] $DryRun,

    # Engines, per layer, so a ported layer rolls back with a flag. A 'rust' value
    # is refused until that layer is actually ported.
    [ValidateSet("rust", "legacy")] [string] $EnginePois = "rust",
    [ValidateSet("rust", "legacy")] [string] $EngineSafety = "rust",
    [ValidateSet("rust", "legacy")] [string] $EngineMaxspeed = "rust",
    [ValidateSet("rust", "legacy")] [string] $EngineTransitLines = "rust",
    [ValidateSet("rust", "legacy")] [string] $EngineAdminCity = "rust",

    # Publishing. Delegates to publish_r2.sh, which needs bash (WSL or Git Bash).
    [switch] $Publish,
    [switch] $PublishDryRun,

    # Graph build-time memory, forwarded to road_graph. None change the on-disk
    # contract, and -Rounds does not change the output at all. Required at planet
    # scale -- see osm_ingest/README.md 'Build-time memory'.
    [switch] $WithinWayChains,
    [int] $Rounds = 0,
    [string] $SpillDir = "",

    [int] $Jobs = 6
)

$ErrorActionPreference = "Stop"
$Here = $PSScriptRoot

function Invoke-Step {
    param([string] $Exe, [string[]] $StepArgs)
    if ($DryRun) {
        Write-Host "[dry-run] $Exe $($StepArgs -join ' ')"
        return
    }
    & $Exe @StepArgs
    if ($LASTEXITCODE -ne 0) { throw "$Exe failed with exit code $LASTEXITCODE" }
}

function Test-Stage {
    param([string] $Name)
    if ($Only) { return $Only -eq $Name }
    switch ($Name) {
        "graph"   { return -not $SkipGraph }
        "pois"    { return -not $SkipPois }
        "transit" { return -not $SkipTransit }
        "tiles"   { return -not $SkipTiles }
    }
    return $false
}

function Test-Stamp {
    param([string] $Name)
    return (-not $Force) -and (Test-Path (Join-Path $Stamps "$Name.done"))
}

function Set-Stamp {
    param([string] $Name)
    if (-not $DryRun) { New-Item -ItemType File -Force -Path (Join-Path $Stamps "$Name.done") | Out-Null }
}

function Assert-Pbf {
    param([string] $Stage)
    if (-not $script:Pbf) { throw "-Pbf or -Geofabrik is required for the $Stage stage (or -Skip$Stage)" }
    if (-not $DryRun -and -not (Test-Path $script:Pbf)) { throw "pbf not found: $script:Pbf" }
}

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw "cargo not installed (https://rustup.rs)"
}
if ($EnginePois -ne "rust" -and $EnginePois -ne "legacy") {
    throw "-EnginePois must be rust or legacy"
}
if ($EnginePois -eq "legacy") {
    throw "-EnginePois legacy needs tippecanoe, which has no Windows build; use rust (the default)"
}
if ($EngineSafety -eq "legacy") {
    throw "-EngineSafety legacy needs osmium, tippecanoe and python3, none of which have a Windows path; use rust (the default), or build under WSL"
}
foreach ($pair in @(@("EngineMaxspeed", $EngineMaxspeed), @("EngineTransitLines", $EngineTransitLines), @("EngineAdminCity", $EngineAdminCity))) {
    if ($pair[1] -eq "legacy") {
        throw "-$($pair[0]) legacy needs osmium, tippecanoe, python3 (and GDAL for transit_lines), none of which have a Windows path; use rust (the default), or build under WSL"
    }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
if (-not $Work) { $Work = Join-Path $OutDir "work" }
New-Item -ItemType Directory -Force -Path $Work | Out-Null
$Work = (Resolve-Path $Work).Path
if (-not $Out) { $Out = Join-Path $OutDir "v5-overlay.pmtiles" }
$Stamps = Join-Path $Work "stamps"
New-Item -ItemType Directory -Force -Path $Stamps | Out-Null

$OsmManifest  = Join-Path $Here "osm_ingest\Cargo.toml"
$TileManifest = Join-Path $Here "tile_build\Cargo.toml"
$GtfsCargo    = Join-Path $Here "gtfs_ingest\Cargo.toml"

# --- input: fetch the PBF if asked ---
# Network I/O lives here rather than in osm_ingest, which is why that crate has
# no HTTP dependency.
if ($Geofabrik) {
    if ($Pbf) { throw "pass -Pbf or -Geofabrik, not both" }
    $leaf = ($Geofabrik -split '/')[-1]
    $Pbf = Join-Path $Work "$leaf-latest.osm.pbf"
    if (Test-Path $Pbf) {
        Write-Host "[all] reusing cached $Pbf"
    } else {
        $url = "https://download.geofabrik.de/$Geofabrik-latest.osm.pbf"
        Write-Host "[all] fetching $url"
        if (-not $DryRun) {
            # Download to .partial so an interrupted fetch is never mistaken for a
            # complete cached extract on the next run.
            $partial = "$Pbf.partial"
            Invoke-WebRequest -Uri $url -OutFile $partial -MaximumRedirection 5
            Move-Item -Force $partial $Pbf
        }
    }
}

$PoisTile    = Join-Path $Work "ma_pois.pmtiles"
$SafetyTile  = Join-Path $Work "safety.pmtiles"
$MaxspeedTile = Join-Path $Work "maxspeed.pmtiles"
$TransitLinesTile = Join-Path $Work "transit_lines.pmtiles"
$AdminCityTile = Join-Path $Work "admin_city.pmtiles"
$StopsTile   = Join-Path $Work "transit_stops.pmtiles"
$TransitWork = Join-Path $Work "transit"

# --- stage: graph (6 files) ---
if (Test-Stage "graph") {
    if (Test-Stamp "graph") {
        Write-Host "=== graph: stamp present, skipping (-Force to redo) ==="
    } else {
        Assert-Pbf "Graph"
        Write-Host "=== graph -> $OutDir (metadata/road_names/nodes/edges/lanes/intermediate.bin) ==="
        # Gated deliberately: road_graph truncates its outputs as it writes, so a
        # failure here must not fall through to the manifest or the publish.
        $graphArgs = @($Pbf, "--out", $OutDir)
        if ($WithinWayChains) { $graphArgs += "--within-way-chains" }
        if ($Rounds -gt 0)    { $graphArgs += @("--rounds", "$Rounds") }
        if ($SpillDir)        { $graphArgs += @("--spill-dir", $SpillDir) }
        Invoke-Step "cargo" (@("run", "--release", "--quiet", "--manifest-path", $OsmManifest,
            "--bin", "road_graph", "--") + $graphArgs)
        Set-Stamp "graph"
    }
}

# --- stage: pois (3 side files + the ma_pois tile layer) ---
# One poi_extract pass produces the geojson the tiler reads AND the three side
# files, so they cannot disagree. The side files go straight to -OutDir.
if (Test-Stage "pois") {
    if (Test-Stamp "pois") {
        Write-Host "=== pois: stamp present, skipping (-Force to redo) ==="
    } else {
        Assert-Pbf "Pois"
        $poiGeo = Join-Path $Work "ma_pois.geojsonseq"
        Write-Host "=== pois -> $OutDir\poi_names.bin + poi_index.bin + poi_attrs.bin + poi_spatial.bin + poi_name_index.bin ==="
        Invoke-Step "cargo" @("run", "--release", "--quiet", "--manifest-path", $OsmManifest,
            "--bin", "poi_extract", "--", $Pbf,
            "--geojson", $poiGeo,
            "--names", (Join-Path $OutDir "poi_names.bin"),
            "--index", (Join-Path $OutDir "poi_index.bin"),
            "--attrs", (Join-Path $OutDir "poi_attrs.bin"),
            "--spatial", (Join-Path $OutDir "poi_spatial.bin"),
            "--name-index", (Join-Path $OutDir "poi_name_index.bin"))
        Write-Host "=== pois -> $PoisTile (z12-16, tile_points) ==="
        Invoke-Step "cargo" @("run", "--release", "--quiet", "--manifest-path", $TileManifest,
            "--bin", "tile_points", "--",
            "--geojson", $poiGeo, "--out", $PoisTile, "--layer", "ma_pois",
            "--minzoom", "12", "--maxzoom", "16")
        Set-Stamp "pois"
    }
}

# --- stage: transit (world.transit) ---
# build_ca_transit.ps1 is the only producer that resolves transitland-atlas DMFR
# references, so it is the one that can emit MOTIS stop ids. Its manifest is the
# three-field form, and it lands beside the downloads.
if (Test-Stage "transit") {
    if (Test-Stamp "transit") {
        Write-Host "=== transit: stamp present, skipping (-Force to redo) ==="
    } else {
        if (-not $GtfsManifest -and -not $GtfsRegion) {
            throw "the transit stage needs -GtfsManifest or -GtfsRegion (or -SkipTransit)"
        }
        if ($GtfsManifest -and $GtfsRegion) {
            throw "pass -GtfsManifest or -GtfsRegion, not both"
        }
        if ($GtfsRegion) {
            Write-Host "=== transit: resolving region $GtfsRegion -> $OutDir\world.transit ==="
            Invoke-Step "pwsh" @("-NoProfile", "-File", (Join-Path $Here "build_ca_transit.ps1"),
                "-Region", $GtfsRegion, "-Work", $TransitWork, "-Out", $OutDir,
                "-PackName", "world", "-Jobs", "$Jobs")
            $script:EffectiveGtfsManifest = Join-Path $TransitWork "feeds.manifest"
        } else {
            Write-Host "=== transit: merging $GtfsManifest -> $OutDir\world.transit ==="
            Invoke-Step "cargo" @("run", "--release", "--quiet", "--manifest-path", $GtfsCargo,
                "--bin", "gtfs_ingest", "--", $OutDir, "world", "--manifest", $GtfsManifest)
            $script:EffectiveGtfsManifest = $GtfsManifest
        }
        Set-Stamp "transit"
    }
}
if (-not $script:EffectiveGtfsManifest) {
    $candidate = Join-Path $TransitWork "feeds.manifest"
    if (Test-Path $candidate) { $script:EffectiveGtfsManifest = $candidate }
    elseif ($GtfsManifest)    { $script:EffectiveGtfsManifest = $GtfsManifest }
}

# --- stage: tiles (the cargo-only overlay layers, merged without a base) ---
if (Test-Stage "tiles") {
    if (Test-Stamp "tiles") {
        Write-Host "=== tiles: stamp present, skipping (-Force to redo) ==="
    } else {
        # safety, maxspeed, transit_lines and admin_city are all cargo-only:
        # osm_extract reads the PBF directly -- assembling boundary rings itself for
        # admin_city -- and the tile_build tilers tile it. No osmium, tippecanoe,
        # GDAL or python3 is involved.
        if ($Pbf) {
            foreach ($spec in @(
                @{ Layer = "safety";        Tile = $SafetyTile;       Bin = "tile_points";   Min = 10; Max = 16; Extra = @() },
                @{ Layer = "maxspeed";      Tile = $MaxspeedTile;     Bin = "tile_lines";    Min = 12; Max = 16; Extra = @() },
                @{ Layer = "transit_lines"; Tile = $TransitLinesTile; Bin = "tile_lines";    Min = 8;  Max = 16; Extra = @() },
                # Admin polygons must stay whole enough to reassemble for the
                # dimming mask, so the per-tile byte budget is effectively lifted --
                # the same reason the legacy path passed --no-tile-size-limit.
                @{ Layer = "admin_city";    Tile = $AdminCityTile;    Bin = "tile_polygons"; Min = 6;  Max = 12;
                   Extra = @("--simplification", "4", "--max-tile-bytes", "100000000") }
            )) {
                $geo = Join-Path $Work "$($spec.Layer).geojsonseq"
                Write-Host "=== tiles: $($spec.Layer) -> $($spec.Tile) ==="
                Invoke-Step "cargo" @("run", "--release", "--quiet", "--manifest-path", $OsmManifest,
                    "--bin", "osm_extract", "--", $Pbf, "--layer", $spec.Layer, "--out", $geo)
                Invoke-Step "cargo" (@("run", "--release", "--quiet", "--manifest-path", $TileManifest,
                    "--bin", $spec.Bin, "--",
                    "--geojson", $geo, "--out", $spec.Tile, "--layer", $spec.Layer,
                    "--minzoom", "$($spec.Min)", "--maxzoom", "$($spec.Max)") + $spec.Extra)
            }
        } else {
            Write-Warning "no -Pbf given; skipping the safety, maxspeed, transit_lines and admin_city layers"
        }

        # transit_stops needs the same feed manifest world.transit was built from,
        # so the pins and the schedules agree on stop ids.
        if ($script:EffectiveGtfsManifest -and (Test-Path $script:EffectiveGtfsManifest)) {
            $stopsGeo = Join-Path $Work "transit_stops.geojsonseq"
            Write-Host "=== tiles: transit_stops -> $StopsTile ==="
            Invoke-Step "cargo" @("run", "--release", "--quiet", "--manifest-path", $GtfsCargo,
                "--bin", "transit_stops", "--",
                "--geojson", $stopsGeo, "--manifest", $script:EffectiveGtfsManifest)
            # z16, matching the merged archive: the merge unions each input's maxzoom
            # and MapLibre overzooms per source, not per layer, so a stops layer tiled
            # lower has its stops vanish above its own maxzoom.
            Invoke-Step "cargo" @("run", "--release", "--quiet", "--manifest-path", $TileManifest,
                "--bin", "tile_points", "--",
                "--geojson", $stopsGeo, "--out", $StopsTile, "--layer", "transit_stops",
                "--minzoom", "10", "--maxzoom", "16")
        } else {
            Write-Warning "no GTFS manifest available; skipping the transit_stops layer"
        }

        # Later inputs win a layer-name collision, so a rebuilt overlay replaces a
        # stale copy. No base is joined: the overlay names are disjoint from the
        # base schema's, so the app mounts the two archives side by side instead.
        $inputs = @()
        foreach ($t in @($SafetyTile, $MaxspeedTile, $TransitLinesTile, $AdminCityTile, $PoisTile, $StopsTile)) {
            if ((Test-Path $t) -or $DryRun) { $inputs += $t }
        }
        Write-Host "=== tiles: merging $($inputs.Count) source(s) -> $Out ==="
        $inputs | ForEach-Object { Write-Host "  + $_" }
        Invoke-Step "cargo" (@("run", "--release", "--quiet", "--manifest-path", $TileManifest,
            "--bin", "tile_join", "--", "--out", $Out) + $inputs)
        Write-Warning "admin_country and admin_region are NOT in $Out (they come from Natural Earth shapefiles, not OSM -- build under WSL for those two)"
        Set-Stamp "tiles"
    }
}

# --- manifest.txt: name, size, SHA-256 for all 11 ---
# Same column layout as build_all.sh so the two are diffable, and lowercase hex
# so the digests compare directly.
$artifacts = @("metadata.bin", "road_names.bin", "nodes.bin", "edges.bin", "lanes.bin",
               "intermediate.bin",
               "poi_names.bin", "poi_index.bin", "poi_attrs.bin", "poi_spatial.bin",
               "poi_name_index.bin", "world.transit",
               (Split-Path $Out -Leaf))

function Get-ArtifactPath {
    param([string] $Name)
    if ($Name -eq (Split-Path $Out -Leaf)) { return $Out }
    return (Join-Path $OutDir $Name)
}

if ($DryRun) {
    Write-Host ""
    Write-Host "[dry-run] would write $OutDir\manifest.txt over these $($artifacts.Count) artifacts:"
    $artifacts | ForEach-Object { Write-Host "  $_" }
} else {
    $manifestFile = Join-Path $OutDir "manifest.txt"
    $lines = @()
    $present = @()
    $missing = @()
    foreach ($a in $artifacts) {
        $p = Get-ArtifactPath $a
        if (Test-Path $p) {
            $len = (Get-Item $p).Length
            $sha = (Get-FileHash -Algorithm SHA256 -Path $p).Hash.ToLowerInvariant()
            $lines += ("{0,-16} {1,14}  {2}" -f $a, $len, $sha)
            $present += $p
        } else {
            $lines += ("{0,-16} {1,14}  {2}" -f $a, "-", "MISSING")
            $missing += $a
        }
    }
    # LF, no BOM: manifest.txt is compared against the one build_all.sh writes.
    [System.IO.File]::WriteAllText($manifestFile, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ""
    Write-Host "=== $manifestFile ($($present.Count)/9 present) ==="
    $lines | ForEach-Object { Write-Host $_ }
    if ($missing.Count -gt 0) {
        # Not fatal: -Skip*/-Only runs are expected to leave holes. Naming them is
        # what stops a partial run from looking like a complete one.
        Write-Warning "NOT built this run: $($missing -join ', ')"
    }
}

# --- publish (optional) ---
# publish_r2.sh stays the single uploader; reimplementing it here would give the
# two platforms different keys, content types and cache headers.
if ($Publish) {
    $bash = Get-Command bash -ErrorAction SilentlyContinue
    if ($DryRun) {
        Write-Host "[dry-run] would publish every present artifact via publish_r2.sh"
    } elseif (-not $bash) {
        throw @"
-Publish needs bash to run publish_r2.sh (WSL or Git Bash). Either install one,
or upload from a Linux box with:

  ./scripts/maps/publish_r2.sh <files...>
"@
    } elseif ($present.Count -eq 0) {
        throw "-Publish but nothing was built"
    } else {
        Write-Host ""
        Write-Host "=== publishing $($present.Count) artifact(s) to R2 ==="
        $pubArgs = @((Join-Path $Here "publish_r2.sh")) + $present
        if ($PublishDryRun) { $pubArgs += "--dry-run" }
        Invoke-Step $bash.Source $pubArgs
    }
} else {
    Write-Host ""
    Write-Host "Publish (creds from env -- see README 'Publishing to R2'):"
    Write-Host "  `$env:R2_ENDPOINT='https://<ACCOUNT_ID>.r2.cloudflarestorage.com'"
    Write-Host "  `$env:R2_ACCESS_KEY_ID='...'; `$env:R2_SECRET_ACCESS_KEY='...'"
    Write-Host "  .\build_all.ps1 <same args> -Publish"
}
