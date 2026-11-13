# Starlit TV Installer — LG webOS

Starlit-branded desktop installer for installing, updating, launching and explicitly uninstalling **only** `app.starlit.webos` on LG webOS TVs. No Nuvio account or third-party installer code is used.

**Status: development / physical LG TV test outstanding.** Builds from this PR are for validation. Stable releases of the LG app and installer are gated on recorded hardware acceptance.

## Run

Requires Node.js 24 and an LG webOS 5+ TV on the same private LAN. On the TV, install **Developer Mode**, sign in with an LG developer account, enable **Dev Mode Status** and **Key Server**, and copy its IP/passphrase.

```sh
cd tv-installer
npm install
npm run lint
npm test
npm start
```

The GUI lets you pair the TV using LG's official `@webos-tools/cli`, select a local Starlit `.ipk` and install/update it in place, launch Starlit, or uninstall it with confirmation. The pairing passphrase is never saved by Starlit; LG's CLI saves the SSH key for later installs. For an unpaired TV, enter the Key Server passphrase and choose **Pair with LG TV**.

The **Latest stable release** option only accepts an accepted Starlit `Starlit-WebOS-<version>.ipk` asset with the matching `SHA256SUMS-WebOS` file, downloads it from the official GitHub repository, verifies SHA-256 and confirms the app ID. Until hardware acceptance, choose a local test package instead.

## Windows portable installer

```sh
npm run dist:win
```

The portable executable is generated under `dist/`. GitHub Actions also publishes an **unverified** development installer artifact for pull-request testing. Windows and actual LG device pairing must still be tested manually.

## Safety and provenance

- Installs target `app.starlit.webos`, not Nuvio TV.
- Targets only private LAN IPv4 addresses, never public developer ports.
- Local `.ipk` packages are parsed and checked for the expected app ID, version and two companion services.
- Downloaded packages have strict source, size and SHA-256 checks; temporary downloads are deleted after use.
- Electron's renderer is sandboxed, uses context isolation and cannot navigate to external sites or run arbitrary commands.
- Uninstall is explicit and may erase TV-local application data. In-place update does not uninstall first.

See [LG webOS packaging and hardware acceptance](../tizen/docs/WEBOS.md).
