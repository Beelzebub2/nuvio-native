import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createServer, request } from 'node:http';
import { createGatewayServer, MemoryCache } from './server.mjs';

async function listen(server, t) {
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  return `http://127.0.0.1:${server.address().port}`;
}

test('memory cache preserves status/headers, expires and bounds bytes and entry count', async () => {
  let now = 0;
  const cache = new MemoryCache({ maxBytes: 6, maxEntries: 2, now: () => now });
  const key = id => new Request(`https://gateway.test/${id}`);
  const put = (id, body) => cache.put(key(id), new Response(body, { status: 404, headers: { 'Cache-Control': 'public, max-age=2', 'X-Test': 'kept' } }));
  await put(1, 'abc'); await put(2, 'def');
  const first = await cache.match(key(1));
  assert.equal(first.status, 404); assert.equal(first.headers.get('X-Test'), 'kept');
  assert.equal(await first.text(), 'abc');
  assert.equal(await (await cache.match(key(1))).text(), 'abc');
  await put(3, 'x');
  assert.equal(await cache.match(key(1)), undefined);
  assert.equal(cache.bytes, 4); assert.equal(cache.entries.size, 2);
  await put(4, '1234567');
  assert.equal(await cache.match(key(4)), undefined);
  now = 2000;
  assert.equal(await cache.match(key(2)), undefined);
  await put(5, 'ok');
  assert.equal(cache.bytes, 2); assert.equal(cache.entries.size, 1);
  await cache.put(key(6), new Response('no cache'));
  assert.equal(await cache.match(key(6)), undefined);
});

test('HTTP adapter serves fixed routes, canonical shared cache, methods and header limits', async t => {
  let calls = 0;
  const server = createGatewayServer({ fetcher: async url => {
    calls++; assert.equal(url, 'https://api.ani.zip/mappings?anilist_id=1');
    return Response.json({ mappings: { anilist_id: 1 }, episodes: {} });
  } });
  const base = await listen(server, t);
  assert.equal((await (await fetch(`${base}/health`)).json()).ok, true);
  assert.equal((await fetch(`${base}/unknown`)).status, 404);
  assert.equal((await fetch(`${base}/episodes?id=no`)).status, 400);
  const method = await fetch(`${base}/health`, { method: 'POST', body: 'ignored' });
  assert.equal(method.status, 405); assert.equal(method.headers.get('Allow'), 'GET');
  const first = await fetch(`${base}/episodes?id=1`);
  assert.equal(first.headers.get('X-Nuvio-Cache'), 'MISS');
  await first.json();
  const cached = await fetch(`${base}/mapping?id=1&ignored=yes`, { headers: { Host: 'untrusted.test' } });
  assert.equal(cached.headers.get('X-Nuvio-Cache'), 'HIT');
  assert.equal(calls, 1);
  const status = await new Promise((resolve, reject) => {
    const req = request(`${base}/health`, { agent: false, headers: { Cookie: 'a'.repeat(20000), Connection: 'close' } }, res => { res.resume(); resolve(res.statusCode); });
    req.on('error', reject); req.end();
  });
  assert.equal(status, 431);
  assert.equal(server.requestTimeout, 15000); assert.equal(server.headersTimeout, 10000);
});

test('oversized provider bodies fail closed without caching the payload', async t => {
  let calls = 0;
  const cache = new MemoryCache();
  const base = await listen(createGatewayServer({ cache, fetcher: async () => {
    calls++; return new Response('x'.repeat(5 * 1024 * 1024 + 1));
  } }), t);
  const response = await fetch(`${base}/episodes?id=1`);
  assert.equal(response.status, 503);
  assert.equal((await response.json()).error, 'Provider unavailable');
  assert.equal((await fetch(`${base}/episodes?id=1`)).status, 503);
  assert.equal(calls, 1); // Only the provider cooldown is cached.
  assert.equal([...cache.entries.keys()].some(key => key.includes('/cache/')), false);
});

test('a stalled provider is aborted at the existing 12-second deadline', async t => {
  const upstream = await listen(createServer((_req, _res) => {}), t);
  const base = await listen(createGatewayServer({ fetcher: (_url, options) => fetch(upstream, options) }), t);
  const started = Date.now();
  const response = await fetch(`${base}/episodes?id=1`);
  assert.equal(response.status, 503);
  assert.ok(Date.now() - started >= 11000);
  assert.ok(Date.now() - started < 18000);
});

test('concurrent request limit protects memory and health recovers after completion', async t => {
  let release;
  const pending = new Promise(resolve => { release = resolve; });
  let calls = 0;
  const base = await listen(createGatewayServer({ fetcher: async () => {
    calls++; await pending; return Response.json({ episodes: {} });
  } }), t);
  const requests = Array.from({ length: 8 }, (_, i) => fetch(`${base}/episodes?id=${i + 1}`));
  while (calls < 8) await new Promise(resolve => setTimeout(resolve, 5));
  const busy = await fetch(`${base}/health`);
  assert.equal(busy.status, 503); assert.equal(busy.headers.get('Retry-After'), '1');
  release();
  for (const response of await Promise.all(requests)) assert.equal(response.status, 200);
  assert.equal((await fetch(`${base}/health`)).status, 200);
});
