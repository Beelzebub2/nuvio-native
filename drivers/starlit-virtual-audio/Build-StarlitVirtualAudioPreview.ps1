#Requires -Version 5.1
<#
Build-only prototype from pinned Microsoft SimpleAudioSample source.
Not installable on stock Windows until validated and Microsoft-signed.
#>
[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'unsigned-preview'),
    [switch]$KeepBuildTree
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$upstream = 'https://github.com/microsoft/Windows-driver-samples.git'
$pin = 'd49bb7d72f3651952b2b6e4ad28d14fe2247eb69'
$work = Join-Path ([IO.Path]::GetTempPath()) ('starlit-virtual-audio-' + [guid]::NewGuid().ToString('N'))
$source = Join-Path $work 'Windows-driver-samples'
$output = [IO.Path]::GetFullPath($OutputDirectory)

function Invoke-Checked([string]$program, [string[]]$arguments) {
    & $program @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $program $($arguments -join ' ')"
    }
}
function Replace-ExactlyOnce([string]$from, [string]$to) {
    $count = [regex]::Matches($script:inxText, [regex]::Escape($from)).Count
    if ($count -ne 1) { throw "Unexpected upstream INF layout: $from ($count matches)" }
    $script:inxText = $script:inxText.Replace($from, $to)
}

if (-not (Get-Command git.exe -ErrorAction SilentlyContinue)) {
    throw 'Git for Windows is required to fetch the pinned source.'
}
if (Test-Path -LiteralPath $output -PathType Leaf) {
    throw 'The output path must be a directory.'
}
[IO.Directory]::CreateDirectory($work) | Out-Null
try {
    Invoke-Checked 'git.exe' @('clone', '--quiet', '--filter=blob:none',
        '--no-checkout', $upstream, $source)
    Invoke-Checked 'git.exe' @('-C', $source, 'sparse-checkout', 'set', 'audio/simpleaudiosample')
    Invoke-Checked 'git.exe' @('-C', $source, 'fetch', '--quiet', '--depth=1', 'origin', $pin)
    Invoke-Checked 'git.exe' @('-C', $source, 'checkout', '--quiet', '--detach', $pin)
    $actualPin = (& git.exe -C $source rev-parse HEAD).Trim()
    if ($actualPin -ne $pin) { throw "Unexpected source commit: $actualPin" }

    $driverRoot = Join-Path $source 'audio/simpleaudiosample'
    $inx = Join-Path $driverRoot 'Source/Main/SimpleAudioSample.inx'
    $script:inxText = [IO.File]::ReadAllText($inx)
    Replace-ExactlyOnce 'ROOT\SimpleAudioSample' 'ROOT\StarlitVirtualAudio'
    Replace-ExactlyOnce 'ProviderName = "TODO-Set-Provider"' 'ProviderName = "Starlit"'
    Replace-ExactlyOnce 'MfgName      = "TODO-Set-Manufacturer"' 'MfgName      = "Starlit"'
    Replace-ExactlyOnce 'SIMPLEAUDIOSAMPLE_SA.DeviceDesc="Virtual Audio Device (WDM) - Simple Audio Sample"' 'SIMPLEAUDIOSAMPLE_SA.DeviceDesc="Starlit Virtual Audio"'
    Replace-ExactlyOnce 'SimpleAudioSample.SvcDesc="Virtual Audio Device (WDM) - Simple Audio Sample Driver"' 'SimpleAudioSample.SvcDesc="Starlit Virtual Audio Driver"'
    Replace-ExactlyOnce 'SIMPLEAUDIOSAMPLE.WaveSpeaker.szPname="Simple Audio Sample Wave Speaker"' 'SIMPLEAUDIOSAMPLE.WaveSpeaker.szPname="Starlit Virtual Audio"'

    # Expose only the render interface, not the sample virtual microphone.
    $lines = [regex]::Split($script:inxText, "\r?\n")
    $mic = @($lines | Where-Object { $_ -match '^\s*AddInterface\s*=.*MicArray1' })
    if ($mic.Count -ne 5) { throw "Unexpected capture-interface count: $($mic.Count)" }
    $script:inxText = (@($lines | Where-Object {
        $_ -notmatch '^\s*AddInterface\s*=.*MicArray1'
    }) -join [Environment]::NewLine)
    [IO.File]::WriteAllText($inx, $script:inxText, [Text.Encoding]::ASCII)

    $msbuild = Get-Command MSBuild.exe -ErrorAction SilentlyContinue
    if ($null -ne $msbuild) {
        $msbuildPath = $msbuild.Source
    } else {
        $programFilesX86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
        $vswhere = Join-Path $programFilesX86 'Microsoft Visual Studio/Installer/vswhere.exe'
        if (-not (Test-Path -LiteralPath $vswhere)) {
            throw 'Visual Studio MSBuild and Windows Driver Kit are required.'
        }
        $found = @(& $vswhere -latest -products '*' -requires Microsoft.Component.MSBuild -find 'MSBuild\**\Bin\MSBuild.exe')
        if ($found.Count -eq 0) { throw 'MSBuild was not found.' }
        $msbuildPath = $found[0]
    }
    Invoke-Checked $msbuildPath @((Join-Path $driverRoot 'SimpleAudioSample.sln'),
        '/m', '/t:Build', '/p:Configuration=Release', '/p:Platform=x64', '/v:minimal')

    $packages = @(Get-ChildItem -LiteralPath $driverRoot -Filter 'SimpleAudioSample.inf' -File -Recurse |
        Where-Object {
            (Test-Path -LiteralPath (Join-Path $_.DirectoryName 'SimpleAudioSample.sys')) -and
            (Test-Path -LiteralPath (Join-Path $_.DirectoryName 'SimpleAudioSample.cat'))
        })
    if ($packages.Count -ne 1) {
        throw "Expected one complete x64 package, found $($packages.Count)."
    }
    [IO.Directory]::CreateDirectory($output) | Out-Null
    foreach ($file in @('SimpleAudioSample.inf', 'SimpleAudioSample.sys', 'SimpleAudioSample.cat')) {
        Copy-Item -LiteralPath (Join-Path $packages[0].DirectoryName $file) -Destination $output
    }
    Copy-Item -LiteralPath (Join-Path $source 'LICENSE') -Destination (Join-Path $output 'MICROSOFT-SAMPLE-LICENSE.txt')
    Write-Output "Preview package built: $output"
    Write-Warning 'Development prototype only. Signing and real device verification are still required.'
} finally {
    # The build tree is a GUID-named scratch folder created by this call.
    # Never remove the caller-controlled output directory.
    if ($KeepBuildTree) {
        Write-Output "Build tree retained: $work"
    } elseif (Test-Path -LiteralPath $work -PathType Container) {
        Remove-Item -LiteralPath $work -Recurse -Force
    }
}
