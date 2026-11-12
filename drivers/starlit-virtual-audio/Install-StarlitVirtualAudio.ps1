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
    [string]$PackageDirectory = '',
    [string]$SetupHelperPath = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($PackageDirectory)) {
    $PackageDirectory = Join-Path $PSScriptRoot 'signed-package'
}
if ([string]::IsNullOrWhiteSpace($SetupHelperPath)) {
    $SetupHelperPath = Join-Path $PSScriptRoot 'StarlitDeviceSetup.exe'
}
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
    $sys = Join-Path $folder 'StarlitVirtualAudio.sys'
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
    # A valid but unrelated catalog must not be accepted as a package signature.
    # DevCon requires the Windows Driver Kit; SignTool is supplied by the SDK.
    $signtool = Get-Command 'signtool.exe' -ErrorAction SilentlyContinue
    if ($null -ne $signtool) {
        foreach ($file in @($inf, $sys)) {
            & $signtool.Source verify /pa /c $cat $file | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "Signed catalog does not validate the packaged file: $file"
            }
        }
    }
    # PnPUtil always validates the catalog against INF, SYS and current driver
    # policy during installation, even when SignTool is not present.
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
        if (-not (Test-Path -LiteralPath $SetupHelperPath -PathType Leaf)) {
            throw 'StarlitDeviceSetup.exe is required; it is built from the reviewed Starlit source.'
        }
        $result = @(& $SetupHelperPath create $inf)
        if ($LASTEXITCODE -ne 0) {
            throw "Starlit root-device registration failed ($LASTEXITCODE)."
        }
        $created = @($result | Where-Object {
            $_ -match '^CREATED_INSTANCE_ID=ROOT\\STARLITVIRTUALAUDIO\\[^\\\r\n]+$'
        })
        if ($created.Count -ne 1) {
            throw 'Starlit root-device helper did not return an unambiguous instance ID.'
        }
        $instanceId = ($created[0] -split '=', 2)[1]
        try {
            & pnputil.exe /add-driver $inf /install
            if ($LASTEXITCODE -ne 0) {
                throw "The signed driver was rejected by Windows PnP ($LASTEXITCODE)."
            }
            $devices = @(Get-StarlitDevice)
            if ($devices.Count -ne 1 -or $devices[0].Status -ne 'OK' -or
                $devices[0].InstanceId -ne $instanceId) {
                throw 'Driver installation did not produce one healthy Starlit device.'
            }
        } catch {
            # Only the exact root instance created by this call may be removed.
            # Do not touch pre-existing, default or unrelated sound devices.
            & pnputil.exe /remove-device $instanceId | Out-Null
            throw
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
