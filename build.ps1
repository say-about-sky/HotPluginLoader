#
#  HotPluginLoader - one-shot build
#
#    powershell -ExecutionPolicy Bypass -File build.ps1
#    powershell -ExecutionPolicy Bypass -File build.ps1 -GameRoot <game dir> -AndroidSdk <sdk>
#    powershell -ExecutionPolicy Bypass -File build.ps1 -DexOnly      # skip gradle, reuse existing jar
#    powershell -ExecutionPolicy Bypass -File build.ps1 -JarOnly      # only build the plugin jar
#
#  What it does:
#    1) resolve game root / game.jar / Android SDK / JDK 11+
#    2) fetch missing compile deps (loader jar download, android.jar + d8.jar from SDK)
#    3) gradlew shadowJar                      -> build\libs\HotPluginLoader-<ver>.jar
#    4) generate PatchBuild\patch.json (absolute paths, never committed)
#    5) render patch dex with the Finality loader -> out\game.dex.zip
#
#  Nothing in libs\ or out\ is tracked by git; see libs\README.md.
#
param(
    [string]$GameRoot = "",
    [string]$AndroidSdk = "",
    [string]$JdkHome = "",
    [string]$GameJar = "",
    [switch]$DexOnly,
    [switch]$JarOnly,
    [switch]$Clean,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$proj = $PSScriptRoot
$libs = Join-Path $proj "libs"
$outDir = Join-Path $proj "out"
$patchDir = Join-Path $proj "PatchBuild"
if (-not (Test-Path -LiteralPath $libs)) { New-Item -ItemType Directory -Path $libs | Out-Null }

function Step([string]$m) { Write-Host ""; Write-Host ("=== " + $m) }
function Fail([string]$m) { Write-Host ("[ERROR] " + $m); exit 1 }

# ---------------------------------------------------------------- 1) game root
if ([string]::IsNullOrEmpty($GameRoot)) {
    if ($env:AOH_GAME_ROOT) { $GameRoot = $env:AOH_GAME_ROOT }
    else { $GameRoot = Split-Path -Parent $proj }
}
Step "game root: $GameRoot"
if (-not (Test-Path -LiteralPath $GameRoot)) { Fail "game root not found: $GameRoot (use -GameRoot or AOH_GAME_ROOT)" }

if ([string]::IsNullOrEmpty($GameJar)) {
    foreach ($p in @((Join-Path $GameRoot "game.jar"), (Join-Path $libs "game.jar"))) {
        if (Test-Path -LiteralPath $p) { $GameJar = $p; break }
    }
}
if ([string]::IsNullOrEmpty($GameJar) -or -not (Test-Path -LiteralPath $GameJar)) {
    Fail "game.jar not found. Put your own game.jar into '$GameRoot' (or pass -GameJar <path>)."
}
Write-Host "game.jar : $GameJar"

# --------------------------------------------------------------- 2) android sdk
if ([string]::IsNullOrEmpty($AndroidSdk)) {
    if ($env:ANDROID_HOME) { $AndroidSdk = $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
    else {
        foreach ($c in @((Join-Path $env:LOCALAPPDATA "Android\Sdk"), "C:\Android\Sdk")) {
            if (Test-Path -LiteralPath $c) { $AndroidSdk = $c; break }
        }
    }
}
$androidJar = $null; $d8Jar = $null
if ($AndroidSdk -and (Test-Path -LiteralPath $AndroidSdk)) {
    $preferred = Join-Path $AndroidSdk "platforms\android-34\android.jar"
    if (Test-Path -LiteralPath $preferred) { $androidJar = $preferred }
    if (-not $androidJar) {
        $plat = @(Get-ChildItem -LiteralPath (Join-Path $AndroidSdk "platforms") -Directory -ErrorAction SilentlyContinue |
                  Sort-Object Name -Descending | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "android.jar") } |
                  Select-Object -First 1)
        if ($plat.Count -gt 0) { $androidJar = Join-Path $plat[0].FullName "android.jar" }
    }
    $bt = @()
    $preferredBt = Join-Path $AndroidSdk "build-tools\35.0.0\lib\d8.jar"
    if (Test-Path -LiteralPath $preferredBt) { $d8Jar = $preferredBt }
    else {
        $bt = @(Get-ChildItem -LiteralPath (Join-Path $AndroidSdk "build-tools") -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "lib\d8.jar") } |
                Select-Object -First 1)
        if ($bt.Count -gt 0) { $d8Jar = Join-Path $bt[0].FullName "lib\d8.jar" }
    }
}
if (-not $androidJar) { Fail "android.jar not found: set ANDROID_HOME (or -AndroidSdk <sdk>)" }
Write-Host "android  : $androidJar"

# --------------------------------------------------------------------- 3) JDK
function Get-JdkMajor([string]$jdkDir) {
    $rel = Join-Path $jdkDir "release"
    if (Test-Path -LiteralPath $rel) {
        $m = Select-String -LiteralPath $rel -Pattern 'JAVA_VERSION="(\d+)' | Select-Object -First 1
        if ($m) { return [int]$m.Matches[0].Groups[1].Value }
    }
    return 0
}

if ([string]::IsNullOrEmpty($JdkHome)) {
    $cands = New-Object System.Collections.ArrayList
    if ($env:JAVA_HOME) { $cands.Add($env:JAVA_HOME) | Out-Null }
    Get-ChildItem "C:\Program Files\Microsoft\jdk-*","C:\Program Files\Java\jdk-*" -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { $cands.Add($_.FullName) | Out-Null }
    $best = $null
    foreach ($c in $cands) {
        if (-not (Test-Path -LiteralPath (Join-Path $c "bin\javac.exe"))) { continue }
        $major = Get-JdkMajor $c
        if ($major -ge 11 -and $major -le 17) { $best = $c; break }   # Gradle 8.1.x supports 11-17
    }
    if (-not $best) {
        foreach ($c in $cands) {
            if (-not (Test-Path -LiteralPath (Join-Path $c "bin\javac.exe"))) { continue }
            if ((Get-JdkMajor $c) -ge 11) { $best = $c; break }
        }
    }
    if ($best) { $JdkHome = $best }
}
if ([string]::IsNullOrEmpty($JdkHome) -or -not (Test-Path -LiteralPath (Join-Path $JdkHome "bin\javac.exe"))) {
    Fail "JDK 11+ not found: set JAVA_HOME (or -JdkHome <jdk>)"
}
$javaExe = Join-Path $JdkHome "bin\java.exe"
$env:JAVA_HOME = $JdkHome
Write-Host "jdk      : $JdkHome"

# ------------------------------------------------------- 4) compile deps (libs)
Step "compile deps"
& (Join-Path $proj "tools\fetch-libs.ps1") -GameRoot $GameRoot -AndroidSdk $AndroidSdk
if (-not (Test-Path -LiteralPath (Join-Path $libs "finality-api-0.1.0.jar")) -or
    -not (Test-Path -LiteralPath (Join-Path $libs "gdx-android-stubs.jar"))) {
    Fail "missing compile stubs in libs\ (finality-api-0.1.0.jar / gdx-android-stubs.jar should ship with the repo)"
}

$loaderJar = Join-Path $GameRoot "loader-1.6.0.jar"
if (-not (Test-Path -LiteralPath $loaderJar)) {
    $loaderJar = Join-Path $libs "loader-1.6.0.jar"
}
if ($DexOnly -and -not (Test-Path -LiteralPath $loaderJar)) {
    Fail "loader-1.6.0.jar not found (needed for -DexOnly). Run without -DexOnly once, or -FetchLoader."
}

# ------------------------------------------------------------- 5) gradle build
$jarPath = $null
if (-not $DexOnly) {
    Step "gradlew shadowJar"
    if ($Clean) { & (Join-Path $proj "gradlew.bat") clean --console=plain | Out-Host }
    & (Join-Path $proj "gradlew.bat") shadowJar --console=plain
    if ($LASTEXITCODE -ne 0) {
        Fail "gradle build failed (if another Gradle build/daemon is running, run: gradlew.bat --stop, then retry)"
    }
}
$jarPath = @(Get-ChildItem -LiteralPath (Join-Path $proj "build\libs") -Filter "*.jar" -ErrorAction SilentlyContinue |
             Sort-Object LastWriteTime -Descending | Select-Object -First 1)
if ($jarPath.Count -eq 0) { Fail "plugin jar not found under build\libs (run without -DexOnly)" }
$jarPath = $jarPath[0].FullName
Write-Host "plugin   : $jarPath"

if ($JarOnly) {
    Step "done (jar only)"
    Write-Host ("[OK] " + $jarPath)
    exit 0
}

# ---------------------------------------------------- 6) patch.json + patch dex
if (-not $d8Jar) { Fail "d8.jar not found under <sdk>\build-tools\*\lib (needed to render dex)" }
if (-not (Test-Path -LiteralPath $patchDir)) { New-Item -ItemType Directory -Path $patchDir | Out-Null }
if (-not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$patchCfg = Join-Path $patchDir "patch.json"
$dexZip = Join-Path $outDir "game.dex.zip"
if (Test-Path -LiteralPath $dexZip) { Remove-Item -LiteralPath $dexZip -Force }

# patch.json 优先写"相对 out\ 的路径"(工程内可移植);跨盘等无法相对化时回退绝对路径。
function To-Rel([string]$baseDir, [string]$absPath) {
    try {
        $b = New-Object System.Uri (([IO.Path]::GetFullPath($baseDir).TrimEnd('\')) + '\')
        $a = New-Object System.Uri ([IO.Path]::GetFullPath($absPath))
        if ($b.Scheme -ne $a.Scheme) { return $absPath }
        $rel = $b.MakeRelativeUri($a).ToString()
        if ($rel -match '^[a-zA-Z][a-zA-Z0-9+.-]*:') { return $absPath }   # different volume
        return ([Uri]::UnescapeDataString($rel) -replace '/', '\')
    } catch { return $absPath }
}
function New-PatchJson([bool]$relative) {
    $g = $GameJar; $d = $d8Jar; $l = $androidJar; $j = $jarPath
    if ($relative) {
        $g = To-Rel $outDir $g; $d = To-Rel $outDir $d; $l = To-Rel $outDir $l; $j = To-Rel $outDir $j
    }
    $g = $g -replace '\\','/'; $d = $d -replace '\\','/'; $l = $l -replace '\\','/'; $j = $j -replace '\\','/'
    return "{`n  `"gamePath`": `"$g`",`n  `"d8Path`": `"$d`",`n  `"androidLibPath`": `"$l`",`n  `"plugins`": [`n    `"$j`"`n  ]`n}`n"
}
function Invoke-Loader([string]$cfg) {
    Push-Location $outDir
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"      # native stderr (libGDX/d8 progress) must not throw
    try {
        $lines = & $javaExe -jar $loaderJar --launch-mode patch --patch-config $cfg 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
        Pop-Location
    }
    $lines | ForEach-Object { Write-Host $_ }
    return $code
}

Step "render patch dex (relative paths)"
Set-Content -LiteralPath $patchCfg -Value (New-PatchJson $true) -Encoding UTF8
Get-Content -LiteralPath $patchCfg | ForEach-Object { Write-Host ("    " + $_) }
$rc = Invoke-Loader $patchCfg
if (-not (Test-Path -LiteralPath $dexZip)) {
    Write-Host "[i] loader could not resolve relative paths, retrying with absolute paths ..."
    Set-Content -LiteralPath $patchCfg -Value (New-PatchJson $false) -Encoding UTF8
    $rc = Invoke-Loader $patchCfg
}
if (-not (Test-Path -LiteralPath $dexZip)) { Fail "game.dex.zip was not generated (loader exit code $rc)" }

Step "done"
Write-Host ("[OK] jar : " + $jarPath)
Write-Host ("[OK] dex : " + $dexZip + "  (" + (Get-Item -LiteralPath $dexZip).Length + " bytes)")
exit 0
