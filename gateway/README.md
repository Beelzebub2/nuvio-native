# Episode artwork

Native Airing retains AniZip episode images first. Missing artwork is filled from
whole TMDB seasons, then TVmaze's show episode list. Playback/download identities
remain Fribb's anime identities. Matching requires a verified series ID, season,
episode number (including Fribb's provider-specific offset), and exact air date.
Missing dates, ambiguous records, future/unavailable episodes, and unknown mappings
keep the neutral placeholder. Existing images are preserved.

The Maomao shorts (AniList 218532 / MAL 65119) have an explicit **artwork-only**
identity: TMDB show 220542, specials season 0. The official [specials list](https://www.themoviedb.org/tv/220542/season/0)
was checked on 2026-10-21: October 5/12/19 are TMDB specials 51/52/53. The same dates
have different TVDB special numbers. The adapter matches dates, never a blanket
episode offset. Those three independently verified dates can supply artwork when
the source metadata has no dates, without changing release/availability fields.
TVmaze's main-series artwork is not used for these shorts.

## Optional TMDB setup

TVmaze works automatically without credentials. To enable the preferred TMDB
fallback, obtain your own **API Read Access Token** from your TMDB account's API
settings and run the pinned Wrangler CLI from `gateway/`:

```powershell
npm ci
npx wrangler secret put TMDB_READ_ACCESS_TOKEN
```

Paste the token at the masked prompt. This updates the existing
`nuvio-native-gateway` Worker. Do not put credentials in source, URLs, configuration,
command arguments, or logs. The Worker uses the token only in an upstream Bearer
header. No app rebuild is needed after setup. A missing token skips TMDB; it does
not impose an upstream cooldown or prevent TVmaze.

The app's existing TMDB settings remain for its separate, optional general
metadata enrichment. That local API-key setting does **not** configure the Worker
or automatically upload a credential. This fallback requires the server token
above; a v3 API key is not the same as an API Read Access Token.

Successful provider responses are cached for an hour in the app and shared Worker
cache; misses are cached for 60 seconds. The Worker retains stale successful
responses for outages and enforces independent per-provider cooldowns. Requests
fetch whole seasons/lists, not individual episode cards. TVmaze attribution and
its CC BY-SA 4.0 license are included in Settings → Licenses & Attributions.
