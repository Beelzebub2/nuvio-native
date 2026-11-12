# Local service ports

| Port | Owner | Restrictions |
| --- | --- | --- |
| 2710/TCP | EngineFS local torrent/proxy Web Service | Bound to `127.0.0.1`, Host and Origin checked, state-changing requests require a trusted MessagePort token |
| 2711/TCP | Plugin Web Service | Bound to `127.0.0.1`, runtime-capability gated |
| 26101/TCP | Samsung Tizen developer interface | Local installation/debugging only; do not expose to WAN |
| 8001/8002 TCP | Samsung TV API | TV-provided API, not a Starlit listener |

The `/settings` and `/range-probe` EngineFS endpoints contain only fixed,
non-private readiness information. The range probe requires an exact 206
response and the expected ASCII bytes, including when older WebKit versions
hide response headers from JavaScript.
