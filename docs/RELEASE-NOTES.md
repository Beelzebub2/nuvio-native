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
