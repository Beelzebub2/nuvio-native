# Starlit TV Installer — Samsung Tizen and LG webOS

Windows desktop installer for managing **Starlit TV on both Samsung Tizen and LG webOS**. It installs only Starlit apps and never silently removes or replaces another TV app.

**Status: developer preview.** CI builds and tests are available, but both platforms still require actual-TV acceptance before the installer is advertised as a stable end-user release.

## Run

Requires Node.js 24 and a Windows PC on the same private LAN as the TV. Run from the tv-installer directory:

    npm ci
    npm run lint
    npm test
    npm start

Switch between Samsung and LG in the same window. Each platform supports a validated local package or a checksum-verified official Starlit release asset; launching and uninstalling target only Starlit. Installing an update does not first uninstall the existing version.

## Samsung Tizen (4.0 and newer)

1. On the TV open Apps and enter **12345** with the remote. Enable Developer Mode, enter the computer's LAN IP as **Host PC IP** and restart the TV. The installer displays the computer's private IP addresses.
2. Select **Samsung Tizen** in the Starlit installer, enter the TV's private IP and select **Check Samsung TV**.
3. Select a Starlit WGT file. The app ID must be **StarlitTV1.StarlitTV** with package ID **StarlitTV1**.
4. Choose the signing method. **Samsung account certificate** is recommended and may open a browser for initial Samsung developer login. **Tizen public certificate** avoids the Samsung account but can be refused by some firmware.
5. Select **Install or update Starlit**. The WGT is signed for the selected TV through Developer Mode, preserving the application identity and TV-local data when compatible. Keep the **same author certificate** for future updates.

Samsung support runs the official independent [Apps2Samsung CLI](https://github.com/Apps2Samsung/Apps2Samsung) **v3.3.0**, a GPL-3.0-only tool. On first Samsung action, Starlit downloads that exact release from GitHub, verifies the ZIP and executable SHA-256, and caches the CLI privately. **Starlit does not redistribute or modify the CLI.** Its GPL source, license and third-party notices are available [at v3.3.0](https://github.com/Apps2Samsung/Apps2Samsung/tree/v3.3.0). No Tizen Studio installation is needed.

The Starlit WGT is intentionally unsigned when built by CI; signing is per TV. If Developer Mode, Host PC IP or certificate checks fail, the installer provides recovery guidance and **does not override safety checks or uninstall the old app**. See the [Samsung developer installation guide](../tizen/docs/INSTALL.md).

## LG webOS (5.0 and newer)

1. Install LG's **Developer Mode** app on the TV and enable Developer Mode and Key Server.
2. Select **LG webOS**, enter the TV IP, saved device name and Developer Mode passphrase, then click **Pair with LG TV**.
3. Select a Starlit IPK and click **Install or update Starlit**. Its app ID is **app.starlit.webos**, distinct from Samsung and other TV apps.

LG uses the official pinned webOS CLI. Starlit does not save the Key Server passphrase. For details see the [LG webOS guide](../tizen/docs/WEBOS.md).

## Downloading and updating

LG uses **Starlit-WebOS-version.ipk** with **SHA256SUMS-WebOS**; Samsung uses **Starlit-Tizen-version.wgt** with **SHA256SUMS-Tizen**. The selected stable release must contain both the correctly named package and its matching SHA-256 file, be hosted by an allowed official Starlit repository URL and match Starlit's app identity and version. Otherwise the installer refuses to install and lets the user choose a local developer package.

Updates are **manual** in the desktop installer. LG sideloads do not automatically update. Samsung Store auto-update metadata applies only after Seller Office distribution; it does not silently update Developer Mode sideloads. **Uninstall** requires explicit confirmation and may erase local app data.

## Windows portable build

    npm ci
    npm run lint
    npm test
    npm run dist:win

The resulting EXE is in the dist directory. PR CI also publishes an **unverified** development artifact. Electron runs the UI in a sandbox with context isolation and denied external navigation; temporary package downloads are removed after use while user-selected local files and legitimate saved signing credentials remain intact.

**Hardware validation still needed:** test LG and Samsung TV connections, signing, installation, in-place updates, D-pad navigation, media playback, audio/subtitles, account sync and standby recovery. Record the actual TV model, firmware, results and exact package checksum; CI success is not a device compatibility guarantee.
