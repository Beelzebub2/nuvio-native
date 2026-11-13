# Tracking provider OAuth configuration

Starlit includes optional account linking for Trakt, Simkl, and MDBList. Each
provider requires **its own registered application**. A user's personal API
key is not an OAuth application Client ID. Do not borrow third-party IDs or
commit client secrets, access tokens, or refresh tokens to the repository.

## Release configuration

Register applications with the official provider developer portals:

- Trakt: https://developer.trakt.tv/docs/create-an-app — use a Production
  application Client ID for public distribution. Production authorization may
  require provider approval. Device-code sign-in does not embed a secret.
- Simkl: https://simkl.com/settings/developer/new/ — register the app and use
  its Client ID for the supported PIN device flow.
- MDBList: https://mdblist.com/developer/ — register an OAuth application and
  verify that the provider supports the device authorization flow selected by
  Starlit before publishing.

Set **GitHub Actions repository variables** `TRAKT_CLIENT_ID`,
`SIMKL_CLIENT_ID`, and `MDBLIST_CLIENT_ID` to those registered application
IDs. The build system also accepts the same names through environment
variables or an untracked `local.properties` file for local development.
These IDs are public OAuth application identifiers and will appear in the
compiled client; **never provide a client secret** to a public client build.

The Windows, Android, Android TV and Linux release jobs fail early when an ID
is missing or malformed. A passing preflight checks only basic configuration:
it does **not** verify provider approval, authorization, or sync functionality.
`TRAKT_REDIRECT_URI` must stay empty until its HTTPS callback is hosted and
verified using App/Universal Links. The desktop device-code fallback is
available without it.

## Acceptance before closing the provider-connection issue

On an actual Windows release installer, independently connect all three
providers, check that each changes to Connected, disconnect and reconnect,
verify account-specific library/watchlist or history refresh, and verify
token expiry/refresh and revocation. Confirm that connecting a provider does
not change a previously selected local Library source. Repeat any
platform-specific auth flow acceptance before claiming mobile/TV support.
Do not print authorization codes, tokens, account identifiers, or client
credential values in public CI logs or issue comments.
