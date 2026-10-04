Nuvio Native is a personal Windows desktop build based on NuvioMedia/NuvioDesktop, with Compose desktop UI, native libmpv playback, a bundled Java runtime, and the upstream TorrServer/anacrolix engine.

- Airing Now is built into Home: Today, Next 24 hours, Recent, and All views, native episode details, time-zone/history/source controls, and refresh without reopening the app. It uses a Cloudflare provider gateway with direct fallbacks and retains schedules when providers fail. Broadcast uses AniList; Sub/Dub use Tsuzuki and depend on that provider's availability.
- Anime-specific Kitsu/MAL stream IDs retain the correct episode numbers. Independent cached ID mappings work when ani.zip is blocked.
- Visible addon home catalogs refresh every minute while the app has focus. Returning to the app triggers a throttled refresh; offline and hidden addon screens pause refreshes. Built-in schedules reclassify every minute and fetch every five minutes.
- The first page of an addon catalog refreshes while visible without blanking existing results or changing scroll position.
- P2P profile, cache, and upload preferences now configure the desktop engine. Disk cache uses the selected size; memory-only buffering is bounded by profile.
- P2P starts serialize, use a separate loopback port, wait up to 90 seconds for metadata, and clean up failed or cancelled streams. Other local torrent servers are not stopped.
- Windows .exe installers are built and published by pushing a matching vMAJOR.MINOR.PATCH tag.
- Updates use this private repository through the GitHub API, read your GitHub CLI login at runtime, verify the downloaded SHA-256, and never bundle an account token. Check at startup and every six hours; choose Install in the update dialog.
- Guest use works without upstream backend credentials. Cloud account integrations require a configured compatible server.

This package has no Windows Authenticode certificate. Preserve the upstream GPLv3 license and provide the matching source to any recipient to whom you distribute a copy.
