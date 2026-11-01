## Starlit 1.2.0

### Features

- **Native desktop file dialogs:** Windows uses the system open, save and folder dialogs for download locations and addon backup import/export, including Unicode paths, file filters, cancellation and overwrite confirmation.
- Metadata requests use the new hosted Starlit gateway, with caching and compatibility for installed releases using the previous endpoint.

### Fixes

- **Anonymous settings import** stays responsive while restoring providers and checking download files. Importing again safely cancels old refreshes instead of crashing when their completion handlers run.
- An animated import popup shows the real restoration and account-sync stages, plus counts of saved addons, providers, downloads, library/history items and lists. It keeps the Close action accessible on compact screens and never displays imported API keys.
- Repeated import clicks share the active operation. Retrying a pending import preserves changes made after its first application, and imported theme or language changes no longer interrupt the operation.
- **Hidden Airing Now anime sync across Windows and Android** for the same account profile. Existing hidden lists are uploaded once, and showing titles again, including clearing the final entry, updates other devices on sync.

## Starlit 1.1.0

### Improvements

- **Import anonymous settings** now also transfers the selected guest profile's Starlit library, watched movies and episodes, resume positions, collections, recent searches and supported desktop Watch Later and personal-media lists, in addition to settings, add-ons, plugins and existing downloads.
- Importing into an account merges previously saved items rather than replacing the account's library or playback history. Cloud records from other devices are checked before guest entries are uploaded, and unfinished imports can retry after a network error or restart.
- The account's Starlit library continues syncing across Windows and Android when an external tracking library is selected. Saved changes that did not upload while offline are retried on later syncs.
- Android now has more of desktop's Airing Now, batch-download and Watch Later functionality, including the corresponding playback actions.
- Torrent streaming is steadier for large episodes and inconsistent peer speeds, with tuned buffering and more careful coordination between downloads and active playback.

### Fixes

- Guest history and playback progress no longer disappear solely because the user imports their anonymous settings into an approved account.
- Account library entries and pending removals are retained when overlapping anonymous entries are imported. Collection imports also preserve the destination account's existing lists.

### Release policy

- Version numbers now follow Semantic Versioning: compatible features advance MINOR, fixes advance PATCH, and incompatible changes advance MAJOR. Windows and Android versions and numeric Android build codes are checked automatically before publication.

## Starlit 1.0.48

### Security

- Tracking-service sign-in now rejects unexpected, expired, mismatched and replayed authorization callbacks, preventing unauthorized account linking.
- Android streaming verifies HTTPS server certificates and names instead of accepting untrusted certificates, and playback redirects cannot reattach credentials to other hosts.
- Account-server connections require HTTPS except when using a local development server on the same device. Android and iOS no longer permit arbitrary cleartext network requests.
- Third-party scraper plugins cannot read your private TMDB key or request local network addresses. Plugin downloads require public HTTPS endpoints, and changed script code stays disabled until you deliberately reinstall the repository.
- Saved tracking tokens and provider keys are protected using Android Keystore, iOS Keychain or Windows user-bound encryption, with migration from older local settings on supported platforms. Diagnostics no longer include sensitive network destinations and exception messages.
- The Windows updater fetches verified release files without accessing a personal GitHub token. Release automation includes source-secret checks and build provenance attestations.

### Fixes

- Download season sections, iOS navigation, and subtitle style controls use the correct cross-platform implementations.
- Anonymous guests can select their first account server without creating an invalid or insecure backend connection.

### Compatibility

- Insecure HTTP plugin repositories and insecure remote stream HTTPS certificates are deliberately blocked. Review and reinstall trusted HTTPS plugin sources if they stop refreshing.

## Starlit 1.0.47

### Fixes

- Android launcher icons are smaller and optically centered, keeping the play mark and twinkle visible on circular, rounded-square and square launchers. All icon colors, themed icons and legacy icons share the corrected alignment.
- Windows release validation now handles canonical file paths in download-recovery tests, including Windows short-path aliases.

## Starlit 1.0.44

### Improvements

- Importing anonymous settings now restores the guest download-folder choice and available offline episodes to the selected account profile, while keeping downloads already saved in that account.
- Selecting a folder containing previously completed Starlit downloads can rediscover episodes with verified file identities, without downloading them again.
- Downloads and folder choices stay associated with their original account and profile on this device when signing out and back in.

### Fixes

- Signing out no longer deletes downloaded media stored in Starlit's default desktop downloads directory.
- Android account switching clears old account download notifications and transfer records without deleting offline media.

## Starlit 1.0.43

### New

- **Request account** on the sign-in screen now lets new users submit an email and password for approval by the Starlit server owner. The account stays inactive until the owner accepts the request.
- The owner receives a one-use review link to approve or deny each request. Denied requests do not create accounts.

### Fixes

- If the owner's notification email cannot be sent, Starlit explains that the request was saved and lets you retry after 30 seconds using the same email and password.

## Starlit 1.0.42

### Improvements

- Desktop and Android now connect to Starlit's account server automatically. Sign in with an owner-approved account to share profiles, add-ons, library, viewing progress and supported settings across devices. Public registration is closed; ask the server owner for an account.
- Guest mode remains available, and existing guest data and custom-server choices are kept. Unsupported TV-code login, profile PINs and Nuvio membership controls are hidden for the Starlit server.

## Starlit 1.0.41

### Improvements

- The player's bottom bar has two new buttons for series: **Auto skip intro** and **Auto next episode**. Click or tap one to turn it on or off. The choice is saved and matches the same options in **Settings → Playback**.
- Signed-out (anonymous) profiles, settings, add-ons and plugins now stay on the device for your next anonymous session. After signing in, **Import anonymous settings** in account settings copies them into the current account profile; account profiles, watch history and linked tracking accounts are kept.
- Torrent streaming and downloads are steadier: the same torrent stays ready between episodes, the right file is chosen by name and episode, downloads resume only from the same source, and finished downloads are verified.

### Fixes

- Anime opening skips now land at the right time for downloaded episodes, using each file's own timing.

## Starlit 1.0.40

### Improvements

- Desktop Home now asks on first install whether to include the built-in Airing Now anime section. You can hide or restore it anytime under **Settings → Home Layout**; existing installs retain their current layout.

### Fixes

- Turning subtitles **Off** now stays Off even after track refreshes, subtitle language preference updates, and delayed Android playback events. The subtitle menu and desktop keyboard toggle show the correct selection.

## Starlit 1.0.39

### Improvements

- Settings → Add-ons can export a JSON backup on desktop and Android and import it on another device.
- Backups preserve configured URLs, order, custom names and enabled state; imports append missing entries, skip duplicates and keep offline endpoints.

## Starlit 1.0.38

### New

- Starlit is now available for Android 7.0 and later. Signed Full APKs for ARM64, ARMv7, x86 and x86_64 are included alongside the Windows installer; most phones need **arm64-v8a**. Fresh installs start with a usable guest profile, and future Full APK updates come from Starlit's own releases.
- Desktop and Android can share profiles, add-ons, library, watch progress, history, collections and provider credentials through a compatible, trusted account server, using the same account and profile on every device. Account-server configuration is covered in the Android guide; the server implementation is maintained separately.

### Fixes

- Android status icons, hero artwork overlays and the details back button stay readable on the dark interface, including over pale artwork.
- Off-screen Home hero pages no longer expose duplicate accessibility actions, and saved subtitle provider/language choices carry into the next episode without reopening the previous episode's subtitle file.

## Starlit 1.0.37

### Improvements

- **Downloads** can now fetch several episodes at the same time. Choose 1 to 5 under **Downloads at once** on the Downloads page (3 by default); the Queue header shows the combined speed.
- Episodes from the same torrent, such as a season pack, now share one connection to its peers, so the next episode starts at full speed instead of searching for peers again.
- Download speed and time left are averaged over a few seconds, so they no longer jump with every burst, and they drop when a transfer stalls instead of freezing.

## Starlit 1.0.36

### Improvements

- Batch episode downloads set to **Highest available** now check every source and pick the least compressed file at the best resolution, preferring remuxes and larger files (for example a 4 GB 1080p file over a 300 MB encode). If the best file can't be reached, the next best one is used.

## Starlit 1.0.35

### Fixes

- Completed episodes are now automatically removed from Watch Later, while unfinished episodes retain their saved progress.

## Starlit 1.0.34

### Fixes

- Desktop Play Next no longer gets stuck when clicked during source search or the selected-source countdown; the pending transition completes and opens the next episode.
- Selecting an episode from a season or multi-episode torrent now opens the requested episode instead of replaying the largest file, with clear errors for ambiguous sources.

## Starlit 1.0.32

### Fixes

- Torrent engine settings now automatically back up and recreate corrupt preferences while preserving downloads, history and valid settings.
- App-managed torrent settings are flushed to disk before replacement to reduce damage from interrupted saves. Storage failures now show a clear error.

## Starlit 1.0.31

### Improvements

- Desktop Home and Library scrolling now eases and glides smoothly between mouse wheel notches while preserving scroll distance, touchpad handling and Shift+wheel behavior.
- Desktop Collections cards now show folder covers and pinned badges, with drag handles for reordering.
- **Settings → Add-ons** uses compact rows with inline install actions, stat tiles and manifest fallback server names.
- Desktop Library unifies Saved and Cloud behind a shared toolbar with type filters, search, sorting, Rows/Grid views, title counts, a clear No matches state and aligned grid spacing.

## Starlit 1.0.30

### Improvements

- On desktop, **Settings** has a new layout. The sidebar shows an icon for every page and your profile at the top (click it to switch profiles), pages have clearer names such as **Appearance**, **Debrid** and **Source list**, and each page opens with a one-line summary of what it covers.
- **Settings → Updates** shows the version you have, a **Check for updates** button and the changelog. **Settings → About** holds the credits, licenses and privacy policy.
- **Settings → Account** is a single card with **Sign out** beside your account, instead of a full-width button.
- Clearing a settings search takes you back to the page you had open, and Esc clears the search box.

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
