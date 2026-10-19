# Starlit

Starlit is made by [Beelzebub2](https://github.com/Beelzebub2) and based on Nuvio by Tapframe and friends. It was called Nuvio Native up to version 1.0.14.

Source: https://github.com/Beelzebub2/nuvio-native (private)

Upstream baseline: NuvioMedia/NuvioDesktop Dev, commit 25390defed025a871f6a3239427b4507f2fafb35. GPLv3 and upstream copyright notices are retained.

## Install and update

Download the latest `Starlit-Windows-x64-*.exe` from your repository's Releases and run the installer. It bundles its Java runtime and native player. It installs as Starlit for the current Windows user, with a separate installer identity from upstream Nuvio.

The installer is Starlit's own setup (`composeApp/src/desktopMain/installer/windows`), not the stock jpackage one. It installs to `%LOCALAPPDATA%\Starlit` without administrator rights, lets you change the folder and choose Start menu and desktop shortcuts, and adds Starlit to Installed apps with an uninstaller. Running a newer installer updates the existing copy in place, including Nuvio Native copies from before the rename and from the earlier MSI-based installers, which move to the Starlit folder and lose their old shortcuts; the app is closed first if it is open, and your data in `%APPDATA%\NuvioNative` is kept. Uninstalling keeps that data unless you choose to delete it. The installer needs the Microsoft Edge WebView2 Runtime, which the player already uses. For scripted installs, run it with `--silent` (optionally `--dir <folder>`, `--no-desktop-shortcut`, `--launch`); the uninstaller accepts `--uninstall --silent`. Setup writes a log to `%TEMP%\Starlit Setup.log`.

Private update access uses your existing GitHub CLI login. On a new computer, install GitHub CLI and run `gh auth login` once with an account that can read this repository. Open Settings → About to check for updates. Updates also check at startup and every six hours. Installation requires choosing Install and restarting the app; catalog refreshes do not require restarting.

An optional `NUVIO_GITHUB_TOKEN` environment variable overrides the CLI credential. Use a fine-grained token restricted to this repository with Contents: read. Never put a token in source, build settings, release assets, command arguments, or logs. GitHub CLI manages its credential; the app reads it only into memory. HTTP redirects are followed without forwarding the credential. Every private installer must have GitHub's SHA-256 digest and pass verification before installation.

Personal builds work as a local guest with playback addons. Official account credentials are not available in this clone; configure your own compatible server if you need account sync.

## Built-in Airing Now

Use View all or click the Airing Now heading to open a full scrollable poster grid. The Home row supports drag scrolling, previous/next buttons, and a horizontal scrollbar. Both views keep the source, time-window filters, settings, and automatic refresh.

## Watch Later and navigation

Middle-click anywhere on a title card, including its image, title, or labels, to toggle Watch Later without opening details or playing it. This works in Home shelves, Airing Now, search results, Discover grids, Library, collection grids, recommendations, Watch Later, Continue Watching (all three layouts), and the featured Home title. The shortcut works with hover previews enabled or disabled, and also inside an open preview. A brief message confirms saving or removal; catalog cards show a bookmark badge. The hover preview also has a Watch Later button. Watch Later is a separate local list for each profile, persists across app restarts, and preserves native anime identifiers; it does not change your Library or connected tracking lists. Click Watch Later in the top navigation to open its full grid, then middle-click a saved card again to remove it.

Version 1.0.1 moves desktop navigation to a horizontal top bar once for each profile. You can subsequently choose a sidebar in Settings → Appearance, and that preference persists.

Desktop notifications appear in the bottom-right corner with a bounded width and up to three lines, keeping the top navigation visible when saving titles with long names.

## Airing schedules

Airing Now appears on Home without installing a schedule addon. It has Today, Next 24 hours, Recent, and All views, groups multiple episodes of the same anime, and shows episode numbers and times. Settings select Broadcast (AniList), Sub or Dub (Tsuzuki), an IANA time zone (default Europe/Lisbon), 1–7 history days, and estimated times. Clicking a poster opens native episode details and your existing stream addons. Exact AniList→Kitsu/MAL mappings preserve anime episode numbering; TVDB season numbers cannot overwrite it.

The app reclassifies schedules every minute while visible and focused, and refreshes provider data every five minutes or when you click Refresh. It retains a local snapshot across source failures and restarts. Broadcast schedules use AniList, which primarily guarantees future data; saved observations supplement recent history. Air times are schedules, not proof that a playable release exists.

Provider calls first use `https://nuvio-native-gateway.cloudflaredomainrrm.workers.dev`, the fixed read-only gateway in `gateway/`. It caches successful provider responses, rejects arbitrary URLs/GraphQL, and requires no account credential in the app. If a provider blocks Cloudflare egress, the client falls back to a direct request. Both paths may fail for a provider with an exhausted quota. The initial live check found Cloudflare requests blocked by AniList and ani.zip, while direct AniList and the independent Fribb ID mapping list worked. Those fallbacks are deliberate. Existing Airing Now addon profiles are separate from these native settings. The old addon's Home rows are suppressed in this desktop build to avoid duplicate schedules; its saved configuration remains available in Addons.

`NUVIO_AIRING_GATEWAY` can override the HTTPS gateway URL; an empty value selects direct requests. Redeploy with `cd gateway`, `npm ci`, `npm test`, `npm run deploy:dry`, then `npm run deploy` using your Wrangler login. The gateway is deployed separately from desktop tag releases. It uses the Workers Cache API without KV, R2, or other provisioned storage.

## Local data

On the first Windows launch, local addons, profiles, watch history, and display/player/torrent preferences are copied from `%APPDATA%/Nuvio` into `%APPDATA%/NuvioNative`. The original files remain intact. Cloud and integration credentials are not imported; the build starts as a local guest unless you configure a compatible server. Subsequent runs keep the private build's own data.

## Release a new version

Change `composeApp/Configuration/DesktopVersion.properties` (VERSION_NAME and increasing VERSION_CODE), commit and push, then push the matching `vMAJOR.MINOR.PATCH` tag. `.github/workflows/windows-release.yml` validates the version, fetches the pinned native binaries from the original public LFS store, runs desktop tests, builds the installer .exe, checks a silent install and uninstall of it, generates SHA256SUMS.txt, and publishes a private GitHub release. No upstream service secrets are required. GitHub-hosted runner availability and private Actions allowances apply.

Local build: use JDK 21, Visual Studio C++ Build Tools, and the Microsoft.Web.WebView2 NuGet SDK. Run `gradlew.bat :composeApp:desktopTest :composeApp:packageReleaseInstaller --no-configuration-cache`; the installer is written to `composeApp/build/compose/binaries/main-release/installer/`. Set `-Pnuvio.webview2.dir=...` if needed. Native files must be materialized from upstream Git LFS first.

To try the installer without touching an existing install, run it with `--sandbox`: it uses a separate "Starlit (setup test)" name, folder, shortcuts and Installed apps entry, and never removes the earlier MSI install or your data. To work on the setup screens in a browser, open `ui/index.html?preview` (add `&mode=update`, `&mode=uninstall` or `&step=progress|done|error`) from a folder that also holds `logo.png` and `fonts/`.

## P2P decision

Compared [TorrServer](https://github.com/YouROK/TorrServer), its [anacrolix engine](https://github.com/anacrolix/torrent), [libtorrent](https://www.libtorrent.org/streaming.html), and [qBittorrent](https://github.com/qbittorrent/qBittorrent). libtorrent is a mature alternative, but a new Windows bridge and a streaming adapter would need their own maintenance and seek/buffering tests. qBittorrent's download API alone is not a drop-in HTTP streaming backend. This release retains the already bundled, pinned TorrServer engine and fixes integration faults found in this desktop client: ignored preferences, fixed-port collisions, short metadata waits, failed-stream cleanup, and concurrent starts.

The cache setting NONE means no persistent disk cache; the engine still needs a bounded memory buffer. SOFT/BALANCED/FAST use 128/256/512 MiB memory-only buffering and 40/100/200 connections respectively. Disk mode honors 2/5/10 GiB, removes data when dropping the stream, and uses a small preload fraction to avoid waiting for most of a large cache before playback. Poor peer availability or network restrictions can still prevent playback.

Research references: [Compose native packaging](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html), [GitHub authenticated release assets](https://docs.github.com/en/rest/releases/assets), [TorrServer settings source](https://github.com/YouROK/TorrServer/blob/master/server/settings/btsets.go).

Schedule references: [AniList API](https://docs.anilist.co/guide/graphql/), [airing data caveat](https://docs.anilist.co/reference/object/airingschedule), [Fribb ID mappings](https://github.com/Fribb/anime-lists), [Workers cache](https://developers.cloudflare.com/workers/runtime-apis/cache/).
