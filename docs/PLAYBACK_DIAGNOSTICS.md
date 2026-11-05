# Playback diagnostics

Starlit offers on-device playback diagnostics on mobile and, with the desktop
native player, in its video-control overlay. Open the **Playback diagnostics**
button in the desktop player header to see live measurements. The displayed
measurements are not uploaded. Desktop diagnostic-only mpv property reads run
only while the panel is open; closing it stops sampling and clears its measurements
and counters. Reopening starts a fresh diagnostic session.

## Desktop fields

- State: paused, buffering, or playing, based on the native mpv state.
- Resolution: video-frame width and height reported by mpv.
- Position, duration, buffer-ahead, playback speed: measured from mpv. Invalid
  and missing values are shown as unavailable rather than estimated.
- Rebuffer events: a local count of transitions back into buffering after playback
  starts while the panel is open. Native seek and paused transitions are excluded.
- Decoder: mpv's active decoder type, reported as hardware/software/unknown.
  Hardware mode alone does not imply a particular codec or RTX filter.
- Source throughput: unavailable until a reliable platform measurement exists.
  Do not estimate it from media bitrate or claim internet speed.
- Network connection hint: the passive browser/OS `navigator.onLine` flag,
  when available. This is **not** a check that the internet, stream host or
  receiver can be reached. No extra network requests or trackers are issued.
- Troubleshooting: bounded, fixed advice for an offline link hint, buffering,
  low buffer-ahead seconds or high-resolution software decoding. If the hints
  are inconclusive, compare an authorized direct stream.

The **Copy sanitized report** action emits only a fixed set of numeric
measurements and state labels. The report does not include media URLs, query
strings, source/header credentials, local file paths, titles or account data.
The desktop WebView additionally validates copied values against an explicit
allowlist of numeric measurements and fixed labels/advice even if a caller
supplies unexpected properties.

## Mobile fields

The mobile panel reads the existing playback snapshot without extra network work.
On Android, Media3's active video-decoder initialization is classified using the
actual platform codec's hardware/software flags (API 29+), and the mpv engine uses
its active `hwdec-current` mode. Raw codec names are never copied. An unsupported
platform/codec, including the current iOS decoder integration, reports unavailable.
The Media3 listener is removed with its player. No codec list is polled per frame.

The mobile buffering counter starts when the panel opens, excludes startup, counts
each observed buffering transition once, and resets on close or source change.
It includes buffering caused by seeks, as stated in the panel/report; it is not a
network failure counter. Invalid playback speeds are unavailable. Throughput remains
unavailable until a trustworthy platform metric exists. Existing network checks are
observed without initiating new probes. The fixed troubleshooting text explains
buffer/source/network and decoder checks without exposing source metadata.

## Validation

Run `node --test tests/native-player-diagnostics.test.cjs` and
`node --check composeApp/src/desktopMain/resources/player-ui/controls.js`.
The playback-diagnostics workflow compiles Linux and macOS native sources, builds
the Windows bridge, and runs native missing-value/disabled-sampling tests, JavaScript
redaction/session tests, and common Kotlin/player lifecycle tests. Compilation and
automated transition tests do not replace device playback checks. Record actual
playback/buffering observations separately and identify platforms not exercised.
