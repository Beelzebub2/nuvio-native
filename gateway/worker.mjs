// A fixed, read-only provider gateway. Never accepts arbitrary URLs or GraphQL.
const MEDIA_FIELDS = 'id idMal isAdult title{english romaji native} coverImage{extraLarge large} bannerImage description genres episodes';
const SCHEDULE_QUERY = `query($start:Int,$end:Int,$page:Int){Page(page:$page,perPage:50){pageInfo{hasNextPage}airingSchedules(airingAt_greater:$start,airingAt_lesser:$end,sort:TIME){episode airingAt media{${MEDIA_FIELDS}}}}}`;
const MEDIA_QUERY = `query($id:Int){Media(id:$id,type:ANIME){${MEDIA_FIELDS}}Page(perPage:50){airingSchedules(mediaId:$id,notYetAired:false,sort:EPISODE_DESC){episode airingAt}}}`;

function integer(params, name, min, max) {
  const value = params.get(name);
  if (!/^\d+$/.test(value || '') || Number(value) < min || Number(value) > max) throw new Error(`Invalid ${name}`);
  return Number(value);
}

export function providerRequest(url) {
  const p = url.searchParams;
  if (url.pathname === '/schedule') {
    const now = Math.floor(Date.now() / 1000);
    const start = integer(p, 'start', now - 10 * 86400, now + 2 * 86400);
    const end = integer(p, 'end', start + 1, start + 10 * 86400);
    const page = integer(p, 'page', 1, 20);
    return { url: 'https://graphql.anilist.co', body: { query: SCHEDULE_QUERY, variables: { start, end, page } }, ttl: 300 };
  }
  if (url.pathname === '/media') {
    const id = integer(p, 'id', 1, 2147483647);
    return { url: 'https://graphql.anilist.co', body: { query: MEDIA_QUERY, variables: { id } }, ttl: 900 };
  }
  if (url.pathname === '/episodes' || url.pathname === '/mapping') {
    const id = integer(p, 'id', 1, 2147483647);
    const path = url.pathname === '/episodes' ? 'episodes' : 'mappings';
    return { url: `https://hayase.ani.zip/v1/${path}?anilist_id=${id}`, ttl: 3600 };
  }
  if (url.pathname === '/release-schedule' || url.pathname === '/release-schedule-adult') {
    const start = p.get('start');
    const date = Date.parse(`${start}T00:00:00Z`);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(start || '') || !Number.isFinite(date) ||
        new Date(date).toISOString().slice(0, 10) !== start || Math.abs(date - Date.now()) > 10 * 86400000) throw new Error('Invalid start date');
    const days = integer(p, 'days', 1, 9);
    const airType = p.get('airType');
    if (!['sub', 'dub'].includes(airType)) throw new Error('Invalid airType');
    const includeAdult = p.get('includeAdult');
    if (includeAdult !== null && !['0', '1'].includes(includeAdult)) throw new Error('Invalid includeAdult');
    if (url.pathname === '/release-schedule-adult' && includeAdult === '0') throw new Error('Invalid includeAdult');
    const adult = url.pathname === '/release-schedule-adult' || includeAdult === '1';
    return { url: `https://tsuzuki.top/api/v1/schedule?start=${start}&days=${days}&airType=${airType}${adult ? '&includeAdult=1' : ''}`, ttl: 300 };
  }
  return null;
}

function json(payload, status = 200, headers = {}) {
  return Response.json(payload, { status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', ...headers } });
}

export async function handleRequest(request, ctx, { fetcher = fetch, cache = caches.default } = {}) {
  const url = new URL(request.url);
  if (request.method !== 'GET') return json({ error: 'Only GET requests are supported' }, 405, { Allow: 'GET' });
  if (url.pathname === '/health') return json({ ok: true, service: 'nuvio-native-gateway', version: '1.0.0' });
  let target;
  try { target = providerRequest(url); } catch (e) { return json({ error: e.message }, 400); }
  if (!target) return json({ error: 'Unknown route' }, 404);
  // Ignore unrecognized query parameters and canonicalize requests for a shared cache.
  const keyUrl = new URL('/cache/v1', url.origin);
  keyUrl.searchParams.set('provider', target.url);
  if (target.body) keyUrl.searchParams.set('body', JSON.stringify(target.body));
  const key = new Request(keyUrl);
  const cached = await cache.match(key);
  if (cached) return cached;
  try {
    const upstream = await fetcher(target.url, {
      method: target.body ? 'POST' : 'GET',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'User-Agent': 'NuvioNative/1.0' },
      body: target.body ? JSON.stringify(target.body) : undefined,
      redirect: 'manual', signal: AbortSignal.timeout(12000),
    });
    if (!upstream.ok) {
      await upstream.body?.cancel();
      console.warn(JSON.stringify({ event: 'provider_error', provider: new URL(target.url).hostname, status: upstream.status }));
      return json({ error: 'Provider unavailable', providerStatus: upstream.status }, 503, { 'Retry-After': '60' });
    }
    // Providers are fixed JSON APIs; bound their bodies before parsing/cache insertion.
    const reader = upstream.body.getReader();
    const chunks = [];
    let size = 0;
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > 5 * 1024 * 1024) throw new Error('Provider response too large');
        chunks.push(value);
      }
    } finally { await reader.cancel(); }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    const payload = JSON.parse(new TextDecoder().decode(bytes));
    if (payload.error || payload.errors?.length) return json({ error: 'Provider returned an error' }, 503, { 'Retry-After': '60' });
    const response = json(payload, 200, { 'Cache-Control': `public, max-age=${target.ttl}`, 'X-Nuvio-Gateway': '1' });
    ctx.waitUntil(cache.put(key, response.clone()).catch(() => {}));
    return response;
  } catch (e) {
    console.warn(JSON.stringify({ event: 'provider_request_failed', provider: new URL(target.url).hostname, name: e.name, message: String(e.message).slice(0, 160) }));
    return json({ error: 'Provider request failed' }, 503, { 'Retry-After': '60' });
  }
}

export default { fetch(request, env, ctx) { return handleRequest(request, ctx); } };
