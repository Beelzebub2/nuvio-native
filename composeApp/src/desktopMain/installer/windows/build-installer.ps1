<#
.SYNOPSIS
  Builds the custom Nuvio Native Windows installer from a jpackage app image.

.DESCRIPTION
  Compiles the setup stub and the packer in this folder with MSVC, packs the app
  image into an LZMS payload and appends it to the stub.
  Called by the packageInstaller / packageReleaseInstaller Gradle tasks; it can also be
  run by hand against an existing createReleaseDistributable output.
#>
param(
    [Parameter(Mandatory = $true)] [string] $AppImage,
    [Parameter(Mandatory = $true)] [string] $Output,
    [Parameter(Mandatory = $true)] [string] $Version,
    [Parameter(Mandatory = $true)] [string] $WorkDir,
    [Parameter(Mandatory = $true)] [string] $WebView2Root,
    [string] $ProductName = 'Nuvio Native',
    [string] $Publisher = 'Nuvio Media',
    [string] $LauncherExe = '',
    [string] $LegacyUpgradeCode = '',
    [string] $Arch = 'x64',
    [string] $Vcvars = '',
    # Local UI work only: keep payload.bin from the last run instead of packing again.
    [switch] $ReusePayload
)

$ErrorActionPreference = 'Stop'
$source = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($LauncherExe)) { $LauncherExe = "$ProductName.exe" }

$AppImage = (Resolve-Path -LiteralPath $AppImage).Path
if (-not (Test-Path -LiteralPath (Join-Path $AppImage $LauncherExe))) {
    throw "App image at $AppImage has no $LauncherExe. Run createReleaseDistributable first."
}

$webView2Include = Join-Path $WebView2Root 'build\native\include'
$webView2Loader = Join-Path $WebView2Root "build\native\$Arch\WebView2LoaderStatic.lib"
foreach ($required in @((Join-Path $webView2Include 'WebView2.h'), $webView2Loader)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "WebView2 SDK file missing: $required. Install the Microsoft.Web.WebView2 NuGet package or pass -Pnuvio.webview2.dir."
    }
}

$vcvarsName = switch ($Arch) { 'x86' { 'vcvars32.bat' } 'arm64' { 'vcvarsarm64.bat' } default { 'vcvars64.bat' } }
if ([string]::IsNullOrWhiteSpace($Vcvars)) {
    $vswhere = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe'
    if (Test-Path -LiteralPath $vswhere) {
        $Vcvars = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -find "VC\Auxiliary\Build\$vcvarsName" | Select-Object -First 1
    }
}
if ([string]::IsNullOrWhiteSpace($Vcvars) -or -not (Test-Path -LiteralPath $Vcvars)) {
    throw 'Visual Studio C++ toolchain was not found. Install MSVC or pass -Pnuvio.windows.vcvars.path=C:\path\to\vcvars64.bat.'
}

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$WorkDir = (Resolve-Path -LiteralPath $WorkDir).Path

# The resource script reads the version from here, so the stub's file
# properties match the release.
$numeric = @(($Version -replace '[-+].*$', '').Split('.') | ForEach-Object { [int]$_ })
while ($numeric.Count -lt 4) { $numeric += 0 }
@(
    "#define NUVIO_SETUP_VERSION `"$Version`""
    "#define NUVIO_SETUP_VERSION_NUMBER $($numeric[0..3] -join ',')"
    "#define NUVIO_SETUP_PRODUCT `"$ProductName`""
    "#define NUVIO_SETUP_PUBLISHER `"$Publisher`""
) | Set-Content -LiteralPath (Join-Path $WorkDir 'setup_version.h') -Encoding ASCII

$flags = '/nologo /EHsc /std:c++17 /O2 /MT /W3 /DUNICODE /D_UNICODE /DNOMINMAX /DWIN32_LEAN_AND_MEAN /D_CRT_SECURE_NO_WARNINGS'
$libs = 'Ole32.lib User32.lib Gdi32.lib Dwmapi.lib Shell32.lib Shlwapi.lib Advapi32.lib Cabinet.lib Msi.lib Shcore.lib Version.lib'
$bat = Join-Path $WorkDir 'build-installer.bat'
@(
    '@echo off'
    "call `"$Vcvars`" >nul"
    'if errorlevel 1 exit /b %errorlevel%'
    "cd /d `"$source`""
    "rc /nologo /i `"$WorkDir`" /fo `"$WorkDir\setup.res`" setup.rc"
    'if errorlevel 1 exit /b %errorlevel%'
    "cd /d `"$WorkDir`""
    "cl $flags /I`"$webView2Include`" `"$source\setup.cpp`" `"$source\setup_engine.cpp`" setup.res /Fesetup.exe /link /SUBSYSTEM:WINDOWS /MANIFEST:NO `"$webView2Loader`" $libs"
    'if errorlevel 1 exit /b %errorlevel%'
    "cl $flags `"$source\pack.cpp`" /Fepack.exe /link Cabinet.lib"
    'exit /b %errorlevel%'
) | Set-Content -LiteralPath $bat -Encoding ASCII

Write-Host "Compiling setup stub and packer ($Arch)"
& cmd.exe /d /c "`"$bat`""
if ($LASTEXITCODE -ne 0) { throw "Installer compilation failed with exit code $LASTEXITCODE." }

$payload = Join-Path $WorkDir 'payload.bin'
if ($ReusePayload -and (Test-Path -LiteralPath $payload)) {
    Write-Host 'Reusing the existing payload'
} else {
    Write-Host "Packing $AppImage"
    & (Join-Path $WorkDir 'pack.exe') $AppImage $payload "name=$ProductName" "version=$Version" "publisher=$Publisher" "exe=$LauncherExe" "legacyUpgradeCode=$LegacyUpgradeCode"
    if ($LASTEXITCODE -ne 0) { throw "Packing the app image failed with exit code $LASTEXITCODE." }
}

$outputDir = Split-Path -Parent $Output
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$target = [System.IO.File]::Create($Output)
try {
    foreach ($part in @((Join-Path $WorkDir 'setup.exe'), $payload)) {
        $stream = [System.IO.File]::OpenRead($part)
        try { $stream.CopyTo($target) } finally { $stream.Dispose() }
    }
} finally {
    $target.Dispose()
}
$size = (Get-Item -LiteralPath $Output).Length
Write-Host ("Windows installer: {0} ({1:N1} MB)" -f $Output, ($size / 1MB))
