# Playback diagnostics

Starlit offers on-device playback diagnostics on mobile and, with the desktop
native player, in its video-control overlay. Open the **Playback diagnostics**
button in the desktop player header to see live measurements. The displayed
measurements are not uploaded and are not retained across player sessions.

## Desktop fields

- State: paused, buffering, or playing, based on the native mpv state.
- Resolution: video-frame width and height reported by mpv.
- Position, duration, buffer-ahead, playback speed: measured from mpv. Invalid
  and missing values are shown as unavailable rather than estimated.
- Rebuffer events: a local count of transitions back into buffering after the
  current player has played. This is **not** a historical account-wide count.
- Decoder: mpv's active decoder type, reported as hardware/software/unknown.
  Hardware mode alone does not imply a particular codec or RTX filter.
- Source throughput: unavailable until a reliable platform measurement exists.
  Do not estimate it from media bitrate or claim internet speed.

The **Copy sanitized report** action emits only a fixed set of numeric
measurements and state labels. The report does not include media URLs, query
strings, source/header credentials, local file paths, titles or account data.
The desktop WebView additionally validates copied values against an explicit
allowlist even if a caller supplies unexpected properties.

## Validation

Run `node --test tests/native-player-diagnostics.test.cjs` and
`node --check composeApp/src/desktopMain/resources/player-ui/controls.js`.
The native metric bridge has Windows, macOS and Linux implementations; exercise
all three platforms and a buffering scenario before declaring this issue
completely verified. The desktop overlay ships independently of the existing
mobile diagnostic screen.
