# Experimental JavaScript plugins on Tizen 5.0

The regular TV QuickJS worker uses WebAssembly and a packaged Node network
service. Samsung Tizen 5.0 lacks browser WebAssembly, so this preview uses a
separate **JavaScript-only (asm.js)** VM. It does not change Tizen 6+, LG webOS,
or standard add-ons.

On a Tizen 5.0 TV, navigate to **Plugins** and use **Enable plugin providers
globally** to opt in. The choice is local to this TV and remains off until
explicitly enabled. Changing it reloads the app to clear the runtime health
and Worker self-test cache.

Only **public HTTPS endpoints supporting browser CORS** are accessible. The
transport rejects literal IP addresses, common internal hostnames, non-HTTPS
URLs, credentials and redirects, and bounds responses to 512 KiB. It does not
start the legacy Node PluginService or expose a general HTTP proxy. Browser
CORS rules, lack of a DNS-rebinding guarantee, and Chrome 63's lack of native
AbortController remain limitations; provider compatibility is not universal.
The JavaScript VM may be slower or use more memory than WASM. The preview
uses one concurrent provider and the limited TV runtime quotas.

**Required before declaring supported on retail hardware:** signed WGT
install/launch on a Tizen 5.0 TV; legal fixture with getStreams/onSettings,
network fixture, provider enable/disable and settings persistence, media first
frame/seek, remote-control response latency, RAM pressure, pause/resume,
timeouts and hung-worker recovery. Keep experimental and opt-in until verified.
