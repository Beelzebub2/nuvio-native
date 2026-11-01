# Owner approval emails for Starlit VPS

This small Cloudflare Worker sends account-review links to one verified Cloudflare Email Routing destination (`ricardo.approvals@rrmtools.uk`). It is separate from the old Cloudflare sync backend: account data and requests remain on the Starlit VPS. Public callers cannot choose a recipient or review URL.

Install and deploy with `npm ci && npm run test && npm run deploy` from this directory. Configure the same randomly generated `SIGNUP_NOTIFY_TOKEN` as a Wrangler secret using `npx wrangler secret put SIGNUP_NOTIFY_TOKEN` and as `SIGNUP_NOTIFY_TOKEN` in the VPS's root-owned `/etc/starlit/runtime.env`. Do not print or commit this secret. In that VPS file, set `SIGNUP_NOTIFY_URL` to `https://starlit-approval-email.<your-workers-subdomain>.workers.dev/notify`, then restart only the Starlit account-request service.

The address in `wrangler.jsonc` must remain verified in Cloudflare Email Routing. The Worker accepts only a token-authenticated POST containing `{ "email": "user@example.com", "reviewUrl": "https://starlit-api.rrmtools.uk/admin/signups/review?token=...", "requestedAt": 1234567890000 }` and sends it only to the pinned owner address. The review link's GET page never approves anything; the owner must explicitly choose Approve or Deny on that page.

If email delivery fails, the VPS keeps the request pending and allows a later notification retry. New accounts cannot sign in unless the owner explicitly approves them.
