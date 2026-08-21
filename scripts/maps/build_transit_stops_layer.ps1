# build_transit_stops_layer.ps1 -- bake GTFS stops into a transit_stops .pmtiles.
#
# Native counterpart to build_transit_stops_layer.sh, and cargo-only: unlike the
# other layer scripts this needs neither osmium nor tippecanoe, so it runs on the
# Windows dev box. Two stages, both Rust:
#
#   1. gtfs_ingest --bin transit_stops  ->  transit_stops.geojsonseq
#   2. tile_build  --bin tile_points    ->  transit_stops.pmtiles
#
# Input is the SAME unzipped GTFS directories build_ca_transit.ps1 already
# downloads for the offline routing pack, addressed through the same
# feeds.manifest -- so the stop pins on the map and the stops the on-device
# planner routes through come from one source of truth.
#
# Each feature carries `motis_id` (<Transitous prefix>_<gtfs stop_id>), `name` and
# `route_type`. The motis_id is what lets a tapped pin fetch live departures from
# MOTIS /stoptimes without the removed /api/v1/map/stops lookup.
#
# Usage:
#   .\build_transit_stops_layer.ps1 -Manifest ca_transit_work\feeds.manifest
#   .\build_transit_stops_layer.ps1 -Manifest m.txt -Out transit_stops.pmtiles `
#       -MinZoom 10 -MaxZoom 14
#
# Requires: cargo (https://rustup.rs). Nothing else.
[CmdletBinding()]
param(
    # feeds.manifest written by build_ca_transit.ps1: one
    # `name=dir[=motis_prefix]` line per feed.
    [Parameter(Mandatory = $true)][string]$Manifest,
    [string]$Out = "transit_stops.pmtiles",
    # Scratch dir for the intermediate geojsonseq.
    [string]$Work = "transit_stops_work",
    # 10 because stops are denser than ma_pois (12) but far sparser than roads.
    [int]$MinZoom = 10,
    [int]$MaxZoom = 14,
    # Keep the intermediate geojsonseq for inspection.
    [switch]$KeepWork
)
$ErrorActionPreference = "Stop"

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw "cargo not found. Install Rust from https://rustup.rs"
}
if (-not (Test-Path $Manifest)) {
    throw "manifest not found: $Manifest"
}
if ($MinZoom -gt $MaxZoom) {
    throw "-MinZoom $MinZoom is above -MaxZoom $MaxZoom"
}

New-Item -ItemType Directory -Force -Path $Work | Out-Null
$Work = (Resolve-Path $Work).Path
$Manifest = (Resolve-Path $Manifest).Path
$geojson = Join-Path $Work "transit_stops.geojsonseq"

$gtfsIngest = Join-Path $PSScriptRoot "gtfs_ingest\Cargo.toml"
$tileBuild = Join-Path $PSScriptRoot "tile_build\Cargo.toml"

Write-Host "[transit_stops] 1/2 GTFS -> geojsonseq"
cargo run --release --quiet --manifest-path $gtfsIngest --bin transit_stops -- `
    --geojson $geojson --manifest $Manifest
if ($LASTEXITCODE -ne 0) { throw "transit_stops (gtfs_ingest) failed with exit code $LASTEXITCODE" }

$features = (Get-Content $geojson | Measure-Object -Line).Lines
Write-Host "[transit_stops] $features stop feature(s)"
if ($features -eq 0) { throw "no stop features produced - check the manifest" }

Write-Host "[transit_stops] 2/2 geojsonseq -> $Out (z$MinZoom-$MaxZoom)"
cargo run --release --quiet --manifest-path $tileBuild --bin tile_points -- `
    --geojson $geojson --out $Out --layer transit_stops `
    --minzoom $MinZoom --maxzoom $MaxZoom
if ($LASTEXITCODE -ne 0) { throw "tile_points failed with exit code $LASTEXITCODE" }

if (-not $KeepWork) { Remove-Item $geojson -Force -ErrorAction SilentlyContinue }

$size = [math]::Round((Get-Item $Out).Length / 1MB, 2)
Write-Host ""
Write-Host "[transit_stops] done: $Out ($size MB), layer 'transit_stops'"
Write-Host "Merge it into the basemap with:"
Write-Host "  cargo run --release --manifest-path scripts/maps/tile_build/Cargo.toml \"
Write-Host "      --bin tile_join -- --out v5-ca.pmtiles base.pmtiles $Out"
