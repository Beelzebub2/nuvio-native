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

- Try QR or the current PC IP address first. Optional **Trouble connecting?**
  help inspects Windows rules only when requested. There is no Starlit startup
  firewall modal or automatic administrator request. Windows may still display
  its own normal first-use firewall alert when an application starts listening.
- Evaluate TCP session permission separately from UDP discovery permission.
  A UDP restriction does not label an allowed TCP session as blocked.
- Probe direct and remembered IP addresses even if UDP cannot start or send.
  A stopped UDP listener does not stop the TCP listener or Bluetooth. A failed
  reply to one discovery packet does not disable later replies.
- Treat rule inspection as advice. A timeout can also mean an incorrect IP,
  guest Wi-Fi isolation, VPN routing or other security software. A live approved
  connection hides irrelevant LAN warnings; a Bluetooth connection is not
  presented as proof that TCP is reachable.
- Keep administrator permission available as an explicit user action. The
  installed rules remain limited to Starlit's executable, the two ports,
  `LocalSubnet`, and Private/Domain profiles. Starlit does not switch profiles,
  disable the firewall, open router ports or grant a Public-profile exception.
  Only mark a home network Private if you trust it. Organization policies can
  still prevent access.

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

During investigation, a physical Android phone and this Windows PC were on the
same private IPv4 subnet. The phone's TCP probe to a new wildcard-bound Java
listener timed out. That demonstrates that wildcard binding alone did not make
that endpoint reachable in this environment; it does not uniquely identify a
firewall as the cause. No firewall or network profile was changed. The Starlit
phone app was not installed, so a physical phone-app pairing/command session
has not been verified. Automated socket tests establish protocol behavior, not
universal reachability across routers, profiles and security products.
