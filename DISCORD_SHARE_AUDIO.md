# Windows Discord share audio (experimental)

Starlit provides an **experimental** Discord Entire Screen audio companion
without Discord plugins, while keeping the normal Starlit window and its
original surround output. When enabled, a **hidden** companion renders only
the 5.1/7.1 **front-centre (FC) channel**, not a second copy of the soundtrack's
left/right music. **Whether Discord includes this supplementary audio in a
given Entire Screen share still needs to be verified with a listener.**
The optional visible **Starlit - Discord Share** window uses a full stereo
mix for **application-window-only** sharing instead.

## How it works

- The normal Starlit player continues using its original `libmpv` instance,
  selected audio endpoint, channel layout, volume and spatial audio processing.
- An independent **child process** decodes audio only and renders to a
  separate virtual WASAPI output. There is no second video decoder. This
  process never changes the original player's audio device or channel layout.
- For **Entire Screen**, toggle the Discord logo beside **Audio** in the
  Windows player's lower-left controls, or use **Discord fullscreen dialogue
  support** in Advanced settings. Both controls share the same on/off setting,
  and the player button indicates the current state. Leave **Show a separate
  full-stereo sharing window** OFF. The hidden companion uses `libmpv` +
  FFmpeg `pan` to send only the `FC` channel into left and right of a
  two-channel virtual output at roughly −3 dB. The decoder retains its
  multichannel layout until `pan`.
- Before unmuting, the hidden companion checks a recognized 5.1/7.1 decoder
  layout, the *effective* audio-track ID against the main player, the intended
  centre-only pan filter and stereo output format. If any check fails or the
  parent has not yet selected a track, it remains **silent** rather than
  risking another full-range soundtrack. Track switches are muted first.
- For standalone **application-window** sharing with a complete stereo mix,
  turn **Show a separate full-stereo sharing window** ON and select only the
  **Starlit - Discord Share** window in Discord (with audio). This mode sends
  the normal stereo downmix; do **not** select Entire Screen simultaneously.
- No changes or plugins are needed in Discord, and no Windows audio device is
  made exclusive or globally rerouted.
- Source URL and HTTP headers stay in the local process tree and are passed over
  a bounded, anonymous stdin pipe, not command-line arguments. Pausing,
  playback position, speed and selected audio-track ID are synchronized.
- The companion stops when sharing is disabled or the original player exits;
  changing the visible-window preference restarts only the child process.

## Requirements and current limitations

- Windows desktop only. Opt-in using the in-player Discord button or **Settings
  > Advanced > Discord > Discord fullscreen dialogue support (experimental)**.
  Off by default.
  Older full-stereo whole-screen preferences are not inherited; the helper
  is not re-enabled automatically in hidden mode after restarting Starlit.
- Prefers the separately installed **VB-CABLE** virtual playback endpoint
  **CABLE Input (VB-Audio Virtual Cable)**. The **Steam Streaming Speakers**
  endpoint remains a compatibility fallback when VB-CABLE is absent; Steam
  itself is not required. The CABLE **Output** recording endpoint, additional
  paid A/B/C/D cable variants and partial-name matches are not selected.
  The render device must be active, uniquely identified and different from
  Windows' console, multimedia and communications default outputs.
  An ambiguous or default VB-CABLE output fails closed instead of switching
  to Steam. Starlit rechecks eligibility during sharing and stops only the
  auxiliary process on device changes. It does **not** install drivers or
  change any Windows audio default.
- When using the **stereo window**, do not minimize the original
  Starlit window: DWM thumbnails may stop refreshing while it is minimized.
- The second audio decoder uses additional CPU/network resources and can take
  time to buffer on slow sources. Independent local/P2P streams, protected
  media, track-ID mapping, and frame-accurate A/V sync need real-source checks.
- Channel isolation does **not** equal speech separation: some music/effects
  are also mixed into `FC`, and some dialogue is mixed outside it. When Discord
  already captures the centre channel correctly, adding it can make voices
  louder or echo. It is not guaranteed for all media or Discord versions.
- A full-stereo companion can cause duplicated music when the display is
  captured. The hidden centre-only mode avoids duplicating the main left/right
  mix. A listener previously heard the **visible stereo companion** during
  an Entire Screen share; this is not a real-media listener verification of
  the newer hidden centre-only mode, which still needs the checks below.

## VB-CABLE installation and Discord verification

1. From Starlit's **Advanced > Discord > Set up VB-CABLE**, open the official
   [VB-Audio download page](https://vb-audio.com/Cable/index.htm). Install
   the original **VB-CABLE** package as administrator, then reboot Windows.
   Installation and removal require administrator privileges; Starlit will
   never install the package silently or change Windows Secure Boot settings.
2. Confirm that **CABLE Input** appears as an active Windows *output* device,
   and **CABLE Output** as an *input* device. Leave the physical headphones or
   speakers as the Windows default for **all three playback roles**. The
   capture/recording device is not the desired render endpoint and should
   **not** be selected as the Discord microphone for this feature.
3. Enable Starlit's experimental Discord dialogue option and select a 5.1/7.1
   source. For **Entire Screen**, keep the separate stereo window OFF and
   ensure Discord's stream audio is enabled. Ask a listener to confirm that
   dialogue is audible without doubling music or sound effects.
4. If Entire Screen fails to capture the non-default output, try the
   optional **Starlit - Discord Share** full-stereo application window and
   share **that application only** with audio. Do not combine its full-stereo
   mode with Entire Screen. This alternative has a separate video mirror,
   which also requires real-playback validation.

**Important:** VB-CABLE reliably forwards render audio to its own recording
endpoint, but that does **not** mean Discord automatically uses its recording
endpoint. Discord documents capturing an application's process tree, but does
not guarantee that **Entire Screen** captures non-default virtual rendering
devices on every client or capture mode. This integration is therefore opt-in,
not a verified replacement for every Discord setup.

**Licensing:** VB-CABLE is third-party donationware from VB-Audio Software.
The original driver is **not bundled** with Starlit. VB-Audio invites users
to install it from the official website; professional and redistribution uses
may require paid licenses. See
[VB-Audio licensing](https://vb-audio.com/Services/licensing.htm).

## Acceptance checklist

- [x] Starlit endpoint safety tests prefer the unique active VB-CABLE render
  input over the legacy Steam endpoint, reject defaults/duplicates/recording
  endpoints, and preserve the existing fallback.
- [ ] Install and reboot with an authentic signed VB-CABLE package; verify
  that the expected device name is exposed by the current Windows driver.
- [ ] Verify real PCM output to VB-CABLE and capture from its CABLE Output.
- [ ] Verify Discord Entire Screen audio from the non-default VB-CABLE render
  device with a separate listener, including 5.1/7.1 centre-only dialogue.

- [x] On a stereo Windows render endpoint, the bundled `libmpv` retains a 5.1
  layout for a 5.1 source when not overridden.
- [x] A 5.1 source containing a center-only synthetic tone loses that tone
  under a naive two-channel process-loopback capture, but an explicit stereo
  downmix retains it.
- [x] The Microsoft process-loopback sample captures the stereo child process
  without capturing a simultaneous source process.
- [x] The native companion builds; its GUI/IPC reader exits cleanly on EOF;
  an audio-only synthetic source plays into a separate virtual endpoint and
  can be captured as a two-channel stream.
- [x] The Windows x64 portable distributable builds and starts. Its resource
  JAR includes the native player DLL, the companion executable, and runtime
  index. A separately launched companion successfully mirrored the **actual
  running Starlit home window** in a real Windows desktop capture.
- [x] The desktop Compose UI test finds the experimental sharing switch in
  Advanced Settings on Windows.
- [x] A Discord viewer received the stereo audio successfully when the
  companion was visible, including when sharing the entire screen.
- [x] Windows hidden-helper lifecycle: no visible window/taskbar/Alt+Tab entry;
  clean EOF termination and process-isolated capture (synthetic check).
- [x] Synthetic 5.1 fixture with different FL/FR/FC frequencies: when the
  helper is hidden, FC must remain audible while FL/FR music is suppressed
  at least 24 dB relative to FC. The Windows process-loopback red/green check
  changed music from ~−22 dBFS to below −140 dBFS, with FC unchanged at
  −15.8 dBFS (10 Nov 2026).
- [x] Synthetic 7.1 source: same hidden process-loopback test kept FC at
  −15.8 dBFS, while FL/FR music measured below −145/−158 dBFS.
- [x] Final isolated Windows portable build completed, including both native
  components and 24/24 lifecycle + 1/1 settings UI tests. The installed/running
  application was not replaced.
- [x] Windows in-player Discord toggle uses the existing player button style,
  inherits theme colour, and synchronizes with Advanced Settings. VM regression
  checks passed for visibility, commands, pressed states and external state
  changes; the isolated Windows package includes the updated WebView controls,
  the native companion and 24/24 controller + 1/1 settings tests.
- [x] The in-player Discord button was tested in the Windows portable build.
- [x] Focused native policy regressions exercise accepted 5.1/7.1 layouts,
  mismatched tracks, missing/incorrect pan filters, non-stereo output and
  absent/default/ambiguous Steam endpoints. These are synthetic policy checks,
  not physical endpoint switching or Discord listener tests.
- [ ] With the new build, select Discord **Entire Screen**, play a real 5.1
  film, verify one copy of the music and audible dialogue with a listener.
- [x] With synthetic stereo source material, the hidden helper output is
  essentially silence (peak = 1 PCM unit in a 16-bit capture).
- [ ] With the visible full-stereo window, share that **application only**,
  verify full stereo dialogue and no doubled sound.
- [ ] With an actual Starlit playback window, verify the DWM mirror displays
  correct video throughout playback and source/window changes.
- [ ] Verify sustained dialogue, no double audio, and synchronization after
  pause, seek, speed and audio-track changes for actual film/episode playback.
- [ ] Validate original surround playback independently on a 5.1/7.1 speaker
  system or an enabled spatial-audio headphone configuration while sharing.
- [ ] Verify volume/mute isolation and failure behaviour if the virtual
  endpoint is disconnected mid-stream.
- [ ] During a real share, change the Windows default audio role to or from
  Steam Streaming Speakers and confirm the auxiliary audio stops safely,
  without changing the original player's output.

Until the unchecked items pass, treat the option as a local experimental
feature; normal playback remains the supported default.
