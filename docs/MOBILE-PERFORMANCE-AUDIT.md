# Mobile performance and responsiveness audit

Scope: Android Full and Play Store distributions, plus shared Compose code used by Android and iOS. Findings are based on source inspection of the `Dev` branch on 2026-11-14. A debug APK or emulator cannot stand in for release-build profiling on physical devices.

## Immediate improvements

| Area | Source-level cause | Change | Expected effect |
| --- | --- | --- | --- |
| Android launch | The Activity initializes the download scheduler before `setContent`. The scheduler constructs the transfer store, which enumerates and decodes persisted transfer files synchronously. | Retain application context at Activity startup; open the scheduler on first repository, notification, worker, or transfer access instead. | Remove transfer-store disk I/O from the Activity's first-frame path. Persistence and transfer restoration still use the same store. |
| Navigation | Floating and desktop navigation bars read the exact Compose scroll offset although their appearance only depends on crossing a threshold. | Observe a derived threshold Boolean. | Avoid invalidating the entire bar on intermediate pixel updates; preserve scroll-dependent appearance. |
| Series details | Season chips and episode cards synchronously block on Compose string resources while being composed. | Resolve the same localized resources with `stringResource` in composition. | Avoid blocking the UI dispatcher on season and episode labels, including within lazy rows. |

Neither change alters the transfer format, player, authentication, or visible UI strings.

## Remaining opportunities, ordered by likely user impact

1. **Cold startup / first usable screen (high):** The Android Activity currently performs dozens of platform initializations before `setContent`. Record a Perfetto startup trace, identify disk I/O and SDK initialization on the main thread, and move nonessential setup off the first-frame path. Do not defer services required by an incoming intent, playback restoration, a foreground worker, or crash-consent guarantees.
2. **Home catalogue refresh (high):** `HomeRepository.refresh` builds catalogue definitions synchronously before launching fetch work. `buildHomeCatalogDefinitions` uses `runBlocking { getString(...) }` per catalogue. A refresh can originate from the UI dispatcher. Batch localized title construction, cache by descriptor and locale, or move preparation behind a serialized background boundary. Guard against stale results after profile/add-on changes.
3. **Profile entry (high):** `AppGate.selectProfile` waits for a large, sequential `warmProfileBoundRepositories` block before exposing the main screen. Profile selection itself runs on `Dispatchers.Default`, and the server pull is scheduled asynchronously. Trace individual local warm-up costs, then separate critical Home prerequisites from noncritical work without displaying cross-profile data.
4. **Download progress notifications (medium):** The Android live-status builder uses synchronous resource lookups, and transfer callbacks can request a new notification repeatedly. Rate-limit progress-only renders while delivering pause/failure/completion immediately; verify foreground-service notification requirements.
5. **Poster rendering and graphics (medium):** Coil already has memory/disk caching, but Home and detail views also apply image blur and Haze effects. Profile GPU composition, decoded artwork sizes, and lazy-list jank on lower-end devices before reducing animation quality globally.
6. **Catalogue and tracking network work (medium):** Home fetches catalogue rows independently with a concurrency limit of four and 30-second per-row timeout; the watched-badge resolver may request metadata for multiple series. Measure duplicate or unnecessary requests under reconnection, rapid profile changes, and scrolling, preserving cancellation and existing cache semantics.
7. **Playback time to first frame (medium):** Android uses Media3 ExoPlayer and already records preparation timing diagnostics. Measure initial frame, decoder fallback, and seek under local H.264/AAC, HTTPS, and P2P sources separately. Avoid codec and buffer changes without actual device evidence.
8. **iOS-specific work (unmeasured):** Shared Compose fixes apply, but iOS navigation and startup additionally use SwiftUI/UIKit wrappers. Run Instruments on macOS with physical iPhone hardware; Android results cannot establish iOS performance.

## Repeatable acceptance plan

- Compare release/profileable builds at the same revision, with identical data and network conditions; use a cold process start, warm start, guest and signed-in profiles.
- Capture median and p95 first-frame, first-content, and first-interaction latency over at least ten runs. `adb shell am start -W` is only an Activity launch metric, **not** time-to-interactive or Home-content readiness.
- Sample janky frames and 90th/99th percentile frame time while scrolling Home, Search and Library; sample memory, allocated bitmap bytes and GC pauses.
- Repeat with empty and substantial transfer stores, small and large installed-addon catalogues, low/high latency links, and a memory-constrained Android device. Check that background downloads survive process death and restore correctly.
- Check player time-to-first-frame and seek time separately from source discovery and torrent peer startup. Measure foreground-service response to pause/resume and notification updates.
- Require Android host tests, APK builds, and a physical-device smoke pass for download lifecycle, profile switching, and navigation before merging. iOS changes require a separate Xcode build and device check.

No improvement percentages are claimed without comparable before/after profiling.
