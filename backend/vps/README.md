# Starlit accounts on the VPS

This Linux deployment uses the shared native PostgreSQL server, a separate `starlit` database, Supabase Auth **2.197.0**, PostgREST **16.5**, Node **22.23.4** and Nginx **1.30.6**. Auth and REST match the source local stack. Docker Compose restarts the five services after reboot; enable the existing Docker daemon at boot. The additional Node service accepts encrypted account requests and owner decisions. There is no second PostgreSQL container, Studio, storage API, or realtime service.

Project files belong at `/root/Projects/ricardo/starlit`, private configuration at `/etc/starlit/runtime.env` (root, mode 0600). PostgreSQL binaries/data remain in their system locations. Preserve Puzzle42, CloudBeaver, shared PostgreSQL and unrelated tunnel routes. All commands below run as root from the project's `backend` directory. Never commit or print the private environment or database archives.

## Account policy

**Only explicit owner approval activates an account.** The public GoTrue signup endpoint remains disabled (`GOTRUE_DISABLE_SIGNUP=true`, `GOTRUE_MAILER_AUTOCONFIRM=false` are fixed in Compose). Nginx sends only `POST /auth/v1/signup` to a separate Node approval service on loopback port `8789`. It returns Supabase-compatible user JSON without a session. It does **not** create a GoTrue user. The app can display its existing pending-approval notice; sign-in is impossible until approval. No GoTrue SMTP is necessary for account approvals. Email-based password recovery/email changes still need separate SMTP configuration.

For each new request, the approval service stores a 7-day pending entry in `/var/lib/starlit/signup/requests.sqlite` (SQLite WAL). The chosen password is encrypted using AES-256-GCM and `SIGNUP_ENCRYPTION_KEY`, **independent** of the GoTrue JWT secret. A keyed verifier checks repeated requests without replacing the stored password. The service sends `{email,reviewUrl,requestedAt}` via HTTPS `POST` to `SIGNUP_NOTIFY_URL`, with `requestedAt` as a Unix timestamp in **milliseconds**. Notifier authentication uses **Ed25519**, not a shared bearer token: `SIGNUP_SIGNING_KEY` is a base64url-encoded PKCS8 DER private key kept in the root-owned VPS environment. Each request includes `X-Starlit-Time` (current Unix milliseconds, decimal) and `X-Starlit-Signature` (base64url-encoded 64-byte Ed25519 signature over the exact UTF-8 bytes of `${timestamp}.${rawBody}`, where `rawBody` is the transmitted JSON body). The Worker verifies this signature with the corresponding **public** base64url SPKI DER `SIGNUP_VERIFY_KEY` and rejects timestamps outside ±5 minutes. The notifier sends the private review link to the verified owner address; its URL is `https://starlit-approval-email.cloudflaredomainrrm.workers.dev/notify`.

Opening `GET /admin/signups/review?token=...` only displays the email, request date, and **Approve**/**Deny** buttons. The 32-random-byte review token is stored only as a SHA-256 hash. It expires after seven days, and **POST** consumes it once. **Approve** calls the separate loopback-only GoTrue `/admin/users` endpoint with a short-lived service-role JWT, creates a confirmed account using the decrypted chosen password, and deletes the pending record. **Deny** deletes the pending record without creating a user. Nginx continues blocking external `/auth/v1/admin/*` and `/auth/v1/invite/*`; the review URL is an explicit, single-use capability for the recipient of the owner email. Treat this link as an administrative secret and never forward it.

Approval verifies the **owner's decision**, not that the applicant controls the email address they typed. Before approving an unfamiliar applicant, verify the address and intended person out of band; GoTrue will mark the approved email confirmed. Public email verification would require a separate flow with trusted SMTP.

Repeated requests with a different password cannot replace the stored password, review token, or notification. A matching-password repeat can request a new notification at most hourly, or 30 seconds after a failed/uncertain delivery. **Only after the notifier accepts the new message** does the service activate its replacement token and invalidate the previous email link. During a send, the previously delivered owner link remains valid for review and approval; if sending fails, the old link continues to work. A failure from the notifier causes the signup endpoint to return a GoTrue-shaped **HTTP 503**, `error_code: signup_notification_failed`, `Retry-After: 30`, and an explicit message that the encrypted request was saved but the owner was **not** notified. The applicant should retry the **same email and password** after 30 seconds; no account is created by retrying. Attempts during the undelivered cooldown also return 503 rather than falsely reporting success. Limits are 12 new requests per IP per hour, 60 signup attempts per IP per hour, 10 wrong-password repeats per email per 15 minutes, 30 notification attempts per hour globally and 200 pending entries. Expired pending entries are removed when the service next handles a signup. The notifier must also enforce its own delivery protection. IPs are stored only as keyed rate-limit fingerprints.

If GoTrue reports an error or times out **after** an explicit approval, the token becomes unusable and the encrypted request is marked `needs_attention`. A timeout might mean the account was created despite a lost response. If the process/container stops after marking the row `approving` but before processing the GoTrue response, the next startup automatically marks the orphaned row `needs_attention` **without repeating the privileged create**. The owner must inspect the private GoTrue users and SQLite record before taking another action; the service never automatically repeats a potentially successful account creation. Never delete or rotate the encryption key while there are unresolved pending or `needs_attention` records.

The owner can still create an account directly through the loopback-only admin API when necessary. Supply JSON `{ "email": "...", "password": "..." }` on stdin from a root-private file or password manager; use a unique password of at least 12 characters. The command prints only the new account UUID and does not send email:

Use the host's Node 22+: `node --env-file=/etc/starlit/runtime.env vps/create-user.mjs < /private/account.json`. The discovery service intentionally receives no admin secret during ordinary operation. With container-only Node, the command is:

```sh
docker run --rm -i --network host --env-file /etc/starlit/runtime.env \
  -v "$PWD/vps:/app:ro" -w /app node:22.23.4-alpine3.23 \
  node create-user.mjs < /private/account.json
```

Delete the temporary account input securely according to the host's normal secret handling. Starlit 1.0.42 and later select `https://starlit-api.rrmtools.uk` automatically on desktop and mobile; sign in with the same approved credentials on both. Earlier Full builds can select it as a **custom server**. Existing source accounts retain their password hashes, user IDs and sync data after import.

## Initial setup

1. Inspect existing source data and preserve a full backup before creating the target. Ensure the destination resolves to `/root/Projects/ricardo/starlit`. Check that loopback ports `8787`, `8788`, `8789`, `9999`, `3100`, `3101` are free. PostgreSQL must accept private TCP on `127.0.0.1:5432` with SCRAM authentication. Do not publish PostgreSQL or these HTTP ports.
2. Copy this project's `backend` files there. Create `/etc/starlit` mode 0700, then generate fresh credentials directly to the new private file. Node is only needed for this command; the same pinned image can run it with `/etc/starlit` mounted:

   ```sh
   install -d -m 700 /etc/starlit
   node vps/generate-env.mjs https://starlit-api.rrmtools.uk /etc/starlit/runtime.env
   ```

   Generation refuses to overwrite a file. The public anon JWT is intentionally advertised by discovery; database passwords, `JWT_SECRET` and `SIGNUP_ENCRYPTION_KEY` remain private. Back up this environment along with PostgreSQL and the SQLite request store. Do not regenerate it on restart or upgrade: changing the signing secret invalidates existing sessions/public keys, and losing the signup key makes existing encrypted signup requests unreadable.
   For a new installation, `generate-env.mjs` creates an independent `SIGNUP_SIGNING_KEY` (Ed25519 PKCS8 DER) and sets `SIGNUP_NOTIFY_URL=https://starlit-approval-email.cloudflaredomainrrm.workers.dev/notify`. The private key must **never** leave the VPS or be copied to Cloudflare. As root, run `node vps/print-signing-public-key.mjs /etc/starlit/runtime.env` and configure the printed **public** SPKI DER base64url value as the Worker's `SIGNUP_VERIFY_KEY` secret/binding. Do not expose the public `SIGNUP_VERIFY_KEY` as a URL parameter, and never print the private `SIGNUP_SIGNING_KEY`. The Worker must have verification enabled before any public signup requests are allowed.
   Create the writable SQLite state directory for the unprivileged Node container (the pinned `node` user is UID/GID 1000):

   ```sh
   install -d -o 1000 -g 1000 -m 0700 /var/lib/starlit/signup
   ```

   To use a different path, set `SIGNUP_STATE_DIR` to that absolute host directory, also owned by UID 1000 and mode 0700. Only the approval container may write this directory.
3. Review `bootstrap.sql`, then create the empty database/roles. It intentionally fails on name collisions instead of replacing another project's roles or database. If interrupted, inspect the state before resuming; do not rerun blindly.

   ```sh
   set -a
   . /etc/starlit/runtime.env
   set +a
   runuser -u postgres --preserve-environment -- psql -X -d postgres < vps/bootstrap.sql
   ```

   The login roles are `starlit_auth_admin` (owns only `auth`) and `starlit_authenticator` (`NOINHERIT`, switches only to `anon`/`authenticated`). `starlit_owner` is a `NOLOGIN` app/schema owner. None has superuser, role creation, replication, or cluster-wide RLS bypass privileges. Configure `pg_hba.conf` so those two logins can authenticate only to `starlit` from loopback, followed by a reject for those logins to other databases; preserve all existing entries. Reload PostgreSQL and verify these specific rules. Do not change shared-role privileges globally.
4. Pull the pinned images and start Auth alone. Auth applies its own schema migrations, including `auth.uid()` and `auth.jwt()`. Then apply app migrations:

   ```sh
   docker compose --env-file /etc/starlit/runtime.env -f vps/compose.yml pull
   docker compose --env-file /etc/starlit/runtime.env -f vps/compose.yml up -d --wait auth
   sh vps/migrate.sh
   ```

   The script takes a private pre-migration custom-format dump in `/var/backups/starlit`, applies each migration in a transaction as `starlit_owner`, and records its checksum. Unchanged applied migrations are skipped; modifying an applied migration aborts. Keep a failed target private and investigate. Future database changes must be new migration files.

## Preserve the existing local database

The initial source has Auth 2.197.0 and this repository's migration. Confirm source and target Auth versions, migration contents, and application/auth table column definitions match **before** importing data. If they differ, prepare a reviewed schema migration instead of forcing the restore. Preserve a full source backup and a schema-only export for comparison. Never reset or delete the source.

Create the data archive on the source PostgreSQL instance using its normal private connection:

```sh
pg_dump -Fc --data-only --schema=auth --schema=public --schema=starlit_private \
  --exclude-table-data=auth.schema_migrations SOURCE_DATABASE > starlit-data.dump
```

Transfer it privately, root-owned mode 0600. This includes password hashes, identities, sessions, sync records, profiles and sequence values, while leaving unrelated Supabase schemas/extensions behind. Excluding Auth's migration records preserves the target's own identical migration state. The migrated target already has the proper object ownership and grants; no source ACLs or Supabase admin roles are imported.

Stop Auth and all other Starlit services during import; the target must have no account/sync rows. `import-data.sh` rejects occupied targets, schema archives and Auth migration data, backs up the empty target, and restores data in one transaction with triggers suppressed (preventing the account-profile trigger from duplicating imported profiles). Restore failure rolls back; subsequent verification failure keeps the services stopped for investigation.

```sh
docker compose --env-file /etc/starlit/runtime.env -f vps/compose.yml stop
sh vps/import-data.sh /private/starlit-data.dump
docker compose --env-file /etc/starlit/runtime.env -f vps/compose.yml up -d --wait
```

Compare printed counts with the source for users, profiles, addons, plugins, devices, events, and each record kind. Verify a known existing account signs in privately without changing its password and pulls its own profiles/data. Preserve the source and its backups after cutover.

## Verify, publish and operate

```sh
docker compose --env-file /etc/starlit/runtime.env -f vps/compose.yml ps
curl --fail http://127.0.0.1:8788/.well-known/nuvio
curl --fail http://127.0.0.1:8788/auth/v1/health
curl --fail http://127.0.0.1:3101/ready
```

`/healthz` checks discovery only. Verify Auth, REST and the private signup service (`curl --fail http://127.0.0.1:8789/healthz`) separately. Verify the gateway blocks `/auth/v1/admin/users` and `/auth/v1/invite`, while a valid new signup **returns a pending user without any token** and never creates an account until the owner submits approval. Check that the notification arrives and that a rejected request cannot sign in. Simulate a failed notifier in a disposable test environment and confirm signup responds **503** with an explicit retry message while the pending record remains. Private GoTrue admin calls use port 9999 directly. The gateway preserves client authorization for PostgREST, which verifies every JWT and applies the existing database grants/RLS. Only the `public` database schema is exposed. The advertised anon key grants no sync/account data access. The gateway trusts client IP headers only from the local tunnel, limits Auth request rate by that address, and avoids logging query strings/tokens. The owner review location additionally disables its own Nginx error log (`error_log /dev/null crit`) because upstream errors might otherwise include a sensitive `?token=` capability URL even with a safe access log.

Run `npm test` for discovery/config generation, `node --test test/vps-approval.test.mjs` for temporary SQLite + fake GoTrue/notifier integration tests, and `sh test/vps-shell.test.sh` on Linux for the migration SQL builder regression check (no database required). Run the existing full `test:integration` suite against a **disposable localhost** backend using its private, freshly owner-created account fixture mode. The production Compose file always disables GoTrue signup, including during tests. Keep the public route unpublished while testing. The suite covers sign-in/refresh, every sync domain, cross-account isolation, mutation denial, malformed atomic rollback, tombstones and concurrent deltas. Test accounts belong only in the disposable test database and can be deleted afterwards through private Auth administration.

For closed-signup fixture mode, create two fresh accounts privately, save a root-private JSON array `[{"email":"...","password":"..."},{"email":"...","password":"..."}]`, and set `STARLIT_TEST_ACCOUNTS_FILE` to its absolute path. Supply `STARLIT_SUPABASE_URL=http://127.0.0.1:8788` and the deployed public anon key. Run `node --test test/sync.integration.test.mjs` with those environment variables. The test signs in twice per account instead of attempting public signup; all other sync/isolation checks remain identical. It mutates the fixtures, so do not reuse accounts with personal data. Remove disposable fixtures through the private Auth admin API after checking the result.

Once private checks pass, add **only** `starlit-api.rrmtools.uk -> http://127.0.0.1:8788` to the existing Cloudflare Tunnel and its DNS. Preserve other routes and the fallback. Check HTTPS discovery, Auth health, blocked admin access and pending-only signup with notification from outside before sharing the address.

Include `starlit` in the existing shared PostgreSQL backup enumeration, `/etc/starlit` in protected configuration backups, **and `/var/lib/starlit/signup` in backups**. Stop the signup service for a consistent SQLite file backup, or use a SQLite-aware online backup that includes WAL state; then resume it. The database contains encrypted pending passwords and hashed review tokens, while the separate runtime environment contains the decryption key. Store both with strict access controls and test restoration. Existing shared database backups are not automatically changed by these project files. PostgreSQL backups still contain provider credentials and password hashes and require restricted access/retention.

For an application rollback, remove only this hostname's ingress route or stop this Compose project. Keep PostgreSQL data, encrypted SQLite requests and configuration untouched. Before a database rollback, stop all Starlit services, preserve the failed state, restore a selected verified dump to a **new** database, check counts/ownership/grants and sign-in privately, then update only Starlit's private connection URLs. A rollback to an old backup discards later changes; do not overwrite a live database or run `db reset`. Keep the old database/source until recovery is verified.

## Upgrade an already deployed VPS

Do **not** rerun `generate-env.mjs` or replace `/etc/starlit/runtime.env`: it holds the live database passwords, GoTrue JWT signing key, and possibly the already-created `SIGNUP_ENCRYPTION_KEY`. Keep every existing account, session, password encryption key and database unchanged. If `SIGNUP_ENCRYPTION_KEY` is not yet configured, run `node vps/add-signup-key.mjs /etc/starlit/runtime.env` **once as root**; it refuses to replace an existing key. Do not run it when the key already exists.

**Migrate the notifier to public-key signatures** without transferring any shared bearer token. After the new backend files have been copied to the VPS, run these commands **as root** from `/root/Projects/ricardo/starlit/backend`:

```sh
# The key generator appends only when SIGNUP_SIGNING_KEY is absent; if it already
# exists, keep it and proceed directly to the next command.
node vps/add-signing-key.mjs /etc/starlit/runtime.env

# Idempotent: appends the fixed HTTPS /notify URL if absent, does nothing if
# already correct, and refuses to overwrite an unexpected existing value.
node vps/add-notify-url.mjs /etc/starlit/runtime.env

# Prints ONLY the public key as base64url SPKI DER (safe to share with Worker).
node vps/print-signing-public-key.mjs /etc/starlit/runtime.env
```

`add-signing-key.mjs` generates an Ed25519 keypair and appends **only the private PKCS8 DER key** as `SIGNUP_SIGNING_KEY` to the existing root-owned file (mode 0600, no symlinks/hardlinks), without printing secrets or changing any other variable. The helper refuses a duplicate key rather than rotating it. `print-signing-public-key.mjs` reads the root-private file and prints **only the matching public SPKI DER key**. Set this public value as the Cloudflare email Worker's `SIGNUP_VERIFY_KEY` binding (for example, using `wrangler secret put SIGNUP_VERIFY_KEY` on the Worker project). This value is public but should still be configured accurately. The VPS must retain the private key; **never upload `SIGNUP_SIGNING_KEY` or `/etc/starlit/runtime.env` to Cloudflare**. The Worker must have deployed Ed25519 verification enabled with the matching public key before public signup is exposed.

`add-notify-url.mjs` appends `SIGNUP_NOTIFY_URL=https://starlit-approval-email.cloudflaredomainrrm.workers.dev/notify` only if absent, and makes no changes when already set to that exact URL. It refuses any conflicting or duplicate URL. Both upgrade helpers check the root-owned private file and preserve existing credentials. If the operator previously created `SIGNUP_NOTIFY_TOKEN`, leave it unchanged for forensic/rollback purposes but **do not pass it to the signup container or use it for notifications**; the new request signature does not rely on this legacy secret. All active signing requests use `X-Starlit-Time` and `X-Starlit-Signature`, never `Authorization: Bearer`.

**Keep the sole encrypted legacy token file safe while migrating.** Its only known copy is in the isolated development worktree at `/nuvio-native/.worktrees/vps-account-approval/backend/approval-email/.signup-notify-token.dpapi` (Windows native path `C:\Users\ricar\Downloads\nuvio-native\.worktrees\vps-account-approval\backend\approval-email\.signup-notify-token.dpapi`). It is ignored by Git and tied to its original Windows DPAPI user. **Do not clean up/delete this isolated worktree** until the owner has verified the Ed25519 deployment and explicitly decided whether to preserve the encrypted token in another secure location or retire it. The token does **not** need to be decrypted, printed, transmitted or copied into the VPS for the Ed25519 flow.

Check `AUTH_DISABLE_SIGNUP=true`, `AUTH_EMAIL_AUTOCONFIRM=false` and existing `SIGNUP_STATE_DIR` without changing their values. Keep `SIGNUP_ENCRYPTION_KEY` and `SIGNUP_SIGNING_KEY` in protected backups; losing the encryption key makes pending password requests unreadable, while rotating the signing key without updating Cloudflare breaks notification delivery. Never regenerate the full environment or the GoTrue JWT secret on upgrade.

Prepare the SQLite directory with `install -d -o 1000 -g 1000 -m 0700 /var/lib/starlit/signup`. Back up the new environment and data location. Validate the Compose configuration without printing secrets, then update only the Starlit services:

```sh
docker compose --env-file /etc/starlit/runtime.env -f vps/compose.yml config --quiet
docker compose --env-file /etc/starlit/runtime.env -f vps/compose.yml up -d --wait signup
docker compose --env-file /etc/starlit/runtime.env -f vps/compose.yml up -d --force-recreate --no-deps --wait gateway
```

Recreating `gateway` is necessary because changing a bind-mounted `nginx.conf` alone does not reload a running Nginx process. Check private health on `127.0.0.1:8789`, then send one test signup for an **unused** email and verify that the notifier delivers the owner link. Inspect the pending state and approve once from that email. Keep the shared PostgreSQL database and other VPS projects running; no migration or import is required for this service. If a review returns **Approval needs attention**, or a container restart finds an interrupted `approving` row, inspect GoTrue first: a timeout or crash may have created the account already. Verify the email/user ID privately before touching the SQLite request; do not blindly approve the same request again. The `needs_attention` record expires after seven days but its review link stays unusable.

Official references: [Auth configuration](https://github.com/supabase/auth/tree/v2.197.0), [PostgREST configuration](https://docs.postgrest.org/en/v16/references/configuration.html), [PostgreSQL data-only dump](https://www.postgresql.org/docs/18/app-pgdump.html), [transactional restore and trigger controls](https://www.postgresql.org/docs/18/app-pgrestore.html), [Supabase self-hosting](https://supabase.com/docs/guides/self-hosting).
