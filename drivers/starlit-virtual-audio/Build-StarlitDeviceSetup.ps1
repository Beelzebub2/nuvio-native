#Requires -Version 5.1
<#
Compile the unprivileged Windows SetupAPI helper. This command does NOT create
devices, install drivers, request elevation or alter system audio settings.
#>
[CmdletBinding()]
param(
    [string]$OutputPath = '',
    [string]$ScratchRoot = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $PSScriptRoot 'StarlitDeviceSetup.exe'
}
if ([string]::IsNullOrWhiteSpace($ScratchRoot)) {
    $ScratchRoot = [IO.Path]::GetTempPath()
}
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
$ScratchRoot = [IO.Path]::GetFullPath($ScratchRoot)
if (-not (Test-Path -LiteralPath $ScratchRoot -PathType Container)) {
    throw "Scratch root must already exist: $ScratchRoot"
}
if (Test-Path -LiteralPath $OutputPath) {
    throw "Refusing to replace an existing installer helper: $OutputPath"
}

$programFilesX86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
$vswhere = Join-Path $programFilesX86 'Microsoft Visual Studio/Installer/vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere -PathType Leaf)) {
    throw 'Visual Studio C++ Build Tools are required to compile the helper.'
}
$vcvars = @(& $vswhere -latest -products '*' -find 'VC/Auxiliary/Build/vcvars64.bat')
if ($vcvars.Count -ne 1 -or -not (Test-Path -LiteralPath $vcvars[0] -PathType Leaf)) {
    throw 'Could not locate the Visual Studio x64 native compiler environment.'
}
$source = Join-Path $PSScriptRoot 'StarlitDeviceSetup.cpp'
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
    throw 'StarlitDeviceSetup.cpp is missing.'
}

$temp = Join-Path $ScratchRoot ('starlit-device-setup-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($temp) | Out-Null
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($OutputPath)) | Out-Null
$obj = Join-Path $temp 'StarlitDeviceSetup.obj'
$command = 'call "' + $vcvars[0] + '" >NUL && cl.exe /nologo /std:c++17 /EHsc /W4 /WX "' +
           $source + '" /Fo:"' + $obj + '" /Fe:"' + $OutputPath + '" /link setupapi.lib uuid.lib'
cmd.exe /d /s /c $command
if ($LASTEXITCODE -ne 0) {
    throw "Starlit setup helper compiler failed ($LASTEXITCODE). Build objects: $temp"
}
Write-Output "Built StarlitDeviceSetup.exe: $OutputPath"
Write-Output 'Only the explicit create command can register a device; status is read-only.'
