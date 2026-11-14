# Smart TV remote (Android phone)

Starlit's **Settings → Remote control → TV** tab provides an independent TV remote alongside the existing PC controller. It sends the same navigation and media keys as a TV remote, so it can operate the Starlit TV app without introducing a new listener in the Tizen/webOS app.

## Supported targets and commands

| Target | Connection | Supported controls |
| --- | --- | --- |
| Samsung Tizen TVs with the Samsung network remote API | WSS on TV port 8002, on-screen Allow prompt | D-pad, OK, Back, Home, play/pause, rewind/fast-forward, volume and mute |
| LG webOS TVs with SSAP | WSS on TV port 3001, on-screen pairing prompt; TV-provided pointer socket for navigation | D-pad, OK, Back, Home, media controls, volume and mute |

The Android implementation requires the TV and phone to be reachable on the same private LAN. SSDP discovery is best effort and can be blocked by guest-Wi-Fi isolation, TV settings, multicast filtering or standby; manually entering the TV's private IPv4 address is supported. TV firmware may reject individual keys or disable remote access.

This is a **TV key remote**, independent of Starlit's app-to-app playback handoff. The separate Starlit TV connection below adds initial source selection; neither protocol yet exposes TV-specific skip-intro, subtitle/track selection, downloads or current playback state to the phone. It does not support waking a sleeping TV.

## Send a title from the phone to Starlit TV

On the TV app, open **Settings → Manage with phone**; the TV displays a short pairing code. On an Android phone open **Settings → Remote control → TV**, enter this code and match/approve the verification code **on the TV**. Once connected, select a movie or episode in the phone app and choose **Play on device**. If a PC and TV are both connected, choose the intended destination. The TV searches its own add-ons and enabled providers, and the phone shows **TV-provided source descriptions**; tapping one makes the TV play that exact source with its own resolver, P2P/debrid support and player. The TV's approved session stays active while it navigates into the stream browser and player, but expires/revokes by policy. The vendor TV key remote continues to work separately.

No raw streaming URLs, torrent hashes, private headers, DRM material, passwords, or provider credentials are sent to the remote service. A session tracks only title/episode identifiers, opaque source indexes and sanitized quality/provider labels. Stale selections, mismatched request IDs and TV profile changes are rejected.

**Deployment dependency:** the Starlit-managed phone setup relay under `https://starlit.rrmtools.uk/tv/` is required for this app-to-app pairing and handoff. Its source and loopback-only deployment instructions are in `tv-setup-server/README.md`. The TV app and Android client alone are not sufficient. Test the HTTPS route and hardware before releasing or claiming this works on a production device.

## Pairing and security

1. On the phone, open **Settings → Remote control → TV**.
2. Select the discovered Samsung/LG TV or enter the IPv4 address manually and select its platform.
3. Approve the connection on the TV. Reject any unexpected pairing request.
4. The phone saves the vendor pairing token and the approved TV certificate fingerprint encrypted using Android Keystore.
5. Reconnection refuses a changed certificate until the user explicitly forgets that TV and pairs again. Forgetting removes the saved token/fingerprint.

Only strict numeric RFC1918 IPv4 addresses can be used as targets; public IPs, DNS names, URL paths and loopback addresses are rejected. TV main pairing uses WSS; some LG TVs supply an unencrypted local pointer channel on port 3000, which is used only for navigation keys and does not change Starlit's global Android cleartext policy. Avoid pairing on untrusted shared networks.

## Validation before release

- Build Android and run the common desktop tests (private IPv4 policy and command mappings).
- On a physical Samsung Tizen 5 TV, test fresh approval, saved-token reconnect, all remote keys, denial, wrong address and changed-certificate rejection.
- On a physical LG webOS TV, test registration, reconnect, WSS SSAP commands, pointer URL and direction key navigation, mute and volume.
- Verify discovery across Wi-Fi and Ethernet; test manual-IP fallback under multicast filtering.
- Ensure PC Wi-Fi/Bluetooth remote and background reconnect are unchanged.
- Test the end-to-end flow on TV hardware: phone pairing and approval, open a film on phone, source list loaded by TV, choose source on phone, TV starts playback, navigation back, request replacement, cancel, session expiry and switch TV profile.
- Verify the relay at `/tv/healthz` on the production hostname returns 200 before shipping; no real playback URLs should appear in the relay logs, in phone API responses or in the stored session state.

Hardware results must be recorded separately; a build or emulator test cannot prove physical Samsung/LG compatibility. The new TV transport is implemented for **Android phones only**; iOS/desktop present an explicit unsupported message rather than pretending to work.
