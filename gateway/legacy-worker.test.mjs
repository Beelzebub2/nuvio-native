import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

test('legacy Worker only forwards GET to the fixed VPS prefix and preserves responses', async () => {
  const requests = [];
  const runtime = new Miniflare(convertV4MiniflareOptions({ workers: [{
    modules: true, script: readFileSync(new URL('./legacy-worker.mjs', import.meta.url), 'utf8'),
    compatibilityDate: '2026-10-04',
    outboundService: request => {
      requests.push(request);
      return new Response('provider cooldown', { status: 429, headers: { 'Retry-After': '120', 'X-Nuvio-Cache': 'STALE', 'Cache-Control': 'public, max-age=60' } });
    },
  }] }));
  try {
    const result = await runtime.dispatchFetch('https://legacy.test/artwork/tvmaze-lookup?tvdb=431162&url=https://evil.test', {
      headers: { Authorization: 'Bearer must-not-forward', Cookie: 'private=value' },
    });
    assert.equal(requests[0].url, 'https://starlit-api.rrmtools.uk/gateway/artwork/tvmaze-lookup?tvdb=431162&url=https://evil.test');
    assert.equal(requests[0].headers.get('Authorization'), null);
    assert.equal(requests[0].headers.get('Cookie'), null);
    assert.equal(result.status, 429);
    assert.equal(result.headers.get('Retry-After'), '120');
    assert.equal(result.headers.get('X-Nuvio-Cache'), 'STALE');
    assert.equal(result.headers.get('Cache-Control'), 'public, max-age=60');
    assert.equal(await result.text(), 'provider cooldown');
    assert.equal((await runtime.dispatchFetch('https://legacy.test/health', { method: 'POST' })).status, 405);
    assert.equal((await runtime.dispatchFetch('https://legacy.test/%2f..%2f..%2fauth/v1/health')).status, 404);
    assert.equal(requests.length, 1);
  } finally { await runtime.dispose(); }
});
