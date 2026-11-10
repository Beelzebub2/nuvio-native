# Optional Starlit diagnostics

Reliability diagnostics and crash reports are **separate opt-in choices**, both
off by default for new installations. Existing crash-report preferences are
preserved. A consent prompt appears after entering the app, and either choice
can be changed in Settings > Advanced. Neither option is required.

## Anonymous reliability diagnostics

Only fixed health codes are accepted:

- **Info:** app launches, successful playback readiness and completed downloads.
- **Warnings:** slow playback startup, mobile download retries and storage-quota pauses.
- **Errors:** playback failures, torrent-engine startup failures and download failures.

The server derives severity and component from each fixed code. OpenObserve
records only app version, platform, code, severity and component. There are
**no screen visits, search events, playback titles, media or episode IDs,
source URLs, server names, account IDs, credentials, stack traces or free-form
error messages** in this stream. The server acknowledges and discards older
clients' screen-visit, player-opening and download-queue-snapshot events.

An opt-in client supplies a random **session-only** token for gateway rate
limiting. The server hashes it only in memory, and **does not store either
the token or its hash**. The app no longer persists a diagnostics installation
identifier and erases an identifier left by older app versions. As with any
network connection, the reverse proxy sees the source address to serve the
HTTPS request; telemetry itself does not save the address or request body.
Configure the reverse proxy not to log these bodies.

Turning off diagnostics cancels queued work and rotates the session token.
Previously ingested records expire according to OpenObserve's configured
30-day retention. Old schema-1 records may temporarily retain a hashed
installation identifier until they expire. Event totals reflect consenting
reports, **not** distinct users, devices or watched titles.
If the network or telemetry server fails, normal app operation continues.

## Optional crash reporting

Sentry reports are enabled independently of reliability diagnostics. When
explicitly enabled, the Android and desktop SDKs provide stack traces and
limited app, OS and device technical metadata, with the existing request,
user and exception-message sanitization. Screenshots, UI hierarchy,
tracing/replay and breadcrumbs (including navigation and network
destinations) are disabled. iOS Sentry reporting is not currently supported.
Disabling this option prevents future reporting.

## Deployment and operational safeguards

See [gateway configuration](../gateway/README.md#opt-in-usage-diagnostics) for
the private ingestion endpoint, server-only HMAC key and credentials,
request-body and rate limits, schema validation, 30-day retention and
verification. Ingestion fails closed without required settings.
Signing into an account does not opt a device into either diagnostics option.

Before public release, ensure the official website privacy policy describes
the two consent categories, the diagnostic fields, retention and contact for
privacy requests. Check that the public reverse proxy does not log telemetry
request bodies.
