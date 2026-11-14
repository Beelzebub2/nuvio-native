# Runtime artifact review

The reviewed combined `media-http.cjs` ES5 bundle is 467,485 bytes, SHA-256 `d9afec449f111f86a90ec5cf8412d941965f5ead107a50094390680c38c0a9a3`, verified against the pinned generated runtime manifest. It includes optional USB cache, expanded metadata and bounded multi-range playback. `build.cjs` rejects changed dependency patch markers and non-ES5 output. `runtime-manifest.json` records engine source hashes and 58 bundled package records. All bundled third-party license texts are preserved in `THIRD-PARTY-LICENSES.txt`; supplemental license sources are retained separately. The artifact must be rebuilt and its digest reconfirmed after each source change.

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

With USB disabled, 48 MiB bounds completed-piece RAM cache, **not total process RSS**. With user-opted-in USB caching, 16 MiB bounds the RAM hot-piece cache; the measured disk quota is separately bounded and confined to an authenticated, Starlit-owned cache directory with cleanup and RAM fallback. Additional metadata, up to four simultaneous media ranges and their in-progress pieces, peer/protocol buffers, HTTP responses and JavaScript bookkeeping require memory outside these caches. Limits are enforced before torrent-piece/file arrays are created, on metadata exchange, peer wire message lengths and tracker response buffers: at most 1,024 files, 65,536 pieces, 2 MiB metadata exchange, 32 MiB piece size and 1 TiB torrent length, regardless of USB. Peers/queued candidates, active HTTP connections, media responses, pending creates, lifetime bad-peer blacklist entries and tracker intervals remain bounded. One torrent and at most four streaming HTTP responses may exist at once.

## Verification

`test-evidence-node24.txt` and `test-evidence-node4.txt` retain earlier baseline results, not proof of this combined runtime. An older metadata-limit build passed the official Linux `node:4.9.1` image (`sha256:fab73fccce5abc3fade13a99179884a306aa6c5292a2fc11833ee25ca15c1f85`) with a read-only mounted worktree. The USB-cache policy/benchmark/eviction/fallback test separately passed the checksum-verified Windows Node 4.9.1 runtime, but USB HTTP service and full Node 4 Windows suite encountered loopback `ECONNRESET`. **The combined USB, metadata and concurrent-range runtime has not yet passed Node 4 HTTP compatibility or retail-TV playback acceptance**.

The memory-only torrent test uses a real localhost TCP peer exchanging generated metadata and pieces for a synthetic multifile torrent. It verifies exact ranged bytes, simultaneous non-adjacent responses, bounded fifth-request HTTP 429, abort cleanup, seeking, HEAD/suffix/416, cache eviction/refetch, removal/recreation and a delayed concurrent-create race while asserting no persistent filesystem writes. Separate large-metadata and limits tests check elevated boundaries and byte counts. USB tests cover storage sizing, ownership, data verification, disk failure fallback, cleanup and authenticated controls. Network and DHT/UDP tests exercise private-address/rebinding rejection, tracker limits, public numeric destinations, blocked private trackers/peers and lifecycle cleanup using controlled fixtures. These are not public-swarm or hardware playback certifications.

Reproduction:

```sh
npm ci
npm run build
npm test
docker run --rm -v "$PWD:/work:ro" -w /work node:4.9.1 node test-all.cjs
```

The build tools require a current development Node/npm installation. Only the generated bundle runs in the legacy service. No claim of tested Samsung hardware, codec/AVPlay support, sustained throughput or production release readiness follows from these checks.
