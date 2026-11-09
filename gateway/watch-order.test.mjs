import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handleWatchOrderRequest } from './watch-order.mjs';
import { MemoryCache, createGatewayServer } from './server.mjs';
import { once } from 'node:events';

const lookup = { franchise: { id: 7404, name: 'KonoSuba' } };
const franchise = { id: 7404, name: 'KonoSuba', series: [] };

function fakeFetch(calls) {
  return async url => {
    calls.push(url);
    if (url.includes('/lookup?site=mal&id=')) return Response.json(lookup);
    if (url.endsWith('/franchises/7404')) return Response.json(franchise);
    throw new Error('unapproved upstream URL: ' + url);
  };
}
function request(query) {
  return new Request('http://test.internal/watch-order?' + query);
}

test('KonoSuba main-story order inserts essential movie before season 3', async () => {
  const calls = [];
  const response = await handleWatchOrderRequest(request('malId=32937'), {
    cache: new MemoryCache(), fetcher: fakeFetch(calls),
  });
  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.equal(payload.confidence, 'verified');
  assert.equal(payload.franchiseId, 'junban:7404');
  assert.deepEqual(payload.steps.filter(step => step.required).map(step => step.malId),
    [30831, 32937, 38040, 49458]);
  assert.equal(payload.steps.find(step => step.malId === 51958).required, false);
  assert.equal(payload.steps.find(step => step.malId === 38040).kind, 'movie');
  assert.deepEqual(payload.steps.map(step => step.malId), [30831, 32937, 38040, 51958, 49458]);
  assert.equal(calls.length, 2);
});

test('alternate MAL IDs share the same verified sequence', async () => {
  for (const malId of [30831, 38040, 49458, 51958]) {
    const response = await handleWatchOrderRequest(request('malId=' + malId), {
      cache: new MemoryCache(), fetcher: fakeFetch([]),
    });
    assert.equal(response.status, 200);
    assert.equal((await response.json()).steps.find(s => s.malId === 38040).required, true);
  }
});

test('release and chronology modes are distinct and explicitly curated', async () => {
  const fetcher = fakeFetch([]);
  const release = await handleWatchOrderRequest(request('malId=38040&mode=release'), {
    cache: new MemoryCache(), fetcher,
  });
  const chronological = await handleWatchOrderRequest(request('malId=38040&mode=chronological'), {
    cache: new MemoryCache(), fetcher,
  });
  assert.deepEqual((await release.json()).steps.map(s => s.malId), [30831, 32937, 38040, 51958, 49458]);
  assert.deepEqual((await chronological.json()).steps.map(s => s.malId), [51958, 30831, 32937, 38040, 49458]);
});

test('invalid and unknown query keys are rejected before any network call', async () => {
  let calls = 0;
  const fetcher = async () => { calls++; throw new Error('unexpected'); };
  const cache = new MemoryCache();
  for (const query of ['malId=-1', 'malId=abc', 'malId=0', 'malId=2147483648', 'malId=2&mode=wrong',
    'malId=1&url=https://evil.test', 'malId=1&malId=2']) {
    const response = await handleWatchOrderRequest(request(query), { cache, fetcher });
    // Duplicate MAL IDs remain invalid rather than accepting a misleading value.
    assert.equal(response.status, 400, query);
  }
  assert.equal(calls, 0);
});

test('shared cache avoids repeated upstream requests and keeps metadata account-independent', async () => {
  const calls = [];
  const cache = new MemoryCache();
  const fetcher = fakeFetch(calls);
  const a = await handleWatchOrderRequest(request('malId=30831'), { cache, fetcher });
  const b = await handleWatchOrderRequest(request('mode=recommended&malId=30831'), { cache, fetcher });
  assert.equal(a.headers.get('X-Nuvio-Cache'), 'MISS');
  assert.equal(b.headers.get('X-Nuvio-Cache'), 'HIT');
  assert.equal(calls.length, 2);
  const text = await b.text();
  assert.ok(!text.includes('accountId') && !text.includes('watchHistory'));
});

test('non-curated anime only include their own series and explicitly connected sequels', async () => {
  const fetcher = async url => {
    if (url.includes('/lookup?')) return Response.json({ franchise: { id: 9999, name: 'Test Series' } });
    if (url.endsWith('/franchises/9999')) return Response.json({ series: [
      { title: 'Test Series', seasons: [
        { number: 1, title: 'First', first_aired: '2020-01-01', external_ids: { mal: '1001' } },
        { number: 2, title: 'Second', first_aired: '2023-01-01', external_ids: { mal: '1002' } },
      ] },
      { title: 'Unrelated crossover', seasons: [
        { number: 1, title: 'Unrelated', external_ids: { mal: '2001' } },
      ] },
    ] });
    if (url.endsWith('/1001/relations')) return Response.json({ data: [{ relation: 'Sequel', entry: [
      { mal_id: 1999, name: 'Essential film', media_type: 'Movie' },
      { mal_id: 2001, name: 'Unrelated show', media_type: 'TV' },
    ] }] });
    if (url.endsWith('/1002/relations')) return Response.json({ data: [{ relation: 'Side Story', entry: [
      { mal_id: 1998, name: 'Optional extra', media_type: 'Movie' },
    ] }] });
    throw new Error('Unexpected URL: ' + url);
  };
  const response = await handleWatchOrderRequest(request('malId=1001'), { cache: new MemoryCache(), fetcher });
  assert.equal(response.status, 200);
  const data = await response.json();
  assert.equal(data.confidence, 'inferred');
  assert.deepEqual(data.steps.map(s => s.malId), [1001, 1999, 1002]);
  assert.ok(!data.steps.some(s => [1998, 2001].includes(s.malId)));
});

test('unknown chronology is refused; unknown MAL ID is not falsely marked verified', async () => {
  const fetcher = async url => url.includes('/lookup?')
    ? Response.json({ franchise: { id: 9999, name: 'Other' } })
    : Response.json({ series: [{ seasons: [{ number: 1, title: 'Other',
      external_ids: { mal: '1001' } }] }] });
  const result = await handleWatchOrderRequest(request('malId=1001&mode=chronological'), {
    cache: new MemoryCache(), fetcher,
  });
  assert.equal(result.status, 409);
  assert.match((await result.json()).error, /not verified/);
});

test('nonexistent MAL IDs produce a cacheable not-found response, not an upstream outage', async () => {
  let fetches = 0;
  const fetcher = async () => { fetches++; return Response.json({ error: 'not found' }, { status: 404 }); };
  const cache = new MemoryCache();
  assert.equal((await handleWatchOrderRequest(request('malId=900001'), { cache, fetcher })).status, 404);
  assert.equal((await handleWatchOrderRequest(request('malId=900001'), { cache, fetcher })).status, 404);
  assert.equal(fetches, 1);
});

test('provider outage uses curated data or stale cache and does not leak URLs', async () => {
  const cache = new MemoryCache();
  const fetcher = async () => { throw new Error('token=private'); };
  const known = await handleWatchOrderRequest(request('malId=30831'), { cache, fetcher });
  assert.equal(known.status, 200);
  const unknown = await handleWatchOrderRequest(request('malId=900000'), { cache, fetcher });
  assert.equal(unknown.status, 503);
  assert.doesNotMatch(await unknown.text(), /token=private/);
});

test('stale fallback survives upstream failure after fresh cache expiry', async () => {
  let timestamp = 1_000;
  let offline = false;
  const cache = new MemoryCache({ now: () => timestamp });
  const fetcher = async url => {
    if (offline) throw new Error('offline');
    if (url.includes('/lookup?')) return Response.json({ franchise: { id: 9999, name: 'Test' } });
    if (url.endsWith('/franchises/9999')) return Response.json({ series: [{
      title: 'Test', seasons: [{ number: 1, title: 'Test',
        first_aired: '2020-01-01', external_ids: { mal: '1001' } }],
    }] });
    return Response.json({ data: [] });
  };
  const options = { cache, fetcher, now: () => timestamp };
  assert.equal((await handleWatchOrderRequest(request('malId=1001'), options)).status, 200);
  timestamp += 6 * 3600 * 1000 + 1;
  offline = true;
  const response = await handleWatchOrderRequest(request('malId=1001'), options);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('X-Nuvio-Cache'), 'STALE');
  assert.equal((await response.json()).confidence, 'inferred');
});

test('node gateway serves watch-order route without changing existing health route', async t => {
  const server = createGatewayServer({ fetcher: fakeFetch([]) });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const base = 'http://127.0.0.1:' + server.address().port;
  assert.equal((await fetch(base + '/health')).status, 200);
  const result = await fetch(base + '/watch-order?malId=30831');
  assert.equal(result.status, 200);
  assert.equal((await result.json()).confidence, 'verified');
});
