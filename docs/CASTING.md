# Chromecast and Cast-enabled Android TV sender

Starlit's Android sender is limited to **plain, unauthenticated, direct HTTPS**
MP4, WebM, HLS and DASH media. The Android player offers a Cast device picker
backed by the official Google Cast SDK (v23.0.0), Google's **Default Media
Receiver**, and a separate **Play on TV** action. Media is **not** transmitted
when the Cast information dialog opens or a device is merely discovered.

## How to use

1. Start an eligible video on Android and open **Casting availability**.
2. Choose a Chromecast or a television with built-in Google Cast using the
   Cast button. Device discovery and reconnection are managed by Google Play
   services, and require a compatible receiver and local network.
3. Confirm the source is public, unsigned media you have permission to watch
   on this receiver. Consent resets if the source, receiver or session changes.
4. Select **Play on TV**. Only then is the media URL sent directly to the
   receiver. On successful load, the local player pauses. Pause/resume and
   disconnect are available when reopening the Cast dialog.
   Remote controls remain available if the local source subsequently becomes
   ineligible; that source cannot be loaded onto the receiver.

## Security and limitations

- No P2P, torrent, magnet, local/localhost, LAN, file or content URI sources.
- No auth headers, cookies, signed query strings or opaque source URLs.
- No proxy, transcoder, port-forwarding, private media relay, upload, token or
  local-file server.
- No automatic media transfer without an explicit rights acknowledgment, route
  selection and Play action.
- A filename check cannot grant media rights or prove a URL is publicly
  reachable. DNS changes, server redirects, receiver codec support, provider
  policy and DRM may still prevent playback; failed loads are reported.
- The Default Media Receiver is generic, not Starlit-branded. A branded web
  receiver requires separate registration, implementation and review.
  Google's Default Media Receiver needs no app registration or private receiver
  credentials and can also run on TVs with built-in Google Cast.
- A TV without built-in Google Cast does **not** become a Cast Connect receiver
  automatically. Supporting a separate Starlit Android TV receiver app requires
  Android TV Cast Connect integration and registration.
- Google Play services may be missing on some Android devices; the UI degrades
  to an unavailable message rather than crashing.
- The app currently targets Android 16 (API 36). Its existing `INTERNET`
  permission covers Cast networking; no additional broad LAN or location
  permission is requested for Cast. Before raising the target to API 37,
  adopt the system output switcher or the new local-network permission flow
  and test denial/revocation, following Android's current official guidance.
- Session and media listeners are removed when the panel closes. Pending
  callbacks are invalidated on source/receiver changes and disposal, so a late
  load response cannot pause a different local video. Closing the panel does
  not cancel playback already requested on the TV; use **Disconnect receiver**.
- iOS and desktop do not advertise sender functionality. **Play on Starlit PC**
  remains an alternative for separately authenticated, paired desktop playback.

## Verification gate

Run `./gradlew :composeApp:desktopTest`, the Android compile and lint tasks,
and the source eligibility unit tests. The Android sender must be tested on
actual Chromecast and Cast-enabled Android TV hardware, including end of
stream, remote pauses/resumes, app backgrounding, Google Play services absence,
reconnection, receiver errors and restricted media sources **before release**.
Source eligibility tests alone cannot establish device compatibility.

Audit on 2026-11-05: an authorized Android 16 phone with Google Play services
was inspected read-only. No Cast/TV receiver is available in this environment.
Physical discovery, playback, reconnect, end-of-stream and receiver-error
verification remain outstanding. Keep issue #77 open and the sender PR draft
until this hardware gate is satisfied; automated tests do not replace it.

Official references: [Android Sender SDK](https://developers.google.com/cast/docs/android_sender/integrate),
[Default Media Receiver](https://developers.google.com/cast/docs/web_receiver),
[Supported media](https://developers.google.com/cast/docs/media),
[Default receiver registration exception](https://developers.google.com/cast/docs/registration),
[Android network permissions](https://developer.android.com/privacy-and-security/local-network-permission),
[Android TV Cast Connect](https://developers.google.com/cast/docs/android_tv_receiver/).
