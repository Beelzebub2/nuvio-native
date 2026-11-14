# Smart TV remote (Android phone)

Starlit's **Settings → Remote control → TV** tab provides an independent TV remote alongside the existing PC controller. It sends the same navigation and media keys as a TV remote, so it can operate the Starlit TV app without introducing a new listener in the Tizen/webOS app.

## Supported targets and commands

| Target | Connection | Supported controls |
| --- | --- | --- |
| Samsung Tizen TVs with the Samsung network remote API | WSS on TV port 8002, on-screen Allow prompt | D-pad, OK, Back, Home, play/pause, rewind/fast-forward, volume and mute |
| LG webOS TVs with SSAP | WSS on TV port 3001, on-screen pairing prompt; TV-provided pointer socket for navigation | D-pad, OK, Back, Home, media controls, volume and mute |

The Android implementation requires the TV and phone to be reachable on the same private LAN. SSDP discovery is best effort and can be blocked by guest-Wi-Fi isolation, TV settings, multicast filtering or standby; manually entering the TV's private IPv4 address is supported. TV firmware may reject individual keys or disable remote access.

This is a **TV key remote**, not the Starlit desktop's encrypted app-to-app remote protocol. It does not yet expose Starlit's TV-specific source picker, playback transfer, skip-intro toggle, subtitle/track selection, downloads or current playback state to the phone. It does not support waking a sleeping TV.

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

Hardware results must be recorded separately; a build or emulator test cannot prove physical Samsung/LG compatibility. The new TV transport is implemented for **Android phones only**; iOS/desktop present an explicit unsupported message rather than pretending to work.
