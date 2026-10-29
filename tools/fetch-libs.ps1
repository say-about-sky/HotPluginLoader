#
#  fetch-libs.ps1 - prepare HotPluginLoader\libs (compile-time dependencies)
#
#  Usage:
#    powershell -ExecutionPolicy Bypass -File tools\fetch-libs.ps1
#    powershell -ExecutionPolicy Bypass -File tools\fetch-libs.ps1 -Force
#    powershell -ExecutionPolicy Bypass -File tools\fetch-libs.ps1 -FinalityApi <path>\finality-api-0.1.0.jar
#    powershell -ExecutionPolicy Bypass -File tools\fetch-libs.ps1 -FetchLoader   # 离线后备：下载 loader jar
#
#  Required: game.jar (from your own game install)
#  Optional fallbacks: android.jar / d8.jar / loader-1.6.0.jar (default build uses Maven + JitPack)
#
#  Inputs (all optional, auto-detected when omitted):
#    -GameRoot    folder that contains game.jar   (default: AOH_GAME_ROOT, else parent of this project)
#    -AndroidSdk  Android SDK root                (default: ANDROID_HOME / ANDROID_SDK_ROOT / common paths)
#    -FinalityApi finality-api-0.1.0.jar path     (built from Finality-Framework/api-aoh3)
#    -FetchLoader download loader-1.6.0.jar as an offline fallback for JitPack
#    -Force       overwrite existing files
#
#  ASCII only (PS 5.1 + no BOM issues). Never redistributes game.jar.
#
param(
    [string]$GameRoot = "",
    [string]$AndroidSdk = "",
    [string]$FinalityApi = "",
    [switch]$FetchLoader,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$here = $PSScriptRoot
$proj = Split-Path -Parent $here
$libs = Join-Path $proj "libs"
if (-not (Test-Path -LiteralPath $libs)) { New-Item -ItemType Directory -Path $libs | Out-Null }

if ([string]::IsNullOrEmpty($GameRoot)) {
    if ($env:AOH_GAME_ROOT) { $GameRoot = $env:AOH_GAME_ROOT }
    else { $GameRoot = Split-Path -Parent $proj }
}
if ([string]::IsNullOrEmpty($AndroidSdk)) {
    if ($env:ANDROID_HOME) { $AndroidSdk = $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
    else {
        foreach ($c in @((Join-Path $env:LOCALAPPDATA "Android\Sdk"), "C:\Android\Sdk")) {
            if (Test-Path -LiteralPath $c) { $AndroidSdk = $c; break }
        }
    }
}

$missing = New-Object System.Collections.ArrayList

function Copy-IfNeeded([string]$src, [string]$dst, [string]$label) {
    if (-not (Test-Path -LiteralPath $src)) {
        Write-Host ("[miss] " + $label + " -> " + $src)
        $script:missing.Add($label) | Out-Null
        return
    }
    if ((Test-Path -LiteralPath $dst) -and -not $Force) {
        Write-Host ("[skip] " + $label + " (already present)")
        return
    }
    Copy-Item -LiteralPath $src -Destination $dst -Force
    Write-Host ("[ok]   " + $label + " -> " + $dst)
}

function Newest-Dir([string]$dir, [string]$probe) {
    if (-not (Test-Path -LiteralPath $dir)) { return $null }
    $hit = @(Get-ChildItem -LiteralPath $dir -Directory -ErrorAction SilentlyContinue |
             Sort-Object Name -Descending |
             Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName $probe) } |
             Select-Object -First 1)
    if ($hit.Count -gt 0) { return $hit[0].FullName }
    return $null
}

Write-Host "[libs] target     : $libs"
Write-Host "[libs] game root  : $GameRoot"
Write-Host "[libs] android sdk: $AndroidSdk"

# 1) game.jar - from the local game installation (never redistributed)
Copy-IfNeeded (Join-Path $GameRoot "game.jar") (Join-Path $libs "game.jar") "game.jar"

# 2) android.jar + d8.jar - from the Android SDK
if (-not [string]::IsNullOrEmpty($AndroidSdk) -and (Test-Path -LiteralPath $AndroidSdk)) {
    $plat = Newest-Dir (Join-Path $AndroidSdk "platforms") "android.jar"
    if ($plat) { Copy-IfNeeded (Join-Path $plat "android.jar") (Join-Path $libs "android.jar") "android.jar" }
    else { Write-Host "[hint] android.jar not found under <sdk>\platforms (needed to compile)" }

    $bt = Newest-Dir (Join-Path $AndroidSdk "build-tools") "lib\d8.jar"
    if ($bt) { Copy-IfNeeded (Join-Path $bt "lib\d8.jar") (Join-Path $libs "d8.jar") "d8.jar (optional fallback)" }
    else { Write-Host "[hint] d8.jar not found under <sdk>\build-tools (optional: Maven R8 is used by default)" }
} else {
    Write-Host "[hint] Android SDK not found: set ANDROID_HOME or pass -AndroidSdk (android.jar is needed to compile)"
}

# 3) loader-1.6.0.jar - optional offline fallback (default build resolves it from JitPack)
$loaderDst = Join-Path $libs "loader-1.6.0.jar"
if (-not $FetchLoader) {
    if (Test-Path -LiteralPath $loaderDst) { Write-Host "[skip] loader-1.6.0.jar (present, optional fallback)" }
    else { Write-Host "[hint] loader-1.6.0.jar absent - default build uses JitPack (-FetchLoader to download)" }
} elseif ((Test-Path -LiteralPath $loaderDst) -and -not $Force) {
    Write-Host "[skip] loader-1.6.0.jar (already present)"
} else {
    $url = "https://github.com/Finality-Framework/loader/releases/download/1.6.0/loader-1.6.0.jar"
    try {
        Write-Host "[get]  loader-1.6.0.jar <- $url"
        Invoke-WebRequest -Uri $url -OutFile $loaderDst -UseBasicParsing
        Write-Host "[ok]   loader-1.6.0.jar ($((Get-Item -LiteralPath $loaderDst).Length) bytes)"
    } catch {
        Write-Host ("[hint] loader-1.6.0.jar download failed: " + $_.Exception.Message)
        Write-Host "       (default build uses JitPack: com.github.Finality-Framework:loader:1.6.0)"
    }
}

# 4) finality-api-0.1.0.jar - built from Finality-Framework/api-aoh3 (small, also shipped in git)
$apiDst = Join-Path $libs "finality-api-0.1.0.jar"
if (Test-Path -LiteralPath $apiDst) {
    Write-Host "[skip] finality-api-0.1.0.jar (already present)"
} elseif (-not [string]::IsNullOrEmpty($FinalityApi)) {
    Copy-IfNeeded $FinalityApi $apiDst "finality-api-0.1.0.jar"
} else {
    Write-Host "[miss] finality-api-0.1.0.jar (pass -FinalityApi <path>, or build api-aoh3)"
    $missing.Add("finality-api-0.1.0.jar") | Out-Null
}

# 5) gdx-android-stubs.jar - tiny hand-written compile stubs (also shipped in git)
$stubDst = Join-Path $libs "gdx-android-stubs.jar"
if (Test-Path -LiteralPath $stubDst) {
    Write-Host "[skip] gdx-android-stubs.jar (already present)"
} else {
    Write-Host "[miss] gdx-android-stubs.jar (should be shipped with the repo)"
    $missing.Add("gdx-android-stubs.jar") | Out-Null
}

Write-Host ""
if ($missing.Count -eq 0) {
    Write-Host "[libs] all dependencies ready - run: gradlew.bat shadowJar"
} else {
    Write-Host ("[libs] missing: " + ($missing -join ", "))
    Write-Host "       see libs\README.md for how to obtain each file"
    exit 1
}
