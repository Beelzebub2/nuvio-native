# Trakt sign-in configuration

Starlit uses a **public OAuth client ID**, not a distributed client secret.
Current Trakt guidance recommends Authorization Code + PKCE (S256) for
browser-based sign-in and also supports Device Code for media-center applications.

## Registration and build configuration

1. Register a Trakt API application: https://developer.trakt.tv/docs/create-an-app
2. Obtain its **Client ID** and request Production access where required.
3. Set the GitHub Actions repository variable **TRAKT_CLIENT_ID** to this public ID
   (Settings → Secrets and variables → Actions → Variables). The tagged release
   workflows provide this value to Windows, Android, TV and Linux builds.
4. For local builds, put TRAKT_CLIENT_ID in an untracked local.properties file,
   or set it in the build process environment.

The Connect button intentionally remains disabled when the public Client ID is
missing. Do not use another application's OAuth identity, hard-code a placeholder
client ID, or put a client secret into a distributable app.

## Device and PKCE authentication

- **Desktop:** device-code authorization, preserving existing desktop UX. All
  requests use the public Client ID; no client secret is sent.
- **Mobile without a verified callback (default):** falls back to device-code
  login. This is preferable to an insecure, hijackable custom URI callback.
- **Mobile with a verified callback (optional):** set **TRAKT_REDIRECT_URI** only
  after deploying and verifying the exact HTTPS callback and configuring
  Android Verified App Links and iOS Universal Links for it. A fresh random
  verifier and state are created per sign-in and the S256 challenge is sent to
  Trakt. Callback origin, state and expiry are checked, and the one-use
  verifier is consumed before code exchange. The verifier is stored with the
  encrypted profile-scoped auth state and never embedded in the browser URL.

**Do not** set TRAKT_REDIRECT_URI to a custom scheme, loopback address, OOB
redirect or an HTTPS URI which does not have working domain/app association.
Without verified app links, browser callbacks will not safely return to the app.

Existing encrypted account tokens stay readable. Refresh tokens are rotated
in persistent storage after every successful refresh. Some older credentials
may require reconnection if the provider responds with invalid_grant.

## Acceptance checklist

Before declaring tracking sign-in fixed in a published release:

1. Build a signed installer with a registered Production Trakt Client ID.
2. Complete Windows device-code sign-in, sign-out and access-token refresh.
3. Confirm persisted sign-in survives a restart and remains profile-scoped.
4. Test Android and iOS device-code sign-in on physical devices; if optional
   HTTPS PKCE is enabled, validate app/universal links, denied, replayed,
   expired and wrong-state callback behavior.
5. Confirm no secrets, tokens, or PKCE verifiers appear in application logs.
6. Separately validate registered SIMKL and MDBList integrations.

The code change does not create or approve a Trakt production application or
configure the GitHub variable on its own.

Official documentation:

- https://developer.trakt.tv/docs/pkce
- https://developer.trakt.tv/docs/authentication-oauth
- https://developer.trakt.tv/docs/create-an-app
