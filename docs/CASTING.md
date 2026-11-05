# Chromecast and Cast-enabled Android TV sender

Starlit's Android sender is limited to **plain, unauthenticated, direct HTTPS**
MP4, WebM, HLS and DASH media. The Android player offers a Cast device picker
backed by the official Google Cast SDK (v23.0.0), Google's **Default Media
Receiver**, and a separate **Play on TV** action. Media is **not** transmitted
when the Cast information dialog opens or a device is merely discovered.

## How to use

1. Start an eligible video on Android and open **Casting availability**.
2. Confirm you have permission to watch the source on the receiver.
3. Choose a Chromecast or a television with built-in Google Cast using the
   Cast button. Device discovery and reconnection are managed by Google Play
   services, and require a compatible receiver and local network.
4. Select **Play on TV**. Only then is the media URL sent directly to the
   receiver. On successful load, the local player pauses. Pause/resume and
   disconnect are available when reopening the Cast dialog.

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
- A TV without built-in Google Cast does **not** become a Cast Connect receiver
  automatically. Supporting a separate Starlit Android TV receiver app requires
  Android TV Cast Connect integration and registration.
- Google Play services may be missing on some Android devices; the UI degrades
  to an unavailable message rather than crashing.
- iOS and desktop do not advertise sender functionality. **Play on Starlit PC**
  remains an alternative for separately authenticated, paired desktop playback.

## Verification gate

Run `./gradlew :composeApp:desktopTest`, the Android compile and lint tasks,
and the source eligibility unit tests. The Android sender must be tested on
actual Chromecast and Cast-enabled Android TV hardware, including end of
stream, remote pauses/resumes, app backgrounding, Google Play services absence,
reconnection, receiver errors and restricted media sources **before release**.
Source eligibility tests alone cannot establish device compatibility.

Official references: [Android Sender SDK](https://developers.google.com/cast/docs/android_sender/integrate),
[Default Media Receiver](https://developers.google.com/cast/docs/receiver_apps),
[Supported media](https://developers.google.com/cast/docs/media),
[Android TV Cast Connect](https://developers.google.com/cast/docs/android_tv_receiver/).
