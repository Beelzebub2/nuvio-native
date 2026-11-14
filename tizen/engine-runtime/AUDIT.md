# Runtime artifact review

The generated `media-http.cjs` bundle is built from integrity-pinned dependencies. `build.cjs` rejects changed patch markers and rejects non-ES5 output. `runtime-manifest.json` records engine source-file hashes, the current generated artifact SHA-256, and 58 bundled package records. All bundled third-party license texts are preserved in `THIRD-PARTY-LICENSES.txt`; supplemental license sources are retained separately. The older memory-only artifact digest is no longer the current artifact after the optional USB-cache implementation.

The installed development tree reports **11 npm advisory records: seven high and four low**. This is not an audit-zero dependency tree. The following disposition describes the generated artifact, not a claim that legacy Node 4 or the service has no vulnerabilities:

| Advisory/package family | Generated artifact disposition |
| --- | --- |
| `ws` and `simple-websocket` | Excluded. The unused WebSocket tracker dependency is removed before bundling; neither package appears in the artifact manifest. WebSocket tracker input is rejected. |
| `elliptic`, `crypto-browserify`, `browserify-sign`, `create-ecdh` | Development tool transitive dependencies only. Excluded from the runtime manifest and bundle. The runtime uses the platform's native `crypto` module. |
| `simple-get` sensitive-header redirect advisory | The original package implementation is replaced in full by the newly authored `tracker-http.cjs`. No request credentials are accepted for trackers. The replacement uses public-address DNS checks on actual native Agent connections, limits response size/time/redirects, and exposes cancellation. |
| `ip@1.1.5` incorrect public/private classification advisories | This legacy module remains bundled for the `ip-set` protocol blocklist's numeric conversion and subnet helpers. Its `isPublic`/`isPrivate` helpers do not enforce any network-security decision. Outbound TCP peers, UDP tracker endpoints, HTTP tracker connections and DHT endpoints use the independently tested native-IP/public-DNS policy. The advisory remains in the installed lock and requires continued review; the dependency is not represented as fixed. |
| `ip-set`, `bittorrent-tracker`, `torrent-discovery`, `torrent-stream` | These npm records inherit the leaf advisories above. The original modules remain only where listed in the runtime manifest, with explicit build patches and full notices. Their inherited npm audit records are not suppressed. |

`bitfield@0.1.0` has no license and its original implementation is entirely absent from the artifact. A newly authored MIT bitset supplies the required `buffer`, `get` and `set` contract. Small compatibility functions replace the original `hat`, `flatten`, `buffer-equal` and `simple-sha1` implementations. The legacy JavaScript uTP transport and filesystem chunk store are excluded. No Stremio server blob, EngineFS implementation, external media server, or VPS process is packaged.

The trusted control token must be supplied as exactly 64 lowercase hex characters before the server can start; missing/empty/malformed configuration throws. The token is never returned by HTTP. State changes and statistics require the header token. Playback tickets are independently random, scoped to the active info hash and selected file index, and invalidated when that torrent is removed. A trusted platform MessagePort bridge and exact app-ID checks are the caller's responsibility. Fixed readiness/range probes reveal no token or torrent state. Tizen hardware authentication and playback release gates remain unverified.

With USB cache disabled, 48 MiB bounds the RAM piece cache, not total process RSS. With USB enabled, 16 MiB bounds the RAM hot-piece cache and the measured, user-opted-in USB disk quota is independently bounded. Files are created exclusively under a verified Starlit-owned cache directory, with automatic cleanup and RAM fallback on I/O failures. Additional metadata, pending pieces, peer/protocol buffers, HTTP responses and JavaScript bookkeeping require overhead. The 256 KiB metadata, 16,384-piece, 256-file and 2 MiB/piece torrent safety bounds remain unchanged regardless of USB space. The service keeps one active torrent and one streaming HTTP response.

## Verification

`test-evidence-node24.txt` and `test-evidence-node4.txt` preserve the earlier memory-only baseline's successful output. For this change, the USB storage policy/benchmark/eviction/fallback test `test-usb-cache.cjs` passed on the official checksum-verified Windows Node 4.9.1 runtime. Its HTTP-service tests and the full Node 4 Windows suite encountered a loopback `ECONNRESET`, so full old-runtime HTTP compatibility is not established by this run. Re-test the service under Linux Node 4 and on physical Samsung TV hardware before release. Automated host tests are not TV hardware certification.

The memory-only torrent test is a real localhost TCP peer exchanging generated metadata and pieces for a synthetic multifile torrent. It checks exact ranged bytes across file/piece boundaries, seeking, suffix/HEAD/416 responses, cache eviction/refetch, cancellation, removal/recreation and a delayed concurrent-create race. It asserts zero filesystem writes in the memory-only configuration. Additional USB tests cover storage policy, ownership, file verification, eviction, disk failure fallback, cleanup and authenticated control endpoints. Network tests use a real native Agent and controlled DNS for private-address/rebinding rejection and tracker limits. DHT/UDP tests use socket fixtures rather than a public swarm.

Reproduction:

```sh
npm ci
npm run build
npm test
docker run --rm -v "$PWD:/work:ro" -w /work node:4.9.1 node test-all.cjs
```

The build tools require a current development Node/npm installation. Only the generated bundle runs in the legacy service. No claim of tested Samsung hardware, codec/AVPlay support, sustained throughput or production release readiness follows from these checks.
