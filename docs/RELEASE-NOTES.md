## Starlit 1.0.29

### Improvements

- The update banner is clearer: it shows the new version, the download size and one action (Update, Install or Retry), with a thin progress bar while the update downloads. On narrow windows its buttons move under the text.
- **What's new** opens redesigned release notes with the current version highlighted, one-line previews of older releases, and an Update button so you can update straight from the notes.
- **Settings → About → Changelog** uses the same refreshed design.
- On desktop, **Library → Downloads** has a new layout: an overview of what is ready to watch and how much space it uses, the download queue with progress and time left, your downloaded shows and movies as poster cards, and the save folder with Change folder and Open folder.

## Starlit 1.0.28

### Improvements

- Episode artwork can optionally use a configured TMDB season fallback, while TVmaze artwork works automatically without a provider key.
- Provider-specific IDs, season offsets and air dates are checked before fallback images are accepted, with bounded caching to avoid repeated lookups.
- TMDB Worker setup is documented in `gateway/README.md`; a local app TMDB key does not configure the Worker.

## Starlit 1.0.25

### Fixes

- Episode lists restore real episode stills through the repaired cached artwork provider without changing playback numbering.
- Missing or broken artwork now shows a clear season and episode placeholder instead of repeating the series poster.
- Per-title caching and exact ID matching make artwork lookups more reliable while keeping unrelated episode mappings intact.

## Starlit 1.0.24

### Fixes

- Episode prompts now wait for an explicit post-credits scene instead of treating every long ending tail as post-credits content.
- Playback avoids restarting when torrent status reporting alone fails; it still recovers when the engine, torrent, sleep, or network genuinely needs it.

## Starlit 1.0.23

### Improvements

- The in-app changelog presents the current release in an expanded version card and keeps older history collapsed until you open it.
- Changelog text is formatted and selectable, with readable headings, bullets, emphasis, code, and safe links.
- The wider responsive viewer is easier to read on desktop and narrow screens.
- **Settings → About → Changelog** stays available offline and can be found through Settings search.

Starlit 1.0.22 adds bulk removal for downloads from Library → Downloads.

- Select individual downloads or whole displayed shows, use Select all or Clear selection, then choose Remove selected and confirm the exact count. Active, queued, paused, failed and completed items are handled together; cancellation and owned-file cleanup run as one batch while unrelated files and downloads remain intact.

Starlit 1.0.21 fixes episode downloads from multi-season torrent packs by using the provider's exact filename and failing clearly when that filename is missing or ambiguous.

- Existing incorrect copies from an earlier download must be removed and queued again after updating.

Starlit 1.0.20 redesigns the episode download dialog and downloads the best quality available by default.

- Pick seasons from chips that show how much of each one is selected, tick a whole season at once, and see each episode's still, air date and runtime.
- Episodes already in Downloads are marked Downloaded, In queue or Paused and aren't added again. Failed ones can be picked to retry, and episodes that haven't aired show when they air.
- Quality now offers Highest available, the new default: Starlit asks every source and downloads the sharpest file it finds. Choosing 2160p or 1080p also matches files labelled 4K, UHD or FHD.
- Quality, source type, provider and the save folder sit in a side panel, and your last choices are remembered next time.
- Movies get a compact version of the same dialog.

Starlit 1.0.19 lets you download whole seasons ahead of time and play them from disk instead of waiting for a stream, and no longer leaves update installers behind.

- Series pages have a Download episodes / seasons button above the episode list, and movies have Download for offline. Pick one or more seasons or single episodes. Episodes that haven't aired yet can't be selected.
- Each download looks for its own source using the provider, quality and Direct files or Torrents choice you set, then downloads one file at a time. Torrent downloads need P2P turned on in Settings. Streaming-only sources and HLS/DASH playlists can't be saved.
- Choose where downloads go, from the download dialog or from Library → Downloads. Changing the folder only affects new downloads; earlier files stay where they were saved.
- Library → Downloads shows the save folder with an Open folder button, Pause all and Resume all, and each download's progress, speed and current step. Unfinished downloads pick up where they left off after a restart.
- Episodes you've downloaded play from the file on disk. Subtitles in your preferred languages are saved first so they still work offline.
- If an automatic download fails, picking a source for that episode by hand replaces it, and queuing the season again retries the episodes that failed.
- In-app updates now download the installer to the local cache instead of your roaming profile and delete it once the updated app starts, along with installers earlier versions left behind (about 200 MB each).

Starlit 1.0.17 makes Airing Now useful sooner and keeps schedule loading responsive while preserving strict filtering, cooldowns, and updater safeguards.

- The newest released schedule page appears first, then history and upcoming pages fill progressively in the background. Valid partial snapshots persist across failures and missing pages are retried.
- Cached gateway pages skip unnecessary upstream pacing while cold gateway and direct AniList requests remain paced. Provider failure cooldowns, Retry-After handling, and the 429 fallback guard remain enforced.
- Restoring schedule snapshots and applying source or policy changes no longer block the UI, and obsolete requests are cancelled safely.
- Native player setup, window chrome readiness, diagnostics, torrent preparation, Discord startup, and first-use QuickJS initialization move off the initial UI path while preserving their existing lifecycle controls.

Starlit 1.0.15 is the first release under the new name: Nuvio Native is now Starlit, made by Beelzebub2 and based on Nuvio by Tapframe and friends.

- A new logo and app icons: a play button holding a star, in all six App icon colourways, with sharper Windows icons at small sizes.
- The app, its window, installer, shortcuts and Installed apps entry are now called Starlit. Settings, profiles, library and other data carry over unchanged.
- Updating from Nuvio Native moves the app to a Starlit folder and replaces the old shortcuts, keeping your desktop shortcut choice.
- Fixed in-app updates failing with "Some files … are in use" after the previous version had been removed. Setup no longer holds the install folder open, installs the new copy before removing an older MSI install, and replaces files one by one if the folder stays busy. If an update to 1.0.14 left the app uninstalled, run this installer from File Explorer to restore it.
- Settings → About credits Beelzebub2 with a link to GitHub, and Licenses & attributions lists Starlit and upstream Nuvio separately.
- Changing the app icon in Settings also updates the Start menu and desktop shortcuts again.

Nuvio Native 1.0.14 introduces a new Windows installer and a reworked player subtitles menu while preserving the navigation and settings refinements from 1.0.13.

- Windows setup is now Nuvio's own installer with Welcome, Options, Install, and Done steps, a Launch Nuvio action, and an uninstaller in Installed apps. It installs for the current user without administrator rights and lets you choose the folder and the Start menu and desktop shortcuts.
- Updating replaces the existing copy in place, including installs made by the earlier MSI-based installer, which it removes so Installed apps lists Nuvio Native once. Nuvio is closed first if it is open, and settings, profiles, and library data are kept. Uninstalling keeps that data unless you choose to delete it.
- The installer accepts `--silent` for scripted installs and uninstalls, and each release build checks a silent install and uninstall before it is published.
- The player's subtitles menu is now a side panel with Tracks, Style, and Timing tabs: languages as chips with a separate Off option, add-on search progress, a live style preview, and delay controls in readable seconds next to Auto sync.

Nuvio Native 1.0.13 refines the desktop experience with clearer navigation, settings, and layout behavior while preserving the airing, updater, and checksum improvements from 1.0.12.

- Builds without a local IntroDB property now use the public IntroDB API by default, with coverage for the generated configuration.
- Desktop detail pages keep their own fullscreen action while the shared top bar exposes a single fullscreen action everywhere else.
- Airing Now settings use the application dialog surface with clearer schedule source and day-segment controls, plus bordered timezone validation and switches.
- Library and Watch Later content now aligns with the desktop top bar through consistent insets.
- The Settings sidebar is transparent, aligns its logo and divider below the top bar, and includes default Account and Collections/About entries with update controls.

Nuvio Native 1.0.12 fixes airing classification, schedule controls, and provider resilience while preserving the cumulative desktop browsing experience, private updater, and checksum workflow.

- Hide NSFW now excludes Ecchi, Hentai, adult titles, and adult-tagged anime across all feeds, details, restored snapshots, and caches. Sub/dub classifications use authoritative AniList metadata, including bulk classification for up to 50 IDs, with migration for older cached data.
- Airing windows use shared stable UTC boundaries with local one-to-five-day, timezone, and estimated controls without extra provider fetches.
- Duplicate refreshes are coalesced, forced refreshes are throttled for 60 seconds, AniList requests are paced at 2100 ms, and Retry-After/reset cooldowns are respected across gateway and direct fallback paths.
- The Worker retains stale pages and applies provider cooldowns so cached schedules remain available during upstream failures or rate limits.

Nuvio Native 1.0.11 delivers the new desktop browsing experience while retaining the MyAnimeList tracking and recovery improvements from the previous release.

- The desktop details hero now puts Play, Library, Watch Later, Watched, shuffle, and MyAnimeList actions in a clear hierarchy, with resume context, genre chips, synopsis expansion, title facts, and trailer controls.
- Episodes can be browsed as rich rows with stills, episode metadata, progress, overviews, Play or Resume, Watch Later, and a context menu. Seasons use a segmented selector with watched, aired, and next-airing information, plus List/Cards and spoiler controls.
- The episode sources page now shows the episode context, source count, add-on search status, best match, quality filters, and grouped streams.
- The desktop top bar keeps Home, Discover, Library, search, Settings, and profile actions visible, adapts over the hero while scrolling, and keeps Watch Later inside the Library view.
- Watch Later is available as a Library switch with saved counts, empty and dismissible-tip states, episode details, time remaining, Play or Resume, and quiet removal actions.
- Desktop Settings now has a flat searchable sidebar grouped by audience and purpose, with breadcrumbs and a complete overview of settings sections.
- Recently released supports a one-to-five-day window and hidden titles, with restore controls; the schedule is presented as Coming up with day tabs.
- Episode hover cards use episode overviews when available, fall back to the series synopsis when needed, and no longer leave stale popups behind. Right-click on details-page episodes opens the episode menu while middle-click remains Watch Later.

The Windows release is built and tested automatically when this version's Git tag is pushed. Windows installers retain SHA-256 checksums and private release distribution; they are not Authenticode-signed.
