# Samsung Tizen physical-device acceptance

The acceptance gate applies to the exact unsigned WGT SHA-256. Passing
automated tests and launching an app on a TV do not certify production playback.

Required checked scenarios: remote navigation; signed install and update;
authorized MP4/HLS/DASH playback; bounded multifile torrent streaming, seeking
and cleanup; two-profile account sync; opted-in tracking; low-storage recovery;
standby/network recovery; protected proxy security; complete license notices.

The `release/acceptance.json` gate requires evidence for all ten scenarios on
the exact artifact, a reviewer, and verified Tizen 4, 5 and 6 hardware. Leave
the gate at `NOT DEVICE-VERIFIED` until the complete matrix passes.

## Verified partial test (11 November 2026)

- A Samsung Tizen 5.0 TV with Developer Mode enabled accepted a Public
  development signature, installed Starlit TV 0.1.0 and launched it.
- Device Web Inspector confirmed Portuguese login, guest/profile selection,
  Settings and directional focus changes.
- The bundled EngineFS service started through the Tizen application API
  despite the TV reporting `web.service=false`; its local `/settings` and
  byte-range endpoints returned HTTP 200 and 206 respectively.
- Legacy WebKit hid the `Content-Range` header from Fetch. The application now
  validates the exact expected bytes as a compatibility fallback.

**Not verified:** sustained playback, lawful torrent session, real account and
two-profile sync, signed update preservation, standby/low-storage recovery,
other Tizen generations or the final physical release acceptance.
