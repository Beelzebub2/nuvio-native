# Runtime artifact review

The reviewed generated artifact is `media-http.cjs`, 449,891 bytes, SHA-256 `621d9cfbbe2dc33dc570ff04e49e1d8a3cb976e4f70e94146cbda76e74c2d699`. Two consecutive builds from the same integrity-pinned npm lock produced this exact digest. `build.cjs` rejects changed patch markers and rejects non-ES5 output. `runtime-manifest.json` records the engine source-file hashes, generated artifact digest, and 58 bundled package records including repeated transitive versions and newly authored replacements. All bundled third-party license texts are preserved in `THIRD-PARTY-LICENSES.txt`; supplemental license sources are retained separately.

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

The 48 MiB setting bounds the media cache, not total process RSS. Additional metadata, one pending piece, bounded peer/protocol buffers, HTTP responses and JavaScript bookkeeping require overhead. Limits are enforced before torrent-piece/file arrays are created, on metadata exchange, on peer wire message lengths, and on HTTP tracker response buffers. Peers/queued candidates, active HTTP connections, same-torrent pending creates, lifetime bad-peer blacklist entries and tracker scheduling intervals are bounded. Unsupported or oversized torrents fail visibly. The service keeps one active torrent and one streaming HTTP response.

## Verification

`test-evidence-node24.txt` and `test-evidence-node4.txt` retain the final successful output. `node test-all.cjs` passed on Windows Node 24.15.0 and in the official Linux `node:4.9.1` image (`sha256:fab73fccce5abc3fade13a99179884a306aa6c5292a2fc11833ee25ca15c1f85`) with the workspace mounted read-only. The generated bundle and tests run entirely in that container; no TV or production backend is involved.

The torrent test is a real localhost TCP peer exchanging generated metadata and pieces for a synthetic multifile torrent. It checks exact ranged bytes across file/piece boundaries, seeking, suffix/HEAD/416 responses, cache eviction and subsequent network refetch, cancellation, removal/recreation, and a delayed concurrent-create race. It intercepts persistent filesystem APIs and asserts zero calls. Network tests use a real native Agent and controlled DNS to reject rebinding/private destinations; synthetic response tests exercise byte caps, redirects and cancellation. A separate DHT/UDP test uses a socket fixture to prove the default DHT code path runs on Node 4, sends only public numeric UDP endpoints, rejects a private-resolving tracker, filters a private torrent peer, and closes discovery on removal. The UDP/DHT test is a controlled socket test, not a public-swarm interoperability claim.

Reproduction:

```sh
npm ci
npm run build
npm test
docker run --rm -v "$PWD:/work:ro" -w /work node:4.9.1 node test-all.cjs
```

The build tools require a current development Node/npm installation. Only the generated bundle runs in the legacy service. No claim of tested Samsung hardware, codec/AVPlay support, sustained throughput or production release readiness follows from these checks.
