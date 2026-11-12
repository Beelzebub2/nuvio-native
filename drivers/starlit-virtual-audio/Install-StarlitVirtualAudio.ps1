#Requires -Version 5.1
<#
Developer-only device provisioning. This script does not bypass kernel
signature enforcement, change boot settings, or select a Windows default.
Only install a Microsoft-signed, separately qualified production package.
#>
[CmdletBinding()]
param(
    [ValidateSet('Status', 'Install', 'Remove')]
    [string]$Action = 'Status',
    [string]$PackageDirectory = (Join-Path $PSScriptRoot 'signed-package')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$hardwareId = 'ROOT\StarlitVirtualAudio'

function Get-StarlitDevice {
    @(Get-PnpDevice -Class 'Media' -ErrorAction Stop | Where-Object {
        $_.InstanceId -like 'ROOT\STARLITVIRTUALAUDIO\*'
    })
}

function Assert-Administrator {
    $principal = [Security.Principal.WindowsPrincipal]::new(
        [Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Open an elevated PowerShell window before installing or removing the driver.'
    }
}

function Assert-SignedPackage([string]$directory) {
    $folder = [IO.Path]::GetFullPath($directory)
    $inf = Join-Path $folder 'SimpleAudioSample.inf'
    $cat = Join-Path $folder 'SimpleAudioSample.cat'
    $sys = Join-Path $folder 'SimpleAudioSample.sys'
    foreach ($candidate in @($inf, $cat, $sys)) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            throw "Driver package incomplete: $candidate"
        }
    }
    $text = [IO.File]::ReadAllText($inf)
    if (-not $text.Contains($hardwareId) -or -not $text.Contains('Starlit Virtual Audio')) {
        throw 'INF does not identify the dedicated Starlit virtual audio device.'
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $cat
    if ($signature.Status -ne 'Valid' -or
        $null -eq $signature.SignerCertificate -or
        $signature.SignerCertificate.Subject -notmatch 'Microsoft Windows Hardware Compatibility Publisher') {
        throw 'A valid Microsoft Windows hardware-signed catalog is required. Test certificates are refused.'
    }
    return $inf
}

switch ($Action) {
    'Status' {
        $devices = @(Get-StarlitDevice)
        if ($devices.Count -eq 0) {
            Write-Output 'Starlit Virtual Audio: not installed'
        } else {
            $devices | Select-Object Status, FriendlyName, InstanceId
        }
        break
    }
    'Install' {
        Assert-Administrator
        if (@(Get-StarlitDevice).Count -ne 0) {
            throw 'Starlit Virtual Audio is already registered. Remove it explicitly before reinstalling.'
        }
        $inf = Assert-SignedPackage $PackageDirectory
        $devcon = Get-Command 'devcon.exe' -ErrorAction SilentlyContinue
        if ($null -eq $devcon) {
            throw 'The Microsoft WDK devcon.exe tool is required for creating the root-enumerated device.'
        }
        & $devcon.Source install $inf $hardwareId
        if ($LASTEXITCODE -ne 0) {
            throw "The signed driver was rejected (devcon exit code $LASTEXITCODE)."
        }
        $devices = @(Get-StarlitDevice)
        if ($devices.Count -ne 1 -or $devices[0].Status -ne 'OK') {
            throw 'Driver installation did not create one healthy Starlit device.'
        }
        Write-Output 'Starlit Virtual Audio installed. Windows default outputs were not changed.'
        break
    }
    'Remove' {
        Assert-Administrator
        $devices = @(Get-StarlitDevice)
        if ($devices.Count -gt 1) {
            throw 'Multiple Starlit devices exist; resolve this manually before removal.'
        }
        if ($devices.Count -eq 0) {
            Write-Output 'Starlit Virtual Audio is already absent.'
            break
        }
        & pnputil.exe /remove-device $devices[0].InstanceId
        if ($LASTEXITCODE -ne 0) {
            throw "Device removal failed (pnputil exit code $LASTEXITCODE)."
        }
        Write-Output 'Starlit Virtual Audio device removed. Other audio devices were untouched.'
        break
    }
}
