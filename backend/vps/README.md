# Starlit accounts on the VPS

This Linux deployment uses the shared native PostgreSQL server, a separate `starlit` database, Supabase Auth **2.197.0**, PostgREST **16.5**, Node **22.23.4** and Nginx **1.30.6**. Auth and REST match the source local stack. Docker Compose restarts these four services after reboot; enable the existing Docker daemon at boot. There is no second database container, Studio, storage, or realtime service.

Project files belong at `/root/Projects/ricardo/starlit`, private configuration at `/etc/starlit/runtime.env` (root, mode 0600). PostgreSQL binaries/data remain in their system locations. Preserve Puzzle42, CloudBeaver, shared PostgreSQL and unrelated tunnel routes. All commands below run as root from the project's `backend` directory. Never commit or print the private environment or database archives.

## Account policy

**Only the owner creates/approves accounts. Public signup is disabled.** Keep `AUTH_DISABLE_SIGNUP=true`, `AUTH_EMAIL_AUTOCONFIRM=false`; no SMTP is required for owner-created accounts. A visitor trying to register in the app receives Auth's disabled-signup error. Email-based password recovery and email changes need SMTP; until configured, the owner manages recovery privately. Enabling verified public registration is a separate deliberate policy change that also requires working SMTP.

After deployment, the owner creates a confirmed account using the loopback-only admin API. Supply JSON `{ "email": "...", "password": "..." }` on stdin from a root-private file or password manager; use a unique password of at least 12 characters. The command prints only the new account UUID and does not send email:

Use the host's Node 22+: `node --env-file=/etc/starlit/runtime.env vps/create-user.mjs < /private/account.json`. The discovery service intentionally receives no admin secret during ordinary operation. With container-only Node, the command is:

```sh
docker run --rm -i --network host --env-file /etc/starlit/runtime.env \
  -v "$PWD/vps:/app:ro" -w /app node:22.23.4-alpine3.23 \
  node create-user.mjs < /private/account.json
```

Delete the temporary account input securely according to the host's normal secret handling. The user selects `https://starlit-api.rrmtools.uk` as the **custom server** in a Starlit Full build, then signs in with the same approved credentials on desktop and mobile. Existing source accounts retain their password hashes, user IDs and sync data after import. No app release is required to select a custom server.

## Initial setup

1. Inspect existing source data and preserve a full backup before creating the target. Ensure the destination resolves to `/root/Projects/ricardo/starlit`. Check that loopback ports `8787`, `8788`, `9999`, `3100`, `3101` are free. PostgreSQL must accept private TCP on `127.0.0.1:5432` with SCRAM authentication. Do not publish PostgreSQL or these HTTP ports.
2. Copy this project's `backend` files there. Create `/etc/starlit` mode 0700, then generate fresh credentials directly to the new private file. Node is only needed for this command; the same pinned image can run it with `/etc/starlit` mounted:

   ```sh
   install -d -m 700 /etc/starlit
   node vps/generate-env.mjs https://starlit-api.rrmtools.uk /etc/starlit/runtime.env
   ```

   Generation refuses to overwrite a file. The public anon JWT is intentionally advertised by discovery; database passwords and `JWT_SECRET` remain private. Back up this environment along with the database. Do not regenerate it on restart or upgrade: changing the signing secret invalidates existing sessions/public keys.
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

`/healthz` checks discovery only. Verify Auth and REST separately. Verify public signup fails and the gateway returns 404 for `/auth/v1/admin/users`; private admin calls use port 9999 directly. The gateway preserves client authorization for PostgREST, which verifies every JWT and applies the existing database grants/RLS. Only the `public` database schema is exposed. The advertised anon key grants no sync/account data access. The gateway trusts client IP headers only from the local tunnel, limits Auth request rate by that address, and avoids logging query strings/tokens.

Run `npm test` for discovery/config generation and `sh test/vps-shell.test.sh` on Linux for the migration SQL builder regression check (no database required). Run the existing full `test:integration` suite against a **disposable localhost** deployment with temporary signup/autoconfirm enabled, or use its private, freshly owner-created account fixture mode. Keep the public route unpublished while testing. The suite covers sign-in/refresh, every sync domain, cross-account isolation, mutation denial, malformed atomic rollback, tombstones and concurrent deltas. Never temporarily open public signup on the published server just to run a test. Test accounts belong only in the disposable test database and can be deleted afterwards through private Auth administration.

For closed-signup fixture mode, create two fresh accounts privately, save a root-private JSON array `[{"email":"...","password":"..."},{"email":"...","password":"..."}]`, and set `STARLIT_TEST_ACCOUNTS_FILE` to its absolute path. Supply `STARLIT_SUPABASE_URL=http://127.0.0.1:8788` and the deployed public anon key. Run `node --test test/sync.integration.test.mjs` with those environment variables. The test signs in twice per account instead of attempting public signup; all other sync/isolation checks remain identical. It mutates the fixtures, so do not reuse accounts with personal data. Remove disposable fixtures through the private Auth admin API after checking the result.

Once private checks pass, add **only** `starlit-api.rrmtools.uk -> http://127.0.0.1:8788` to the existing Cloudflare Tunnel and its DNS. Preserve other routes and the fallback. Check HTTPS discovery, Auth health, blocked admin access and blocked signup from outside before sharing the address.

Include `starlit` in the existing shared PostgreSQL backup enumeration and `/etc/starlit` in protected configuration backups; verify a fresh backup contains both. Existing shared database backups are not automatically changed by these project files. Backups contain provider credentials and password hashes and require restricted access/retention. Test restoration to an isolated database before relying on them.

For an application rollback, remove only this hostname's ingress route or stop this Compose project. Keep PostgreSQL data and configuration untouched. Before a database rollback, stop all Starlit services, preserve the failed state, restore a selected verified dump to a **new** database, check counts/ownership/grants and sign-in privately, then update only Starlit's private connection URLs. A rollback to an old backup discards later changes; do not overwrite a live database or run `db reset`. Keep the old database/source until recovery is verified.

Official references: [Auth configuration](https://github.com/supabase/auth/tree/v2.197.0), [PostgREST configuration](https://docs.postgrest.org/en/v16/references/configuration.html), [PostgreSQL data-only dump](https://www.postgresql.org/docs/18/app-pgdump.html), [transactional restore and trigger controls](https://www.postgresql.org/docs/18/app-pgrestore.html), [Supabase self-hosting](https://supabase.com/docs/guides/self-hosting).
