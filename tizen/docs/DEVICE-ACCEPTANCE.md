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

## Additional Tizen 5.0 development test (11 November 2026)

The unsigned WGT with SHA-256
`8ba05ec6ce5addb13df7681d8e1b37a67ca5b2d6f6be3df67ea59c0bfb3591b0`
was signed with a Public development certificate, installed and launched on a
Samsung Tizen 5.0 TV. On-device Web Inspector confirmed:

- Profile-selection Arrow Right moves focus to Add Profile; Arrow Left returns
  to the first profile; Enter advances to the add-on setup screen
- The TV's `tvinputdevice.getSupportedKeys()` reports media Play/Pause
  `10252`, fast-forward `417`, rewind `412`, track Previous `10232`
  and track Next `10233`, matching the client key map
- These key-navigation checks were **injected through Web Inspector**,
  **not** performed with the physical Samsung handheld remote

Web Inspector subsequently detached and the device became unavailable on the
network, preventing further tests. The reason for the network loss is unknown;
it is **not evidence of an established application crash**. No AVPlay/torrent,
account, standby or update acceptance was completed in that session.

A newer unsigned WGT containing onboarding and Licenses screen remote-focus
corrections has **not** been installed or device-verified. Release acceptance
must be recorded for the exact newer WGT hash, not inherited from the earlier
development package.

### Physical Samsung remote acceptance checklist

1. On a freshly installed candidate, confirm profile selection with Left,
   Right and OK. On a three-card layout screen, a single Right must move
   exactly **one** card, not skip the middle card. Back must return to mode
   choice, not exit the application
2. Confirm add-on setup with Left, Right and OK, including held OK without
   duplicate actions. Check Home, Search, Library, Settings and Licenses
   navigation, focus restoration and long multi-section lists
3. Confirm Back/Return on every overlay and player route, including a held Back
   press and an independent next press; neither should skip a navigation level
4. During licensed test playback, confirm Play, Pause, Play/Pause, fast-forward,
   rewind, Next and Previous on remotes exposing those optional keys. Confirm
   unsupported keys do not block mandatory D-pad, OK and Back
5. Check the on-screen keyboard, player dialogs, standby/network resume and
   signed update with the **same** verified WGT, and retain reproducible
   firmware, test steps and results without including account credentials

Node regression suites and the browser smoke are useful preconditions, not a
substitute for physical remote-button and AVPlay acceptance.
