# build_all.ps1 — the Windows twin of build_all.sh.
#
# Same job: one command, from an .osm.pbf plus a GTFS source to the runtime
# artifacts, with every bytes-to-bytes transformation done by our own Rust crates
# and only network I/O and process orchestration here.
#
# WHAT THIS TWIN CAN AND CANNOT DO. The layers that still go through osmium,
# tippecanoe, GDAL and python3 (safety, maxspeed, transit_lines, admin) have no
# Windows path, and neither does the Planetiler base build. So the tiles stage
# here composites only the cargo-only layers -- ma_pois and transit_stops -- on
# top of a base archive you point it at with -BaseArchive. It says so loudly
# rather than quietly emitting an archive with layers missing. For a complete v5,
# run build_all.sh under WSL. Stages graph, pois and transit are complete.
#
# THE 9 ARTIFACTS, all landing in -OutDir:
#   graph    metadata.bin road_names.bin nodes.bin edges.bin lanes.bin
#   pois     poi_names.bin poi_index.bin
#   transit  world.transit
#   tiles    v5.pmtiles                       (name from -Out)
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
    # Base tile archive to composite onto: a local .pmtiles, or a URL to fetch.
    # Required by the tiles stage on Windows.
    [string] $BaseArchive = "",

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

    # Publishing. Delegates to publish_r2.sh, which needs bash (WSL or Git Bash).
    [switch] $Publish,
    [switch] $PublishDryRun,

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

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
if (-not $Work) { $Work = Join-Path $OutDir "work" }
New-Item -ItemType Directory -Force -Path $Work | Out-Null
$Work = (Resolve-Path $Work).Path
if (-not $Out) { $Out = Join-Path $OutDir "v5.pmtiles" }
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
$StopsTile   = Join-Path $Work "transit_stops.pmtiles"
$TransitWork = Join-Path $Work "transit"

# --- stage: graph (5 files) ---
if (Test-Stage "graph") {
    if (Test-Stamp "graph") {
        Write-Host "=== graph: stamp present, skipping (-Force to redo) ==="
    } else {
        Assert-Pbf "Graph"
        Write-Host "=== graph -> $OutDir (metadata/road_names/nodes/edges/lanes.bin) ==="
        # Gated deliberately: road_graph truncates its outputs as it writes, so a
        # failure here must not fall through to the manifest or the publish.
        Invoke-Step "cargo" @("run", "--release", "--quiet", "--manifest-path", $OsmManifest,
            "--bin", "road_graph", "--", $Pbf, "--out", $OutDir)
        Set-Stamp "graph"
    }
}

# --- stage: pois (2 side files + the ma_pois tile layer) ---
# One poi_extract pass produces the geojson the tiler reads AND the two side
# files, so they cannot disagree. The side files go straight to -OutDir.
if (Test-Stage "pois") {
    if (Test-Stamp "pois") {
        Write-Host "=== pois: stamp present, skipping (-Force to redo) ==="
    } else {
        Assert-Pbf "Pois"
        $poiGeo = Join-Path $Work "ma_pois.geojsonseq"
        Write-Host "=== pois -> $OutDir\poi_names.bin + poi_index.bin ==="
        Invoke-Step "cargo" @("run", "--release", "--quiet", "--manifest-path", $OsmManifest,
            "--bin", "poi_extract", "--", $Pbf,
            "--geojson", $poiGeo,
            "--names", (Join-Path $OutDir "poi_names.bin"),
            "--index", (Join-Path $OutDir "poi_index.bin"))
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

# --- stage: tiles (cargo-only layers onto a base archive) ---
if (Test-Stage "tiles") {
    if (Test-Stamp "tiles") {
        Write-Host "=== tiles: stamp present, skipping (-Force to redo) ==="
    } else {
        if (-not $BaseArchive) {
            throw @"
the tiles stage needs -BaseArchive on Windows.

Only ma_pois and transit_stops are cargo-only today; safety, maxspeed,
transit_lines and admin still need osmium/tippecanoe/GDAL/python3, and the
Planetiler base build needs Java. So this twin composites the cargo-only layers
onto an existing archive rather than building one:

  .\build_all.ps1 ... -BaseArchive https://data.vayunmathur.com/v5.pmtiles

For a complete v5 with every layer, run build_all.sh under WSL.
"@
        }

        $base = $BaseArchive
        if ($BaseArchive -match '^https?://') {
            $base = Join-Path $Work "base.pmtiles"
            if (Test-Path $base) {
                Write-Host "[all] reusing cached base $base"
            } else {
                Write-Host "[all] fetching $BaseArchive (this can be very large)"
                if (-not $DryRun) {
                    $partial = "$base.partial"
                    Invoke-WebRequest -Uri $BaseArchive -OutFile $partial -MaximumRedirection 5
                    Move-Item -Force $partial $base
                }
            }
        } elseif (-not $DryRun -and -not (Test-Path $base)) {
            throw "-BaseArchive not found: $base"
        }

        # transit_stops needs the same feed manifest world.transit was built from,
        # so the pins and the schedules agree on stop ids.
        if ($script:EffectiveGtfsManifest -and (Test-Path $script:EffectiveGtfsManifest)) {
            $stopsGeo = Join-Path $Work "transit_stops.geojsonseq"
            Write-Host "=== tiles: transit_stops -> $StopsTile ==="
            Invoke-Step "cargo" @("run", "--release", "--quiet", "--manifest-path", $GtfsCargo,
                "--bin", "transit_stops", "--",
                "--geojson", $stopsGeo, "--manifest", $script:EffectiveGtfsManifest)
            Invoke-Step "cargo" @("run", "--release", "--quiet", "--manifest-path", $TileManifest,
                "--bin", "tile_points", "--",
                "--geojson", $stopsGeo, "--out", $StopsTile, "--layer", "transit_stops",
                "--minzoom", "11", "--maxzoom", "14")
        } else {
            Write-Warning "no GTFS manifest available; skipping the transit_stops layer"
        }

        # Later inputs win a layer-name collision, so the overlays go after base.
        $inputs = @($base)
        foreach ($t in @($PoisTile, $StopsTile)) {
            if ((Test-Path $t) -or $DryRun) { $inputs += $t }
        }
        Write-Host "=== tiles: merging $($inputs.Count) source(s) -> $Out ==="
        $inputs | ForEach-Object { Write-Host "  + $_" }
        Invoke-Step "cargo" (@("run", "--release", "--quiet", "--manifest-path", $TileManifest,
            "--bin", "tile_join", "--", "--out", $Out) + $inputs)
        Write-Warning "safety, maxspeed, transit_lines and admin are NOT in $Out (they need osmium/tippecanoe/GDAL/python3 -- build under WSL for a complete archive)"
        Set-Stamp "tiles"
    }
}

# --- manifest.txt: name, size, SHA-256 for all 9 ---
# Same column layout as build_all.sh so the two are diffable, and lowercase hex
# so the digests compare directly.
$artifacts = @("metadata.bin", "road_names.bin", "nodes.bin", "edges.bin", "lanes.bin",
               "poi_names.bin", "poi_index.bin", "world.transit", (Split-Path $Out -Leaf))

function Get-ArtifactPath {
    param([string] $Name)
    if ($Name -eq (Split-Path $Out -Leaf)) { return $Out }
    return (Join-Path $OutDir $Name)
}

if ($DryRun) {
    Write-Host ""
    Write-Host "[dry-run] would write $OutDir\manifest.txt over these 9 artifacts:"
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
