# Starlit TV for LG webOS

Starlit has a separately identified LG webOS TV package built from the shared Starlit TV client. The service implementation is fetched from the exact GPLv3-licensed [NuvioTVSmart revision](../UPSTREAM.json), never from a mutable upstream branch. Services and app identities use the `app.starlit.webos` namespace; they do not overwrite Nuvio TV.

**Status: developer build, not physically verified.** The CI-produced IPK must not be advertised as a supported release until its exact checksum passes the [physical-device gate](../release/webos-acceptance.json). The target is LG webOS 5 (2020) or newer; this is not proof of support for every TV or codec.

## Build a webOS package

Install Node 24, Git and the webOS CLI. From `tizen/`:

```sh
npm ci --ignore-scripts
npm install --no-save --ignore-scripts @webos-tools/cli@3.2.4
npm run lint
npm test
npm run package:webos
npm run verify:package:webos
```

The `fetch:webos` step downloads the license-preserving upstream service at the pinned commit, verifies the Git SHA, and caches it in the ignored `.cache/webos-upstream/` folder. The service includes a prebuilt bundled media runtime and LGPL FFmpeg/WebAssembly files. Review their security and license notices before distribution. Account sync is configured for Starlit, never Nuvio's login or cloud identity.

The result is `artifacts/Starlit-WebOS-<version>.ipk`. The CI artifact is marked **NOT DEVICE-VERIFIED**. Publishing to stable GitHub Releases is blocked pending physical-TV acceptance.

## Install on an LG TV

1. On the TV, install **Developer Mode** from LG Content Store, log into your LG developer account, enable Developer Mode and Key Server, and note the TV's LAN IP. The TV and computer must be on the same trusted LAN. Do not expose developer ports publicly.
2. Install LG's [webOS CLI](https://webostv.developer.lge.com/develop/tools/cli-installation) and configure the TV with `ares-setup-device`. Most consumer TVs use SSH port `9922` and user `prisoner`.
3. Run `ares-novacom --device <name> --getkey` and enter the Developer Mode passphrase.
4. From `tizen/`, install and launch the exact developer IPK:

```sh
ares-install --device <name> artifacts/Starlit-WebOS-0.1.0.ipk
ares-launch --device <name> app.starlit.webos
```

Replace the example TV name and package version. You can also use the [Starlit TV Installer](../../tv-installer/README.md) to pair and install a local IPK once its desktop build is available.

**Updates:** Prefer installing a newer IPK under the same app ID in place. Uninstalling may erase TV-local data; sideloaded IPKs are not silently auto-updated by webOS. The desktop installer can fetch a checksum-verified package only after an accepted stable release includes a matching webOS asset.

## Hardware acceptance

Verify remote-control navigation, in-place developer updates, MP4/HLS/DASH playback, torrent seek/cleanup, two-profile account sync, audio/subtitles, standby recovery, local proxy security, and license notices on actual webOS hardware. Record the TV model, firmware, reviewer, evidence for each check and the exact IPK SHA-256 in `release/webos-acceptance.json`. Tizen's WGT acceptance remains separate.

Passing browser tests or making an IPK is not proof of playback on a real LG TV.
