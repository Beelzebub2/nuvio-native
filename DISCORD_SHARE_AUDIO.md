# Windows Discord share audio (experimental)

Starlit supports **Entire Screen** sharing without Discord plugins, while
keeping the normal Starlit window and its original surround output.
When enabled, a **hidden** companion sends only the 5.1/7.1 **front-centre
(FC) dialogue channel**, not a second copy of the soundtrack's left/right
music. Discord captures the original audio plus this centre-only supplement.
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
- Prefer a unique, active **Starlit Virtual Audio** virtual speaker
  endpoint if installed separately; otherwise the existing **Steam Streaming
  Speakers** device remains a compatibility fallback. Both routes require an
  unused render endpoint, different from every Windows console, multimedia
  and communications default. An unsafe or ambiguous Starlit endpoint rejects
  capture rather than falling back to Steam. Starlit rechecks endpoint
  eligibility during sharing and stops only the auxiliary process on changes.
  The app does **not** change global audio settings or automatically install
  a driver. Its dedicated device is a [build-only preview](drivers/starlit-virtual-audio/README.md)
  pending a production-signed package and real audio capture validation.
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

## Acceptance checklist

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
- [ ] On an isolated Windows test machine, verify the future Starlit driver
  supplies actual PCM render/loopback audio rather than the upstream sample's
  synthetic loopback tone, and validates under normal Secure Boot and signing.
- [ ] Verify the dedicated device's exact friendly name, package install/removal,
  device-disconnect failure mode and Discord capture on real 5.1/7.1 material.

Until the unchecked items pass, treat the option as a local experimental
feature; normal playback remains the supported default.
