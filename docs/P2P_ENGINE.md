# P2P engine behavior and verification

Desktop playback and offline downloads use the bundled TorrServer MatriX.142.
Android and iOS Full use Nuvio Engine; the iOS App Store distribution keeps P2P disabled.

## Desktop startup and discovery

New app-owned runtime directories receive the final cache/upload/profile settings
before TorrServer starts. The seed retains the bundled engine's nonzero defaults;
existing JSON preferences, unknown fields and database-backed settings are preserved.
Invalid JSON is still quarantined without replacing the original bytes.

The running settings comparison is idempotent. Unsupported `EnableBonjour` is only
sent when the engine reports that field. Previously, MatriX.142 discarded it, so
every comparison requested a client rebuild. That rebuild closes peer sockets and
includes an upstream two-second delay. Loopback API clients explicitly use HTTP/1.1.

The app supplies its explicit fallback trackers alongside provider trackers for
both playback and downloads. `RetrackersMode=0` avoids the bundled engine's
synchronous, unbounded remote tracker-list fetch during torrent addition. DHT,
PEX, upload consent and profile connection/cache budgets retain their existing behavior.

During metadata discovery, peer counts and rates reach the Connecting state.
Polling uses 250 ms intervals for the first five seconds, then one-second intervals.
Replacing an obsolete metadata request reuses a fully prepared process and its
swarm. Caller cancellation, explicit stop and incomplete process startup still clean up.

`PreloadCache` is a percentage of the engine cache, not the media file. The app's
`/stream?...&play` route does not request TorrServer's explicit `preload` operation;
that setting alone is not evidence of a contiguous startup buffer. Selected-file
buffer/verified/delivered counters remain unknown on desktop; the player measures
its own demuxer buffer. Do not treat torrent-wide cache occupancy as playable bytes.

## Mobile request lifecycle

Android and iOS Full cancel obsolete starts before those requests wait behind
native-engine mutexes. Stop/shutdown also revoke queued requests. Cleanup for an
older request cannot clear the cancellation handle of its replacement. Native
stream selection, cache budgets and the engine's piece scheduler are unchanged.

## Validation

On Windows with JDK 21:

```powershell
./gradlew.bat :composeApp:desktopTest --tests 'com.nuvio.app.features.p2p.*' --tests '*DesktopDownloadsTest*' --tests '*DownloadTorrentResourcesTest*'
```

The bundled-engine integration fixture generates its own private torrent and
serves pieces through a loopback BitTorrent peer and tracker. It verifies exact
HTTP range bytes for the beginning, tail, middle seek and rewind. Fixture DHT,
PEX, LPD and port forwarding are disabled. This validates engine/protocol
integration; it does not measure internet swarm throughput or actual video decode.

PR CI runs the Windows tests with the checksum-pinned bundled executable, Android
Full/Play Store checks, and both iOS App Store and Full Kotlin compilations. Physical
phone testing, long playback under memory pressure, real NAT/VPN transitions and
public-swarm bitrate/seek latency still require device/network measurements.
