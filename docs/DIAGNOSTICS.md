# Optional Starlit diagnostics

Usage analytics and crash reporting are separate **opt-in** choices. Existing
saved crash-reporting preferences are retained; new installations have neither
reporting category enabled until the user chooses. After entering the app for the
first time, users see a consent prompt with independent switches, both off by
default. Declining is remembered, and either option can be changed later in
Settings → Advanced. Neither choice is required to use the app.

## What usage analytics report

Only allowlisted events are transmitted: application opens; Home, Search,
Library and Settings visits; player openings; and download starts, completions
and failures. Each event contains the app version, platform (Android, desktop,
iOS), fixed event code and a random installation ID. It does not contain names,
media IDs, watched content, search terms, IP metadata, account identity, emails,
URLs, addons, authentication data or device-specific hardware fingerprints.
The gateway necessarily sees the connection address to service the HTTPS
request, but the telemetry collector does not write it to its records.

The ID exists only for distinguishing active installations across app versions;
the gateway HMAC hashes it before sending a record to the self-hosted OpenObserve
`starlit_usage` stream. OpenObserve stores the hash, platform, version and fixed
event name, never the raw installation ID.
Turning analytics off cancels queued sends and removes the local identifier.
Past records are not immediately erased when a user opts out, but expire after
the stream's configured 30-day retention period. A subsequent opt-in uses a fresh ID.

Authorized operators can query OpenObserve for version and platform counts,
with active-installation estimates and event counts for a 1–30 day window.
These figures only reflect *consenting active installations*, not total
installs or individuals. Network failures never interrupt Starlit.

## Crash reports

Sentry receives error stack traces and platform diagnostics only when its
separate setting is enabled. Sentry's existing event sanitization and redaction
remain in force. Crash diagnostics are managed independently of usage
analytics and do not use the usage-statistics installation identifier.

## Deployment and operational safeguards

See [the gateway configuration](../gateway/README.md#opt-in-usage-diagnostics)
for the HMAC key, private OpenObserve ingestion endpoint, dedicated server-only
credentials, HTTPS path, 30-day stream retention and verification steps. The
gateway endpoint is disabled unless all secrets and the endpoint are configured.
Installations do not upload analytics on the basis of signing into an account.

Before public release, update the user-facing privacy policy on the official
site to describe both categories, the collection purpose, retention and contact
for privacy requests. Confirm the reverse proxy doesn't log request bodies.
