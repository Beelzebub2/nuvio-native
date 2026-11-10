param([string]$VcvarsPath = '')

$ErrorActionPreference = 'Stop'
$vswhere = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFilesX86)) 'Microsoft Visual Studio\Installer\vswhere.exe'
if ([string]::IsNullOrWhiteSpace($VcvarsPath) -and (Test-Path -LiteralPath $vswhere)) {
    $VcvarsPath = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -find 'VC\Auxiliary\Build\vcvars64.bat' | Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace($VcvarsPath) -or -not (Test-Path -LiteralPath $VcvarsPath)) {
    throw 'Visual Studio MSVC vcvars64.bat is required for Windows media session tests.'
}

$composeApp = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..\..')).Path
$buildDir = Join-Path $composeApp 'build\native\windows'
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$source = Join-Path $PSScriptRoot 'windows_media_session_actions_test.cpp'
$object = Join-Path $buildDir 'windows_media_session_actions_test.obj'
$executable = Join-Path $buildDir 'windows_media_session_actions_test.exe'
$script = Join-Path $buildDir 'task-windows-media-test.bat'
$lines = @(
    '@echo off',
    ('call "{0}" >nul' -f $VcvarsPath),
    'if errorlevel 1 exit /b %ERRORLEVEL%',
    ('cl /nologo /EHsc /std:c++17 "{0}" /Fo"{1}" /Fe"{2}"' -f $source, $object, $executable),
    'if errorlevel 1 exit /b %ERRORLEVEL%',
    ('"{0}"' -f $executable),
    'exit /b %ERRORLEVEL%'
)
Set-Content -LiteralPath $script -Value $lines -Encoding ASCII
& cmd.exe /d /c $script
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output 'Windows media session command and teardown tests passed.'
