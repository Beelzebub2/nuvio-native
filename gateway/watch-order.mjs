// Account-independent viewing orders. Junban groups franchises; MAL sequel
// relationships (via Tenrai's Jikan-compatible API) help infer the sequence.
// Only reviewed overrides can be labelled verified.
const JUNBAN = 'https://junban.moe/api/v1';
const TENRAI = 'https://api.tenrai.org/v1/anime';
const TTL = 6 * 3600;
const STALE_TTL = 7 * 86400;
const pending = new Map();
const cooldown = new Map();

// Reviewed main-story progression; the Explosion prequel is optional.
export const OVERRIDES = Object.freeze({
  7404: {
    checked: '2026-11-09',
    sources: ['https://myanimelist.net/anime/32937', 'https://myanimelist.net/anime/38040',
      'https://myanimelist.net/anime/49458'],
    steps: [
      [30831, "KonoSuba Season 1", 'tv', '2016-01-14'],
      [32937, "KonoSuba Season 2", 'tv', '2017-01-12'],
      [38040, "KonoSuba: Legend of Crimson", 'movie', '2019-08-30'],
      [49458, "KonoSuba Season 3", 'tv', '2024-04-10'],
    ],
    optional: [[51958, "An Explosion on This Wonderful World!", 'tv', '2023-04-06']],
  },
});
const known = new Map(Object.entries(OVERRIDES).flatMap(([group, order]) =>
  [...order.steps, ...order.optional].map(row => [row[0], Number(group)])));

function id(value) {
  return typeof value !== 'object' && /^\d+$/.test(String(value)) &&
    Number.isSafeInteger(Number(value)) && Number(value) > 0 && Number(value) <= 2147483647
    ? Number(value) : null;
}
function name(value) { return typeof value === 'string' ? value.trim().slice(0, 180) : ''; }
function step(row, required = true) {
  return { malId: id(row[0]), title: name(row[1]), kind: row[2], date: row[3] || null, required };
}
function json(data, status = 200, ttl = 0) {
  return Response.json(data, { status, headers: {
    'Cache-Control': ttl ? 'public, max-age=' + ttl : 'no-store',
    'X-Content-Type-Options': 'nosniff', 'Access-Control-Allow-Origin': '*',
  } });
}
class ProviderError extends Error {
  constructor(status) { super('Watch-order provider unavailable'); this.status = status; }
}
async function getJson(fetcher, url, now) {
  const host = new URL(url).hostname;
  if ((cooldown.get(host) || 0) > now()) throw new ProviderError(429);
  let response;
  try {
    response = await fetcher(url, { method: 'GET', redirect: 'error',
      headers: { Accept: 'application/json', 'User-Agent': 'StarlitWatchOrder/1.0' },
      signal: AbortSignal.timeout(4500) });
  } catch { throw new ProviderError(503); }
  if (response.status === 429 || response.status >= 500) {
    cooldown.set(host, now() + Math.min(300, Math.max(30, Number(response.headers.get('Retry-After')) || 60)) * 1000);
  }
  if (!response.ok) { await response.body?.cancel(); throw new ProviderError(response.status); }
  const reader = response.body?.getReader();
  if (!reader) throw new ProviderError(503);
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 512 * 1024) throw new ProviderError(503);
      chunks.push(value);
    }
  } finally { await reader.cancel().catch(() => {}); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  try { return JSON.parse(new TextDecoder().decode(bytes)); }
  catch { throw new ProviderError(503); }
}
function seriesFor(franchise, malId) {
  return Array.isArray(franchise?.series) ? franchise.series.slice(0, 50).find(series =>
    Array.isArray(series.seasons) &&
    series.seasons.some(season => id(season?.external_ids?.mal) === malId)) : null;
}
function seasonsFor(series) {
  return (series?.seasons || []).filter(s => s?.number > 0 && id(s.external_ids?.mal))
    .slice(0, 50).map(s => step([s.external_ids.mal, s.title || series.title, 'tv', s.first_aired]));
}
function curated(order, group, malId, mode, title) {
  const main = order.steps.map(row => step(row));
  const optional = order.optional.map(row => step(row, false));
  let steps = [...main, ...optional];
  if (mode === 'recommended') {
    // Optional Explosion belongs between the Crimson movie and Season 3 in
    // recommended viewing, never after the last main-story installment.
    steps = [...main.slice(0, 3), ...optional, ...main.slice(3)];
  }
  if (mode === 'release') steps.sort((a, b) => (a.date || '9999').localeCompare(b.date || '9999'));
  if (mode === 'chronological') steps = [...optional, ...main];
  return { franchiseId: 'junban:' + group, franchiseName: title || 'KonoSuba',
    requestedMalId: malId, mode, confidence: 'verified', reviewed: order.checked,
    steps, sources: ['starlit-overrides', 'junban', 'mal-relations'],
    references: order.sources,
    attribution: { name: 'Junban', url: 'https://junban.moe', license: 'ODbL 1.0' } };
}
async function resolve(malId, mode, fetcher, now) {
  let group = known.get(malId);
  let title = null;
  let series = null;
  try {
    const lookup = await getJson(fetcher, JUNBAN + '/lookup?site=mal&id=' + malId, now);
    group = id(lookup?.franchise?.id) || group;
    title = name(lookup?.franchise?.name);
    if (group) {
      const franchise = await getJson(fetcher, JUNBAN + '/franchises/' + group, now);
      title = name(franchise?.name) || title;
      series = seriesFor(franchise, malId);
    }
  } catch (error) {
    if (!known.has(malId)) {
      if (error instanceof ProviderError && error.status === 404) {
        return { error: 'No reliable watch order', status: 404 };
      }
      throw error;
    }
  }
  const override = OVERRIDES[group];
  if (override && [...override.steps, ...override.optional].some(row => row[0] === malId)) {
    return curated(override, group, malId, mode, title);
  }
  if (mode === 'chronological') return { error: 'Chronological order is not verified', status: 409 };
  if (!series) return { error: 'No reliable watch order', status: 404 };
  const steps = seasonsFor(series);
  if (!steps.some(row => row.malId === malId)) return { error: 'No reliable watch order', status: 404 };
  // Only direct sequel MOVIES of the actual series are considered essential
  // candidates. Other Junban series are never merged by title similarity.
  const lookups = [...new Set([malId, ...steps.map(row => row.malId)])].slice(0, 4);
  const results = await Promise.allSettled(lookups.map(value =>
    getJson(fetcher, TENRAI + '/' + value + '/relations', now)));
  const relationCount = results.filter(result => result.status === 'fulfilled').length;
  for (let index = 0; index < results.length; index++) {
    if (results[index].status !== 'fulfilled') continue;
    const position = steps.findIndex(row => row.malId === lookups[index]);
    if (position < 0) continue;
    const sequel = results[index].value?.data?.find(row => row.relation === 'Sequel');
    for (const entry of (Array.isArray(sequel?.entry) ? sequel.entry : []).slice(0, 8)) {
      const movieId = id(entry?.mal_id);
      if (entry.media_type !== 'Movie' || !movieId || !name(entry.name) ||
          steps.some(row => row.malId === movieId)) continue;
      steps.splice(position + 1, 0, step([movieId, entry.name, 'movie', null]));
    }
  }
  if (mode === 'release') steps.sort((a, b) => (a.date || '9999').localeCompare(b.date || '9999'));
  return { franchiseId: 'junban:' + group, franchiseName: title,
    requestedMalId: malId, mode, confidence: relationCount ? 'inferred' : 'partial',
    reviewed: null, steps, sources: ['junban', ...(relationCount ? ['mal-relations'] : [])],
    attribution: { name: 'Junban', url: 'https://junban.moe', license: 'ODbL 1.0' } };
}

export async function handleWatchOrderRequest(request, { cache, fetcher = fetch, now = Date.now } = {}) {
  const url = new URL(request.url);
  if (url.pathname !== '/watch-order') return null;
  if (request.method !== 'GET') return json({ error: 'Only GET supported' }, 405);
  const malId = id(url.searchParams.get('malId'));
  const mode = url.searchParams.get('mode') || 'recommended';
  if (!malId || !['recommended', 'release', 'chronological'].includes(mode) ||
    url.searchParams.getAll('malId').length !== 1 ||
    url.searchParams.getAll('mode').length > 1 ||
    [...url.searchParams.keys()].some(key => !['malId', 'mode'].includes(key))) {
    return json({ error: 'Invalid watch-order query' }, 400);
  }
  const key = new Request('http://watch-order.internal/watch-order?malId=' + malId + '&mode=' + mode);
  const staleKey = new Request(key.url + '&stale=1');
  const hit = await cache.match(key);
  if (hit) { hit.headers.set('X-Nuvio-Cache', 'HIT'); return hit; }
  if (!pending.has(key.url)) {
    const work = (async () => {
      const data = await resolve(malId, mode, fetcher, now);
      if (data.error) {
        const errorResponse = json({ error: data.error }, data.status, data.status === 404 ? 300 : 0);
        if (data.status === 404) await cache.put(key, errorResponse.clone());
        return errorResponse;
      }
      const result = json(data, 200, TTL);
      await Promise.all([cache.put(key, result.clone()), cache.put(staleKey, json(data, 200, STALE_TTL))]);
      result.headers.set('X-Nuvio-Cache', 'MISS');
      return result;
    })().finally(() => pending.delete(key.url));
    pending.set(key.url, work);
  }
  try { return (await pending.get(key.url)).clone(); }
  catch {
    const stale = await cache.match(staleKey);
    if (stale) { stale.headers.set('X-Nuvio-Cache', 'STALE'); stale.headers.set('X-Nuvio-Stale', '1'); return stale; }
    return json({ error: 'Watch-order provider unavailable' }, 503);
  }
}
