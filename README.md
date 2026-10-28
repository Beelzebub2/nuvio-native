<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="branding/readme/starlit-wordmark-dark.png">
  <img src="branding/readme/starlit-wordmark-light.png" alt="Starlit" width="340">
</picture>

<h3>Anime and TV on your desktop: streams, schedules and offline downloads in one app.</h3>

Made by [Beelzebub2](https://github.com/Beelzebub2) · Based on [Nuvio](https://github.com/NuvioMedia/NuvioDesktop) by Tapframe and friends

[![Windows 10 and 11](https://img.shields.io/badge/Windows-10%20%7C%2011-3965D4?style=flat-square)](https://github.com/Beelzebub2/nuvio-native/releases/latest)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-B250EF?style=flat-square)](LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-45D6EB?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-3965D4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/compose-multiplatform/)

**[Download for Windows](https://github.com/Beelzebub2/nuvio-native/releases/latest)** · [What's new](docs/RELEASE-NOTES.md) · [Install and build guide](docs/PRIVATE-WINDOWS-BUILD.md)

</div>

> [!NOTE]
> Starlit is a personal build of Nuvio, maintained by Beelzebub2. It isn't an official Nuvio release, so please report Starlit problems here rather than to the Nuvio team. Up to version 1.0.14 it was called Nuvio Native.

## Highlights

### Watch

- **A player built on mpv**, with a side panel for subtitle tracks, style and timing, and intro, recap and credits skipping from IntroDB.
- **Streams from your add-ons and plugins**, cloud playback through Premiumize and TorBox, and torrent streaming through a bundled TorrServer engine.
- **Continue Watching, Watch Later and collections.** Middle-click any title card to save it for later, and keep separate libraries per profile.

### Anime

- **Airing Now, built in.** Today's and upcoming episodes from AniList, Sub and Dub schedules from Tsuzuki, your own time zone, and a filter for adult titles.
- **Tracking** with MyAnimeList, Trakt, Simkl and MDBList, right from the details page.
- **Correct anime episode numbering**, using Fribb's anime ID mappings so seasons from other databases don't scramble it.

### Offline

- **Download whole seasons ahead of time** and play them from disk instead of waiting for a stream.
- Each episode finds its own source. Starlit picks the highest quality available by default, saves to a folder you choose, and resumes unfinished downloads after a restart.
- Library → Downloads shows progress, speed and status, with Pause all and Resume all.

### On Windows

- **Starlit's own installer.** Installs for the current user without administrator rights and keeps your data when you update.
- **In-app updates** verified against each release's SHA-256 checksum, with the installer removed afterwards.
- Six app icon colourways, Discord Rich Presence, and a searchable Settings sidebar.

## Install

Starlit runs on Windows 10 and 11 (64-bit).

1. Download `Starlit-Windows-x64-<version>.exe` from [Releases](https://github.com/Beelzebub2/nuvio-native/releases/latest). Releases are private, so you need read access to this repository.
2. Run it. Setup installs to `%LOCALAPPDATA%\Starlit` and lets you choose the folder and shortcuts.
3. For updates, sign in to the [GitHub CLI](https://cli.github.com/) once with `gh auth login`. Starlit checks for updates at startup, every six hours, and from Settings → About.

Settings, profiles and your library live in `%APPDATA%\NuvioNative` and are kept across updates. The [install and build guide](docs/PRIVATE-WINDOWS-BUILD.md) covers silent installs, the update token, and moving over from Nuvio Native.

## Build from source

You need JDK 21. Starlit reuses Nuvio's pinned native runtimes (libmpv and TorrServer), so fetch them from the upstream Git LFS store first:

```bash
git lfs fetch https://github.com/NuvioMedia/NuvioDesktop.git 25390defed025a871f6a3239427b4507f2fafb35 --include='composeApp/src/desktopMain/native/windows/runtime/**,composeApp/src/desktopMain/resources/torrserver/windows-amd64/TorrServer.exe' --exclude=''
git lfs checkout
```

Run the app, or run the desktop tests:

```powershell
.\gradlew.bat :composeApp:run
.\gradlew.bat :composeApp:desktopTest
```

Building the installer also needs the Visual Studio C++ Build Tools and the [Microsoft.Web.WebView2](https://www.nuget.org/packages/Microsoft.Web.WebView2) NuGet SDK:

```powershell
.\gradlew.bat :composeApp:packageReleaseInstaller "-Pnuvio.webview2.dir=<path to Microsoft.Web.WebView2>" --no-configuration-cache
```

The installer is written to `composeApp/build/compose/binaries/main-release/installer/`. Run it with `--sandbox` to try it without touching an existing install: it uses a separate name, folder and Installed apps entry.

### Android mobile

The Android app uses the shared mobile UI and Android player. See the [Android build and account-server guide](docs/ANDROID.md) for SDK setup, APK installation, distribution differences, and account-sync requirements. Android builds can run as a local guest without Nuvio's backend configuration. For cross-device accounts, run the independent [Starlit sync backend](private-server-setup) and sign into the same server on desktop and Android. iOS sources are also present, but require macOS and Xcode to build and test.

### Releasing

Bump `VERSION_NAME` and `VERSION_CODE` in `composeApp/Configuration/DesktopVersion.properties`, add the changes to `docs/RELEASE-NOTES.md`, commit, and push a matching `vMAJOR.MINOR.PATCH` tag. The [release workflow](.github/workflows/windows-release.yml) runs the tests, builds the installer, checks a silent install and uninstall, and publishes the release with its checksum. The schedule gateway in `gateway/` is deployed separately with Wrangler.

## Project layout

| Path | What's there |
| --- | --- |
| `composeApp/src/commonMain` | Shared UI, features and data, written once for every platform |
| `composeApp/src/desktopMain` | Desktop player, downloads, P2P, airing schedules, updater and installer |
| `composeApp/src/desktopMain/installer/windows` | Starlit's Windows setup (C++ with a WebView2 interface) |
| `gateway/` | Read-only Cloudflare Worker that caches schedule provider responses |
| `branding/` | The Starlit logo source and the script that renders every icon and wordmark |
| `docs/` | Install guide, release notes and the MyAnimeList setup guide |

## Credits

Starlit stands on other people's work, and they deserve the credit.

**Nuvio, by Tapframe and friends.** Starlit is based on [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop) and shares its code with [Nuvio Mobile](https://github.com/NuvioMedia/NuvioMobile). It started from Nuvio Desktop at commit [`25390de`](https://github.com/NuvioMedia/NuvioDesktop/commit/25390defed025a871f6a3239427b4507f2fafb35) and keeps Nuvio's GPLv3 license and copyright notices. Almost everything you see in Starlit began there. Thank you.

**Playback and streaming**

- [mpv / libmpv](https://github.com/mpv-player/mpv): desktop playback.
- [TorrServer](https://github.com/YouROK/TorrServer) by YouROK: the torrent streaming and download engine.
- [MPVKit](https://github.com/mpvkit/MPVKit) and [AndroidX Media3 ExoPlayer](https://github.com/androidx/media): playback in the iOS and Android builds of the shared codebase.

**Libraries**

- [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) and [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) by JetBrains
- [Coil](https://github.com/coil-kt/coil), [Ktor](https://github.com/ktorio/ktor), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) and [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)
- [Haze](https://github.com/chrisbanes/haze) by Chris Banes, for background blur
- [QuickJS-KT](https://github.com/dokar3/quickjs-kt), [Ksoup](https://github.com/fleeksoft/ksoup), [kmpalette](https://github.com/jordond/kmpalette), [Kermit](https://github.com/touchlab/Kermit), [supabase-kt](https://github.com/supabase-community/supabase-kt) and [Sentry](https://github.com/getsentry/sentry-java)
- [Microsoft Edge WebView2](https://developer.microsoft.com/microsoft-edge/webview2/), for the installer's interface

Each project's license terms are in its repository, and Settings → Licenses & Attribution lists them in the app too.

**Data and services.** Starlit isn't affiliated with or endorsed by any of these.

- [The Movie Database (TMDB)](https://www.themoviedb.org/): movie and TV metadata, artwork and trailers. This product uses the TMDB API but is not endorsed or certified by TMDB.
- [IMDb Non-Commercial Datasets](https://developer.imdb.com/non-commercial-datasets/): ratings and vote counts. Information courtesy of IMDb (https://www.imdb.com), used with permission for personal and non-commercial use.
- [AniList](https://anilist.co/), [Tsuzuki](https://tsuzuki.top/), ani.zip and [Fribb's anime-lists](https://github.com/Fribb/anime-lists): airing schedules, episode details and anime ID mappings.
- [MyAnimeList](https://myanimelist.net/), [Trakt](https://trakt.tv/), [Simkl](https://simkl.com/) and [MDBList](https://mdblist.com/): tracking, lists and ratings.
- [IntroDB](https://introdb.app/): community intro, recap and credits timestamps.
- [Premiumize](https://www.premiumize.me/) and [TorBox](https://torbox.app/): cloud playback.

## Legal

Starlit is a client. It browses metadata and plays media from add-ons, plugins and sources that you install or provide, and it is meant for content you own or are otherwise allowed to access. Starlit doesn't host, store or distribute media, and it isn't affiliated with any add-on, catalog, source or content provider.

## License

Starlit is licensed under the [GNU General Public License v3.0](LICENSE), like Nuvio. Nuvio's copyright notices are kept, and Starlit's source is available under the same terms.
