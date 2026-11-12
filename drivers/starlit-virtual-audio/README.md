# Starlit Virtual Audio — render-only Windows driver (development build)

The Windows Discord dialogue companion can target a **dedicated Starlit Virtual Audio render endpoint** without rerouting the main player's 5.1/7.1 audio. The player selects an active, unique, non-default Starlit endpoint first. If none exists, it can still use a safe, unused Steam Streaming Speakers endpoint. An ambiguous or unsafe Starlit endpoint prevents fallback.

## Driver prototype

`Build-StarlitVirtualAudioPreview.ps1` retrieves **exact commit `d49bb7d72f3651952b2b6e4ad28d14fe2247eb69`** of Microsoft's Windows-driver-samples using sparse checkout. Its reproducible source overlay:

- Identifies this as `ROOT\StarlitVirtualAudio` and **Starlit Virtual Audio**, with its own `StarlitVirtualAudio` driver service and `StarlitVirtualAudio.sys` binary.
- Registers only the render-side WaveRT endpoint, never the sample virtual microphone.
- Enforces disabled sample audio-file logging and synthetic tone generation even when a registry value would request them.
- Uses the official Windows SDK/WDK NuGet packages (26100 for VS2022, 28000 for VS2026).

The original Microsoft source is not checked into the app repository. Output uses `SimpleAudioSample.inf`, `StarlitVirtualAudio.sys` and `SimpleAudioSample.cat` (the catalog is not production signed).

Requirements: Windows x64, Visual Studio C++/MSBuild, Windows SDK/WDK NuGet tooling, NuGet CLI and Git for Windows. The prepared-source-only mode needs neither WDK nor administrator privileges.

```powershell
powershell -NoProfile -File .\drivers\starlit-virtual-audio\Build-StarlitVirtualAudioPreview.ps1 -PrepareOnly
# Build unsigned x64 package with VS2022 + NuGet (does not install):
powershell -NoProfile -File .\drivers\starlit-virtual-audio\Build-StarlitVirtualAudioPreview.ps1
```

The script works within a unique temporary directory. It never installs the driver, changes Windows default audio devices, enables test-signing, disables Secure Boot or turns off driver-signature enforcement. Output is in the ignored `unsigned-preview/` directory. The Microsoft Public License is copied alongside the build output. `-ScratchRoot` can contain ephemeral downloads; `-KeepBuildTree` retains source for manual debugging.

**Important:** This is a **render-only sink**, not a general-purpose render-to-microphone virtual cable. Windows **application/process loopback** can capture an app's shared-mode render streams independently of their physical output device, which is the proposed Discord sharing route. Microsoft's sample endpoint-loopback implementation uses a synthetic tone instead of the real mix; no promise is made that endpoint-loopback recording works. The sample must **not** be shipped or automatically installed until Microsoft signing and real Windows/Discord process-loopback acceptance pass.

## Production provisioning gate

`Install-StarlitVirtualAudio.ps1` has explicit `Status`, `Install` and `Remove` actions. The install action requires administrator privileges and a valid **Microsoft Windows Hardware Compatibility Publisher-signed** `SimpleAudioSample.inf`, `StarlitVirtualAudio.sys`, `SimpleAudioSample.cat` package in `signed-package/`. It rejects missing or unsigned packages. A small source-built `StarlitDeviceSetup.exe` creates exactly one root-enumerated Starlit device via SetupAPI, then Windows `pnputil` installs/verifies the signed driver; failed installs attempt rollback. No WDK-only DevCon dependency is required for end users.

Build the device helper on a developer machine (no elevation, no driver installation):

```powershell
powershell -NoProfile -File .\drivers\starlit-virtual-audio\Build-StarlitDeviceSetup.ps1
.\drivers\starlit-virtual-audio\StarlitDeviceSetup.exe status
```

```powershell
powershell -NoProfile -File .\drivers\starlit-virtual-audio\Install-StarlitVirtualAudio.ps1 -Action Status
# Only after signing, testing and explicit choice on a test machine:
# powershell -NoProfile -File .\drivers\starlit-virtual-audio\Install-StarlitVirtualAudio.ps1 -Action Install
```

## Remaining acceptance criteria (must pass before release)

- [x] Patch the sample into a **render-only** endpoint with isolated binary/service identity, no virtual microphone and no registry-enabled media file dumps; static source-preparation assertions passed on Windows. This does **not** prove real PCM playback.
- [x] Build and run the read-only SetupAPI device discovery tool on Windows without modifying system devices; confirmed missing Starlit endpoint and no privilege prompt.
- [ ] Verify the **real** PCM engine process-loopback capture (not the sample synthetic endpoint loopback) on a signed Starlit output and compare it with the source audio.
- [ ] Build the x64 driver successfully with the pinned WDK NuGet toolchain (CI job) and review INF/service names, driver binaries, copyright and componentization requirements.
- [ ] Verify driver package signing and Windows 10/11 Secure Boot compatibility without test-signing mode.
- [ ] Provision/remove/upgrade on a disposable Windows test machine, including restart, device disable and default-role changes; never modify normal output devices.
- [ ] Real Discord **Entire Screen** listener: 5.1/7.1 centre dialogue audible, no duplicate music/effects, seek/pause/speed/track changes in sync, and source teardown cleanly releases the endpoint.
- [ ] Verify actual visible device name matches the endpoint-selection policy, installation/remove scripts and app packaging; add an in-app opt-in installer only after signed packages exist.
- [ ] Test normal playback, startup without any virtual device and safe legacy fallback with no regression.

No signed driver is currently bundled in Starlit, and this branch does not change the default Discord setting. **Do not merge this as a finished virtual audio device or include the preview driver in a public application release.**

## Sources and licensing

Microsoft [SimpleAudioSample](https://github.com/microsoft/Windows-driver-samples/tree/d49bb7d72f3651952b2b6e4ad28d14fe2247eb69/audio/simpleaudiosample) is distributed under [MS-PL](https://github.com/microsoft/Windows-driver-samples/blob/d49bb7d72f3651952b2b6e4ad28d14fe2247eb69/LICENSE). See Microsoft's [driver signing](https://learn.microsoft.com/windows-hardware/drivers/dashboard/get-started-with-the-hardware-dashboard) and [sample limitations](https://learn.microsoft.com/en-us/samples/microsoft/windows-driver-samples/sysvad-virtual-audio-device-driver-sample/) before advancing this prototype.
