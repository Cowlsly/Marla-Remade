# build_ca_transit.ps1 -- build a regional offline transit pack on Windows.
#
# Native counterpart to build_world_transit.sh, in the spirit of build_graph.ps1:
# regenerating a transit pack should not need WSL. WSL here also lacks cargo and
# jq, and its own filesystem is nearly full, so a bash run is not an option.
#
# It also fixes a gap that matters for California. build_world_transit.sh scrapes
# `url` fields out of the Transitous registry, but only 11 of California's 49
# sources have one: 38 are `transitland-atlas` references carrying nothing but a
# feed id. Scraping URLs silently yields a pack with a third of the state's
# agencies. This script resolves those ids through the transitland-atlas DMFR
# files, so "every agency in California" means every agency.
#
# Feed URL resolution, in order:
#   1. `url-override` from the Transitous source (already carries any API key)
#   2. `url` from the Transitous source
#   3. the atlas feed's `urls.static_current` -- unless it needs authorization
#   4. the atlas feed's `urls.static_historic[0]`, which is a direct zip and
#      needs no key. This is the fallback for the 511.org-brokered feeds.
# A source Transitous marks `skip` is skipped, with its reason reported.
#
# Usage:
#   .\build_ca_transit.ps1                      # build california.transit
#   .\build_ca_transit.ps1 -Resolve             # just report feed resolution
#   .\build_ca_transit.ps1 -Region us-ny -PackName newyork
#
# Requires: cargo (https://rustup.rs). tar and Invoke-WebRequest ship with the OS
# and PowerShell 7. Downloads are resumable: a feed whose directory already has
# stops.txt is left alone.
[CmdletBinding()]
param(
    # Transitous registry file under feeds/, without the .json.
    [string]$Region = "us-ca",
    # Scratch dir for the registries, zips and unzipped GTFS. Reused/resumable.
    [string]$Work = "ca_transit_work",
    # Output dir for <PackName>.transit (+ .json). Defaults to -Work.
    [string]$Out = "",
    [string]$PackName = "california",
    # Parallel downloads.
    [int]$Jobs = 6,
    # Cap the feeds processed (0 = no cap). For a quick smoke build.
    [int]$Limit = 0,
    # Resolve and report feed URLs, then stop. Downloads nothing.
    [switch]$Resolve
)
$ErrorActionPreference = "Stop"

$TransitousUrl = "https://codeload.github.com/public-transport/transitous/tar.gz/refs/heads/main"
$AtlasUrl = "https://codeload.github.com/transitland/transitland-atlas/tar.gz/refs/heads/main"

if (-not $Out) { $Out = $Work }
New-Item -ItemType Directory -Force -Path $Work, $Out | Out-Null
$Work = (Resolve-Path $Work).Path
$Out = (Resolve-Path $Out).Path
$gtfsRoot = Join-Path $Work "gtfs"
$zipDir = Join-Path $Work "zips"
New-Item -ItemType Directory -Force -Path $gtfsRoot, $zipDir | Out-Null

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw "cargo not found. Install Rust from https://rustup.rs"
}

# A feed name safe for a filename and for the on-device string pool, where it is
# the per-route provenance label. Lossy on purpose, so the ORIGINAL source name is
# kept alongside it (`Source`) for the MOTIS id namespace, which needs the exact
# spelling: `SF-bayarea` collapses to `sf_bayarea` here and cannot be recovered.
function Get-SafeName([string]$s) {
    ($s.ToLowerInvariant() -replace '[^a-z0-9]+', '_').Trim('_')
}

function Get-Registry([string]$url, [string]$tarball, [string]$expectDir) {
    $tarPath = Join-Path $Work $tarball
    $dir = Join-Path $Work $expectDir
    if (-not (Test-Path $dir)) {
        if (-not (Test-Path $tarPath)) {
            Write-Host "  downloading $tarball ..."
            Invoke-WebRequest -Uri $url -OutFile $tarPath
        }
        Push-Location $Work
        try { tar -xzf $tarball } finally { Pop-Location }
    }
    if (-not (Test-Path $dir)) { throw "expected $dir after extracting $tarball" }
    $dir
}

Write-Host "[1/5] Fetching feed registries"
$transitousDir = Get-Registry $TransitousUrl "transitous.tar.gz" "transitous-main"
$atlasDir = Get-Registry $AtlasUrl "transitland-atlas.tar.gz" "transitland-atlas-main"

$regionFile = Join-Path $transitousDir "feeds\$Region.json"
if (-not (Test-Path $regionFile)) {
    throw "no Transitous registry file for region '$Region' (looked for $regionFile)"
}

Write-Host "[2/5] Indexing transitland-atlas feeds"
# atlas feed id -> the feed object, so a Transitous `transitland-atlas-id` can be
# turned into a download URL.
$atlasById = @{}
foreach ($f in Get-ChildItem (Join-Path $atlasDir "feeds") -Filter *.dmfr.json) {
    $doc = Get-Content $f.FullName -Raw | ConvertFrom-Json
    foreach ($feed in $doc.feeds) {
        if ($feed.id) { $atlasById[$feed.id] = $feed }
    }
}
Write-Host "  indexed $($atlasById.Count) atlas feeds"

Write-Host "[3/5] Resolving $Region sources to GTFS URLs"
$sources = (Get-Content $regionFile -Raw | ConvertFrom-Json).sources
$resolved = [System.Collections.Generic.List[object]]::new()
$skipped = [System.Collections.Generic.List[object]]::new()

foreach ($s in $sources) {
    $name = if ($s.name) { $s.name } else { $s.'transitland-atlas-id' }
    $note = $null
    $url = $null

    if ($s.skip) {
        $skipped.Add([pscustomobject]@{ Name = $name; Why = "registry says skip: $($s.'skip-reason')" })
        continue
    }
    # `spec` is absent for plain GTFS; anything else named is a different format.
    if ($s.spec -and $s.spec -ne "gtfs") {
        $skipped.Add([pscustomobject]@{ Name = $name; Why = "spec is $($s.spec), not gtfs" })
        continue
    }

    # Resolve the atlas entry first, even when a url-override exists: an agency is
    # often listed twice, once static and once realtime, and only the atlas feed's
    # spec distinguishes them. Trusting the override first lets a `gtfs_rt`
    # endpoint win the dedup below and knock out the agency's real schedule.
    $feed = $null
    if ($s.'transitland-atlas-id') {
        $feed = $atlasById[$s.'transitland-atlas-id']
        if (-not $feed) {
            $skipped.Add([pscustomobject]@{ Name = $name; Why = "atlas id $($s.'transitland-atlas-id') not in the atlas" })
            continue
        }
        if ($feed.spec -and $feed.spec -ne "gtfs") {
            $skipped.Add([pscustomobject]@{ Name = $name; Why = "atlas spec is $($feed.spec)" })
            continue
        }
    }

    if ($s.'url-override') {
        $url = $s.'url-override'; $note = "url-override"
    } elseif ($s.url) {
        $url = $s.url; $note = "registry url"
    } elseif ($feed) {
        $needsKey = [bool]$feed.authorization
        $current = $feed.urls.static_current
        $historic = @($feed.urls.static_historic) | Where-Object { $_ }
        if ($current -and -not $needsKey) {
            $url = $current; $note = "atlas static_current"
        } elseif ($historic.Count -gt 0) {
            # static_current is behind an API key we do not have; the historic
            # entries are direct zips published by the agency itself.
            $url = $historic[0]
            $note = if ($needsKey) { "atlas static_historic (static_current needs a key)" } else { "atlas static_historic" }
        } elseif ($current) {
            $url = $current; $note = "atlas static_current (needs a key; will likely fail)"
        }
    }

    if (-not $url) {
        $skipped.Add([pscustomobject]@{ Name = $name; Why = "no usable GTFS URL" })
        continue
    }
    # Belt and braces: a realtime endpoint serves protobuf, not a schedule zip, and
    # some registry entries carry one without saying so in `spec`. Test the path,
    # not the whole URL, and treat a `.zip` as definitively static — Golden Gate
    # serves its schedule from `realtime.goldengate.org`, so a host name proves
    # nothing.
    $path = ($url -split '\?')[0]
    if ($path -notmatch '\.zip$' -and
        ($path -match 'gtfs[-_]?rt' -or $url -match 'feed_type=' -or $path -match '\.pb$')) {
        $skipped.Add([pscustomobject]@{ Name = $name; Why = "URL is a realtime endpoint" })
        continue
    }
    $resolved.Add([pscustomobject]@{ Name = Get-SafeName $name; Source = $name; Url = $url; Via = $note })
}

# Two sources can resolve to the same zip (an agency listed twice, or an
# aggregate plus a member). Merging the same feed twice would double its stops.
$resolved = $resolved | Sort-Object Name -Unique | Sort-Object Url -Unique
if ($Limit -gt 0) { $resolved = $resolved | Select-Object -First $Limit }

Write-Host "  resolved $($resolved.Count) feeds, skipped $($skipped.Count) of $($sources.Count) sources"
$resolved | ForEach-Object { Write-Host ("    {0,-32} {1}" -f $_.Name, $_.Via) }
if ($skipped.Count) {
    Write-Host "  skipped:"
    $skipped | ForEach-Object { Write-Host ("    {0,-32} {1}" -f $_.Name, $_.Why) }
}
if ($Resolve) { return }
if ($resolved.Count -eq 0) { throw "no feeds resolved - nothing to build" }

Write-Host "[4/5] Downloading + unzipping ($Jobs parallel)"
$results = $resolved | ForEach-Object -ThrottleLimit $Jobs -Parallel {
    $item = $_
    $dir = Join-Path $using:gtfsRoot $item.Name
    $zip = Join-Path $using:zipDir "$($item.Name).zip"
    if (Test-Path (Join-Path $dir "stops.txt")) {
        return [pscustomobject]@{ Name = $item.Name; Status = "cached" }
    }
    try {
        Invoke-WebRequest -Uri $item.Url -OutFile $zip -MaximumRedirection 5 -TimeoutSec 300
    } catch {
        return [pscustomobject]@{ Name = $item.Name; Status = "download failed: $($_.Exception.Message)" }
    }
    # A key-protected or moved endpoint answers with HTML, not a zip.
    $head = [System.IO.File]::ReadAllBytes($zip) | Select-Object -First 2
    if ($head.Count -lt 2 -or $head[0] -ne 0x50 -or $head[1] -ne 0x4B) {
        Remove-Item $zip -Force -ErrorAction SilentlyContinue
        return [pscustomobject]@{ Name = $item.Name; Status = "not a zip (auth wall or error page)" }
    }
    try {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $dir, $true)
    } catch {
        Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue
        return [pscustomobject]@{ Name = $item.Name; Status = "unzip failed: $($_.Exception.Message)" }
    } finally {
        Remove-Item $zip -Force -ErrorAction SilentlyContinue
    }
    # Some feeds nest the .txt files one directory deep.
    if (-not (Test-Path (Join-Path $dir "stops.txt"))) {
        $inner = Get-ChildItem $dir -Recurse -Filter stops.txt | Select-Object -First 1
        if ($inner) { Get-ChildItem $inner.Directory.FullName | Move-Item -Destination $dir -Force }
    }
    if (Test-Path (Join-Path $dir "stops.txt")) {
        [pscustomobject]@{ Name = $item.Name; Status = "ok" }
    } else {
        [pscustomobject]@{ Name = $item.Name; Status = "no stops.txt in the zip" }
    }
}

$good = $results | Where-Object { $_.Status -in @("ok", "cached") }
Write-Host "  $($good.Count) feeds usable"
# Write-Output, not Write-Host, so a failure survives being piped to a log file.
$results | Where-Object { $_.Status -notin @("ok", "cached") } |
    ForEach-Object { Write-Output ("    FAILED {0,-30} {1}" -f $_.Name, $_.Status) }
if ($good.Count -eq 0) { throw "every feed failed to download - nothing to build" }

# The ingest tool takes `feed_name=dir=motis_prefix` lines, one per feed. The
# third field is the feed's Transitous id namespace: a MOTIS stop id is
# `<registry file>-<source name>_<gtfs stop_id>` (e.g. us-ca-SF-bayarea_901201),
# so baking the prefix lets the device name a stop for the realtime /stoptimes
# overlay without a /map/stops lookup. It must be the unmangled `Source`.
#
# Written from $resolved rather than from the directories on disk, which is what
# carries `Source` through -- and also means a stale feed left in the work dir by
# an earlier run with a different -Region or -Limit is no longer picked up.
$manifest = Join-Path $Work "feeds.manifest"
$usable = @{}
$good | ForEach-Object { $usable[$_.Name] = $true }
$lines = foreach ($item in $resolved) {
    if (-not $usable.ContainsKey($item.Name)) { continue }
    $dir = Join-Path $gtfsRoot $item.Name
    if (-not (Test-Path (Join-Path $dir "stops.txt"))) { continue }
    "$($item.Name)=$dir=$Region-$($item.Source)"
}
Set-Content -Path $manifest -Value $lines -Encoding utf8
Write-Host "  wrote $($lines.Count) feeds to $manifest"

Write-Host "[5/5] Merging into $PackName.transit"
$ingestManifest = Join-Path $PSScriptRoot "gtfs_ingest\Cargo.toml"
# --bin is required: the crate also carries `transit_stops` (the basemap stop
# layer), so a bare `cargo run` cannot choose.
cargo run --release --quiet --manifest-path $ingestManifest --bin gtfs_ingest -- $Out $PackName --manifest $manifest
if ($LASTEXITCODE -ne 0) { throw "gtfs_ingest failed with exit code $LASTEXITCODE" }

$packPath = Join-Path $Out "$PackName.transit"
Write-Host ""
Write-Host "Done: $packPath ($([math]::Round((Get-Item $packPath).Length / 1MB, 1)) MB)"
Get-Content (Join-Path $Out "$PackName.transit.json")
