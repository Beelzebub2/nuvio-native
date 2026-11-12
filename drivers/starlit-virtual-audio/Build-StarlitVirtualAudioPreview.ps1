#Requires -Version 5.1
<#
Build a render-only Windows audio device from a pinned Microsoft sample.
No driver installation or signature policy changes are performed here.
#>
[CmdletBinding()]
param(
    [string]$OutputDirectory = '',
    [switch]$KeepBuildTree,
    [switch]$PrepareOnly,
    [string]$ScratchRoot = '',
    [ValidateSet('10.0.26100.6584', '10.0.28000.2526')]
    [string]$KitVersion = '10.0.26100.6584'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot 'unsigned-preview'
}
if ([string]::IsNullOrWhiteSpace($ScratchRoot)) {
    $ScratchRoot = [IO.Path]::GetTempPath()
}
$ScratchRoot = [IO.Path]::GetFullPath($ScratchRoot)
if (-not (Test-Path -LiteralPath $ScratchRoot -PathType Container)) {
    throw "Scratch root must already exist: $ScratchRoot"
}
$upstream = 'https://github.com/microsoft/Windows-driver-samples.git'
$pin = 'd49bb7d72f3651952b2b6e4ad28d14fe2247eb69'
$work = Join-Path $ScratchRoot ('starlit-virtual-audio-' + [guid]::NewGuid().ToString('N'))
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
function Replace-Count([string]$from, [string]$to, [int]$expected) {
    $count = [regex]::Matches($script:inxText, [regex]::Escape($from)).Count
    if ($count -ne $expected) { throw "Unexpected INF occurrence count: $from ($count instead of $expected)" }
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
    Replace-Count 'simpleaudiosample.sys' 'StarlitVirtualAudio.sys' 5
    Replace-ExactlyOnce 'AddService=SimpleAudioSample,0x00000002,SimpleAudioSample_Service_Inst' 'AddService=StarlitVirtualAudio,0x00000002,StarlitVirtualAudio_Service_Inst'
    Replace-ExactlyOnce '[SimpleAudioSample_Service_Inst]' '[StarlitVirtualAudio_Service_Inst]'
    Replace-ExactlyOnce 'SimpleAudioSample.SvcDesc=' 'StarlitVirtualAudio.SvcDesc='
    Replace-ExactlyOnce 'DisplayName=%SimpleAudioSample.SvcDesc%' 'DisplayName=%StarlitVirtualAudio.SvcDesc%'
    Replace-ExactlyOnce 'KmdfService = SimpleAudioSample,' 'KmdfService = StarlitVirtualAudio,'

    # Expose only the render interface, not the sample virtual microphone.
    $lines = [regex]::Split($script:inxText, "\r?\n")
    $mic = @($lines | Where-Object { $_ -match '^\s*AddInterface\s*=.*MicArray1' })
    if ($mic.Count -ne 5) { throw "Unexpected capture-interface count: $($mic.Count)" }
    $script:inxText = (@($lines | Where-Object {
        $_ -notmatch '^\s*AddInterface\s*=.*MicArray1'
    }) -join [Environment]::NewLine)
    [IO.File]::WriteAllText($inx, $script:inxText, [Text.Encoding]::ASCII)

    # A unique service/binary identity prevents collisions with any Microsoft
    # development sample installation and permits independent upgrades.
    $vcx = Join-Path $driverRoot 'Source/Main/Main.vcxproj'
    $project = [IO.File]::ReadAllText($vcx)
    if ([regex]::Matches($project, '<TargetName>SimpleAudioSample</TargetName>').Count -ne 4) {
        throw 'Unexpected output-target layout in upstream project.'
    }
    $project = $project.Replace('<TargetName>SimpleAudioSample</TargetName>',
        '<TargetName>StarlitVirtualAudio</TargetName>')
    [IO.File]::WriteAllText($vcx, $project)

    # The device is a dedicated render sink: never register the sample's
    # fake microphone, synthesize capture tones or write media to files.
    $adapter = Join-Path $driverRoot 'Source/Main/adapter.cpp'
    $adapterText = [IO.File]::ReadAllText($adapter)
    $captureInstall = 'ntStatus = InstallAllCaptureFilters(DeviceObject, Irp, pAdapterCommon);'
    if ([regex]::Matches($adapterText, [regex]::Escape($captureInstall)).Count -ne 1) {
        throw 'Unexpected sample capture install path.'
    }
    $adapterText = $adapterText.Replace($captureInstall,
        'ntStatus = STATUS_SUCCESS; // Render-only; no capture endpoints installed.')
    # Enforce privacy even when a registry value attempts to enable file dumps.
    $registryAnchor = 'DPF(D_VERBOSE, ("DoNotCreateDataFiles: %u", g_DoNotCreateDataFiles));'
    if ([regex]::Matches($adapterText, [regex]::Escape($registryAnchor)).Count -ne 1) {
        throw 'Unexpected sample registry settings path.'
    }
    $adapterText = $adapterText.Replace($registryAnchor,
        ('g_DoNotCreateDataFiles = 1; g_DisableToneGenerator = 1;' + [Environment]::NewLine +
        '    ' + $registryAnchor))
    [IO.File]::WriteAllText($adapter, $adapterText)

    # Official 2026 WDK NuGet restores require a version matching VS/WDK.
    # VS2022 uses 26100; VS2026 uses 28000. Do not edit user-installed kits.
    if ($KitVersion -ne '10.0.28000.2526') {
        foreach ($file in @('packages.config', 'Directory.Build.props')) {
            $path = Join-Path $source $file
            $text = [IO.File]::ReadAllText($path)
            if (-not $text.Contains('10.0.28000.2526')) {
                throw "Unexpected WDK version file: $path"
            }
            [IO.File]::WriteAllText($path, $text.Replace('10.0.28000.2526', $KitVersion))
        }
    }
    # Only restore packages used by an x64 build. Keeping ARM64 NuGet packages
    # is unnecessary and dramatically increases CI download time.
    $packageConfig = Join-Path $source 'packages.config'
    $packageText = [IO.File]::ReadAllText($packageConfig)
    $packageText = [regex]::Replace($packageText,
        '(?m)^.*<package\s+id="Microsoft\.Windows\.(?:WDK|SDK\.CPP)\.arm64"[^>]+/>\s*\r?\n',
        '')
    [IO.File]::WriteAllText($packageConfig, $packageText)
    if ($PrepareOnly) {
        Write-Output "Prepared pinned driver source: $driverRoot"
        Write-Output "Use NuGet restore and MSBuild to validate; no device was installed."
        return
    }

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
    # WDK packages are sourced through a pinned NuGet restore, not downloaded
    # as an unverified standalone executable or installed machine-wide.
    if (-not (Get-Command nuget.exe -ErrorAction SilentlyContinue)) {
        throw 'NuGet CLI is required for restoring Microsoft WDK/SDK packages.'
    }
    Invoke-Checked 'nuget.exe' @('restore', (Join-Path $source 'packages.config'),
        '-PackagesDirectory', (Join-Path $source 'packages'), '-NonInteractive')
    # WDK-as-NuGet imports MSBuild targets, but standalone utilities (e.g.
    # stampinf.exe) may not land on PATH on a clean CI runner. Expose only
    # the executables from this exact, pinned Microsoft package.
    $wdkPackage = Join-Path $source ('packages/Microsoft.Windows.WDK.x64.' + $KitVersion)
    if (-not (Test-Path -LiteralPath $wdkPackage -PathType Container)) {
        throw "Pinned Microsoft x64 WDK package was not restored: $wdkPackage"
    }
    $toolDirectories = @()
    foreach ($utility in @('stampinf.exe', 'inf2cat.exe', 'tracewpp.exe')) {
        # NuGet's x64 WDK archive ALSO contains ARM64 and x86 versions.
        # Never put ARM64 ahead of x64: file-tracker can find it by name but
        # cannot execute it on a Windows x64 runner (TRK0002).
        $foundTools = @(Get-ChildItem -LiteralPath $wdkPackage -Recurse -File -Filter $utility |
            Where-Object { $_.DirectoryName -match '[\\/](?:x64|amd64)$' })
        if ($utility -eq 'stampinf.exe' -and $foundTools.Count -eq 0) {
            throw 'The restored WDK NuGet package does not contain an x64 stampinf.exe.'
        }
        foreach ($entry in $foundTools) {
            if ($entry.DirectoryName -notin $toolDirectories) {
                $toolDirectories += $entry.DirectoryName
            }
        }
    }
    if ($toolDirectories.Count -gt 0) {
        $env:PATH = ($toolDirectories -join ';') + ';' + $env:PATH
        Write-Output ("Available WDK tool directories: " + ($toolDirectories -join ', '))
    }
    # The NuGet WDK InfVerif MSBuild task loads x86\InfVerif.dll relative
    # to its process working directory rather than by an absolute path.
    # Launch MSBuild from the directory containing x86 and x64 tools, without
    # copying WDK binaries into the global toolchain or bypassing validation.
    $wdkBin = @(Get-ChildItem -LiteralPath (Join-Path $wdkPackage 'c/bin') -Directory)
    if ($wdkBin.Count -ne 1 -or -not (Test-Path -LiteralPath (Join-Path $wdkBin[0].FullName 'x86') -PathType Container)) {
        throw 'Expected exactly one WDK tools directory with x86 and x64 validators.'
    }
    Push-Location -LiteralPath $wdkBin[0].FullName
    try {
        Invoke-Checked $msbuildPath @((Join-Path $driverRoot 'SimpleAudioSample.sln'),
            '/m', '/nr:false', '/t:Build', '/p:Configuration=Release',
            '/p:Platform=x64', '/v:minimal')
    } finally {
        Pop-Location
    }

    $packages = @(Get-ChildItem -LiteralPath $driverRoot -Filter 'SimpleAudioSample.inf' -File -Recurse |
        Where-Object {
            (Test-Path -LiteralPath (Join-Path $_.DirectoryName 'StarlitVirtualAudio.sys')) -and
            (Test-Path -LiteralPath (Join-Path $_.DirectoryName 'SimpleAudioSample.cat'))
        })
    if ($packages.Count -ne 1) {
        throw "Expected one complete x64 package, found $($packages.Count)."
    }
    [IO.Directory]::CreateDirectory($output) | Out-Null
    foreach ($file in @('SimpleAudioSample.inf', 'StarlitVirtualAudio.sys', 'SimpleAudioSample.cat')) {
        Copy-Item -LiteralPath (Join-Path $packages[0].DirectoryName $file) -Destination $output
    }
    Copy-Item -LiteralPath (Join-Path $source 'LICENSE') -Destination (Join-Path $output 'MICROSOFT-SAMPLE-LICENSE.txt')
    Write-Output "Preview package built: $output"
    Write-Warning 'Development prototype only. Signing and real device verification are still required.'
} finally {
    # The build tree is a GUID-named scratch folder created by this call.
    # Never remove the caller-controlled output directory.
    if ($KeepBuildTree -or $PrepareOnly) {
        Write-Output "Build tree retained: $work"
    } elseif (Test-Path -LiteralPath $work -PathType Container) {
        try {
            Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction Stop
        } catch {
            # MSBuild may retain WDK build-task assemblies until its process
            # exits. Never hide an earlier compiler result with cleanup failure
            # or forcefully close handles belonging to another process.
            Write-Warning "WDK tools still hold temporary build files; preserved $work"
        }
    }
}
