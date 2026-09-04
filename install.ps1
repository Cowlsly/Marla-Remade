#!/usr/bin/env pwsh
# Windows / PowerShell port of ./install (see the bash `install` for macOS/Linux).
# Kept feature-compatible with the bash version so `./install x y z` behaves the
# same on every platform.

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = $ScriptDir

if ($null -eq $Arguments) { $Arguments = @() }

function Print-Help {
    Write-Host @'
Usage:
  ./install [dev|release] <module> [module...]
  ./install <module> [module...]            # defaults to dev
  ./install [dev|release] all               # installs all app modules
  ./install all                             # same, defaults to dev

Description:
  Ergonomic wrapper around ./gradlew :module:installDev / installRelease.
  - Variant is swappable and optional (defaults to dev)
  - Supports both slash and colon notations: games/voxels == games:voxels
  - Supports games shorthand: ./install voxels -> games:voxels (auto-prefixed if games/<name> is a game)
  - Supports personal shorthand: ./install dooraccess -> personal:dooraccess (auto-prefixed if personal/<name> is a personal app)
  - Supports "all" keyword: ./install all -> installs every app module
  - Supports multiple modules in one call (single gradlew invocation)

Examples:
  ./install dev contacts                    -> :contacts:installDev
  ./install release games:voxels            -> :games:voxels:installRelease
  ./install dev games/voxels                -> :games:voxels:installDev (slash normalized)
  ./install dev :games:voxels:              -> :games:voxels:installDev (colon trimming)
  ./install voxels                          -> :games:voxels:installDev (shorthand auto games:)
  ./install chess                           -> :games:chess:installDev (shorthand auto games:)
  ./install dooraccess                      -> :personal:dooraccess:installDev (shorthand auto personal:)
  ./install all                             -> :contacts:installDev :games:chess:installDev ... (all modules)
  ./install dev all                         -> same as above with dev variant
  ./install contacts calendar               -> :contacts:installDev :calendar:installDev
  ./install release contacts                -> :contacts:installRelease
  ./install contacts dev                    -> :contacts:installDev (variant anywhere)
  ./install --help
  ./install --dry-run dev contacts          # prints what would run without executing gradle
  ./install --dry-run all                   # preview all modules

Variant rules (see .llms/rules/installing.md):
  - dev (default): always use installDev unless explicitly asked for release
  - release: use installRelease when explicitly requested
  - debug: BLOCKED - never use installDebug

Notes:
  - NEVER uninstalls an app (no uninstall tasks)
  - Validates modules against app modules (those applying common-conventions-app)
  - Installs to the first connected device whose serial does not start with 'emulator'
  - Set INSTALL_DRY_RUN=1 or pass --dry-run for dry-run mode
'@
}

function Normalize-Module([string]$m) {
    $m = $m.Trim()
    $m = $m -replace '^[/:]+', '' -replace '[/:]+$', ''
    $m = $m -replace '/', ':'
    while ($m -match '::') { $m = $m -replace '::', ':' }
    return $m.ToLower()
}

function Get-AppModulesSlash {
    $settingsFile = Join-Path $Root 'settings.gradle.kts'
    if (-not (Test-Path $settingsFile)) { return @() }

    $found = @()
    foreach ($line in Get-Content -LiteralPath $settingsFile) {
        if ($line -match 'include\(":([^"]+)"\)') {
            $slash = $Matches[1] -replace ':', '/'
            if ([string]::IsNullOrEmpty($slash)) { continue }
            $buildFile = Join-Path $Root (Join-Path $slash 'build.gradle.kts')
            if ((Test-Path -LiteralPath $buildFile) -and
                (Select-String -LiteralPath $buildFile -Pattern 'id("common-conventions-app")' -SimpleMatch -Quiet)) {
                $found += $slash
            }
        }
    }
    return ($found | Sort-Object -Unique)
}

function Print-ValidModules {
    $slashModules = Get-AppModulesSlash
    if (-not $slashModules -or $slashModules.Count -eq 0) {
        Write-Host '  (could not discover app modules)'
        return
    }
    Write-Host ''
    Write-Host 'Valid app modules (slash notation for filesystem, colon for Gradle):'
    Write-Host ''
    Write-Host ('  {0,-30} {1}' -f 'Filesystem (slash)', 'Gradle (colon)')
    Write-Host ('  {0,-30} {1}' -f '--------------------', '-------------------')
    foreach ($sm in $slashModules) {
        if ([string]::IsNullOrEmpty($sm)) { continue }
        $colon = $sm -replace '/', ':'
        Write-Host ('  {0,-30} :{1}' -f $sm, $colon)
    }
    Write-Host ''
    Write-Host 'Tip: Use either notation: ./install dev games/voxels  OR  ./install dev games:voxels'
    Write-Host 'Shorthand: ./install dev voxels  (auto-expands to games:voxels if games/<name> exists)'
    Write-Host '           ./install dev dooraccess  (auto-expands to personal:dooraccess if personal/<name> exists)'
    Write-Host 'All: ./install all  (installs every app module)'
}

function Find-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $bases = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    foreach ($base in $bases) {
        if ([string]::IsNullOrEmpty($base)) { continue }
        $candidate = Join-Path $base 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    return $null
}

function Select-TargetSerial([string]$adb) {
    $out = & $adb devices 2>$null
    foreach ($line in $out) {
        $line = $line.Trim()
        if ([string]::IsNullOrEmpty($line)) { continue }
        if ($line -like 'List of devices*') { continue }
        $parts = $line -split '\s+'
        if ($parts.Count -lt 2) { continue }
        $serial = $parts[0]
        $state = $parts[1]
        if ($state -ne 'device') { continue }
        if ($serial -like 'emulator*') { continue }
        return $serial
    }
    return $null
}

# --- Handle zero args early ---
if ($Arguments.Count -eq 0) {
    Print-Help
    Write-Host ''
    Write-Host 'Error: No modules specified.'
    Print-ValidModules
    exit 1
}

# --- Detect --help/-h/help anywhere ---
foreach ($arg in $Arguments) {
    switch ($arg) {
        '--help' { Print-Help; exit 0 }
        '-h'     { Print-Help; exit 0 }
        'help'   { Print-Help; exit 0 }
    }
}

# --- Dry-run detection (env or flag) ---
$DryRun = $false
if ($env:INSTALL_DRY_RUN -eq '1') { $DryRun = $true }
foreach ($arg in $Arguments) {
    if ($arg -eq '--dry-run') { $DryRun = $true }
}

# --- Build filtered args (exclude --dry-run) ---
$filtered = @()
foreach ($arg in $Arguments) {
    if ($arg -eq '--dry-run') { continue }
    $filtered += $arg
}

if ($filtered.Count -eq 0) {
    Print-Help
    Write-Host ''
    Write-Host 'Error: No modules specified (dry-run mode).'
    Print-ValidModules
    exit 1
}

# --- Arg parsing: variant detection anywhere ---
$VariantLc = ''
$modulesRaw = @()

foreach ($arg in $filtered) {
    $lc = $arg.ToLower()
    if ($lc -eq 'dev' -or $lc -eq 'release' -or $lc -eq 'debug') {
        if ([string]::IsNullOrEmpty($VariantLc)) {
            $VariantLc = $lc
        }
        elseif ($VariantLc -ne $lc) {
            Write-Host "Error: Multiple distinct variants specified: '$VariantLc' and '$lc'."
            Write-Host 'Please specify only one variant: dev or release.'
            Write-Host ''
            Print-Help
            exit 1
        }
    }
    else {
        $modulesRaw += $arg
    }
}

if ([string]::IsNullOrEmpty($VariantLc)) { $VariantLc = 'dev' }

if ($VariantLc -eq 'debug') {
    Write-Host "Error: 'debug' variant is blocked."
    Write-Host ''
    Write-Host 'Per .llms/rules/installing.md:'
    Write-Host '  - NEVER uninstall an app EVER'
    Write-Host '  - Always install with the installDev task, unless specifically asked to installRelease task'
    Write-Host '  - never use installDebug'
    Write-Host ''
    Write-Host "Use 'dev' (default) or 'release' instead:"
    Write-Host '  ./install dev <module>'
    Write-Host '  ./install release <module>'
    exit 1
}

if ($modulesRaw.Count -eq 0) {
    Write-Host "Error: No modules specified (only variant '$VariantLc' found)."
    Write-Host ''
    Print-Help
    Print-ValidModules
    exit 1
}

# --- Normalization + Dedup + Shorthand + "all" + auto games:/personal: prefix ---
$normalizedModules = @()
$allExpanded = $false

foreach ($raw in $modulesRaw) {
    $norm = Normalize-Module $raw
    if ([string]::IsNullOrEmpty($norm)) {
        Write-Host "Warning: Skipping empty/invalid module token '$raw' after normalization."
        continue
    }

    # Handle "all" keyword - expand to every app module
    if ($norm -eq 'all') {
        if (-not $allExpanded) {
            Write-Host "Info: Expanding 'all' to all app modules..."
            $allSlash = Get-AppModulesSlash
            if (-not $allSlash -or $allSlash.Count -eq 0) {
                Write-Host "Error: Could not discover app modules for 'all' expansion."
                Print-ValidModules
                exit 1
            }
            foreach ($sm in $allSlash) {
                if ([string]::IsNullOrEmpty($sm)) { continue }
                $colon = $sm -replace '/', ':'
                if ($normalizedModules -notcontains $colon) {
                    $normalizedModules += $colon
                }
            }
            $allExpanded = $true
        }
        continue
    }

    # Auto games:/personal: prefix - if token has no colon and games/<name> or
    # personal/<name> is a valid app module, prefix it.
    if ($norm -notmatch ':') {
        foreach ($autoPrefix in @('games', 'personal')) {
            $prefPath = Join-Path $Root (Join-Path $autoPrefix $norm)
            $prefBuild = Join-Path $prefPath 'build.gradle.kts'
            if ((Test-Path -LiteralPath $prefBuild) -and
                (Select-String -LiteralPath $prefBuild -Pattern 'id("common-conventions-app")' -SimpleMatch -Quiet)) {
                $expanded = "${autoPrefix}:${norm}"
                Write-Host "Info: Expanding shorthand '$raw' ($norm) -> $expanded (found $autoPrefix/$norm)"
                $norm = $expanded
                break
            }
            elseif ((-not (Test-Path -LiteralPath (Join-Path $Root $norm))) -and
                    (Test-Path -LiteralPath $prefPath)) {
                # Fallback: root/<name> doesn't exist but <prefix>/<name> does (backward compat)
                $expanded = "${autoPrefix}:${norm}"
                Write-Host "Info: Expanding shorthand '$raw' ($norm) -> $expanded (found $autoPrefix/$norm)"
                $norm = $expanded
                break
            }
        }
    }

    if ($normalizedModules -notcontains $norm) {
        $normalizedModules += $norm
    }
}

if ($normalizedModules.Count -eq 0) {
    Write-Host 'Error: No valid modules after normalization.'
    Print-ValidModules
    exit 1
}

# --- Validation ---
$invalidModules = @()
foreach ($mod in $normalizedModules) {
    $fsPath = $mod -replace ':', '/'
    $buildFile = Join-Path $Root (Join-Path $fsPath 'build.gradle.kts')
    if (-not (Test-Path -LiteralPath $buildFile)) {
        $invalidModules += "$mod (not found: $fsPath/build.gradle.kts)"
    }
    elseif (-not (Select-String -LiteralPath $buildFile -Pattern 'id("common-conventions-app")' -SimpleMatch -Quiet)) {
        $invalidModules += "$mod (exists but is not an app module - missing common-conventions-app)"
    }
}

if ($invalidModules.Count -gt 0) {
    Write-Host 'Error: Some modules are invalid:'
    foreach ($im in $invalidModules) {
        Write-Host "  - $im"
    }
    Print-ValidModules
    exit 1
}

# --- Build Gradle tasks ---
$VariantCap = $VariantLc.Substring(0, 1).ToUpper() + $VariantLc.Substring(1)
$tasks = @()
foreach ($mod in $normalizedModules) {
    $tasks += ":${mod}:install${VariantCap}"
}

$modulesStr = $normalizedModules -join ' '
$tasksStr = $tasks -join ' '

Write-Host "Variant: $VariantLc (task suffix: install${VariantCap})"
Write-Host "Modules ($($normalizedModules.Count)): $modulesStr"
Write-Host "Gradle tasks: $tasksStr"
Write-Host ''

# --- Device selection: first connected device whose serial doesn't start with 'emulator' ---
$adb = Find-Adb
if (-not $adb) {
    if ($DryRun) {
        Write-Host '[DRY-RUN] Warning: adb not found; a device could not be selected.'
    }
    else {
        Write-Host 'Error: adb not found; cannot select a target device.'
        Write-Host 'Install Android platform-tools and put adb on PATH (or set ANDROID_HOME).'
        exit 1
    }
}
else {
    $target = Select-TargetSerial $adb
    if ($target) {
        $env:ANDROID_SERIAL = $target
        Write-Host "Target device: $target (first connected device not starting with 'emulator')"
    }
    elseif ($DryRun) {
        Write-Host '[DRY-RUN] Warning: no non-emulator device connected; ANDROID_SERIAL not set.'
    }
    else {
        Write-Host 'Error: No non-emulator device connected.'
        Write-Host "Connect a physical device (check 'adb devices'); refusing to install to an emulator."
        exit 1
    }
}
Write-Host ''

$gradlew = Join-Path $ScriptDir 'gradlew.bat'

if ($DryRun) {
    Write-Host "[DRY-RUN] Would execute: $gradlew $tasksStr"
    exit 0
}

Write-Host "Running: $gradlew $tasksStr"
Write-Host ''

& $gradlew @tasks
exit $LASTEXITCODE
