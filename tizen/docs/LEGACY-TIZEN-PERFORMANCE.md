# Legacy Samsung Tizen responsiveness audit

This audit covers the 2019 Tizen 5.0 / Chromium 63 compatibility tier and the
shared TV client. Findings derived from source/build inspection are **not**
hardware performance measurements. See [device acceptance](DEVICE-ACCEPTANCE.md)
for the physical-TV release gate.

## Work already in place

| Area | Current behavior | Verification still required |
| --- | --- | --- |
| UI/video resolution | The interface uses a 1080-high reference canvas; native AVPlay remains responsible for suitable 4K video. | First frame, seek and 4K AVPlay compositing on real firmware. |
| Legacy effects | The runtime profile selects `performance-constrained` and `legacy-tizen`, disabling heavy CSS effects and using a single hero artwork layer. | Frame timings on Home during long D-pad holds. |
| Navigation | Stale key repeats older than 240 ms can be dropped, and accepted held repeats are throttled on constrained devices. | Physical remote button-to-visible-focus latency; event timestamps and dropped press counts. |
| Home | Catalog snapshots, bounded poster hydration/eviction and cached focus lookup avoid broad DOM work. | Memory growth while traversing large catalogs. |
| Streaming | HLS/DASH libraries are not warmed during constrained-TV startup; AVPlay is preferred for suitable UHD HLS. | Codec fallback, AVPlay start/seek, first-byte and first-frame timings. |
| Plugins/services | Optional plugin service starts on demand; unsupported runtime paths are gated. | CPU and RAM under active plugin/source use, not only at idle. |

## Confirmed gap fixed in this branch

The non-Home poster loader previously returned early if the runtime lacked
`OffscreenCanvas`. Tizen 5.0 does not expose that API, even though it supports
`IntersectionObserver`. Search, Library, see-all and related-title poster images
therefore missed the visibility-based loading gate. These screens now defer
eligible offscreen TMDB images through `IntersectionObserver` independently of
worker image resizing. Visible posters are hydrated immediately, and a browser
without either supported mechanism retains its previous image path. No
main-thread canvas resizing or additional network privilege is introduced.

Regression coverage runs through `npm test`, including offscreen, immediately
visible and unsupported-observer paths. This change is expected to reduce
offscreen image downloads/decodes on those screens; its actual effect on RAM,
frame time and perceived response remains to be measured on physical Tizen 5.

## Remaining opportunities (prioritized for measurement)

| Priority | Candidate | Evidence / constraint | Next check |
| --- | --- | --- | --- |
| High | Reduce startup parsing and stylesheet work. | A local minified Tizen build produced a **3,870,744-byte app script**, **165,089-byte core-js script**, and **590,575-byte CSS bundle**. The first boot parses the application IIFE and polyfills before routing. | Record script evaluation and boot marks on device; profile the largest synchronous imports before designing Chrome 63-compatible deferred chunks. |
| High | Bound all non-Home poster decodes. | The new observer covers eligible TMDB poster images, not arbitrary add-on image hosts. Tizen 5 cannot use the current OffscreenCanvas JPEG worker. | Track decoded image memory and failed image hosts after repeated Library/Search visits; only expand to trusted or safely fall-back hosts. |
| High | Observe and remove long tasks in the focus hot path. | Input throttling already exists, but synchronous DOM geometry/scroll code still appears in individual screens. Removing forced layout without an actual trace risks reintroducing scroll/focus bugs. | Capture key event-to-focus and Chrome performance traces around Search, Library and Settings; target any proven forced reflow. |
| Medium | Right-size artwork for each surface. | TMDB 4K TV poster cards retain `w500` for density; hero backdrops use up to `w1280`. Reducing these broadly would trade clarity for memory and is not justified without measurements. | Compare image memory/quality at existing source sizes on FHD and UHD sets. |
| Medium | Remove playback work from the UI thread. | The streaming metadata/HTTP service and native AVPlay are separate from the UI; actual seek and first-frame behavior remain device-specific. USB cache is storage, **not additional RAM**. | Profile direct MP4, licensed HLS and authorized multifile torrent cases separately, including RAM/USB fallback. |

Optional plugin/QuickJS, HLS and DASH assets appear in the distribution but are
not evidence that all those assets execute at startup. Check actual script
requests and parse stacks rather than treating package size as launch memory.

## Repeatable physical-TV protocol

1. Record firmware, free storage, active app version, signed WGT SHA-256 and the
   test mode. Perform at least five true cold launches, separating bundle
   evaluation, first routed screen and first Home paint using the existing
   boot-timing diagnostics. Do not compare a warm cache with a cold cache.
2. With local opt-in `?perf=1` instrumentation, collect
   `window.__NUVIO_PERF__.snapshot()` after each 30-second Home, Search and
   Library run. Compare p50/p95 frame intervals, over-50-ms frames and the
   number of fullscreen layers before and after the change.
3. Using the **physical** remote, repeat single directional press, held-repeat,
   Enter and Back runs. Measure keydown-to-visible-focus latency with the
   Web Inspector timeline or a high-frame-rate recording. Keep first presses
   distinct from held key repeats and check for lost or duplicated actions.
4. Check app-process RAM, image decode counts and foreground/background
   recovery. Revisit a large Home catalog and Library/Search list repeatedly;
   observe retained image memory and long tasks.
5. Use authorized local fixtures to measure AVPlay first byte, first frame,
   buffering, seeks and cleanup with both RAM and explicit USB-cache modes.
   Repeat after standby and a network interruption. Never use an inferred
   torrent-file-size limit as a proxy for device RAM.

**Release status:** No new runtime-performance change is accepted for retail
TVs without the exact-package hardware matrix in `DEVICE-ACCEPTANCE.md`.
