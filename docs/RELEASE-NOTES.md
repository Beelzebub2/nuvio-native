## Starlit 1.17.1

### Private reliability diagnostics

- **Useful errors and warnings, only with consent:** The optional Usage Statistics setting now covers fixed, anonymous technical signals for slow playback startup, torrent-engine errors, download retries, storage-quota pauses, playback failures and download failures. Successful playback and downloads can also be counted to evaluate reliability.
- **No browsing or viewing history in usage diagnostics:** Starlit stops reporting screen visits, player openings and download-list refreshes. Technical health events contain no media titles, searches, stream URLs, account details, free-form error messages or permanent installation identifiers.
- **Temporary abuse protection:** An opt-in diagnostic session uses a random token for brief request-rate limiting rather than keeping a persistent tracking ID. Older locally stored diagnostic identifiers are cleared after updating.
- **Crash reports stay a separate choice:** Optional crash diagnostics retain sanitized stack traces and limited technical platform information, without navigation or network-request breadcrumbs, screenshots or replay.

### Compatibility and limitations

- This version replaces an earlier tagged release candidate that did not publish after a desktop Settings UI test expected the previous diagnostics label. The assertion now matches the current UI; diagnostic behavior is unchanged from the validated privacy update.
- The upgraded server collector accepts fixed technical signals from compatible older versions but no longer stores obsolete activity-only events. Historical records expire under the server's configured retention period.
- Aggregate account counts and error summaries are available to the service operator; event totals are not unique-user or unique-installation counts.
- Desktop and Android builds are included. Physical-device confirmation of the new warnings and crash-report consent remains outstanding; iOS is not released through the App Store in this version.

## Starlit 1.16.1

### Phone-to-PC playback

- **Compare recommended sources on your phone:** The paired-PC source picker places the desktop's best match first, explains its ranking, and shows resolution-tier stars, reported seed/peer availability and cached status on alternatives. Stars describe quality tiers rather than viewer ratings; unavailable metrics are not invented.
- **Choose the PC's audio track remotely:** The phone controller can show and select tracks offered by the playing desktop session. Track changes are authorized for the active playback session and older remote peers keep their previous controls.

### Playback and MyAnimeList

- **Fullscreen during stream preparation:** The desktop startup overlay now exposes fullscreen entry and exit before the player is ready, without hiding its close control or changing the torrent engine.
- **Prevent premature automatic rewatches:** Automatic desktop MyAnimeList Rewatching updates require at least 55% of the episode by default; set a 55–95% threshold per local profile in Tracking settings. Loading, paused, unknown-duration and stale playback states do not trigger early updates. Manual MyAnimeList edits are unchanged.

### Validation and limitations

- Related pull requests #185–#188 passed their applicable Android Full/Play Store, iOS Kotlin, desktop and source-security CI checks. Regression tests cover fullscreen state, source ranking, remote audio commands and playback thresholds.
- Hardware pairing and track switching, provider-dependent seed/peer data and mixed-version remote operation still need device testing. The release provides Windows and Android builds, not an iOS App Store package.

## Starlit 1.15.0

### Anime franchise watch orders

- **Follow the story across seasons and films:** Anime detail pages can show a compact viewing-order preview and a full franchise list. Reviewed sequences place essential movies and related seasons in their intended progression, while optional side stories remain clearly distinguished.
- **Choose how to browse:** Switch between recommended, release and verified chronological ordering, include or hide optional entries, and see which installments are current or completed. Incomplete or uncertain source data is labelled as inferred or partial rather than presented as a definitive order.
- **Find the next installment easily:** Select a title from the watch-order list to search through the existing Starlit add-ons and source picker. At the end of the final available episode or a movie, a dismissible suggestion can point to the next eligible title; ordinary next-episode autoplay still takes precedence, and playback never starts across titles without a new selection.
- **Clear recovery and attribution:** Missing mappings, unavailable orders and connection failures have retry or fallback states. Franchise data is attributed to Junban under the Open Database License (ODbL 1.0), with read-only relationship metadata provided through a compatible third-party API.

### Phone-to-PC connectivity

- **Pair with wired computers over local Wi-Fi:** Restore reliable discovery, pairing and control when the phone is on Wi-Fi but the paired Windows PC uses Ethernet. LAN and firewall checks handle the different network interfaces more accurately while keeping the existing authenticated device trust and firewall protections.
- **More reliable compatibility checks:** Improve Windows firewall rule detection and recovery without creating unnecessary public-network allowances. The iOS Kotlin build also uses checksum-verified dependency downloads.

### Service reliability

- **Avoid repeated failed analytics submissions:** The independent account-security analytics exporter handles stale ingestion failures without rapid retry loops. This server-side change does not enable additional client analytics or collect watch-order history.

### Validation and limitations

- The anime watch-order change passed Android Full/Play Store, iOS Kotlin, desktop, gateway, Unix-native and repository-security pull-request checks. Focused gateway tests passed 47/47 and targeted watch-order tests passed 8/8; phone-sized and desktop-sized layouts were visually checked. The read-only watch-order API was deployed and checked over HTTPS before this release.
- Watch-order availability depends on reliable third-party franchise metadata and the user's installed add-ons; some franchises have no verified order or playable title. Chronological ordering is only shown where reviewed. Watch progress, third-party list statuses, and existing media sources are not automatically modified.
- Real-world phone-to-PC pairing, specific streaming providers, and physical Android/iOS playback still require hardware testing. This release packages Windows and Android builds, not an iOS App Store release.

## Starlit 1.14.1

### More reliable phone-to-PC control

- **Keep controlling the PC after leaving the remote screen:** While a phone is connected, Android can keep its authenticated remote session running through a connected-device foreground service when Starlit is minimized or the user navigates elsewhere. Disconnecting ends the service instead of leaving an untracked connection behind.
- **Keep the PC awake when a phone is controlling it:** Windows prevents automatic system sleep only during an authenticated remote session and returns to normal power management afterwards. The phone also offers a **Wake PC** action for a saved LAN computer using Wake-on-LAN, when the hardware and network support it.
- **Restore a timed-out monitor when starting playback:** Play, next/previous episode, episode selection and approved source selection from the paired phone ask Windows to wake a display that turned off through its idle timer. This does not unlock Windows, resume a genuinely sleeping PC without Wake-on-LAN, or turn on a physically switched-off monitor.

### Bluetooth and volume corrections

- **Only show current nearby Bluetooth PCs:** Android expires stale scan results instead of offering devices that were nearby earlier. Compatible previously paired older PCs can connect through a legacy Bluetooth RFCOMM fallback where the secure-first connection is unsupported.
- **Control the PC player up to 200% volume:** The phone slider now follows the desktop player's native 0–200% range. It negotiates the maximum with the PC, so older PCs without volume-boost support stay limited to 100%.
- **Prevent the volume slider jumping backwards:** The phone keeps the chosen volume visible while awaiting confirmation from the PC, previews adjustments during interaction and reconciles the value when the PC responds. Mute continues to use the existing command.

### Validation and limitations

- Includes merged remote-control fixes [#174](https://github.com/Beelzebub2/nuvio-native/pull/174), [#175](https://github.com/Beelzebub2/nuvio-native/pull/175), [#176](https://github.com/Beelzebub2/nuvio-native/pull/176), and [#177](https://github.com/Beelzebub2/nuvio-native/pull/177). Their Android, desktop, iOS Kotlin and security CI checks passed prior to merge; the tag-triggered release workflow independently validates the combined Windows/Android release.
- Bluetooth RFCOMM compatibility depends on the older PC's actual Bluetooth stack. Wake-on-LAN needs firmware, network-adapter and network support. Playing on a screen locked by Windows does not bypass the lock screen. Boosted volume above 100% may distort some audio.
- Real Android-to-Windows Wi-Fi/Bluetooth pairing, background sessions, monitor timeout and Wake-on-LAN still require testing on physical devices. This release packages Windows and Android installers/APKs; it does not publish an iOS App Store build.

## Starlit 1.14.0

### Phone-to-PC playback and pairing

- **Start a movie or episode from the phone:** Once a phone is securely paired with a compatible Starlit PC, choose a film, series or episode without accepting an extra playback prompt on the computer. The PC uses its built-in player so playback controls remain available on the phone.
- **Choose the PC's source on your phone:** Browse eligible streams found by the PC's installed add-ons, see provider, source and available size information, and choose the initial source before the PC starts playback. The existing desktop playback path continues to enforce provider/debrid and P2P settings; stream URLs, torrent details and credentials are never sent to the phone.
- **Keep pairing across Wi-Fi reconnects:** Android and Windows securely remember a previously approved LAN pairing and mutually prove trust during reconnect, including across local IP address changes. Forgetting the device revokes its saved LAN credential. The initial connection still requires code verification on both devices; unknown or revoked devices cannot skip it.
- **Better remote session feedback:** Source searching, selection, preparation, cancellation, unavailable sources and connection timeouts display on the phone. Player replacement no longer disconnects the phone remote. Older app versions use their prior compatible pairing/playback behavior or receive an update-required message.

### MyAnimeList and anime playback

- **Manage MyAnimeList directly on desktop:** A redesigned, responsive list provides status groups, sorting, richer details and inline editing of watch status, score, watched episodes and rewatching. Removal requires confirmation, and the interface handles failed updates without losing track of the user's changes.
- **Recover more anime intro and credit markers:** Anime-Skip can find intervals even when optional TVDB episode mapping is missing, using available anime IDs. Multiple community markers are handled more carefully and incomplete or unbounded intervals are never used to guess where to skip; existing movie and TV intro support remains intact.

### Languages and security

- **More languages:** Add 13 additional interface translations for supported locales, excluding Hindi. Language catalogs are initial translations and can be improved through native-speaker review.
- **Private desktop preferences on Unix:** On Linux and macOS, app-owned preference files and their backups receive owner-only permissions, including when written atomically. Existing Windows permissions and explicitly customized storage locations are unchanged.
- **More robust CI dependencies:** The iOS Kotlin compile check verifies pinned Gradle and Kotlin/Native artifacts against official SHA-256 checksums, avoiding intermittent release-asset download failures without changing the versions used for normal application builds.

### Validation and limitations

- The source changes from PRs #162, #167, #169, #170, #171 and #172 are included. Remote integration tests passed 67/67 on Windows, with the release pipeline independently rechecking the integrated desktop build, provider gateway, version policy, Windows installation and signed Android packages.
- Initial PC source selection is manual (not automatic), and only eligible PC-playable streams are offered. A source needing P2P permission still follows the PC's existing consent policy; the phone does not grant new P2P rights silently. The source list is capped for remote performance.
- Physical Android-to-Windows Wi-Fi/Bluetooth and provider-specific P2P/debrid playback still need device-level verification. The official 1.14.0 GitHub release packages Windows and Android; an iOS App Store distribution is not part of these release assets.

## Starlit 1.13.0

### Privacy and diagnostics

- **Find privacy controls easily:** Mobile Settings now includes a Privacy & Diagnostics shortcut to the existing Usage statistics switch. Usage data remains opt-in and off by default; crash reporting still has an independent setting.
- **Official privacy policy:** Mobile Settings and desktop About now link to Starlit's official privacy policy instead of the legacy Nuvio link, with corrected English and Portuguese labels.
- **Private account-security analytics:** Starlit's source now includes a restricted server-side login audit exporter; security operators can review the separately hosted private OpenObserve dashboard. It is independent of the optional app-usage analytics switch and does not add emails, account IDs, credentials, or full request URLs to exported events. Server deployment and dashboard access are administered separately from an app installation.

### Phone remote and playback

- **Show the actual connected PC:** The phone controller displays a verified active computer as Connected, offers Disconnect instead of a second Connect action, and avoids duplicate reconnections or queuing commands while disconnected.
- **More consistent discovery and pairing:** Saved LAN connections start the existing secure pairing flow automatically; Bluetooth auto-resume respects the most recently selected transport. Remote tab positions, troubleshooting panels, search status, and manual-IP editing remain stable during scans.
- **Select the right desktop audio track:** Preferred audio languages now match common three-letter ISO codes and regional variants, favoring exact locales before compatible language fallbacks. Conflicting explicit track languages are not overridden by a descriptive label.

### Desktop performance and reliability

- **Lower overhead for ordinary streams:** Regular desktop streams use smaller forward/backward byte caches without reducing the existing P2P cache budgets or changing the established cache time horizon. Native player initialization also guards Windows numeric-locale compatibility.
- **Right-sized posters and safer animated artwork:** Desktop images use actual layout sizing instead of unnecessarily large decodes. GIF decoders are no longer closed while a visible collection card still needs them.
- **Recover from temporary download outages:** Desktop HTTP downloads back off and retry transient network failures and server throttling, while preserving existing resume checks, file ownership, fingerprints, SHA-256 proofs, and cancellation behavior. Local disk failures retain a shorter retry budget.

### Validation and limitations

- PRs #159, #161, #163, #164, and #165 passed their relevant Android Full/Play Store, iOS, desktop regression, native, and security checks before merging. The release pipeline additionally tests the integrated desktop app and gateway, packages Windows, signs Android APKs, and validates checksums before publication.
- Wi-Fi and Bluetooth pairing, remote playback commands, and large 7-10 GB P2P streams still need physical-device checks on representative networks. The remote does not relax Windows Public-network firewall rules or device-approval security.
- Server-side login audit collection is distinct from opt-in device usage diagnostics. Account-security dashboards and ingestion require their own private server configuration; the app release does not grant dashboard access.

## Starlit 1.12.3

### Intro skipping for movies and TV series

- **Skip openings beyond anime:** The player can use available intro and recap markers for films and regular TV episodes, not only anime. Existing intro providers remain supported, with TheIntroDB as a fallback when a compatible IMDb ID and reliable timestamps are available.
- **Recognize more episode sources:** Metadata IMDb IDs and existing TMDB/Simkl lookups help identify shows whose add-on video IDs do not use the IMDb format. Built-in and external playback share the same resolution logic instead of creating separate skip controls.
- **Avoid unintended skips:** Invalid, incomplete and open-ended ranges are not used for seeking. Existing skip settings still apply, and movie post-credits scenes remain protected. Content without verified timestamps will not gain a guessed skip button.

### Phone-to-PC remote controls

- **No duplicate actions:** The phone controller offers the existing Next episode transport button and a single contextual skip action, with the existing fullscreen-on-PC control. Skip intro uses the established skip-interval command; previously released clients that send the legacy skipIntro command remain supported.
- **Clearer fullscreen labels:** The phone now explicitly says Fullscreen on PC or Exit fullscreen on PC in English and Portuguese, so it is clear which screen changes.

### Safer Android download cleanup

- **Delete only files owned by the active profile:** Android checks that a completed download belongs to the active profile, was created by the transfer scheduler, and has a matching stored file URI before deleting it. Arbitrary saved paths and imported/stale queue entries cannot authorize deletion.
- **Protect files on older Android versions:** The checks work from Android API 24, reject symlinks, path traversal, unmanaged files, and files from other profiles. Regression tests cover Android API 24, 34 and 36.

### Validation and limitations

- PRs #146, #154 and #156 passed their respective Android Full/Play Store, iOS Kotlin, desktop or storage, and repository security checks before merging. The release workflow will additionally test the combined desktop build and metadata gateway, package the Windows installer, sign Android APKs, and verify SHA-256 checksums before publication.
- Intro timing coverage depends on accurate third-party timestamps; no content is skipped automatically when a trustworthy interval is unavailable. Physical-device Android download cleanup tests remain recommended alongside automated coverage.

## Starlit 1.12.2

### Account creation and email verification

- **Create an account without administrator approval:** New Starlit users can register with email and a password of at least 12 characters. GoTrue requires email confirmation before password-based access; no owner review queue is involved.
- **Use an existing Google identity:** When the official account server has Google enabled, verified Google sign-in can also create an account. Existing device pairing, authenticated syncing, and third-party authorization confirmations remain separate security decisions.
- **Keep registration feedback accurate:** The app explains the confirmation-email step rather than showing a manual-approval or pending-review message.

### Phone remote playback actions

- **Control fullscreen from your phone:** The remote can request fullscreen on the connected Starlit PC instead of only changing its phone interface.
- **Skip introductions and advance episodes:** The phone remote exposes skip intro and next episode through the existing authenticated playback-control protocol, rather than introducing duplicate commands.

### Authentication security

- **Keep passwords out of Android saved state:** Password input is stored only in transient Compose memory and is not written into saved-instance-state bundles.
- **Avoid sensitive auth details in logs:** Sign-in and sign-up failures show safe messages without forwarding raw HTTP authentication exceptions to diagnostics. Automated security checks protect against reintroducing password persistence.

### Validation and release process

- Account registration PR #152, phone remote PR #153, and authentication security PR #155 were merged to Dev. The security PR passed Android Full, Android Play Store, desktop-auth, iOS Kotlin, and source/history security checks.
- The tag-triggered release workflow rechecks versioning, tests the desktop application and metadata gateway, builds the Windows installer, signs Android packages, performs install/uninstall smoke checks, and publishes artifacts only after required jobs succeed.

## Starlit 1.12.1

### Phone remote connection reliability

- **Keep direct pairing independent of discovery:** QR, manual IP and remembered-PC TCP checks continue when UDP discovery cannot start or send. A failed UDP discovery worker no longer stops a healthy TCP listener or Bluetooth hosting, and a failed reply to one device does not disable later discovery replies.
- **Get help when you need it:** The PC's Phone remote window offers **Trouble connecting?** instead of Starlit's startup firewall modal. Windows rule checks distinguish TCP session permission from optional UDP discovery and explain uncertainty without claiming that a missing rule proves the phone is blocked. Connected phones do not see irrelevant LAN warnings.
- **Open the correct Windows page:** **Open network settings** now uses the native Windows protocol handler to open Network & internet, avoiding a blank default-browser window. Connection and pairing timeout messages offer more specific recovery steps; network help is available in English, Portuguese and Brazilian Portuguese.
- **Retain the existing security boundaries:** Two-device code approval, same-account/server verification, encrypted sessions and local-subnet IPv4 restrictions remain in place. Optional administrator-approved firewall rules stay scoped to Starlit's executable and ports on Private/Domain networks; Starlit does not switch network profiles or open Public-network exceptions.

### Bluetooth and QR connection feedback

- **See when your phone is searching or connecting:** The PC controller now distinguishes Bluetooth/LAN searches from a connection attempt, keeps known nearby PCs visible during a new Bluetooth scan and stops an in-progress scan before connecting. Scanning times out cleanly and late Bluetooth callbacks cannot overwrite newer connection status.
- **Connect directly from a scanned desktop QR code:** After confirming that the QR address belongs to the phone's private subnet, Starlit probes the selected PC and starts the existing authenticated, encrypted two-device pairing flow automatically. A deliberate QR scan can replace an unfinished broad network search, without granting trust or bypassing confirmation.
- **Exercise the new behavior in CI:** Desktop PR checks now include regressions for discovery progress, duplicate Bluetooth results, stale callbacks, QR auto-connect and controller state. The manual-IP and UDP-independent TCP routes remain supported.

### Validation and limitations

- PR #148 passed Android Full/Play Store, desktop, iOS Kotlin and repository security checks. Local validation passed 74 targeted desktop tests, two focused UI capture rechecks and the Android production compile. Both native-helper and asynchronous-entry smoke tests opened the actual Windows Network & internet page. The tag-triggered pipeline runs the full desktop suite, builds and tests the Windows installer, signs Android APKs and verifies release artifacts before publication.
- A physical phone's TCP probe to a new wildcard-bound Java listener timed out on the current network. No firewall or network profile was changed, and that timeout does not uniquely identify the cause. Physical phone-app pairing and command control remain unverified; automatic tests do not guarantee reachability across routers, VPNs and security products.

## Starlit 1.12.0

### Android Google Cast

- **Play eligible videos on a Cast-enabled TV:** Android can select a Google Cast receiver and send compatible, public, unsigned HTTPS MP4/WebM/HLS/DASH streams after explicit confirmation that you have playback rights and choose **Play on TV**. Once loaded, the local player pauses; basic TV pause/resume and disconnect controls are available.
- **Keep private sources private:** Torrent/P2P, local and private-network addresses, authenticated or signed URLs, DRM-restricted media and proxy/transcoding are not supported. Opening the picker or selecting a TV never starts playback without the separate confirmation.

### Offline download storage limits

- **Optional limits for each profile:** Desktop and Android can enforce a selected download storage target instead of only warning about it. Known-size downloads are reserved against the quota; queued or running downloads pause when they would exceed the configured budget.
- **No automatic file deletion:** Limits are off by default, paused downloads need an explicit Resume, and cleanup still requires a preview and confirmation. Unknown-size transfers may briefly exceed a target before progress is measured; this is a best-effort app-level limit, not a filesystem guarantee.

### Playback diagnostics

- **Inspect playback problems:** The desktop player offers measured playback state, buffer, speed, decoder mode and rebuffer metrics; Android exposes available decoder and buffering details. Unsupported measurements are marked unavailable rather than guessed.
- **Copy a sanitized report:** Diagnostic sampling is scoped to the open panel; reports exclude media titles, source URLs, credentials and private paths. These panel metrics are not automatically uploaded and are separate from opt-in usage analytics.

### PC remote connection and update reliability

- **Remember approved Bluetooth PCs:** After the initial code confirmation, Android and Windows store a protected trust key to reconnect without asking to pair again on every visit to PC Control. Account verification remains required, and Forget removes the local trust. Older clients fall back to the existing pairing flow.
- **Recover LAN hosting:** The Windows PC remote listener retries transient TCP port 47789 failures, keeps manual/QR pairing available if UDP discovery cannot start and distinguishes unknown firewall state from an actual block.
- **Prepare for the Starlit repository rename:** The updater prefers the future Beelzebub2/starlit releases location while retaining fallback to Beelzebub2/nuvio-native and support for existing installation and update links.

### Validation and limitations

- Android Full/Play Store, desktop, iOS Kotlin and repository security checks passed for the included feature and fix PRs (#131, #132, #134, #144, #145). The separate release pipeline validates the version and notes, runs desktop and installer tests, builds signed Android APKs, and checks artifact hashes before publication.
- Real-device acceptance is still outstanding for Chromecast/Google Cast playback, Android-to-Windows Bluetooth trust and reconnection, and storage quota behavior during long/background downloads. Google Cast receiver compatibility depends on the receiver, media format and rights; the optional sender is not a torrent relay.
- Windows installers are not yet publisher-code-signed, and a new Microsoft Defender verdict after the earlier antivirus-heuristic mitigations has not been independently established.

## Starlit 1.11.1

### Windows security and antivirus compatibility

- **Avoid suspicious background scripts:** Windows desktop now uses native Windows APIs instead of hidden or encoded PowerShell to inspect the firewall, protect saved credentials and update shortcut icons. This removes startup behavior correlated with Microsoft Defender's `Bearfoos.A!ml` classification of version 1.11.0. A fresh Defender verdict for the new executable is not yet guaranteed.
- **Keep existing account connections:** Credential protection uses Windows DPAPI in-process. Tokens encrypted by older Starlit versions remain readable, preserving saved sign-ins and MyAnimeList connections.
- **Safer phone remote firewall approval:** A read-only Windows Firewall inspection runs in-process. Explicitly choosing firewall access prompts for administrator approval to relaunch Starlit for one fixed task rather than running elevated PowerShell. New TCP 47789 and UDP 47788 inbound allowances remain limited to local-subnet traffic on Private and Domain networks. Existing Block rules and unrelated or ambiguously owned firewall rules are not overwritten. Public networks remain restricted, and existing Block rules may still require manual review.
- **Update shortcut icons without scripts:** Shortcut icons use the native Windows shell APIs. Starlit updates writable per-user shortcuts and skips administrator-owned shortcuts rather than launching elevated scripts.

### Validation

- PR #142 passed Windows desktop-auth, Android Full and Play Store, iOS Kotlin, GitGuardian and public source-security checks. Regression tests cover legacy DPAPI compatibility, firewall-rule inspection and identity safeguards, and prevention of hidden PowerShell launch patterns.
- The tag-triggered release pipeline validates the version, tests the Windows desktop build and installer, builds and signs Android APKs, checks installation and file hashes, and publishes only after those jobs pass. A new Microsoft Defender classification and Windows publisher code signing remain separate follow-ups.

## Starlit 1.11.0

### MyAnimeList rewatching and library

- **Automatically recognize rewatches:** On desktop, starting playback of a completed anime in a connected MyAnimeList account marks it as rewatching without clearing its completed status, score or watched-episode count. Updates are guarded against duplicate requests and switching profiles.
- **Browse MyAnimeList in Starlit:** An optional desktop MyAnimeList tab lets you explore watching, completed, on-hold, dropped and planned anime, with posters, progress, ratings, status filters, paging and refresh. The tab is off by default and can be enabled when wanted.
- **Control rewatching yourself:** Anime details also offer a manual rewatch toggle. Android and iOS MyAnimeList behavior is unchanged by this feature.

### Optional diagnostics and privacy

- **Choose which diagnostics to share:** A first-use prompt offers separate, off-by-default switches for anonymous usage analytics and crash reports. Existing Sentry crash-reporting preferences are preserved, and both choices can be changed later in Settings > Advanced.
- **Understand which versions are in use:** When opted in and the server is configured, Starlit reports limited app-version, platform, navigation and download event codes to the self-hosted OpenObserve service. A random installation identifier is pseudonymized at the gateway; media titles, watch history, search terms, account identities and URLs are not exported.
- **No interruption if diagnostics are unavailable:** Analytics can be turned off at any time, which cancels queued events and resets the local identifier. An unconfigured collector or network failure does not interrupt Starlit. MyAnimeList tab visits are correctly categorized in usage diagnostics.

### PC controller and Windows firewall

- **Dedicated phone controller page:** The Android PC controller opens a full-screen Settings page with Remote, Downloads and PCs tabs, plus clearer connection options.
- **Separate desktop pairing window:** Connect phone opens a modeless window with the pairing QR code, local IP addresses, setup steps and confirmation code. You can continue using Starlit while pairing your phone.
- **Safer Windows firewall guidance:** The remote window checks Windows Firewall without administrator access and explains when inbound connections are blocked. With a single explicit administrator confirmation, Starlit can add app-specific TCP 47789 and UDP 47788 inbound rules on Private/Domain networks limited to the local subnet. The same approval disables inbound Block rules that target the Starlit executable, such as those Windows creates when its own firewall prompt is cancelled; other rules are left unchanged. Public-network restrictions remain in place.

### Validation

- MyAnimeList feature validation included 11 targeted desktop tests, desktop Kotlin compilation, and Android, iOS and source-security checks. The new diagnostics gateway passed 14 targeted Node tests covering ingestion, quotas, consent-related behavior and failure handling.
- The controller and firewall changes passed a 1,907-test Windows desktop suite, Android Full/Play Store, iOS Kotlin, desktop and security CI. Actual two-device pairing and the elevated firewall-approval path still need hardware/UAC acceptance tests.
- The tag-triggered release workflow tests the Windows installer and signed Android APKs, verifies checksums and publishes only after all required jobs pass. The public privacy page covers opt-in diagnostics; configuring the OpenObserve ingestion service remains a separate deployment requirement.

## Starlit 1.10.0

### Safer download storage cleanup

- **Find older downloaded episodes to review:** Downloads > Storage management can suggest completed episodes that have not been watched recently, making it easier to free space without searching through every file.
- **You stay in control:** Suggestions never delete anything automatically. Review each candidate and explicitly confirm a removal; Starlit excludes unverified paths, active downloads and files it does not own.
- **Keep playback data:** Cleaning up a suggested offline file does not erase watch history or change the account library.

### Validation

- The download-cleanup feature's Android Full and Play Store, iOS Kotlin, desktop and security CI checks passed before merge.
- A clean combined desktop build passed the complete `:composeApp:desktopTest` suite. Device-level storage and filesystem acceptance testing is still recommended.
- The official release workflow verifies release notes and version policy, builds and tests the Windows installer and signed Android APKs, checks checksums, and publishes only after required jobs succeed.

## Starlit 1.9.2

### PC discovery and Bluetooth remote

- **Find PCs more reliably:** Local-network remote control now uses a stable TCP port alongside UDP discovery. Search local network can probe compatible PCs directly, including saved PCs and nearby private-network addresses, so it can recover when UDP broadcasts fail.
- **Control a Windows PC over Bluetooth:** The Android app can discover and connect to a Windows Starlit host over Bluetooth Classic without requiring a LAN connection for the remote transport. Bluetooth uses the existing encrypted, account-verified pairing and command restrictions; Windows and Android Bluetooth permissions and OS pairing are still required. Other desktop platforms continue to use LAN remote control.

### Account security and playback

- **Manage sign-in credentials:** Signed-in users can change their password or request a verified email-address change in Account > Sign-in & security. Password changes require reauthentication, and email updates retain two-address confirmation.
- **Compare RTX processing on Windows:** While RTX Video Super Resolution is requested, the native player now offers a press-and-hold original-video comparison that bypasses and restores the video filter without changing saved playback preferences. NVIDIA App remains the source of truth for driver activation.
- **Consistent email branding:** Versioned light and dark Starlit wordmarks are available for account and notification email templates.

### Repository rename compatibility

- **Seamless transition for updated installations:** Before the repository rename, the desktop and Full Android update check still uses `Beelzebub2/nuvio-native`. If GitHub redirects or removes that releases API after the rename, the app checks the fixed `Beelzebub2/starlit` path instead. Existing update channels remain unchanged.
- **Support both installer paths:** The desktop downloader accepts release URLs from either repository name while retaining strict HTTPS/GitHub-host restrictions and SHA-256 installer verification. The installer, app data, package IDs and account configuration are unchanged.
- **Staged migration:** Publish this compatibility release under the original repository name before completing the separate rename. Desktop installations that skip this release may still require a one-time manual update after the rename.

### Validation

- Android Full and Play Store, iOS Kotlin, desktop authentication and GitGuardian PR checks passed for the PC-discovery/Bluetooth update. The Windows Bluetooth helper compiled and registered successfully, and desktop remote-protocol tests passed.
- Physical Android-to-Windows Bluetooth pairing and remote control have **not** been tested end-to-end on devices; hardware validation remains outstanding.
- Regression coverage exercises old and new repository endpoints, redirect and missing-repository fallback, preservation of rate-limit errors, rejected download URLs, and checksum metadata.
- The release workflow verifies the version policy, tests the Windows installer and builds the signed Android APKs before publication.

## Starlit 1.9.1

### Interface refinements

- **Consistent Starlit dialogs:** Sign-in, account-security confirmations, passkey and Google linking, add-on backups, and batch episode downloads now share the app's dialog styling. Text fields, switches, checkboxes, buttons and feedback follow the same design on desktop and Android.
- **Clearer downloads and phone pairing:** Offline storage management and cleanup confirmations, phone-to-PC remote controls, pairing QR codes and network selection have cleaner layouts and clearer status guidance.
- **Player and Airing Now polish:** Playback diagnostics, safe casting guidance, Airing Now selection popups and desktop player controls follow the same visual language. English and Portuguese labels are refreshed.

### Validation

- Desktop Kotlin source and test compilation passed. The six targeted anonymous-workspace regression tests pass from a clean isolated test-data directory.
- The release workflow tests desktop playback and the Windows installer, builds signed Android APKs, verifies release checksums and publishes only after its required jobs succeed.
## Starlit 1.9.0

### Offline downloads

- **Recover torrents that disappear from the local engine:** Desktop downloads now re-add a torrent when TorrServer temporarily loses its metadata entry or returns a missing-stream response. Recovery attempts are bounded and preserve the selected episode and existing verified partial download.
- **Retry temporary download failures:** Interrupted HTTP requests, including asynchronous timeouts, now enter the resumable retry path. Temporary HTTP errors are retried, and P2P streams have more time to connect to peers before the first response times out. Unavailable sources can still require a different provider.
- **Remove watched episodes to free storage:** In Downloads > Storage management, choose **Remove watched episodes** to review previously watched offline episodes, including manually selected and batch downloads. Files are preselected but never deleted automatically; users can deselect items before confirming. Only completed, verified Starlit-owned files are eligible, and watch history is retained.

### Validation

- The desktop download, queue, torrent resource and torrent file-selection suites passed 67 tests. The watched-cleanup and storage-management suites passed 6 targeted tests. The source/history security checks passed for both feature PRs.
- The release workflow tests the Windows installer, builds signed Android APKs, verifies checksums and publishes only after its required jobs succeed.

## Starlit 1.8.3

### Account recovery and security

- **Reset a forgotten password:** The official Starlit sign-in page now offers Forgot password in English and Portuguese. Reset links open a secure browser page, where you can choose a new password and return to the app. Requests use the same confirmation for existing and unknown accounts.
- **Account email notifications:** Approved accounts receive a welcome message. Security emails cover successful sign-ins, password and email changes, passkey additions and removals, linked sign-in methods, verified two-step verification changes, recovery-code changes, and disabled or deleted accounts. Sign-in alerts are limited to prevent email floods.
- **Reliable delivery:** Account emails use Starlit's configured mail server with verified encryption, bounded retries and background delivery. The recovery page clears credentials from the address bar and does not retain them in browser storage.

### Validation

- Desktop recovery tests and Android Full Kotlin compilation passed. Live mailbox tests verified actual recovery and account-alert receipt, the exact app request, rejection of untrusted redirects, password replacement, recovery logout and denial of reused reset links.
- Version metadata and authored release notes pass the repository release-policy checks. The release workflow verifies the Windows installer and signed Android APKs before publication.

## Starlit 1.8.2

### Performance

- **Avoid unnecessary Home catalogue requests:** Forced and timed Home refreshes no longer refetch cached catalogue rows that are disabled from both the Home screen and hero. Initial loading still warms hidden rows, missing cached rows are retried, and active hero sources continue refreshing normally.
- **Keep runtime labels off the blocking UI path:** Details, episode cards and Watch Later hover cards now format localized runtimes through Compose resources rather than synchronously waiting for translated strings during rendering. The visible labels and runtime parsing remain unchanged.

### Validation

- Both performance fixes passed Android Full, Android Play Store, iOS Kotlin and source-security CI. The runtime formatting change also passed two targeted Windows desktop Compose and parsing tests. Four Home refresh-selection regression cases were added. Device-level frame-time and real-world network-traffic improvements have not yet been quantified.
- The existing release workflow verifies and packages the Windows installer and signed Android APKs before publication.

## Starlit 1.8.1

### Fixes

- **Watch Later is accessible from Library on Android:** The Library / Watch Later selector now appears on phones, so saved Watch Later titles and episodes can be opened without switching to desktop. The selector has a larger touch target, and the Library icon remains selected while viewing Watch Later.
- **Cloud library explains separate service connections:** The Cloud files tab now distinguishes Starlit's Saved library from optional TorBox and Premiumize cloud-file services. A Starlit account sign-in alone does not connect those external services; the empty state explains where to configure them instead of incorrectly implying the Starlit session is disconnected. English and European Portuguese text is updated.

### Validation

- The fix passed Android Full, Android Play Store, iOS Kotlin and source-security CI. Desktop shared Kotlin compilation and 15 focused Cloud Library / Watch Later tests passed, with no failures. The existing release workflow builds and verifies the Windows installer and signed Android APKs before publishing.

## Starlit 1.8.0

### Account settings and sign-in

- **Organized desktop Account page:** Sign-in and security, passkeys, anonymous-settings import, phone connections and supported account actions now have clear sections and compact controls at wide and narrow desktop sizes. The signed-in account and server are visible.
- **Link Google on desktop:** Approved users of the official Starlit server can link a Google identity to their existing account after password confirmation, without replacing the current profile or its library. Registered passkeys can be inspected and removed on desktop; registering a new passkey remains available on Android.
- **More reliable browser authorization:** Closing the desktop Account page while Google consent is open no longer cancels the pending callback. Denied consent returns a clear message, stale callbacks are rejected, and logout, account changes and explicit cancellation stop pending linking. Google-link availability can be checked again without leaving Settings.

### Phone remote pairing

- **Desktop pairing QR code:** Account Settings now shows a QR code containing a local, private IPv4 discovery address when the PC listener is available. Users with multiple interfaces can choose the right address; reopening the dialog refreshes the available addresses after a network change.
- **Scan from Android:** The Android PC controller can scan the desktop code through Google Play services. Manual IP entry and local-network discovery remain available if the scanner is unsupported or cannot start. The desktop Connect phone action explains when a signed-in custom HTTP server cannot host pairing.
- **Existing pairing protections remain:** The code is only a discovery hint. The phone and PC must still be on a compatible local network, use the same Starlit account and server, and confirm matching six-digit pairing codes. Tokens, private keys and media URLs are never encoded in the QR image.

### Validation

- Desktop authorization-callback tests cover valid codes, rejected or malformed callbacks, consent denial and provider errors. Desktop QR tests cover scanned payload decoding, network switching, fractional screen scaling, supported servers and responsive Account layouts. Android Full and Play Store, iOS Kotlin, desktop-auth and source-security CI are required to pass before publishing the signed installers. Actual two-device QR pairing and real Google linking should also be checked on devices with valid accounts and Google Play services.

## Starlit 1.7.4

### Fixes

- **Desktop sign-in stays connected after restarting Starlit:** The desktop app now saves and restores its cloud account session. Saved sessions are tied to the configured account server, protected for the current Windows user, and retained when Starlit cleans up a previous guest workspace.
- **Recover from a missing desktop account session:** If an older Starlit version lost a session while keeping a local viewing profile, the Account page now offers **Sign In** to reconnect. While the app restores a session, the account card shows **Checking sign-in** instead of incorrectly reporting that the user is signed out.
- **Safe account-session cleanup:** Signing out removes both the saved session and its recovery copy. Session storage runs off the UI thread; Android and iOS authentication storage remains unchanged.

### Validation

- Desktop Kotlin compilation and the four targeted session-persistence tests passed twice, including after the final I/O scheduling adjustment. Tests cover restart restoration, guest-workspace cleanup, sign-out, server isolation and corrupted sessions. Android Full, Android Play Store, desktop-auth, iOS Kotlin and source-security CI passed for the fix. The signed installer and APK release workflow performs the final packaging checks.

## Starlit 1.7.3

### Fixes

- **Desktop torrents wait for usable episode metadata:** The player waits for complete video names and sizes instead of trying to select an episode from TorrServer's temporary incomplete entries.
- **Safer torrent engine shutdown and recovery:** An engine process that refuses to exit remains tracked, preventing its download cache from being cleaned up while still in use. Playback shows a useful error if shutdown also fails during recovery.
- **Android login stays usable after activity recreation:** Rotating the phone or recreating the login screen during a sign-in request no longer leaves the login controls permanently disabled.
- **Android Account Settings no longer crashes on Google linking:** The shared Google logo now uses an Android-supported vector resource instead of an SVG that crashed the signed-in Account page. The same replacement also protects the Google login controls.
- **Correct offline download source validation:** HLS playlists, DASH manifests and torrent files are rejected as direct video downloads even when their URLs contain fragments or query parameters.
- **Bounded provider cooldowns in gateway source:** The gateway now interprets rate-limit reset timestamps in seconds or milliseconds and caps provider-wide cooldowns at one hour. Deploying the gateway on the VPS is a separate server update.

### Validation

- Targeted Windows desktop regression tests passed for downloaded URL filtering, incomplete torrent metadata, TorrServer shutdown ownership and playback error recovery. Gateway tests and local account/sync backend tests also passed. The Android Google-logo regression was reproduced in the released 1.7.1 APK; Android Full and Play Store CI passed, and the corrected signed-in Account page stayed open in repeated isolated emulator checks. The existing release workflow verifies and signs the Windows installer and Android APKs before publication.

## Starlit 1.7.2

### Fixes

- **Torrent engine starts reliably after an interrupted Windows shutdown:** A TorrServer process left running by an earlier Starlit session no longer prevents the new session from opening its own engine state. Each desktop engine launch uses a separate runtime configuration directory while keeping saved app preferences and torrent piece cache intact.

### Validation

- Reproduced the failed torrent engine startup in Starlit 1.7.1 on Windows 11 with a surviving old TorrServer process, then verified recovery after removing the orphan. Desktop integration tests passed, including a new regression test that runs old and new torrent engines with independent state directories. Windows installer and signed Android release builds are verified by the release workflow.

## Starlit 1.7.1

### Fixes

- **Safer Android login and Account Settings startup:** Initializing a passkey provider is no longer required just to open either screen. Devices whose credential manager is unavailable can continue to the normal email/password login or account settings without the provider being started unexpectedly.
- **Recoverable passkey provider errors:** When a user explicitly requests a passkey operation, unavailable or misconfigured credential managers now report a normal passkey error instead of failing while the screen is being created. Passkey verification and account approval requirements are unchanged.

### Validation

- Added Android-host regression tests for opening the login screen, opening signed-in and signed-out Account Settings, and handling provider initialization failures during both registration and sign-in. Physical-device verification of the reported crashes still requires a device crash log or reproduction.

## Starlit 1.7.0

### Features

- **PC controller is always accessible:** On Android, open **Settings → PC controller** and choose **Search local network** even when automatic discovery has found no computers. The option is searchable in Settings; unsigned-in users see why discovery is unavailable. iOS shows the entry but does not support pairing yet.
- **Alternative local PC discovery:** When Wi-Fi broadcasting fails, enter a PC's private, same-subnet IPv4 address to request discovery. Previously paired PCs can be remembered and forgotten without storing credentials, security approvals, or reusable pairing keys. A matching six-digit code must still be approved on both devices for every new session.
- **Optional Wake-on-LAN:** After explicitly configuring a previously paired PC's network-adapter address, Android can send a local wake packet. Waking requires compatible PC, BIOS and network settings and is not guaranteed; this feature does not expose an internet-facing receiver.
- **Manage existing PC downloads from Android:** After secure pairing, view the current desktop profile's download progress and pause or resume its existing jobs. Commands cannot initiate downloads, inject links, access other profiles, delete files, or change destinations.
- **Send playback to a paired PC:** Choose **Play on Starlit PC** from supported titles, episodes, or mobile player controls. The PC shows a separate approval prompt and opens its usual source picker; no torrent, stream URL, token, or private file is sent from the phone.
- **Safe download storage tools:** See tracked offline storage and available space, set an informational storage target, and review verified Starlit-owned completed downloads for explicit, user-selected deletion. Targets do not stop transfers and no files are removed automatically.

### Player and settings

- **Playback diagnostics:** An optional player panel shows known playback state, resolution, buffer-ahead time, playback position, speed and app connectivity, with a copyable report containing only allowlisted metrics. Decoder and measured throughput remain unavailable until accurate platform metrics are supported.
- **Clearer account settings on phones:** Login, account security, linked Google identity, passkeys, import and account actions are grouped into more useful sections.
- **Casting compatibility information:** Android can explain why the current source cannot safely be sent to Chromecast or Android TV. **Actual Google Cast device selection and playback are not implemented in this release** and still require official sender/receiver integration.

### Security and compatibility

- Remote commands remain confined to the encrypted, same-account, same-server, private-LAN channel with independent device approval and profile/ownership checks. Local IP discovery, remembered PCs, Wake-on-LAN and playback handoff do not weaken this authentication.
- No DRM, paywall, provider restriction, or account-approval bypass was introduced. Local network operation and wake packets require compatible networks and device configuration. QR-camera pairing, automatic storage cleanup and Google Cast reception are not part of this release.

## Starlit 1.6.2

### Fixes

- **Startup loading screen stays clear:** The "Continue where you left off" episode card now waits until the Starlit logo and loading animation have completely disappeared, including their fade-out, instead of overlapping the startup screen. The splash also stays above floating app prompts.
- **Subtitle choices stay selected:** Manually selecting a subtitle track no longer silently reverts when the player refreshes its available tracks.
- **Episode information appears at the right time:** Video-player metadata and the paused-episode overlay now wait until playback has actually started, avoiding episode titles over a prepared but still blank player.

### Performance

- **Smoother Home catalog scrolling:** Poster appearance settings are observed once per shelf instead of once per card, and lazy-list item keys are reused to reduce repeated work during recomposition and scrolling.

### Validation

- Automated Android Full, Android Play Store, iOS Kotlin and security checks passed for the underlying fixes. Startup splash regression tests cover loading, fade-out, profile switching and no-splash launches.

## Starlit 1.6.1

### Fixes

- **Clear Android passkey registration results:** Account settings now report registration, verification and removal progress explicitly. Registered passkeys have visible names, dates, refresh controls and errors instead of an empty or ambiguous list; existing credentials remain manageable even when new passkeys cannot be created.
- **Google linking updates immediately:** Returning to Starlit after browser-based Google consent refreshes the current account's linked identity without having to navigate away and back. Feedback stays scoped to the correct signed-in account.
- **Recognisable Google sign-in controls:** The compact Google sign-in option and the existing-account Link Google control now display the multicolour Google mark instead of a plain letter.

### Security and compatibility

- The existing password-confirmed Google account linking, server approval and passkey verification requirements are unchanged. The account server's branded browser return page is a separate backend deployment and does not claim the link succeeded until the app verifies it.
- Automated desktop authentication tests passed. Android and iOS build checks are required to pass before publishing; physical-device Google linking and Bitwarden passkey registration should also be confirmed on real devices.

## Starlit 1.6.0

### Features

- **Link Google to an existing Android account:** Approved users on the official Starlit server can open Account settings and securely link their Google identity to the same account after confirming their password. The callback checks the original Starlit account and server before accepting the new sign-in method, preserving the account's library and history.
- **Compact Google sign-in and sign-up requests:** Login and account-request screens now offer a small circular Google sign-in option. The sign-up action still sends a request for owner approval; it does not automatically create or approve an account.

### Fixes

- **Bitwarden Android passkey registration:** The official server now publishes the additional Android Digital Asset Links permission required by Bitwarden for Starlit's signed APK. Credential Manager errors now explain how to update or change providers without disabling any verification.

### Security and compatibility

- Google account linking is offered only to approved users of the official server on Android, and only when Google OAuth is enabled. Existing email/password login and owner approval remain unchanged.
- Automated Android, desktop-auth, iOS Kotlin and security checks passed. A physical-device Bitwarden passkey retry and a real Google account-linking round trip remain to be verified on a user device; provider association caches can delay a Bitwarden retry after the server fix.

## Starlit 1.5.0

### Features

- **Windows phone-passkey sign-in:** On Windows devices with WebAuthn API 6 or later, approved Starlit accounts can use the native Windows Security picker to sign in using an existing passkey registered on an Android phone, including the phone/QR option. Passkey registration and removal still take place on Android.
- **Native passkey security:** The Windows ceremony binds its reply to the official Starlit relying party and the server's original challenge, requires user presence and verification, and cancels native operations when sign-in is canceled or times out.

### Availability and compatibility

- Passkey sign-in is available only after the official server enables it. It remains unavailable for custom account servers, macOS, Linux, and iOS; existing email/password sign-in is unaffected.
- Automated Windows API, build, and authentication tests passed. Scanning the QR code and unlocking with a physical phone have not yet been confirmed by an end-to-end device test.

## Starlit 1.4.1

### Authentication support

- **Android passkeys:** Added sign-in, registration, listing, and password-confirmed removal for approved accounts on the official Starlit server. These controls stay hidden until server activation and signed Android device verification are complete. Desktop, iOS, and custom servers do not offer passkeys.
- **Google sign-in and access requests:** Added client support on Windows, Android, and iOS, preserving guest settings and owner-approved registration. Controls appear only after the official server explicitly enables Google. Production Google login remains disabled until a real Google Web OAuth client is configured and tested.

### Security

- Google sign-in uses PKCE authorization codes. Desktop callbacks bind only to the local loopback interface on a random port and path; token injection, duplicate codes, and unrelated callback paths are rejected. Mobile callbacks accept codes only for the official server.
- Neither authentication method is activated by installing this release. Existing email/password login remains available.

### Fixes

- Google sign-in can complete after the mobile app restarts while its browser is open. The guest workspace is preserved until the authorization code is verified, then the approved account can be selected safely.

## Starlit 1.3.0

### Features

- **Control desktop playback from Android:** An Android phone signed in to the same Starlit account and server can discover and securely pair with the desktop app on the same local network. Compare the matching codes on both screens, then control play/pause, seek, volume/mute, intro and credits skipping, episodes, sources and subtitles. Open **Settings → Desktop remote** on Android to connect. This supports the built-in desktop player on private IPv4 networks; iOS and external players are not supported yet.

### Fixes

- **Only one desktop Starlit window:** Opening the app again now brings the running window to the foreground instead of starting a duplicate. Supported `nuvio://` and `stremio://` links are delivered to the existing window, including during startup.
- **Readable source selection on 1080p displays:** Long source names no longer make the recommended-source card crowd out other results. The full metadata remains available in the source list, with usable layouts at common display scaling levels.

## Starlit 1.2.4

### Fixes

- **Android torrent downloads:** Directly chosen torrent sources and automatically selected torrent episodes can now be downloaded for offline viewing when P2P is enabled in Settings. Android uses a separate native torrent engine for downloads so playback is not interrupted.
- **Safer offline transfers:** Native torrent metadata identifies the correct video in multi-file torrents rather than trusting stale episode indexes. Resume state is tied to the exact torrent file, and invalid HTTP ranges are rejected instead of risking a corrupted saved episode. Native loopback requests do not forward provider credentials or follow redirects.
- **Redesigned mobile Downloads:** Added an offline-library overview, artwork cards, clearer download states and progress, more readable errors, accessible pause/resume/retry/play controls, compact simultaneous-transfer settings, and selection mode for bulk removal. Updated English and Portuguese translations.

## Starlit 1.2.3

### Fixes

- **Android Airing Now:** Restored the Today and next-six-days selector, with an option to return to recently aired episodes on the full page. Text, icons, selected-day controls and hide actions now have readable contrast.
- **Mobile home banner:** The rotating hero now loads eligible trending movies or shows when addon catalogs are empty, and can fall back to the built-in Airing Now schedule when that data source is unavailable. Broken artwork falls back to posters, and an empty carousel no longer reserves a blank space.
- **Reliable torrent episode downloads:** Season packs can identify the requested episode from verified torrent metadata when a provider's filename is missing or stale. Automatic downloads try other eligible sources when selection is ambiguous, protect against selecting a different episode, and preserve partial torrent downloads on retry.

## Starlit 1.2.2

### Fixes

- **Responsive Windows startup with large offline libraries:** Saved downloads are verified in the background so even very large episodes no longer keep the app on its loading spinner.
- **Safe offline playback and account switching:** Files become playable after their complete integrity check; interrupted checks are canceled when changing profiles, and existing files are preserved.
- **Reliable desktop release checks:** Completed-download UI fixtures include the integrity proofs required by the new verifier, allowing the full desktop test suite to validate this patch correctly.

## Starlit 1.2.1

### Fixes

- **Windows startup no longer waits for downloaded episodes to be fully scanned.** Large offline files are verified in the background after profile selection, so Starlit can open without remaining on the loading spinner.
- Completed downloads are available for offline playback only after their saved content proofs have been checked. Changed or missing files remain untouched and are reported as unavailable; profile switches cannot apply stale verification results to a different profile.

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
