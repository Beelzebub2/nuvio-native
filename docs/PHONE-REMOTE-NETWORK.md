# Phone remote: local connectivity and permissions

Starlit already listens on the wildcard address, equivalent to an IPv4 server
bound to `0.0.0.0`. TCP **47789** carries the encrypted remote session. UDP
**47788** helps automatic discovery; QR pairing and direct IP connections do not
require a UDP reply. LAN connections are restricted to private IPv4 peers on an
actual local interface's subnet.

Binding a web server to `0.0.0.0` does not bypass a firewall or make an isolated
Wi-Fi client reachable. A web server that already works may have permission for
its executable, port and network profile that another application does not.
Windows evaluates those properties separately. Microsoft's [firewall guidance](https://learn.microsoft.com/en-us/windows/security/operating-system-security/network-security/windows-firewall/rules)
recommends narrow application/port rules, suitable profiles and `LocalSubnet`
scope for local sharing. Finding no matching allow rule is not an end-to-end
test proving the phone is blocked.

## What the implementation does

- Try QR or the current PC IP address first. The Phone remote window checks
  Windows firewall rules while the listener is ready. Inaccessible profiles
  are shown automatically; **Trouble connecting?** still offers manual checks.
  There is no startup firewall modal or automatic administrator request.
- Evaluate TCP session permission separately from UDP discovery permission.
  A UDP restriction does not label an allowed TCP session as blocked.
- Probe direct and remembered IP addresses even if UDP cannot start or send.
  A stopped UDP listener does not stop the TCP listener or Bluetooth. A failed
  reply to one discovery packet does not disable later replies.
- Treat rule inspection as advice. A timeout can also mean an incorrect IP,
  guest Wi-Fi isolation, VPN routing or other security software. A live approved
  connection hides irrelevant LAN warnings; a Bluetooth connection is not
  presented as proof that TCP is reachable.
- Keep administrator permission available as an explicit user action.
  Private/Domain rules remain limited to Starlit's executable, the two ports
  and `LocalSubnet`. For a trusted Ethernet connection Windows still marks
  Public, **Allow on wired Ethernet** can install separate Public-profile
  rules only for Starlit's executable, the same TCP/UDP ports and `LocalSubnet`
  on **Lan** interface types. Windows requests administrator confirmation
  for this optional exception; it does not grant access on Public Wi-Fi.
  Starlit does not switch profiles, disable the firewall or open router ports.
  Only mark a home network Private if you trust it. Organization policies
  can still prevent access.
- On PCs with several active adapters (including VMware, Hyper-V or VPN),
  present physical Ethernet/Wi-Fi addresses first when choosing the QR
  discovery hint. Direct subnet TCP searches also prioritize those adapters.

Kotlin's [coroutine exception documentation](https://kotlinlang.org/docs/exception-handling.html)
explains that an unhandled child exception other than cancellation cancels its
parent. Handling optional UDP failures inside that worker, while rethrowing
cancellation, keeps the healthy TCP listener alive without preventing shutdown.

KDE Connect makes the same useful distinction: its [official troubleshooting
guide](https://wikisandbox.kde.org/KDEConnect/en) describes UDP broadcast discovery
and adding a device by IP when broadcast is unavailable, such as on a TUN VPN.
Microsoft's [TCP troubleshooting guidance](https://learn.microsoft.com/en-us/troubleshoot/windows-server/networking/troubleshoot-tcp-ip-communication-guidance)
likewise recommends checking the actual application port; ping alone does not
establish application connectivity.

Changing this native protocol to an HTTP or WebSocket server would not remove
those network constraints. OWASP's [WebSocket security guidance](https://cheatsheetseries.owasp.org/cheatsheets/WebSocket_Security_Cheat_Sheet.html)
also requires explicit authentication, authorization, input bounds and, for
browser clients, origin checks. The existing encrypted native protocol retains
two-device code approval, same-account/server verification, token revalidation,
bounded frames and command validation. A discovered address or matching
firewall rule never grants control.

The Android app currently compiles against SDK 37 and targets SDK 36. Android's
[local-network permission guidance](https://developer.android.com/privacy-and-security/local-network-permission)
requires `ACCESS_LOCAL_NETWORK` for apps targeting SDK 37 or later; the current
target uses `INTERNET` implicitly for this access. This fix does not add an
unnecessary runtime permission request.

## Verification and limits

Regression tests exercise the production TCP listener with UDP unavailable,
the committed pairing handshake, direct discovery with UDP denied, and socket
peers that require both approvals and account verification before an encrypted
command/response round trip. They also cover rule inspection, UI recovery paths,
pairing/security failures, command bounds and QR validation. Windows Settings
launching was checked against the actual Network & internet page.

### Physical OPPO-to-Windows audit, 2026-11-07

- A USB-debugging-authorized OPPO CPH2639 (Android 16) was connected to the
  same local IPv4 `/24` as the Windows Starlit 1.12.3 desktop. ICMP ping worked
  in both directions, showing that the endpoints have an IP route.
- The official signed Starlit 1.12.3 arm64 Android APK was downloaded, verified
  against its published SHA-256 checksum, installed and opened on the OPPO.
  The **PC controller** page rendered and the discovery options were accessible.
- A direct TCP probe from the OPPO to the PC's remote port **47789** timed out.
  Windows marked its Ethernet connection **Public** while its Starlit inbound
  TCP/UDP firewall allow rules applied only to **Private/Domain**, making the
  current firewall profile incompatible with those rules. During later
  observations the Starlit TCP listener was also absent, so there was no
  available listener to probe in those moments. A timeout alone cannot
  distinguish these failure modes.
- The fresh OPPO installation remained in **guest mode**. The controller
  correctly explained that both apps must be signed into the same Starlit
  account/server before secure discovery or pairing. Neither a six-digit-code
  handshake nor playback/download commands were exercised on the real devices.
- On Android 16, the system's Bluetooth **Nearby devices** permission prompt
  appeared when starting Bluetooth discovery. The two requested Bluetooth
  permissions were granted. Guest mode still prevented an authenticated scan.
  Expanding the manual IP entry and focusing its text field showed that the
  on-screen keyboard could obscure the action below it. The controller now
  applies IME-aware padding and a URI-friendly keyboard configuration; this
  layout change requires rechecking with a freshly built APK.
- No account was selected from the phone's Google sign-in chooser. The Windows
  firewall, network profile, account data and protected Bluetooth keys were
  not changed as part of this test. Bluetooth pairing was not tested on the
  physical OPPO; transport/command scenarios are separately covered by
  automated Kotlin tests.

**End-to-end acceptance still needed:** On a *trusted* home/office network,
either switch Windows Ethernet to **Private** in Windows Settings or explicitly
choose the Starlit **Allow on wired Ethernet** firewall option (administrator
approval required). Sign in to the same Starlit account and
server on both devices, open the desktop Phone remote window and confirm its
listener is ready, then retry LAN discovery/direct IP and fresh two-device
pairing. Verify play/pause, fullscreen, skip intro, next episode, seeking,
volume, disconnect/reconnect, and that a Bluetooth reconnect only skips a code
when both sides retain their protected trust keys. Check the Android UI during
search, pairing, disconnection and returning from the background. Also verify
Public Wi-Fi remains inaccessible to the LAN remote, and that on a mixed
virtual/physical-adapter PC the QR defaults to the real wired LAN IP.
