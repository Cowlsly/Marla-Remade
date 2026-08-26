# Wall clock and PEAK working set for one tiler run, at a given thread count.
#
# Peak RSS is the number that matters as much as wall clock: a phase that gets
# faster while multiplying peak memory by the thread count has not succeeded. The
# .NET Process object is used rather than Measure-Command because only it exposes
# PeakWorkingSet64.
param(
    [Parameter(Mandatory = $true)][string] $Bin,
    [Parameter(Mandatory = $true)][string] $Geojson,
    [int[]] $Threads = @(1, 32),
    [int] $MinZoom = 11,
    [int] $MaxZoom = 16,
    [switch] $Stream
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$exe = Join-Path $here "../target/release/$Bin.exe"
if (-not (Test-Path $exe)) { throw "build it first: cargo build --release ($exe)" }

# Scratch goes to the system temp dir, not into the repo: the outputs are hundreds of
# MB and the spill is larger still, and neither belongs anywhere git has to think
# about. Only this script and gen_roads.py live under bench/.
$work = Join-Path ([System.IO.Path]::GetTempPath()) "tile_build_bench_$PID"
New-Item -ItemType Directory -Force -Path $work | Out-Null

try {
$results = @()
foreach ($n in $Threads) {
    $out = Join-Path $work "out_$n.pmtiles"
    $spill = Join-Path $work "spill_$n"
    Remove-Item -Recurse -Force $out, $spill -ErrorAction SilentlyContinue

    $argv = @("--geojson", $Geojson, "--out", $out, "--layer", "roads",
              "--minzoom", "$MinZoom", "--maxzoom", "$MaxZoom", "--threads", "$n")
    if ($Stream) { $argv += @("--stream", "--spill-dir", $spill) }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = (Resolve-Path $exe).Path
    foreach ($a in $argv) { $psi.ArgumentList.Add($a) }
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $p = [System.Diagnostics.Process]::Start($psi)
    # Drained on background tasks so a full pipe buffer cannot deadlock the child.
    $so = $p.StandardOutput.ReadToEndAsync()
    $se = $p.StandardError.ReadToEndAsync()
    # Polled while the child is alive: PeakWorkingSet64 reads 0 once a process has
    # exited, so sampling after WaitForExit measures nothing at all.
    $peak = 0L
    while (-not $p.HasExited) {
        try { $p.Refresh(); if ($p.PeakWorkingSet64 -gt $peak) { $peak = $p.PeakWorkingSet64 } } catch { }
        Start-Sleep -Milliseconds 20
    }
    $p.WaitForExit()
    $sw.Stop()
    $null = $so.Result; $err = $se.Result

    if ($p.ExitCode -ne 0) { Write-Host $err; throw "$Bin exited $($p.ExitCode) at --threads $n" }

    $results += [pscustomobject]@{
        Threads    = $n
        Seconds    = [math]::Round($sw.Elapsed.TotalSeconds, 1)
        PeakRssMiB = [math]::Round($peak / 1MB, 1)
        OutMiB     = [math]::Round((Get-Item $out).Length / 1MB, 1)
        Sha256     = (Get-FileHash $out -Algorithm SHA256).Hash.Substring(0, 16)
    }
    Remove-Item -Recurse -Force $spill -ErrorAction SilentlyContinue
}

$results | Format-Table -AutoSize

$hashes = $results.Sha256 | Sort-Object -Unique
if ($hashes.Count -ne 1) {
    throw "THREAD COUNT CHANGED THE OUTPUT: $($results.Sha256 -join ', ')"
}
Write-Host "all $($results.Count) run(s) byte-identical (sha256 $($hashes[0])...)"
$base = $results | Where-Object { $_.Threads -eq $results[0].Threads }
Write-Host ("speedup vs {0} thread(s): {1}" -f $results[0].Threads,
    (($results | ForEach-Object { "{0}t {1:N2}x" -f $_.Threads, ($base.Seconds / $_.Seconds) }) -join "  "))
}
finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
