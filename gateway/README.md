# Starlit metadata gateway

Android and desktop use `https://starlit-api.rrmtools.uk/gateway`. The fixed,
read-only provider handler in `worker.mjs` runs on the VPS through `server.mjs`
(Node 22 or newer, no runtime dependencies). The backend Compose configuration
runs it on `127.0.0.1:8790` and Nginx removes the `/gateway/` prefix. DNS and the
existing Cloudflare Tunnel continue to provide HTTPS ingress.

Deploy this directory to `/root/Projects/ricardo/starlit/gateway`, alongside the
existing `backend` directory. Do not copy `node_modules`, `.wrangler`, or private
configuration. Follow `backend/vps/README.md` for container startup and rollback.
Locally, run `npm start`; `npm ci && npm test` also exercises provider contracts,
the actual Node HTTP server, cache bounds/expiry, body limits, timeout handling,
and the legacy Worker in Miniflare.

The response cache retains at most 512 entries and 64 MiB of bodies in memory;
fresh/stale entries share that budget. It expires according to response TTLs,
evicts oldest insertions under pressure, and resets on restart. Provider responses
remain limited to 5 MiB and 12 seconds; the adapter accepts at most eight concurrent
requests, 16 KiB of headers, and returns 503 with a short retry during overload.
Account and sync data are separate PostgreSQL services and never enter this cache.

`legacy-worker.mjs` is the only remaining app gateway Worker: installed releases
using the original `workers.dev` address forward GET requests to the fixed VPS
prefix. It does no provider work or application caching and does not forward
client cookies/authorization. `wrangler.jsonc` and `npm run deploy` deliberately
target this compatibility shim. Deploy it only after public VPS checks pass; keep
the preceding Cloudflare version/source for rollback. The separate approval email
Worker remains in use. New releases use the VPS URL directly; `NUVIO_AIRING_GATEWAY`
still overrides the URL for development.

## Anime viewing order (read-only, opt-in UI)

The VPS metadata gateway also offers
GET /watch-order?malId=30831&mode=recommended. The recognized modes are
recommended (default), release, and chronological. It resolves a positive MAL
anime ID using the Junban franchise directory, then uses Jikan-compatible
MAL sequel/prequel relations from the Tenrai service when safe. Only the
version-controlled Starlit override for KonoSuba's main progression is marked
"verified"; other inferred results are identified as inferred or partial.
Unknown chronology fails closed rather than fabricating viewing order.
Movie MAL IDs also resolve when the movie has an explicit Tenrai Prequel
link to one matching Junban TV series; unrelated franchise entries or
ambiguous prequels are not joined. Unknown movie dates are kept adjacent
to their source installment in release mode instead of moved to the end.

Junban's API and database are independent projects with their own terms.
Attribution: Junban (https://junban.moe), database ODbL 1.0. Its franchise
membership should not be confused with verified viewing order. Verify
provider licensing, attribution and any share-alike obligations before
production deployment. Tenrai is an independently hosted, Jikan-compatible
beta service, **not** the retired api.jikan.moe public endpoint.

The gateway has fixed upstream hosts, positive-ID validation, bounded provider
responses, 4.5-second provider request timeouts, shared six-hour cache, seven
days of stale data, concurrent-request coalescing and host-specific cooldowns
for 429/5xx. It sends no account credentials, watched history, media sources,
or tokens. An upstream outage doesn't block other metadata routes or change
existing episode autoplay. The app UI opens normal Starlit search for the next
title instead of making an unverified playback-ID guess.

Run node --test watch-order.test.mjs for fixture, error and cache tests.

## Episode artwork

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
fallback, store your own **API Read Access Token** as `TMDB_READ_ACCESS_TOKEN` in
the existing root-private `/etc/starlit/runtime.env` (mode 0600), then recreate only
the backend Compose `metadata` service. Preserve every existing environment value.
Do not put credentials in source, URLs, command arguments, or logs. The VPS uses
the token only in an upstream Bearer header. No app rebuild is needed after setup.
The previous Worker had no TMDB token configured; the migration preserves that
state. A missing token skips TMDB without imposing a cooldown or preventing TVmaze.

## Opt-in usage diagnostics

The VPS Node gateway exposes `POST /telemetry/events` for opted-in clients. It
accepts only fixed event names, platform, app version and a random 128-bit
installation ID. It rejects unknown fields and any media, account, search or
URL data. The ID is HMAC-hashed **on the gateway** before being sent to the
separate, private `starlit_usage` logs stream in the self-hosted OpenObserve
instance. Raw IDs, IP addresses, user agents, request headers and URLs are not
exported. Crash reporting remains an independent Sentry opt-in.

Configure these server-only variables in the existing root-private
`/etc/starlit/runtime.env` (mode 0600):

- `TELEMETRY_HMAC_KEY`: a stable random secret of at least 32 characters.
  Changing it changes pseudonymous identifiers and distinct-installation counts.
- `OPENOBSERVE_INGEST_URL`: the **server-reachable** JSON logs endpoint, such as
  `http://openobserve:5080/api/default/starlit_usage/_json` on a private Docker
  network, or an HTTPS endpoint. Do not point container traffic at the public
  Cloudflare Access dashboard login page.
- `OPENOBSERVE_INGEST_AUTH`: `Basic <base64(username:ingest-token)>` from a
  dedicated OpenObserve ingestion-only credential. Keep it in the server env;
  never place it in app builds, logs, source, or public Cloudflare configuration.

The gateway returns 503 with no outbound request unless all three settings
are valid. It returns 202 only when OpenObserve confirms the record was ingested.
In the VPS `backend/vps/compose.yml`, the `metadata` service's `environment:`
must explicitly forward all three values from the runtime env. Merely adding
them to `/etc/starlit/runtime.env` does not pass them into the container:

```yaml
OPENOBSERVE_INGEST_URL: ${OPENOBSERVE_INGEST_URL:-}
OPENOBSERVE_INGEST_AUTH: ${OPENOBSERVE_INGEST_AUTH:-}
TELEMETRY_HMAC_KEY: ${TELEMETRY_HMAC_KEY:-}
```

Recreate just the `metadata` service with the existing Compose deployment
procedure and `--env-file /etc/starlit/runtime.env`; do not log environment
values. The deployment currently uses `network_mode: host`, so
`http://127.0.0.1:5080/api/default/starlit_usage/_json` reaches OpenObserve
without exposing its ingest port publicly.

OpenObserve errors or a four-second timeout return 502; the app treats analytics
as best-effort. A four-request concurrency budget, per-installation minute cap,
and 100,000-event daily process cap keep telemetry from starving provider
requests. Configure edge IP rate limits too, because client installation IDs are
unauthenticated. Existing GET routes and the old `workers.dev` compatibility
shim are unchanged; the shim does not forward telemetry POST requests.

**Retention:** Set the `starlit_usage` stream's data retention to **30 days** in
OpenObserve. The collector no longer writes JSONL files and does not expose an
operator summary endpoint. Restrict stream access to authorized operators.
In the OpenObserve Logs or Dashboard interface, select `starlit_usage` and query:

```sql
SELECT version, platform,
       COUNT(DISTINCT installation_hash) AS active_installations,
       COUNT(*) AS app_opens
FROM "starlit_usage"
WHERE event = 'app_open'
GROUP BY version, platform
ORDER BY active_installations DESC;
```

For feature usage, query `SELECT event, COUNT(*) AS events FROM "starlit_usage"
GROUP BY event ORDER BY events DESC`. Choose the 1–30 day time window in the
OpenObserve UI. These are *consenting active installations*, not all users or
unique real people.

**Deployment:** Nginx must forward `/gateway/telemetry/` to the metadata Node
service after stripping `/gateway/`. Ensure the metadata container can reach
OpenObserve over a private route before releasing new clients. Test both the
public telemetry endpoint and the stream query; exclude request bodies and IDs
from reverse-proxy logs. Keep the OpenObserve UI protected by Cloudflare Access.

The VPS's existing generic `/gateway/` Nginx location disables request bodies
for its read-only metadata providers (`proxy_pass_request_body off` and an empty
`Content-Length`). **Do not reuse it for the telemetry POST:** add a more-specific
`/gateway/telemetry/` location which forwards request bodies to
`http://127.0.0.1:8790/telemetry/`, keeps the original client-IP rate limit and
does not log bodies. Apply a small request body cap (such as 2 KiB), check with
`nginx -t`, and verify that existing metadata GET routes still work. Otherwise
the collector will receive an empty body and reject opted-in events with 400.

The app's existing TMDB settings remain for its separate, optional general
metadata enrichment. That local API-key setting does **not** configure the gateway
or automatically upload a credential. This fallback requires the server token
above; a v3 API key is not the same as an API Read Access Token.

Successful artwork responses are cached for an hour in the app and shared VPS
cache; misses are cached for 60 seconds. The gateway retains stale successful
responses for outages and enforces independent per-provider cooldowns. Requests
fetch whole seasons/lists, not individual episode cards. TVmaze attribution and
its CC BY-SA 4.0 license are included in Settings → Licenses & Attributions.
