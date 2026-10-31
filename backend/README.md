# Starlit sync backend

An independent Supabase Auth + Postgres/PostgREST backend for Starlit mobile and desktop. It stores only accounts and data created on **your own** local stack or Supabase project. No upstream Nuvio account, key, database, membership service, or content service is used. The discovery document intentionally retains `service: "nuvio"` because that is the existing client protocol identifier.

## Run locally

Requirements: Node.js 22+, npm, and a running Docker engine. Commands run from this `backend/` directory. The CLI is pinned in `package-lock.json`.

```sh
npm ci
npx supabase start
npx supabase status -o json | node configure-local.mjs
npm start
```

`supabase start` applies the migrations to a new local stack. When adding later migrations to an existing stack, run `npx supabase migration up --local`. `npx supabase db reset --local --no-seed` rebuilds the **disposable local database and deletes its accounts/data**; it is useful when developing the initial migration, not for upgrading an installation with data.

`configure-local.mjs` creates the ignored `.env` with only the API URL and public publishable key; it refuses to overwrite an existing file. It does not persist service-role keys, secret keys, or JWT signing secrets from the CLI output. To intentionally refresh a disposable local configuration, pass `--force`. For an existing configuration, copy `.env.example` to `.env` and supply your own project's API URL and publishable key.

The app's custom server address is `http://127.0.0.1:8787`. Discovery serves `/.well-known/nuvio`; it points the client to Supabase on port 54321. `http://127.0.0.1:8787/healthz` checks the discovery process, not database readiness. Studio is `http://127.0.0.1:54323`; local confirmation emails, when enabled, are at `http://127.0.0.1:54324`.

For Android emulator use, either:

- Run `adb reverse tcp:8787 tcp:8787` and `adb reverse tcp:54321 tcp:54321`, then use `http://127.0.0.1:8787` in the app. This preserves the desktop discovery configuration.
- Or set `HOST=0.0.0.0` and `STARLIT_SUPABASE_URL=http://10.0.2.2:54321` in `.env`, restart discovery, and enter `http://10.0.2.2:8787` in the emulator. The advertised API URL must be reachable from the device. A physical phone needs the host's LAN address in both places and network/firewall access.

When Docker runs inside WSL without Docker Desktop, invoke the Supabase CLI from that WSL distribution. The discovery service and HTTP tests may run in Windows Node using forwarded localhost ports. Keep an active WSL command open during use: systemd services alone may not prevent an idle WSL shutdown. A foreground `docker events` command is sufficient; close it when you finish. Avoid exposing local development ports to an untrusted network.

Create a new email/password account in Starlit after selecting this server, then sign into the same account on another client. Local email confirmation is disabled and passwords require 12 characters. The Starlit Full builds support custom servers; guest mode keeps data on the device and does not sync.

## Implemented client contract

All authenticated data is scoped by Supabase `auth.uid()` and the account's integer profile index (1–6). Caller-supplied profile IDs are never global user IDs. Internal tables live in an unexposed schema with no client privileges. Every table enables RLS; the two directly selected tables additionally use account-scoped SELECT policies. Public RPCs have explicit authenticated-only execution grants, an empty SQL search path, and ownership checks. The standard Supabase Auth service handles passwords, tokens, refresh, and email flows.

| Client area | Contract |
| --- | --- |
| Profiles | `sync_pull_profiles`, `sync_push_profiles(p_profiles,p_client_max_profiles,p_origin_client_id?)`, `sync_delete_profile_data(p_profile_id,p_origin_client_id?)`, `sync_pull_profile_locks` |
| Addons | RLS-protected `addons` SELECT by `profile_id`, ordered by `sort_order`; `sync_push_addons(p_profile_id,p_addons,p_origin_client_id?)` |
| Plugins | RLS-protected `plugins` SELECT; `sync_push_plugins(p_profile_id,p_plugins,p_origin_client_id?)` |
| Library | `sync_pull_library(p_profile_id,p_limit?,p_offset?)`, `sync_push_library_items(p_profile_id,p_items,p_origin_client_id?)`, `sync_delete_library_items(p_profile_id,p_keys,p_origin_client_id?)`, `sync_get_library_delta_cursor`, `sync_pull_library_delta(p_profile_id,p_since_event_id,p_limit?)` |
| Playback progress | `sync_pull_watch_progress(p_profile_id,p_since_last_watched?,p_limit?)`, `sync_push_watch_progress(p_profile_id,p_entries,p_origin_client_id?)`, `sync_delete_watch_progress(p_profile_id,p_keys,p_origin_client_id?)`, `sync_get_watch_progress_delta_cursor`, `sync_pull_watch_progress_delta(p_profile_id,p_since_event_id,p_limit?)` |
| Watched history | `sync_pull_watched_items(p_profile_id,p_page?,p_page_size?)` (pages start at **1**), `sync_push_watched_items(p_profile_id,p_items,p_origin_client_id?)`, `sync_delete_watched_items(p_profile_id,p_keys,p_origin_client_id?)`, `sync_get_watched_items_delta_cursor`, `sync_pull_watched_items_delta(p_profile_id,p_since_event_id,p_limit?)` |
| Collections | `sync_pull_collections(p_profile_id)` / `sync_push_collections(p_profile_id,p_collections_json,p_origin_client_id?)` |
| Home catalogs | `sync_pull_home_catalog_settings(p_profile_id,p_platform)` / `sync_push_home_catalog_settings(p_profile_id,p_platform,p_settings_json,p_origin_client_id?)`; current clients share `home_catalog_shared` |
| Preferences | `sync_pull_profile_settings_blob(p_profile_id,p_platform)` / `sync_push_profile_settings_blob(p_profile_id,p_platform,p_settings_json,p_origin_client_id?)`; `mobile` and `desktop` are separate blobs |
| Provider keys | `sync_pull_provider_credentials(p_profile_id)` / `sync_push_provider_credentials(p_profile_id,p_credentials,p_origin_client_id?)`; dedicated private records, absent from ordinary settings |
| Device registration | `register_current_device(p_installation_id,p_client_name,p_client_version,p_platform,p_device_name)` |

Pull functions return arrays with the exact serialized client field names. Delta cursors return JSON integers, with ordered `event_id` / `operation: upsert|delete` events. Progress deletion accepts string `progress_key` values. Watched deletion uses `content_id`, nullable `season`, and nullable `episode`, matching the client (which does not send `content_type`). Library keys include both `content_id` and `content_type`.

Pushes of library/progress/history upsert only the submitted records, so a partial snapshot cannot erase unrelated data. Old timestamps do not overwrite newer progress/history/library records. Explicit deletes leave tombstones. Account-scoped transaction locks serialize writes through commit, preventing concurrent requests from creating an event cursor gap. Events are retained without truncation; add a coordinated cursor-reset/retention protocol before pruning them. `p_origin_client_id` is accepted for compatibility but is not used to suppress events. Progress snapshots return one scalar JSON array (still the same array of client records), because the client does not provide pagination for this RPC; this avoids PostgREST's row-count truncation for accounts with more than 1000 progress records.

Profiles are upserted without removing omitted profiles; deleting profile 2–6 explicitly cascades its settings/credentials/extensions/records and retains sync tombstones. Profile 1 cannot be deleted. Addon/plugin pushes and collection/settings pushes are whole-list or whole-blob replacements, as sent by the clients. Provider pushes upsert named providers, preserve omitted providers, and accept empty credential strings to clear a key. Mutations reject invalid shapes, types, pagination, or oversized payloads atomically. Limits are 1000 array items per mutation, 1000 rows per paginated pull, and 1 MiB per payload (smaller bounds for profiles/credentials). The unpaged progress snapshot returns all matching entries unless the client explicitly supplies `p_limit`.

Concurrent whole-list/settings edits use the last accepted request. A later explicit item push can recreate a previously deleted item; clients do not supply a deletion version that would let the server distinguish an intentional restore from an old offline edit. This backend does not add a second conflict protocol or realtime channel; it supports the client's existing pull/push/delta behavior.

## Explicit limits

- TV/device-code login is not implemented; discovery advertises `tv_login: false`, so clients use email/password.
- Profile PIN protection is not implemented. Profiles/lock pulls always report `pin_enabled: false`. `verify_profile_pin` returns `unlocked: false` with an explanatory message; set/clear/password-recovery PIN calls return PostgreSQL `0A000` (unsupported). A PIN UI should stay disabled for this backend. No password is verified or stored by these RPCs.
- Account deletion, web account/device administration, payments, member avatars/background catalogs, and upstream membership endpoints are not implemented. Built-in profile colors and user-supplied avatar URLs remain usable; optional remote catalogs may be unavailable. No media/catalog/debrid provider itself is hosted here.
- Provider API keys are synced because the clients request it. They are readable only by the owning account through the dedicated RPC, but **the backend/database administrator is trusted and can access them**. This is not end-to-end encryption. Backups must receive the same access controls as the database. Provider URLs installed as addons/plugins can themselves contain credentials and are account-isolated too.
- The local Supabase CLI stack uses development keys and unauthenticated local tooling. It is a local development backend, not an internet-ready production deployment.

## Verify

```sh
npm test
npx supabase test db
npm run test:integration
```

The integration test requires the running local stack, migrated database, and the localhost `.env` configuration. It refuses a remote hostname. It creates two disposable ordinary email/password accounts (no admin key), signs in/refreshes sessions, opens a second session for the same user, and tests actual GoTrue/PostgREST requests. It checks every sync domain, two-account isolation, RLS, direct mutation denial, profile ownership/cascade, platform separation, credential clearing, malformed atomic rollback, pagination, concurrent deltas, tombstones, and anonymous RPC denial. Test accounts remain in the disposable database. The pgTAP test separately audits schema grants, helper/function grants, RLS, and definer search paths. Discovery tests verify the protocol and refusal to advertise secret/service-role keys.

CI runs the same commands after `npm ci`, `npx supabase start`, and `npx supabase status -o json | node configure-local.mjs`. No production secrets are required.

## Deploy your own project

For the shared native PostgreSQL deployment on Ricardo's VPS, use [the VPS deployment guide](vps/README.md). It runs only Auth, PostgREST, discovery and a private gateway, and includes the existing account/data import workflow. It does not use the local Supabase CLI stack in production.

Create an independent Starlit Supabase project or operate Supabase's production self-hosting stack. Apply this migration to **that** database with the Supabase CLI (`supabase link --project-ref YOUR_PROJECT_REF`, then review and run `supabase db push`). Do not point this backend or client at upstream Nuvio services. Do not use `db reset` on production data.

Configure Supabase Auth separately for production: email/password enabled, anonymous sign-in disabled, 12+ character passwords, verified email confirmation, working SMTP, and the correct site/redirect URLs. The checked-in `config.toml` is the local development configuration and does not provision a production SMTP service or automatically configure a hosted project's Auth settings. Set up backups/recovery and restrict database/admin access.

Run `node discovery.mjs` under your service manager with `STARLIT_SUPABASE_URL` set to the public HTTPS API root and `STARLIT_SUPABASE_PUBLISHABLE_KEY` set to that project's publishable key (or legacy **anon** JWT). Place discovery behind an HTTPS reverse proxy with `/.well-known/nuvio` routed to it. Both discovery and the advertised Supabase API must be reachable by the clients. Keep secret/service-role keys, database credentials, and JWT signing secrets out of client builds, discovery, logs, and public files. The discovery service rejects secret/service-role keys but cannot verify which project a public key belongs to.

Official references: [Supabase local development](https://supabase.com/docs/guides/local-development), [CLI database tests](https://supabase.com/docs/reference/cli/supabase-test-db), [securing the API](https://supabase.com/docs/guides/api/securing-your-api), [API keys](https://supabase.com/docs/guides/api/api-keys), [self-hosting](https://supabase.com/docs/guides/self-hosting).
