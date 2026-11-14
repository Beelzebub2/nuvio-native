# Starlit TV — Phone-assisted extension setup

This first-party service implements short-lived QR pairing for TV add-ons and native JavaScript plugin repositories. It is separate from account sign-in and the Samsung/LG developer installer.

It also powers the Android Starlit remote's **Play on device** workflow. After on-TV approval, the phone sends metadata-only title/episode identifiers through `handoffSearch`. The TV resolves available sources using its own installed add-ons and providers. The phone sees descriptive source labels and opaque `source:N` handles; `handoffSelect` makes the TV play the selected original stream object. `handoffCancel` clears the request. No streaming links, credentials, torrent metadata or DRM material are sent to or stored by the relay. This is intentionally separate from manufacturer TV key controls.

## User flow

On Starlit TV open Settings > Content & Discovery > Manage with phone, or the button in Add-ons / Plugins. Scan the QR code or enter its short code using a phone. Verify the matching verification code and **approve on the TV**. The phone can then preview/add/remove/enable add-ons and, on compatible devices, plugin repositories and providers.

The TV remains authoritative for repository validation and changes, preserving per-profile permissions. Newly remotely installed JavaScript providers are disabled until selected. Unsupported local plugin execution (including the existing Tizen 5.0 gate) is shown as unavailable, not presented as installed and working. This feature is not a plugin store and does not publish or endorse third-party catalogs.

## Security boundaries

- Five-minute unapproved pairing challenge; 20-minute active idle timeout and 60-minute absolute session lifetime; explicit on-TV approval and TV or phone revocation.
- Browser-to-relay requests require a same-origin browser action and a scoped, unguessable phone credential. TV-to-relay requests carry a separate TV credential. Both credentials are held in memory only and are never put in the QR payload or website JavaScript.
- No account tokens, API keys, streaming URLs, watch history or executable JavaScript in the relay's command state. Preview metadata, sanitized extension lists and typed commands only. The phone never sends executable JS to the TV.
- Public HTTPS input only, no embedded URL credentials, unsafe hostnames or literal IP addresses; no implicit redirects. Validate DNS/redirect safeguards and plugin sandbox policies as part of your final security review.
- Memory-only sessions for one Node process. Sessions are lost on restart. Never expose the Node port publicly.

## Local tests

At the repository root:

    node --test tv-setup-server/test/*.test.mjs
    node --test tizen/tests/starlit-phone-setup.test.mjs
    node --test tizen/tests/starlit-phone-handoff.test.mjs
    npm --prefix tizen run lint
    npm --prefix tizen run build

The relay has no external runtime dependencies and needs Node.js 22 or newer.

To serve the local phone page, set STARLIT_SITE_ORIGIN to http://127.0.0.1:8751, then run node tv-setup-server/server.mjs. The route is http://127.0.0.1:8751/tv/connect in local development. Real TVs use the pinned HTTPS origin below.

## Private-container production integration

Use the included compose.private.yml on the existing private Caddy bridge network, setting STARLIT_CADDY_NETWORK in a server-only environment file. Verify the service with docker compose config and its health check. It has no published host ports, runs as an unprivileged user, drops Linux capabilities and uses a read-only filesystem with CPU/memory/process limits. The listener binds the isolated container network, not a public interface. Its optional STARLIT_TV_TRUST_CF_IP rate-limit mode must only be enabled for traffic authenticated by a private Cloudflare Tunnel; a directly internet-exposed listener must not trust caller-supplied CF-Connecting-IP headers.

Alternatively run Node under an unprivileged systemd service on host loopback (HOST=127.0.0.1) only if the configured Caddy origin can reach that loopback address.

Inside the existing private Caddy site block, add a host-and-path-scoped handler, and exclude /tv/* from the broad website handler:

    @tv_phone {
        host starlit.rrmtools.uk
        path /tv/*
    }
    handle @tv_phone {
        reverse_proxy starlit-tv-phone-relay:8751
    }
    @starlit {
        host starlit.rrmtools.uk
        not path /tv/*
    }

Validate with Caddy adapt/validate, reload without restarting unrelated services, and verify both /tv/healthz and existing homepage/account routes. Back up the original config and retain tested rollback steps. Bypass caching for /tv/* and exclude those paths from access logs (for example Caddy log_skip), where ephemeral session IDs are present. Preserve account-specific cache/CSP isolation, TLS/HSTS and Cloudflare protections. The management page has its own restrictive CSP.

Deploy/test the route in staging before production. Confirm GET /tv/healthz, QR scan and manual code flows, matching code, TV authorization, add-on preview/install, enabled-state changes, revoked/expired tokens, local account guest handling and unsupported plugin behavior. Real-device acceptance must include a Samsung Tizen 5 TV (Chromium 63), at least one Tizen 6+ TV, and LG webOS. Test phone browsers on mobile data and Wi-Fi.

No backend deployment or TV package release is included by merely merging this code. Keep the feature unreleased until the HTTPS route and hardware acceptance are verified.
