import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';
import { handleRequest } from './worker.mjs';

// Process-local metadata only: eviction or restart never loses account data.
export class MemoryCache {
  constructor({ maxBytes = 64 * 1024 * 1024, maxEntries = 512, now = Date.now } = {}) {
    this.entries = new Map();
    this.bytes = 0;
    this.maxBytes = maxBytes;
    this.maxEntries = maxEntries;
    this.now = now;
  }

  remove(key) {
    const entry = this.entries.get(key);
    if (entry) this.bytes -= entry.body.byteLength;
    this.entries.delete(key);
  }

  async match(request) {
    const entry = this.entries.get(request.url);
    if (!entry) return undefined;
    if (entry.expires <= this.now()) { this.remove(request.url); return undefined; }
    return new Response(entry.body, { status: entry.status, headers: entry.headers });
  }

  async put(request, response) {
    const ttl = Number(/(?:^|,)\s*max-age=(\d+)/i.exec(response.headers.get('Cache-Control') || '')?.[1]);
    if (!(ttl > 0)) return;
    const body = new Uint8Array(await response.arrayBuffer());
    this.remove(request.url);
    if (body.byteLength > this.maxBytes) return;
    for (const [key, entry] of this.entries) if (entry.expires <= this.now()) this.remove(key);
    while (this.entries.size && (this.bytes + body.byteLength > this.maxBytes || this.entries.size >= this.maxEntries)) {
      this.remove(this.entries.keys().next().value);
    }
    if (this.maxEntries < 1) return;
    this.entries.set(request.url, { body, status: response.status, headers: [...response.headers], expires: this.now() + ttl * 1000 });
    this.bytes += body.byteLength;
  }
}

export function createGatewayServer({ cache = new MemoryCache(), fetcher = fetch, tmdbToken = '' } = {}) {
  let active = 0;
  const server = createServer({ maxHeaderSize: 16 * 1024 }, async (incoming, outgoing) => {
    if (active >= 8) {
      outgoing.writeHead(503, { 'Retry-After': '1', 'Cache-Control': 'no-store' });
      outgoing.end();
      return;
    }
    active++;
    try {
      if (incoming.method !== 'GET') {
        outgoing.writeHead(405, { Allow: 'GET', 'Cache-Control': 'no-store' }); outgoing.end(); return;
      }
      // Ignore Host/forwarded headers so all requests share the same cache origin.
      if (!incoming.url.startsWith('/') || incoming.url.startsWith('//')) {
        outgoing.writeHead(400); outgoing.end(); return;
      }
      const request = new Request(`http://gateway.internal${incoming.url}`, { method: incoming.method });
      const pending = [];
      const response = await handleRequest(request, { waitUntil: task => pending.push(task) }, { cache, fetcher, tmdbToken });
      outgoing.writeHead(response.status, Object.fromEntries(response.headers));
      outgoing.end(Buffer.from(await response.arrayBuffer()));
      await Promise.allSettled(pending);
    } catch (error) {
      console.error(JSON.stringify({ event: 'gateway_request_failed', name: error.name }));
      if (!outgoing.headersSent) outgoing.writeHead(500, { 'Cache-Control': 'no-store' });
      outgoing.end();
    } finally { active--; }
  });
  server.requestTimeout = 15000;
  server.headersTimeout = 10000;
  server.keepAliveTimeout = 5000;
  server.maxRequestsPerSocket = 100;
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.PORT || 8790);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid PORT');
  const server = createGatewayServer({ tmdbToken: process.env.TMDB_READ_ACCESS_TOKEN || '' });
  server.listen(port, '127.0.0.1', () => console.log(`Starlit metadata gateway listening on 127.0.0.1:${port}`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(1), 15000).unref();
  });
}
