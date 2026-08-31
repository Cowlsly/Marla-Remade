# measure_build.ps1 — build one .mamaps and report what it cost.
#
# One command, live progress, and a summary at the end.
#
# The build's own output goes **straight to the console**, not through a pipeline. That
# matters: the progress bars redraw with a carriage return and no newline, and anything
# that pipes them buffers until the line ends, so a piped bar does not animate — it
# appears once, finished. The numbers for the summary come from `--report`, which the
# build already writes as JSON, rather than from screen-scraping what you just watched.
#
# What this adds that the binary cannot easily measure about itself on Windows:
#   * Peak resident memory. `PeakWorkingSet64` is a kernel high-water mark, so sampling
#     from outside cannot miss a spike between two polls.
#   * Peak scratch disk. The spill is written during stage A and drained during tiling,
#     so it is gone before the build ends and only a sampler ever sees its size.
#   * The archive's section breakdown, from `mamaps_dump`, which already decodes the
#     header — reimplementing that here would be a second copy to keep in step.
#
# Usage:
#   .\measure_build.ps1 -Pbf ..\..\..\maps-work\us-west-latest.osm.pbf -Out usw.mamaps
#   .\measure_build.ps1 -Pbf north-america-latest.osm.pbf -Out na.mamaps -MaxZoom 13 -Keep
#   .\measure_build.ps1 -Pbf x.osm.pbf -Out x.mamaps -Timing   # + encode / node-pass CPU
param(
    [Parameter(Mandatory = $true)][string] $Pbf,
    [Parameter(Mandatory = $true)][string] $Out,
    [int]    $MaxZoom = 14,
    [int]    $MinZoom = 0,
    [int]    $Threads = 0,
    # Prints the encode and node-pass CPU splits. Off by default because the counters
    # behind it are hit per tile on every worker, and an instrument that contends changes
    # what it measures.
    [switch] $Timing,
    [switch] $Keep,
    # Leave the feature spill and its index behind, so a later -ReuseStore run skips stage A.
    [switch] $KeepStore,
    # Tile from a spill an earlier -KeepStore run left. Stage A is most of a large build and is
    # identical every time for the same input, so this is the difference between a 20-minute
    # experiment and a 60-minute one. Refused if the spill was built from anything else.
    [switch] $ReuseStore,
    [string] $Exe,
    [string] $Dump
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

function Find-Binary([string] $given, [string] $crate, [string] $name) {
    if ($given) { return (Resolve-Path $given).Path }
    $built = Join-Path $repo "scripts\maps\$crate\target\release\$name.exe"
    if (Test-Path $built) { return $built }
    throw "no $name.exe — run: cargo build --release --manifest-path scripts\maps\$crate\Cargo.toml"
}

$build = Find-Binary $Exe  "mamaps_build" "mamaps_build"
$dump  = Find-Binary $Dump "tile_build"   "mamaps_dump"
$Pbf   = (Resolve-Path $Pbf).Path
$Out   = [System.IO.Path]::GetFullPath($Out)
# Named by the writer, beside the output. This is the scratch that peaks.
$spill  = [System.IO.Path]::ChangeExtension($Out, "features.tmp")
$json   = "$Out.report.json"
$name   = [System.IO.Path]::GetFileNameWithoutExtension($build)

if (Test-Path $Out)  { Remove-Item $Out -Force }
if (Test-Path $json) { Remove-Item $json -Force }
if ($KeepStore -and $ReuseStore) { throw "-KeepStore and -ReuseStore are opposites; pass one" }
if ($ReuseStore -and -not (Test-Path $spill)) {
    throw "-ReuseStore needs a spill at $spill — run once with -KeepStore first"
}
if ($Threads -gt 0) { $env:MAPS_THREADS = "$Threads" } else { Remove-Item Env:MAPS_THREADS -ErrorAction SilentlyContinue }
if ($Timing)        { $env:MAPS_TIMING  = "1"       } else { Remove-Item Env:MAPS_TIMING  -ErrorAction SilentlyContinue }

# Started before the build, so the first spike cannot be missed.
$sampler = Start-Job -ArgumentList $name, $spill, $Out -ScriptBlock {
    param($name, $spill, $out)
    $peakWs = 0L; $peakPriv = 0L; $peakSpill = 0L; $peakData = 0L
    while ($true) {
        $p = Get-Process -Name $name -ErrorAction SilentlyContinue
        if ($p) {
            $peakWs   = [Math]::Max($peakWs,   ($p | Measure-Object PeakWorkingSet64    -Maximum).Maximum)
            $peakPriv = [Math]::Max($peakPriv, ($p | Measure-Object PrivateMemorySize64 -Maximum).Maximum)
        }
        foreach ($pair in @(@($spill, [ref]$peakSpill), @("$out.tiledata", [ref]$peakData))) {
            if (Test-Path $pair[0]) {
                $len = (Get-Item $pair[0] -ErrorAction SilentlyContinue).Length
                if ($len -gt $pair[1].Value) { $pair[1].Value = $len }
            }
        }
        [pscustomobject]@{ Ws = $peakWs; Priv = $peakPriv; Spill = $peakSpill; Data = $peakData }
        Start-Sleep -Milliseconds 250
    }
}

$flags = @()
if ($KeepStore)  { $flags += "--keep-store" }
if ($ReuseStore) { $flags += "--reuse-store" }

Write-Output ("=== {0} -> {1}   z{2}..z{3}{4}{5} ===" -f `
    (Split-Path $Pbf -Leaf), (Split-Path $Out -Leaf), $MinZoom, $MaxZoom,
    $(if ($Threads -gt 0) { ", $Threads thread(s)" } else { "" }),
    $(if ($ReuseStore) { ", reusing the spill" } elseif ($KeepStore) { ", keeping the spill" } else { "" }))
Write-Output ""

# No pipeline, no redirect: the bars need the console to redraw on.
$sw = [System.Diagnostics.Stopwatch]::StartNew()
& $build --input $Pbf --out $Out --min-zoom $MinZoom --max-zoom $MaxZoom --report $json @flags
$exit = $LASTEXITCODE
$sw.Stop()

Start-Sleep -Milliseconds 400
$s = Receive-Job $sampler
Stop-Job $sampler; Remove-Job $sampler -Force
$peakWs    = ($s | Measure-Object Ws    -Maximum).Maximum
$peakPriv  = ($s | Measure-Object Priv  -Maximum).Maximum
$peakSpill = ($s | Measure-Object Spill -Maximum).Maximum
$peakData  = ($s | Measure-Object Data  -Maximum).Maximum

# Adaptive, because these span a 128-byte header and a 30 GB spill; a fixed unit makes
# one end of that unreadable.
function Size([double] $b) {
    if ($b -ge 1GB) { return "{0,9:N2} GB" -f ($b / 1GB) }
    if ($b -ge 1MB) { return "{0,9:N1} MB" -f ($b / 1MB) }
    if ($b -ge 1KB) { return "{0,9:N1} KB" -f ($b / 1KB) }
    return "{0,9:N0} B " -f $b
}

Write-Output ""
Write-Output "======================== RESULT ========================"
if ($exit -ne 0) {
    Write-Output ("FAILED, exit {0}, after {1:N1} s" -f $exit, $sw.Elapsed.TotalSeconds)
    exit $exit
}
$r = Get-Content $json -Raw | ConvertFrom-Json
$archive = if (Test-Path $Out) { (Get-Item $Out).Length } else { 0 }

# --- time, per step -------------------------------------------------------
# Tiling is per zoom and comes from the report; stage A's marks are cumulative and are
# printed by the build itself as it goes, so they are summarised rather than repeated.
$map = ($r.zooms | Measure-Object map_ms    -Sum).Sum
$mrg = ($r.zooms | Measure-Object merge_ms  -Sum).Sum
$enc = ($r.zooms | Measure-Object encode_ms -Sum).Sum
$app = ($r.zooms | Measure-Object append_ms -Sum).Sum
$tiling = ($map + $mrg + $enc + $app) / 1000.0
$stageA = $sw.Elapsed.TotalSeconds - $tiling
Write-Output ""
Write-Output "TIME"
Write-Output ("  {0,-24} {1,8:N1} s  {2,5:N1}%   {3}" -f `
    "stage A", $stageA, (100.0 * $stageA / $sw.Elapsed.TotalSeconds),
    $(if ($ReuseStore) { "SKIPPED (spill reused) - this is open/report overhead" } else { "parse, resolve, materialise, spill" }))
foreach ($p in @(@("map (clip)", $map), @("merge", $mrg), @("encode", $enc), @("append", $app))) {
    Write-Output ("  {0,-24} {1,8:N1} s  {2,5:N1}%" -f `
        $p[0], ($p[1] / 1000.0), (100.0 * $p[1] / 1000.0 / $sw.Elapsed.TotalSeconds))
}
Write-Output ("  {0,-24} {1,8:N1} s" -f "TOTAL WALL", $sw.Elapsed.TotalSeconds)

# --- per zoom -------------------------------------------------------------
Write-Output ""
Write-Output "PER ZOOM"
Write-Output ("  {0,-5}{1,12}{2,14}{3,16}{4,10}{5,9}{6,9}{7,9}" -f `
    "zoom", "tiles", "features", "body bytes", "map_s", "merge_s", "enc_s", "app_s")
foreach ($z in $r.zooms) {
    Write-Output ("  z{0,-4}{1,12:N0}{2,14:N0}{3,16:N0}{4,10:N1}{5,9:N1}{6,9:N1}{7,9:N1}" -f `
        $z.zoom, $z.tiles, $z.features, $z.bytes,
        ($z.map_ms / 1000.0), ($z.merge_ms / 1000.0), ($z.encode_ms / 1000.0), ($z.append_ms / 1000.0))
}

# --- memory ---------------------------------------------------------------
Write-Output ""
Write-Output "PEAK MEMORY"
Write-Output ("  {0,-24} {1}   kernel high-water, cannot be missed" -f "resident", (Size $peakWs))
Write-Output ("  {0,-24} {1}" -f "committed private", (Size $peakPriv))

# --- disk -----------------------------------------------------------------
# The input is read-only and pre-existing, so it is reported apart from what the build
# creates. Spill and archive do not peak together, so their sum is an upper bound.
Write-Output ""
Write-Output "DISK"
Write-Output ("  {0,-24} {1}   read-only input" -f "source .pbf", (Size (Get-Item $Pbf).Length))
Write-Output ("  {0,-24} {1}   scratch, deleted" -f "feature spill, peak", (Size $peakSpill))
if ($peakData -gt 0) {
    Write-Output ("  {0,-24} {1}   scratch, deleted" -f "tile scratch, peak", (Size $peakData))
}
Write-Output ("  {0,-24} {1}   the deliverable" -f "archive", (Size $archive))
Write-Output ("  {0,-24} {1}   upper bound; the scratches do not peak together" -f `
    "created, worst case", (Size ($peakSpill + $peakData + $archive)))

# --- the archive, by part -------------------------------------------------
$h = @{}
foreach ($line in (& $dump $Out --mode header)) {
    $f = $line -split "`t"
    if ($f.Count -ge 2) { $h[$f[0]] = $f[1] }
}
function Span([string] $key) {
    # `offset+length`, as the header mode prints it.
    if ($h.ContainsKey($key) -and $h[$key] -match '^(\d+)\+(\d+)$') { return [long]$Matches[2] }
    return 0L
}
Write-Output ""
Write-Output "ARCHIVE BY PART"
foreach ($part in @(
    @("header",      128,          "fixed"),
    @("dictionary",  (Span "dict"),   "layer / kind / detail names"),
    @("root index",  (Span "root"),   "one entry per leaf, binary-searched"),
    @("leaf index",  (Span "leaves"), "16 B per stored body"),
    @("tile bodies", (Span "data"),   "the geometry, DEFLATE'd per body")
)) {
    $pct = if ($archive -gt 0) { 100.0 * $part[1] / $archive } else { 0 }
    Write-Output ("  {0,-14}{1} {2,6:N2}%   {3}" -f $part[0], (Size $part[1]), $pct, $part[2])
}
Write-Output ("  {0,-14}{1} {2,6:N2}%" -f "TOTAL", (Size $archive), 100.0)

$bodyBytes = ($r.zooms | Measure-Object bytes -Sum).Sum
Write-Output ""
Write-Output ("  tiles addressed   {0,14:N0}" -f [long]$h["tiles_addressed"])
Write-Output ("  bodies written    {0,14:N0}   dedup {1} — run-length plus content" -f [long]$h["bodies_written"], $h["dedup"])
Write-Output ("  body bytes raw    {0,14:N0}   {1:N2}x compression into the archive" -f `
    $bodyBytes, ($bodyBytes / [Math]::Max(1, (Span "data"))))
Write-Output ("  features          {0,14:N0}   from {1:N0} ways + {2:N0} relations" -f `
    $r.features, $r.ways_classified, $r.relations_classified)
if ($r.coalesced.line_features_before -gt $r.coalesced.line_features_after) {
    Write-Output ("  coalesced         {0,14:N0} -> {1:N0} line features ({2:N0}x), {3:N0} -> {4:N0} parts ({5:N1}x)" -f `
        $r.coalesced.line_features_before, $r.coalesced.line_features_after,
        ($r.coalesced.line_features_before / [Math]::Max(1, $r.coalesced.line_features_after)),
        $r.coalesced.parts_before, $r.coalesced.parts_after,
        ($r.coalesced.parts_before / [Math]::Max(1, $r.coalesced.parts_after)))
}
Write-Output ("  build id          {0,14}" -f $h["build_id"])
Write-Output ("  sha256            {0}" -f (Get-FileHash $Out -Algorithm SHA256).Hash)

if (-not $Keep) {
    Remove-Item $Out -Force -ErrorAction SilentlyContinue
    Remove-Item $json -Force -ErrorAction SilentlyContinue
    Write-Output ""
    Write-Output "  (archive removed; pass -Keep to keep it)"
}
Write-Output ""
