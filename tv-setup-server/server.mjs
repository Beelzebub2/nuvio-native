import { createServer } from "node:http";
import { readFileSync } from "node:fs";
import { isIP } from "node:net";
import { fileURLToPath, pathToFileURL } from "node:url";
import { TvSetupRelay, RelayError } from "./relay.mjs";

const files = new Map([
  ["/tv/connect", ["./web/index.html", "text/html; charset=utf-8"]],
  ["/tv/connect/", ["./web/index.html", "text/html; charset=utf-8"]],
  ["/tv/app.js", ["./web/app.js", "text/javascript; charset=utf-8"]],
  ["/tv/style.css", ["./web/style.css", "text/css; charset=utf-8"]],
  ["/tv/logo.svg", ["./web/logo.svg", "image/svg+xml"]]
]);
const staticFiles = new Map([...files].map(([url, [path, type]]) =>
  [url, { body: readFileSync(fileURLToPath(new URL(path, import.meta.url))), type }]));
// A TV with many installed add-ons/providers can legitimately publish 30-50KB
// of bounded descriptive metadata. Keep the limit above that envelope while
// rejecting large arbitrary uploads before JSON parsing.
const MAX_BODY = 64_000;
const CORS_TV_PATHS = new Set([
  "/tv/api/start", "/tv/api/tv", "/tv/api/approve", "/tv/api/state",
  "/tv/api/ack", "/tv/api/stop"
]);
const SITE_ORIGIN = process.env.STARLIT_SITE_ORIGIN || "https://starlit.rrmtools.uk";
const DEFAULT_HOST = "127.0.0.1";
const MINUTE = 60_000;

function reply(res, code, payload, extra = {}) {
  res.writeHead(code, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(JSON.stringify(payload)),
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    ...extra
  });
  res.end(JSON.stringify(payload));
}
async function body(req) {
  if (req.headers["content-type"]?.split(";")[0] !== "application/json") {
    throw new RelayError(415, "JSON requests required");
  }
  let size = 0;
  const parts = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY) throw new RelayError(413, "Request too large");
    parts.push(chunk);
  }
  try {
    const parsed = JSON.parse(Buffer.concat(parts).toString("utf8"));
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed))
      throw new Error("not an object");
    return parsed;
  } catch { throw new RelayError(400, "Invalid JSON"); }
}
function requiredId(url) {
  const id = url.searchParams.get("id");
  if (!id || !/^[a-f0-9]{64}$/.test(id)) throw new RelayError(400, "Invalid pairing");
  return id;
}

export function createTvSetupServer({
  relay = new TvSetupRelay(), origin = SITE_ORIGIN, now = Date.now,
  // Only enable behind a private Cloudflare Tunnel; do not trust a caller-supplied
  // IP header on a publicly exposed Node listener.
  trustCloudflareIp = process.env.STARLIT_TV_TRUST_CF_IP === "1"
} = {}) {
  const rates = new Map();
  let lastRateSweep = now();
  return createServer(async (req, res) => {
    const requestUrl = new URL(req.url || "/", "http://internal.invalid");
    const path = requestUrl.pathname;
    const tvRoute = CORS_TV_PATHS.has(path);
    const cors = tvRoute ? {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type,X-TV-Token",
      "Access-Control-Max-Age": "600",
      "Vary": "Origin"
    } : {};
    const send = (status, payload) => reply(res, status, payload, cors);
    try {
      if (tvRoute && req.method === "OPTIONS") return send(204, {});
      if (req.method === "GET" && staticFiles.has(path)) {
        const file = staticFiles.get(path);
        res.writeHead(200, {
          "Content-Type": file.type, "Content-Length": file.body.length,
          "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
          "Referrer-Policy": "no-referrer", "X-Frame-Options": "DENY",
          "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
        });
        return res.end(file.body);
      }
      if (path === "/tv/healthz" && req.method === "GET") return send(200, { ok: true });
      if (!path.startsWith("/tv/api/")) throw new RelayError(404, "Not found");
      // TV app sends cross-origin credentials-omitted requests with a high-entropy
      // X-TV-Token. Phone writes must come from the first-party page.
      if (!tvRoute && req.method === "POST" &&
          req.headers.origin !== origin)
        throw new RelayError(403, "Invalid origin");
      const id = path === "/tv/api/start" || path === "/tv/api/claim" ? null : requiredId(requestUrl);
      const tv = req.headers["x-tv-token"];
      const phone = req.headers["x-phone-token"];
      // All traffic arrives from one private Caddy proxy. Authenticated sessions
      // need independent limits; otherwise a few connected TVs lock everyone out.
      // Never accept an arbitrary URL id as a separate bucket without first
      // proving knowledge of the appropriate 256-bit scoped credential.
      const role = tvRoute ? "tv" : "phone";
      const authorized = id && relay.ownsCredential(id, role, tvRoute ? tv : phone);
      const cloudflareIp = req.headers["cf-connecting-ip"];
      const remote = trustCloudflareIp && typeof cloudflareIp === "string" &&
        cloudflareIp.length <= 45 && isIP(cloudflareIp)
        ? "edge-client:" + cloudflareIp
        : "peer:" + (req.socket.remoteAddress || "unknown");
      const cap = path === "/tv/api/claim" ? 20 : path === "/tv/api/start" ? 40 :
        authorized ? 90 : 30;
      const key = authorized ? "session:" + id + ":" + role + ":" + path : remote + ":" + path;
      const time = now();
      const current = rates.get(key);
      const window = current && time - current.at < MINUTE ? current : { at: time, count: 0 };
      window.count++;
      rates.set(key, window);
      if (window.count > cap) throw new RelayError(429, "Too many requests");
      // Never retain a session or IP-derived limiter entry indefinitely, even
      // when request volume stays below the high-cardinality safety threshold.
      if (time - lastRateSweep >= MINUTE || rates.size > 10000) {
        for (const [entry, value] of rates) if (time - value.at >= MINUTE) rates.delete(entry);
        lastRateSweep = time;
      }
      let data = {};
      if (req.method === "POST") data = await body(req);
      if (path === "/tv/api/start" && req.method === "POST") return send(200, relay.start(data));
      if (path === "/tv/api/claim" && req.method === "POST") return send(200, relay.claim(data.code));
      if (path === "/tv/api/tv" && req.method === "GET") return send(200, relay.inspectTV(id, tv));
      if (path === "/tv/api/phone" && req.method === "GET") return send(200, relay.inspectPhone(id, phone));
      if (path === "/tv/api/approve" && req.method === "POST") return send(200, relay.approve(id, tv, data.accept));
      if (path === "/tv/api/state" && req.method === "POST") return send(200, relay.updateState(id, tv, data));
      if (path === "/tv/api/command" && req.method === "POST") return send(200, relay.enqueue(id, phone, data));
      if (path === "/tv/api/ack" && req.method === "POST") return send(200, relay.acknowledge(id, tv, data));
      if (path === "/tv/api/stop" && req.method === "POST") return send(200, relay.revoke(id, tv));
      if (path === "/tv/api/leave" && req.method === "POST") return send(200, relay.revoke(id, phone));
      throw new RelayError(404, "Not found");
    } catch (error) {
      return send(error instanceof RelayError ? error.status : 500,
        { error: error instanceof RelayError ? error.message : "Request failed" });
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const host = process.env.HOST || DEFAULT_HOST;
  const privateContainer = process.env.STARLIT_TV_RELAY_CONTAINER === "1";
  if (!["127.0.0.1", "::1"].includes(host) && !(privateContainer && host === "0.0.0.0"))
    throw new Error("The TV setup service must listen on loopback or an isolated private container network");
  const port = Number(process.env.PORT || 8751);
  if (!Number.isInteger(port) || port < 1 || port > 65535)
    throw new Error("Invalid TV setup service port");
  createTvSetupServer().listen(port, host, () =>
    console.log("Starlit TV setup service ready on port", port));
}
