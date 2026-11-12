# Starlit Virtual Audio — Windows prototype (not release-ready)

The Windows Discord dialogue companion can target a **dedicated Starlit Virtual Audio render endpoint** without rerouting the main player's 5.1/7.1 audio. The player selects an active, unique, non-default Starlit endpoint first. If none exists, it can still use a safe, unused Steam Streaming Speakers endpoint. An ambiguous or unsafe Starlit endpoint prevents fallback.

## Driver prototype

`Build-StarlitVirtualAudioPreview.ps1` retrieves **exact commit `d49bb7d72f3651952b2b6e4ad28d14fe2247eb69`** of Microsoft's Windows-driver-samples using sparse checkout. It locally patches the SimpleAudioSample INF to use `ROOT\StarlitVirtualAudio`, advertises a **Starlit Virtual Audio** speaker and removes the sample microphone's device interface registrations. The original upstream source is not checked into the app repository. Build output has the original `SimpleAudioSample.inf/.sys/.cat` file names but a distinct INF hardware ID and device name.

Requirements: Windows x64, Visual Studio C++/MSBuild, Windows SDK and WDK, and Git for Windows.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\drivers\starlit-virtual-audio\Build-StarlitVirtualAudioPreview.ps1
```

The script only builds the sample in a unique temporary directory. It never installs the driver, changes Windows default audio devices, enables test-signing, disables Secure Boot or turns off driver-signature enforcement. Generated output is in the untracked `unsigned-preview/` directory. The Microsoft Public License is copied alongside the build output.

**Important:** This is a build scaffold, not a production-validated audio driver. Microsoft explicitly notes that the upstream sample does not faithfully implement endpoint loopback and supplies simulated loopback audio. It therefore must **not** be shipped, presented as a working cable, or automatically installed for end users before real audio transport and Discord capture are verified.

## Production provisioning gate

`Install-StarlitVirtualAudio.ps1` has explicit `Status`, `Install` and `Remove` actions. The install action requires administrator privileges, the WDK `devcon.exe` utility to register a root-enumerated device, and a valid Microsoft Windows Hardware Compatibility Publisher-signed `SimpleAudioSample.inf/.sys/.cat` package in `signed-package/`. It rejects missing, mismatched and unsigned packages. It does not silently alter default audio roles, install another device or change security boot settings.

```powershell
powershell -NoProfile -File .\drivers\starlit-virtual-audio\Install-StarlitVirtualAudio.ps1 -Action Status
# Only after signing, testing and explicit choice on a test machine:
# powershell -NoProfile -File .\drivers\starlit-virtual-audio\Install-StarlitVirtualAudio.ps1 -Action Install
```

## Remaining acceptance criteria (must pass before release)

- [ ] Adapt the sample to a production-ready **render-only** endpoint and verify real PCM capture, not generated test tone, via WASAPI process and endpoint loopback.
- [ ] Build the x64 driver successfully with the supported WDK and review driver symbols, service names, INF hardware IDs, copyright and componentization requirements.
- [ ] Verify driver package signing and Windows 10/11 Secure Boot compatibility without test-signing mode.
- [ ] Provision/remove/upgrade on a disposable Windows test machine, including restart, device disable and default-role changes; never modify normal output devices.
- [ ] Real Discord **Entire Screen** listener: 5.1/7.1 centre dialogue audible, no duplicate music/effects, seek/pause/speed/track changes in sync, and source teardown cleanly releases the endpoint.
- [ ] Verify actual visible device name matches the endpoint-selection policy, installation/remove scripts and app packaging; add an in-app opt-in installer only after signed packages exist.
- [ ] Test normal playback, startup without any virtual device and safe legacy fallback with no regression.

No signed driver is currently bundled in Starlit, and this branch does not change the default Discord setting. Do not include the preview driver in a public application release.

## Sources and licensing

Microsoft [SimpleAudioSample](https://github.com/microsoft/Windows-driver-samples/tree/d49bb7d72f3651952b2b6e4ad28d14fe2247eb69/audio/simpleaudiosample) is distributed under [MS-PL](https://github.com/microsoft/Windows-driver-samples/blob/d49bb7d72f3651952b2b6e4ad28d14fe2247eb69/LICENSE). See Microsoft's [driver signing](https://learn.microsoft.com/windows-hardware/drivers/dashboard/get-started-with-the-hardware-dashboard) and [sample limitations](https://learn.microsoft.com/en-us/samples/microsoft/windows-driver-samples/sysvad-virtual-audio-device-driver-sample/) before advancing this prototype.
