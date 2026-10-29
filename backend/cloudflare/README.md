# Starlit sync on Cloudflare (free plan)

A Cloudflare Worker that gives Starlit desktop and mobile account sync without a Supabase project. It answers the same Supabase Auth and PostgREST requests as the [Supabase backend](../README.md), so the apps need no special setup: you enter the Worker's address as a custom server.

Everything runs on the Workers **Free** plan:

- One SQLite Durable Object holds accounts, sign-up requests and sessions.
- One SQLite Durable Object per account holds that account's profiles, add-ons, library, progress, history, collections, settings and provider keys. Each request runs in one transaction, so an account's writes never interleave and a failed write changes nothing.
- New accounts need the owner's approval. A sign-up emails a review link to a verified address in your Cloudflare Email Routing (free); the account works only after you choose **Approve** on that page.

## What it supports

The same contract as the Supabase migration, checked by the same HTTP integration test: profiles, add-ons and plugins, library, watch progress and watched history (snapshots, deltas, tombstones, stale-write protection), collections, home catalog settings, per-platform settings blobs, provider credentials and device registration. TV login, profile PINs, password reset by email and account deletion are not available, as with the Supabase backend.

Passwords are hashed with PBKDF2-SHA256 (100,000 iterations, the Workers maximum). Access tokens are HS256 JWTs valid for one hour; refresh tokens rotate on every use and are stored hashed. Ten wrong passwords lock that email for 15 minutes. Provider keys are readable only by their account, but you, as the Cloudflare account owner, can read everything stored here.

## Sign-up approval

1. A friend picks **Sign Up** in Starlit with your server selected.
2. The Worker stores a pending request (with their password hash) and emails a review link to `APPROVAL_TO`. The app tells them their account is waiting for approval; signing in says the same until you decide.
3. Open the link and choose **Approve** or **Deny**. Opening the page alone changes nothing, so mail scanners that preview links cannot approve anyone. Links are single use and expire after 7 days.
4. After approval they sign in with the password they chose.

A request's password is never replaced, so nobody can swap it before you approve. A repeated sign-up for a waiting email only counts when it has the same password: it keeps the request alive and, at most once an hour, emails you a fresh link that replaces the old one. A repeat with any other password changes nothing. A request that expired unanswered can be made again from scratch, with a new link; the old one never works again. At most 10 approval emails go out per hour and at most 20 requests can wait at once. Set `SIGNUP_MODE` to `closed` to stop new requests entirely.

## Deploy

Requirements: Node.js 22+, a Cloudflare account, and a zone with Email Routing enabled (for the approval email).

```sh
cd backend/cloudflare
npm ci
npx wrangler login
```

1. Make the approval address a verified Email Routing destination. Cloudflare emails it a verification link; open it before the first sign-up:

   ```sh
   npx wrangler email routing addresses create ricardo.approvals@rrmtools.uk
   ```

2. Review `wrangler.jsonc`. `APPROVAL_TO` and the `send_email` binding's `destination_address` must be that verified address; `APPROVAL_FROM` must be an address on the Email Routing domain.

3. Deploy, then set the token signing secret (a long random value that never leaves Cloudflare):

   ```sh
   npx wrangler deploy
   node -e "process.stdout.write(require('crypto').randomBytes(48).toString('base64url'))" | npx wrangler secret put JWT_SECRET
   ```

   Until `JWT_SECRET` is set, every request answers `503 server_not_configured`. Changing it later signs everyone out of their current access tokens (refresh tokens keep working).

4. Check it: `https://starlit-sync.<your-subdomain>.workers.dev/healthz?deep=1` should report `"status":"ok"` with the time one password hash took on Cloudflare.

In Starlit (Full builds): **Profile → Account → Sign Out**, then **Server options → Connect to custom server**, enter `https://starlit-sync.<your-subdomain>.workers.dev`, **Check server**, confirm, and **Sign Up**. Use the same server and account on every device.

## Settings

| Variable | Default | Meaning |
| --- | --- | --- |
| `SIGNUP_MODE` | `approval` | `approval` emails the owner; `closed` refuses new accounts. `auto` (instant accounts) only works on localhost, for tests. |
| `APPROVAL_TO` | `ricardo.approvals@rrmtools.uk` | Where review links go. Must be a verified Email Routing destination. |
| `APPROVAL_FROM` | `starlit-sync@rrmtools.uk` | Sender, on your Email Routing domain. |
| `PASSWORD_HASH_ITERATIONS` | `100000` | PBKDF2 cost, 10,000–100,000. Existing hashes keep their own cost. |
| `ACCESS_TOKEN_SECONDS` | `3600` | Access token lifetime, 300–86,400. |
| `JWT_SECRET` (secret) | — | Signs access tokens. Required. |

## Free plan limits

Workers Free allows 100,000 requests a day (each sync call is one), and 5 GB of Durable Object storage. Emails to your own verified address are free and do not count toward sending quotas. For a few people syncing a few devices, this is far below the limits.

## Test locally

```sh
npm ci
npm test
```

This starts two disposable local servers with `wrangler dev`, one with instant sign-up and one requiring approval. It runs the shared Supabase-contract integration test plus the Cloudflare tests: auth edge cases, refresh rotation and logout, and the full approval flow using wrangler's simulated email. Nothing is sent and no Cloudflare resources are used.

`npm run test:unit` runs only the sign-up request rules (repeat, expiry and race cases) in plain Node against an in-memory database with a simulated clock. It needs no server and Node.js 22.15 or newer.
