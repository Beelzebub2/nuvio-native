# Chromecast and Android TV casting — capability gate

Starlit does **not** currently have a production Google Cast sender or a
configured receiver. The Android player has a **Casting availability** action
that explains this limitation and classifies the current stream entirely
on-device. It **does not** scan for Cast devices or send playback requests.
It must not be described as functioning Chromecast support.

## What the compatibility check excludes

- Magnet/torrent, P2P, loopback, private-network and local file or content URI sources.
- Streams requiring private HTTP headers, cookies or signed/opaque URLs.
- Separate audio/video streams requiring remuxing or transcoding.
- Non-HTTPS media or URLs without an obvious direct MP4, WebM, HLS (.m3u8)
  or DASH (.mpd) format.

A passing format check does **not** grant rights, guarantee that the receiver
can fetch the URL, prove that a source is public, or permit publishing media.
It is informational only. No media URL, credential or local path is copied
or logged by the compatibility UI.

## Requirements before adding real casting

1. Choose and register a production Web Receiver in the **Google Cast SDK
   Developer Console**. The default media receiver is not suitable for a
   production branded application. A custom receiver may be needed for
   authorization, codecs, subtitles or a protected playback flow.
2. Implement the official Android Sender SDK, CastOptionsProvider,
   CastContext, device route UI, MediaInfo load, session lifecycle,
   disconnect and error UX. Observe Google Cast's device and media format
   constraints and Android platform/network permissions.
3. For Android TV native-app reception, add a proper Cast Connect receiver
   and its media session/receiver registration, not an arbitrary open port or
   shared localhost playback server.
4. Verify the source's provider/licensing/DRM terms and require explicit
   user selection. Never relay torrents, signed URLs or private tokens.
5. End-to-end test actual Chromecast and Android TV hardware before making
   the route button functional or claiming support.

The existing **Play on Starlit PC** authenticated LAN handoff is the safe
alternative for a paired desktop. It sends *content identifiers* only and
requires separate desktop confirmation and local source resolution.

Official specifications:
- [Android Sender SDK](https://developers.google.com/cast/docs/android_sender/integrate)
- [Receiver types and limitations](https://developers.google.com/cast/docs/overview)
- [Supported media](https://developers.google.com/cast/docs/media)
- [Android TV Cast Connect](https://developers.google.com/cast/docs/android_tv_receiver/)
