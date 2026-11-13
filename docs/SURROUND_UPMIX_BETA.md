# Stereo surround enhancement (Windows experimental beta)

The desktop player's opt-in PCM upmixer uses the existing libmpv/FFmpeg
audio-filter pipeline. It does **not** recreate the original discrete surround
master and should not be described as native multichannel content.

## Availability and user controls

- Supported initially on 64-bit Windows with a default shared-mode PCM output
  configured for six speakers. Both side and back 5.1 speaker layouts are
  recognized. Other platforms do not expose the setting yet.
- In **Settings > Playback > Surround audio**, switch on the experimental
  **Stereo to 5.1 surround** option. It is off by default.
- **Stereo to virtual 7.1 headset output** is a separate, default-off mode for
  compatible Corsair VOID iCUE headsets. It routes eight-channel PCM to the
  headset's driver when Windows accepts an exact 7.1 shared-mode stream, even
  when Windows advertises a stereo *mix* format. The iCUE surround-processing
  toggle remains controlled by iCUE itself; Starlit cannot verify that it is
  enabled or guarantee an audible spatial effect. This mode is not a second
  headphone spatializer, so it avoids layering two HRTF processors.
- Selecting one upmix mode disables the other. The in-player **5.1/7.1** button
  can toggle the most recently selected preference. Its label and
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
7. Virtual 7.1 is allowed only on a known compatible Corsair VOID route when
   the Console and Multimedia default endpoints match, the endpoint's
   WAVEFORMATEXTENSIBLE mix is stereo or exact 7.1, and
   `IAudioClient::IsFormatSupported` accepts an *exact* 7.1 IEEE-float PCM
   format. Generic stereo outputs are **not** enabled merely because WASAPI
   accepts eight channels. Unknown layouts and unapproved headsets bypass.
8. Verify that libmpv reports `audio-out-params/channels=7.1` after applying
   the headset mode; otherwise remove the filter and revert to original audio.
   Actual driver/DSP speaker virtualization, comfort, and positioning remain
   hardware/listening tests, not inferred from software format negotiation.

## Verification

- Run `./gradlew.bat :composeApp:buildWindowsPlayerBridge` on Windows.
- Run `./gradlew.bat :composeApp:testWindowsSurroundUpmixPolicy` to compile
  and run deterministic speaker-mask and source-format classification tests.
- Run `py -3 tests/surround-upmix-libmpv-probe.windows.py` to check the
  bundled DLL can add/remove the real FFmpeg filter using synthetic audio and
  an audio-null sink; the test renders six- and eight-channel WAVEFORMATEXTENSIBLE
  float PCM files through mpv's PCM sink and checks non-silent front, centre and
  surround samples with silent LFE. This does not play through real speakers.
- Perform a real-hardware speaker-identification test before treating this as
  production-ready. Compare original stereo with upmix, then test actual
  native 5.1 and 7.1 sources, device unplugging, HDMI/USB audio, mute,
  playback speed, seek, and source/track changes.

### Not yet supported or proven

- Arbitrary-headset HRTF/binaural processing, generic stereo-to-7.1 speaker
  upmix, Linux/macOS/Android/iOS/Tizen upmix, head-tracked headphone
  spatialization and compressed Dolby/DTS 5.1 encoding.
- Detecting whether a connected AVR/soundbar already applies its own upmixer.
- Real-speaker listening quality, A/V latency and output routing across
  manufacturers/firmware. The initial setting remains opt-in pending those
  acceptance tests.

Implementation uses the existing open-source libmpv/FFmpeg dependency and
requires no proprietary surround decoder, device driver, server-side
processing or external media upload.

## Headset compatibility and verification boundaries

- Corsair documents its VOID RGB ELITE Wireless headset as supporting virtual
  7.1 through iCUE:
  https://www.corsair.com/ww/en/p/gaming-headsets/ca-9011201-eu/void-rgb-elite-wireless-premium-gaming-headset-with-7-1-surround-sound-carbon-eu-ca-9011201-eu
- Windows may accept eight-channel streams even when the endpoint's mix
  format is stereo, especially when a driver-provided processing object
  handles format conversion. That is **necessary but not sufficient** evidence
  of virtual surround:
  https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclient-isformatsupported
- On a Windows CORSAIR VOID ELITE headset, a *muted synthetic-audio* libmpv
  test confirmed stereo input -> 7.1 WASAPI output -> stereo restoration.
  Subjective position, DSP engagement, mono compatibility, A/V latency and
  device reconnect behavior were **not** measured in that test. This feature
  remains opt-in and experimental pending listening and switching validation.
