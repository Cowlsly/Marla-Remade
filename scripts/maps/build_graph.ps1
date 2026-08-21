# build_graph.ps1 -- build the single global routing graph on Windows.
#
# The whole point of porting generator.cpp to Rust (scripts/maps/osm_ingest) was
# that regenerating a pack no longer needs WSL, g++ or libosmium. This is the
# native entry point: one command, no bash.
#
# run_generator.sh is still the full Linux pipeline (it also configures the AWS
# CLI and syncs to R2). This script only builds the graph; upload separately with
# publish_r2.sh or `aws s3 sync`.
#
# Usage:
#   .\build_graph.ps1                                   # california-latest.osm.pbf -> map_data
#   .\build_graph.ps1 -Pbf C:\maps\california-latest.osm.pbf -Out C:\maps\map_data
#   .\build_graph.ps1 -Pois                             # also build the POI side files
#
# Only the default California extract is downloaded automatically; any other -Pbf
# must already exist (grab it from https://download.geofabrik.de).
#
# Requires: cargo (https://rustup.rs). The build needs roughly 10 GB of RAM on a
# state-sized extract.

[CmdletBinding()]
param(
    # Input OSM extract. Downloaded from Geofabrik if missing.
    [string]$Pbf = "california-latest.osm.pbf",
    # Output directory; this is the layout maps/src/main/rust/src/graph.rs mmaps.
    [string]$Out = "map_data",
    # Also emit poi_names.bin / poi_index.bin into $Out. The .pmtiles tile build
    # still needs tippecanoe, so it is not done here.
    [switch]$Pois
)

$ErrorActionPreference = "Stop"
$manifest = Join-Path $PSScriptRoot "osm_ingest\Cargo.toml"
$DefaultPbf = "california-latest.osm.pbf"
$DefaultUrl = "https://download.geofabrik.de/north-america/us/$DefaultPbf"

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw "cargo not found. Install Rust from https://rustup.rs"
}

if (Test-Path $Pbf) {
    Write-Host "[1/3] Using $Pbf ($([math]::Round((Get-Item $Pbf).Length / 1GB, 2)) GB)"
} elseif ((Split-Path $Pbf -Leaf) -eq $DefaultPbf) {
    # Only the default extract has a known URL. Auto-downloading California into
    # some other requested filename would silently build a wrong-region graph.
    Write-Host "[1/3] $Pbf not found. Downloading $DefaultUrl ..."
    $partial = "$Pbf.partial"
    Invoke-WebRequest -Uri $DefaultUrl -OutFile $partial
    Move-Item -Force $partial $Pbf
} else {
    throw "$Pbf not found. Download it from https://download.geofabrik.de and retry."
}

Write-Host "[2/3] Building the routing graph -> $Out"
cargo run --release --manifest-path $manifest --bin road_graph -- $Pbf --out $Out
if ($LASTEXITCODE -ne 0) { throw "road_graph failed with exit code $LASTEXITCODE" }

if ($Pois) {
    Write-Host "[3/3] Building the POI side files -> $Out"
    cargo run --release --manifest-path $manifest --bin poi_extract -- $Pbf `
        --geojson (Join-Path $Out "ma_pois.geojsonseq") `
        --names   (Join-Path $Out "poi_names.bin") `
        --index   (Join-Path $Out "poi_index.bin")
    if ($LASTEXITCODE -ne 0) { throw "poi_extract failed with exit code $LASTEXITCODE" }
} else {
    Write-Host "[3/3] Skipping POIs (pass -Pois to build them)"
}

Write-Host ""
Write-Host "Graph written to $((Resolve-Path $Out).Path):"
Get-ChildItem $Out | Select-Object Name, Length | Format-Table -AutoSize
