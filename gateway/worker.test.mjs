import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handleRequest, providerRequest } from './worker.mjs';

const now = Math.floor(Date.now() / 1000);
const schedule = `https://gateway.test/schedule?start=${now - 86400}&end=${now + 86400}&page=1`;
function environment(fetcher) {
  const values = new Map();
  return { fetcher, cache: {
    match: async key => values.get(key.url)?.clone(),
    put: async (key, response) => { values.set(key.url, response); },
  } };
}
const ctx = { waitUntil: promise => promise };

test('fixed routes cannot become an open proxy or arbitrary GraphQL service', async () => {
  let called = false;
  const env = environment(async () => { called = true; });
  const today = new Date().toISOString().slice(0, 10);
  for (const path of ['/proxy?url=https://internal', '/schedule?page=999', '/episodes?id=../bad', '/release-schedule?start=invalid&days=2&airType=sub', `/release-schedule?start=${today}&days=2&airType=sub&includeAdult=yes`, `/release-schedule-adult?start=${today}&days=2&airType=sub&includeAdult=0`]) {
    const r = await handleRequest(new Request(`https://gateway.test${path}`), ctx, env);
    assert.ok([400, 404].includes(r.status));
  }
  assert.equal(called, false);
  const target = providerRequest(new URL(`${schedule}&query=mutation&url=https://internal`));
  assert.equal(target.url, 'https://graphql.anilist.co');
  assert.ok(target.body.query.startsWith('query('));
});

test('AniList schedule and media responses include the authoritative adult flag', () => {
  const airings = providerRequest(new URL(schedule));
  const details = providerRequest(new URL('https://gateway.test/media?id=42'));
  assert.match(airings.body.query, /media\{[^}]*isAdult/);
  assert.match(details.body.query, /isAdult/);
});

test('release-schedule explicitly opts into adult releases and isolates the cache key', async () => {
  const today = new Date().toISOString().slice(0, 10);
  const base = `https://gateway.test/release-schedule?start=${today}&days=2&airType=sub`;
  const safe = providerRequest(new URL(base));
  const adult = providerRequest(new URL(base + '&includeAdult=1'));
  const adultPath = base.replace('/release-schedule?', '/release-schedule-adult?');
  assert.equal(safe.url.includes('includeAdult'), false);
  assert.equal(adult.url.endsWith('&includeAdult=1'), true);
  assert.equal(providerRequest(new URL(adultPath)).url, adult.url);
  let requests = 0;
  const env = environment(async () => { requests++; return Response.json({ ok: true, episodes: [] }); });
  assert.equal((await handleRequest(new Request(base), ctx, env)).status, 200);
  assert.equal((await handleRequest(new Request(adultPath), ctx, env)).status, 200);
  assert.equal((await handleRequest(new Request(base + '&includeAdult=1'), ctx, env)).status, 200);
  assert.equal(requests, 2, 'adult and safe schedule results must not share the same cache');
});

test('only public read operations and health are accepted', async () => {
  const env = environment(async () => { throw Error('must not fetch'); });
  assert.equal((await handleRequest(new Request(schedule, { method: 'POST', body: 'mutation' }), ctx, env)).status, 405);
  assert.equal((await (await handleRequest(new Request('https://gateway.test/health'), ctx, env)).json()).ok, true);
});

test('successful provider JSON is shared across equivalent requests', async () => {
  let calls = 0;
  const env = environment(async (url, options) => {
    calls++;
    assert.equal(url, 'https://graphql.anilist.co');
    assert.equal(options.method, 'POST');
    return Response.json({ data: { Page: { airingSchedules: [] } } });
  });
  const first = await handleRequest(new Request(schedule), ctx, env);
  assert.equal(first.status, 200);
  assert.match(first.headers.get('cache-control'), /max-age=300/);
  assert.equal((await handleRequest(new Request(`${schedule}&extra=ignored`), ctx, env)).status, 200);
  assert.equal(calls, 1);
});

test('HTTP and GraphQL errors are never cached as a successful schedule', async () => {
  for (const response of [() => Response.json({ errors: [{ message: 'quota' }] }), () => new Response('<html>blocked</html>')]) {
    let calls = 0;
    const env = environment(async () => { calls++; return response(); });
    assert.equal((await handleRequest(new Request(schedule), ctx, env)).status, 503);
    assert.equal((await handleRequest(new Request(schedule), ctx, env)).status, 503);
    assert.equal(calls, 1, "provider failures are cooled down without caching a successful schedule");
  }
});

test('classification batches are fixed queries with canonical bounded IDs and adult tags', () => {
  const first = providerRequest(new URL('https://gateway.test/classifications?ids=218217,31,31'));
  assert.deepEqual(first.body.variables.ids, [31, 218217]);
  assert.match(first.body.query, /tags\{name isAdult\}/);
  assert.equal(first.ttl, 86400);
  for (const ids of ['1,bad', '0', '2147483648', Array(51).fill('1').join(',')]) {
    assert.throws(() => providerRequest(new URL(`https://gateway.test/classifications?ids=${ids}`)));
  }
});

test('429 preserves provider cooldown across routes without repeatedly contacting upstream', async () => {
  let calls = 0;
  const env = environment(async () => { calls++; return new Response('rate limited', { status: 429, headers: { 'Retry-After': '180' } }); });
  const first = await handleRequest(new Request(schedule), ctx, env);
  assert.equal(first.status, 429);
  assert.equal(first.headers.get('Retry-After'), '180');
  assert.equal((await first.json()).providerStatus, 429);
  const second = await handleRequest(new Request('https://gateway.test/media?id=21'), ctx, env);
  assert.equal(second.status, 429);
  assert.equal(calls, 1);
});

test('retained stale JSON is served during provider limits and fresh cache remains separate', async () => {
  let calls = 0;
  const env = environment(async () => {
    calls++;
    return calls === 1 ? Response.json({ data: { safe: true } }) : new Response('limited', { status: 429 });
  });
  const entries = new Map();
  env.cache = {
    match: async key => entries.get(key.url)?.clone(),
    put: async (key, value) => { entries.set(key.url, value); },
  };
  assert.equal((await handleRequest(new Request(schedule), ctx, env)).status, 200);
  for (const key of entries.keys()) if (!key.endsWith('&stale=1')) entries.delete(key);
  const stale = await handleRequest(new Request(schedule), ctx, env);
  assert.equal(stale.status, 200);
  assert.equal(stale.headers.get('X-Nuvio-Stale'), '1');
  assert.deepEqual(await stale.json(), { data: { safe: true } });
  assert.equal((await handleRequest(new Request(schedule), ctx, env)).headers.get('X-Nuvio-Stale'), '1');
  assert.equal(calls, 2);
});

test('recent page is newest-first and future requests stay separate from legacy schedules', () => {
  const recent = providerRequest(new URL(schedule.replace('/schedule?', '/schedule-recent?')));
  const future = providerRequest(new URL(schedule + '&future=1'));
  const legacy = providerRequest(new URL(schedule));
  assert.match(recent.body.query, /notYetAired:false,sort:TIME_DESC/);
  assert.match(future.body.query, /notYetAired:true,sort:TIME/);
  assert.doesNotMatch(legacy.body.query, /notYetAired/);
  assert.throws(() => providerRequest(new URL(schedule + '&future=invalid')));
});

test('cached pages identify hits and gateway outages are cooled down across routes', async () => {
  let calls = 0;
  const env = environment(async () => { calls++; return Response.json({ data: {} }); });
  assert.equal((await handleRequest(new Request(schedule), ctx, env)).headers.get('X-Nuvio-Cache'), 'MISS');
  assert.equal((await handleRequest(new Request(schedule), ctx, env)).headers.get('X-Nuvio-Cache'), 'HIT');
  assert.equal(calls, 1);
  const failed = environment(async () => { calls++; return new Response('blocked', { status: 403 }); });
  assert.equal((await handleRequest(new Request(schedule), ctx, failed)).status, 503);
  const later = await handleRequest(new Request('https://gateway.test/media?id=21'), ctx, failed);
  assert.equal(later.status, 503);
  assert.equal((await later.json()).providerStatus, 403);
  assert.equal(calls, 2, 'one failed origin attempt; other routes honor the outage cooldown');
});

test('episode and mapping aliases share the supported upstream and canonical cache', async () => {
  let calls = 0;
  const env = environment(async url => {
    calls++;
    assert.equal(url, 'https://api.ani.zip/mappings?anilist_id=176301');
    return Response.json({ mappings: { anilist_id: 176301 }, episodes: { 1: { image: 'https://images.test/1.jpg' } } });
  });
  // A cache entry from the old upstream must not mask the repaired route.
  const old = new URL('/cache/v2', 'https://gateway.test');
  old.searchParams.set('provider', 'https://hayase.ani.zip/v1/episodes?anilist_id=176301');
  await env.cache.put(new Request(old), Response.json({ old: true }));
  const first = await handleRequest(new Request('https://gateway.test/episodes?id=176301'), ctx, env);
  assert.equal(first.status, 200);
  assert.match(first.headers.get('cache-control'), /max-age=3600/);
  assert.equal((await first.json()).mappings.anilist_id, 176301);
  const second = await handleRequest(new Request('https://gateway.test/mapping?id=176301&ignored=1'), ctx, env);
  assert.equal(second.headers.get('X-Nuvio-Cache'), 'HIT');
  assert.equal(calls, 1);
});

test('unmapped anime has a short ID-specific negative cache and does not disable other anime', async () => {
  const calls = [];
  const env = environment(async url => {
    calls.push(url);
    return url.endsWith('=218532') ? new Response('not found', { status: 404 })
      : Response.json({ mappings: { anilist_id: 176301 }, episodes: {} });
  });
  const missing = 'https://gateway.test/episodes?id=218532';
  const first = await handleRequest(new Request(missing), ctx, env);
  assert.equal(first.status, 404);
  assert.match(first.headers.get('cache-control'), /max-age=60/);
  assert.equal((await first.json()).providerStatus, 404);
  const repeated = await handleRequest(new Request(missing.replace('/episodes?', '/mapping?')), ctx, env);
  assert.equal(repeated.status, 404);
  assert.equal(repeated.headers.get('X-Nuvio-Cache'), 'HIT');
  assert.equal((await handleRequest(new Request('https://gateway.test/episodes?id=176301'), ctx, env)).status, 200);
  assert.equal(calls.length, 2);
});

test('AniZip aliases preserve shared cooldown and valid stale artwork on rate limits', async () => {
  let calls = 0;
  const env = environment(async () => {
    calls++;
    if (calls === 1) return Response.json({ mappings: { anilist_id: 176301 }, episodes: { 1: { image: 'still' } } });
    return new Response('quota', { status: 429, headers: { 'Retry-After': '120' } });
  });
  await handleRequest(new Request('https://gateway.test/episodes?id=176301'), ctx, env);
  // Simulate fresh expiry while retaining the existing longer-lived stale entry.
  const cacheMatch = env.cache.match;
  env.cache.match = async key => key.url.includes('/cache/v2') && !key.url.endsWith('&stale=1') ? undefined : cacheMatch(key);
  const stale = await handleRequest(new Request('https://gateway.test/mapping?id=176301'), ctx, env);
  assert.equal(stale.status, 200);
  assert.equal(stale.headers.get('X-Nuvio-Stale'), '1');
  assert.equal((await stale.json()).episodes['1'].image, 'still');
  assert.equal((await handleRequest(new Request('https://gateway.test/episodes?id=218532'), ctx, env)).status, 429);
  assert.equal(calls, 2);
});
