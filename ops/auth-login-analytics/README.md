# Starlit authentication audit to private OpenObserve

This read-only exporter records sign-in security data without changing GoTrue
login endpoints, blocking authentication on telemetry outages, or adding a
client-controlled analytics endpoint.

## Collected events

| Event | Source | Fields |
| --- | --- | --- |
| Successful sign-in | Committed GoTrue auth.audit_log_entries | Time, method, public client IP when reliably established, approximate city/region/country |
| Failed token request | Nginx 4xx POST /auth/v1/token for a recognized interactive grant | Time, grant method, status, trusted public client IP, approximate city/region/country |

Records also carry a random event ID for distinct counting after retries.
No email, account/user ID, tokens, passwords, request bodies, raw URL, query
strings, precise coordinates or user agents are exported. **An IP address is
personal data**: restrict the stream and dashboards to operators and disclose
this security processing in the Starlit privacy policy. Configure starlit_auth
stream retention to **30 days** in OpenObserve; the gateway failure file
rotates after seven daily generations. Locations are approximate and can be
missing if the local GeoLite2 database cannot resolve an IP.

Failed password/OAuth requests before enabling the Nginx audit log cannot
be reconstructed. Cloudflare-blocked requests, recovery requests, refresh-token
failures, other GoTrue endpoints and app-side authentication errors are not
counted as failed sign-ins. Successful sign-ins are backfilled at most 30 days.
The event outcome field distinguishes success from failure.

## Deployment (existing /root/Projects/ricardo/starlit on VPS)

1. Back up the database, existing backend/vps/nginx.conf,
   backend/vps/compose.yml and /etc/starlit/runtime.env. Create a new
   high-entropy PostgreSQL password. Apply 005-auth-login-analytics.sql
   as the local PostgreSQL administrator. It creates a NOLOGIN role: enable
   LOGIN with the generated password and append only AUTH_LOGIN_DATABASE_URL
   to the root-owned runtime env. Do not write a credential into Git.
2. Copy this directory to backend/vps/auth-login-analytics, including the
   package lockfile. Insert the Nginx directives from
   nginx-auth-audit-snippet.conf into the existing http block. Inside the
   exact /auth/v1/token location keep safe stdout logging and add the
   conditional auth failure log.
3. Create /var/log/starlit/auth-failures.jsonl with mode 0660 and owner
   root:101 (the Nginx worker group). Mount the file into the gateway
   read/write and into the exporter read-only. Install logrotate-auth.conf
   under /etc/logrotate.d. Never expose the log publicly.
4. Add auth-login-analytics to the existing Compose services using
   compose-service.snippet.yml. This nonroot, read-only container has no
   additional capabilities. Its PostgreSQL role can only execute the
   limited login-success function and update two cursor rows. It uses
   the existing private ingestion-only credential; the exporter changes
   the stream name from starlit_usage to starlit_auth.
5. Validate Nginx syntax, docker compose config, SQL role permissions,
   Node tests, container startup and the existing auth/mail health checks
   before reloading the gateway. Restore backed-up files if validation fails.
6. In OpenObserve, set starlit_auth retention to 30 days, create admin-only
   dashboards and compare successful event totals to source audit aggregates.
   Do not generate fake login failures with passwords.

The exporter only advances its cursor when OpenObserve acknowledges every
item. Retried sends can duplicate raw records: dashboard counts must use
COUNT(DISTINCT event_id), not row counts. SQL limits reads to a 30-day
lookback and 100 rows per poll; Nginx log polling is bounded to 256 KiB
per cycle. The failure cursor stores inode and byte offset. Copytruncate
rotation can still lose a few events around an unusual concurrent crash.

## Local checks

Run npm ci --ignore-scripts --no-audit and npm test in this directory.
All secrets are supplied only as process environment variables on the VPS
and must never appear in source, PRs, logs or dashboard descriptions.
