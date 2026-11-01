# Owner approval emails for Starlit VPS

This small Cloudflare Worker sends account-review links to one verified Cloudflare Email Routing destination (`ricardo.approvals@rrmtools.uk`). It is separate from the old Cloudflare sync backend: account data and requests remain on the Starlit VPS. Public callers cannot choose a recipient or review URL.

Install and deploy with `npm ci && npm run test && npm run deploy` from this directory. The VPS generates a private Ed25519 signing key (`SIGNUP_SIGNING_KEY`) directly in its root-owned `/etc/starlit/runtime.env`. Configure **only its public SPKI key** as `SIGNUP_VERIFY_KEY` in Cloudflare (`npx wrangler secret put SIGNUP_VERIFY_KEY`); the private key stays on the VPS. Set `SIGNUP_NOTIFY_URL=https://starlit-approval-email.cloudflaredomainrrm.workers.dev/notify` in the VPS environment and restart the Starlit signup service. No shared bearer token or private key transfer is required.

The address in `wrangler.jsonc` must remain verified in Cloudflare Email Routing. The Worker accepts only a POST containing `{ "email": "user@example.com", "reviewUrl": "https://starlit-api.rrmtools.uk/admin/signups/review?token=...", "requestedAt": 1234567890000 }`, with `X-Starlit-Time` (Unix milliseconds) and `X-Starlit-Signature` (base64url Ed25519 signature over `${timestamp}.${exactJsonBody}`). Signatures more than five minutes old are rejected, and the recipient is pinned to the verified owner address. The review link's GET page never approves anything; the owner must explicitly choose Approve or Deny on that page.

If email delivery fails, the VPS keeps the request pending and allows a later notification retry. New accounts cannot sign in unless the owner explicitly approves them.
