# Stereo-to-5.1 surround (Windows experimental beta)

The desktop player's opt-in PCM upmixer uses the existing libmpv/FFmpeg
audio-filter pipeline. It does **not** recreate the original discrete surround
master and should not be described as native multichannel content.

## Availability and user controls

- Supported initially on 64-bit Windows with a default shared-mode PCM output
  configured for six speakers. Both side and back 5.1 speaker layouts are
  recognized. Other platforms do not expose the setting yet.
- In **Settings > Playback > Surround audio**, switch on the experimental
  **Stereo to 5.1 surround** option. It is off by default.
- The in-player **5.1** button can toggle the same preference. Its label and
  tooltip distinguish *active*, *waiting/incompatible*, and *rejected* states.
- The preference is stored locally, not synced across accounts. Route-specific
  preferences and an automated speaker test are future enhancements.

## Safety policy

1. Only the decoded two-channel stereo track is eligible.
2. Windows' multimedia and console default endpoint must match, and the
   shared-mode output format must advertise an exact 5.1 channel/speaker mask.
   Unknown, stereo-only and eight-channel routes remain unchanged.
3. The filter is only applied to the primary mpv player when it uses its
   default audio device; explicitly routed playback and passthrough are
   ineligible. Native 5.1/7.1 sources are never upmixed.
4. The named `@starlit-surround` filter is added and removed without clearing
   other user or playback filters. An audio-track switch removes it first.
5. If the actual player output layout does not match the requested six-channel
   layout, the filter is removed and the rejection is reported. If the route
   disappears, processing is bypassed. Playback continues.
6. The upmixer does not synthesize LFE: the sound system owns bass management.

## Verification

- Run `./gradlew.bat :composeApp:buildWindowsPlayerBridge` on Windows.
- Run `./gradlew.bat :composeApp:testWindowsSurroundUpmixPolicy` to compile
  and run deterministic speaker-mask and source-format classification tests.
- Run `py -3 tests/surround-upmix-libmpv-probe.windows.py` to check the
  bundled DLL can add/remove the real FFmpeg filter using synthetic audio and
  an audio-null sink. This is a software test, not speaker verification.
- Perform a real-hardware speaker-identification test before treating this as
  production-ready. Compare original stereo with upmix, then test actual
  native 5.1 and 7.1 sources, device unplugging, HDMI/USB audio, mute,
  playback speed, seek, and source/track changes.

### Not yet supported or proven

- 7.1 upmix, Linux/macOS/Android/iOS/Tizen upmix, head-tracked headphone
  spatialization and compressed Dolby/DTS 5.1 encoding.
- Detecting whether a connected AVR/soundbar already applies its own upmixer.
- Real-speaker listening quality, A/V latency and output routing across
  manufacturers/firmware. The initial setting remains opt-in pending those
  acceptance tests.

Implementation uses the existing open-source libmpv/FFmpeg dependency and
requires no proprietary surround decoder, device driver, server-side
processing or external media upload.
