Nuvio Native 1.0.8 adds MyAnimeList account controls and improves anime browsing and playback startup.

- Anime details now read and update actual MyAnimeList statuses and ratings. Connect through Settings → Tracking → MyAnimeList using the in-app public-client setup. OAuth PKCE uses a loopback callback, and account tokens are protected by Windows DPAPI per Nuvio profile. Rating changes send only the score field. Unknown titles can be linked to the correct MAL entry explicitly.
- The extra local My List tab is removed. Previously saved local records remain on disk; they are not silently transferred to a MAL account. Watch Later remains separate.
- Right-click an episode in details, Continue Watching, Watch Later, or the native airing catalog to open its parent title's details. Middle-click still saves the specific episode. Hovering an episode shows its parent synopsis.
- Airing Now combines today's aired episodes and recent releases into one feed. The All and Next 24 hours filters are removed. A seven-day anime schedule appears below it, using the selected time zone and broadcast/sub/dub source. Requests remain within the existing Worker gateway bounds.
- English subtitles become the desktop default once for unset/legacy disabled preferences. Later explicit subtitle choices remain intact.
- Episode metadata lookup has an eight-second fallback to the original playable ID. Source preparation cleans up loading flags on failures; stalled automatic lookup falls back to source selection and retry instead of remaining in a loading screen. Movie and other series playback remains supported.
- Concurrent scraper runtimes serialize native creation and closure to prevent an upstream QuickJS JNI instance-counter race. Search execution stays concurrent, and canceled runtimes still close before a fresh search starts. Stress and cancellation checks cover this lifecycle.

Windows tag releases and authenticated private updates retain SHA-256 verification. This installer has no Windows Authenticode certificate. Source and upstream GPLv3 notices remain in this private repository.

Live MyAnimeList account authorization requires the user's API client ID and approval. Tests use local API fixtures; the original intermittent startup failure cannot be guaranteed reproduced on every machine.

References: [MAL authorization](https://myanimelist.net/apiconfig/references/authorization), [MAL API v2](https://myanimelist.net/apiconfig/references/api/v2), [Compose tooltips](https://developer.android.com/develop/ui/compose/components/tooltip).
