// A fixed, read-only provider gateway. Never accepts arbitrary URLs or GraphQL.
const MEDIA_FIELDS = 'id idMal isAdult tags{name isAdult} title{english romaji native} coverImage{extraLarge large} bannerImage description genres episodes';
const SCHEDULE_QUERY = `query($start:Int,$end:Int,$page:Int){Page(page:$page,perPage:50){pageInfo{hasNextPage}airingSchedules(airingAt_greater:$start,airingAt_lesser:$end,sort:TIME){episode airingAt media{${MEDIA_FIELDS}}}}}`;
const RECENT_QUERY = SCHEDULE_QUERY.replace('sort:TIME', 'notYetAired:false,sort:TIME_DESC');
const FUTURE_QUERY = SCHEDULE_QUERY.replace('sort:TIME', 'notYetAired:true,sort:TIME');
const MEDIA_QUERY = `query($id:Int){Media(id:$id,type:ANIME){${MEDIA_FIELDS}}Page(perPage:50){airingSchedules(mediaId:$id,notYetAired:false,sort:EPISODE_DESC){episode airingAt}}}`;

function integer(params, name, min, max) {
  const value = params.get(name);
  if (!/^\d+$/.test(value || '') || Number(value) < min || Number(value) > max) throw new Error(`Invalid ${name}`);
  return Number(value);
}

export function providerRequest(url) {
  const p = url.searchParams;
  if (url.pathname === '/schedule' || url.pathname === '/schedule-recent') {
    const now = Math.floor(Date.now() / 1000);
    const start = integer(p, 'start', now - 10 * 86400, now + 2 * 86400);
    const end = integer(p, 'end', start + 1, start + 10 * 86400);
    const page = integer(p, 'page', 1, 20);
    if (p.has('future') && p.get('future') !== '1') throw new Error('Invalid future');
    return { url: 'https://graphql.anilist.co', body: { query: url.pathname === '/schedule-recent' ? RECENT_QUERY : p.get('future') === '1' ? FUTURE_QUERY : SCHEDULE_QUERY, variables: { start, end, page } }, ttl: 300 };
  }
  if (url.pathname === '/classifications') {
    const raw = p.get('ids') || '';
    if (!/^\d+(,\d+){0,49}$/.test(raw)) throw new Error('Invalid ids');
    const ids = [...new Set(raw.split(',').map(Number))].sort((a, b) => a - b);
    if (ids.some(id => !Number.isSafeInteger(id) || id < 1 || id > 2147483647)) throw new Error('Invalid ids');
    return { url: 'https://graphql.anilist.co', body: {
      query: `query($ids:[Int]){Page(perPage:50){media(id_in:$ids,type:ANIME){${MEDIA_FIELDS}}}}`, variables: { ids },
    }, ttl: 86400 };
  }
  if (url.pathname === '/media') {
    const id = integer(p, 'id', 1, 2147483647);
    return { url: 'https://graphql.anilist.co', body: { query: MEDIA_QUERY, variables: { id } }, ttl: 900 };
  }
  if (url.pathname === '/episodes' || url.pathname === '/mapping') {
    const id = integer(p, 'id', 1, 2147483647);
    return { url: `https://api.ani.zip/mappings?anilist_id=${id}`, ttl: 3600 };
  }
  if (url.pathname === '/artwork/tmdb-season') {
    const id = integer(p, 'id', 1, 2147483647);
    const season = integer(p, 'season', 0, 1000);
    return { url: `https://api.themoviedb.org/3/tv/${id}/season/${season}`, ttl: 3600, tmdb: true };
  }
  if (url.pathname === '/artwork/tvmaze-lookup') {
    const imdb = p.get('imdb');
    if (imdb !== null) {
      if (!/^tt\d{5,12}$/.test(imdb) || p.has('tvdb')) throw new Error('Invalid imdb');
      return { url: `https://api.tvmaze.com/lookup/shows?imdb=${imdb}`, ttl: 86400 };
    }
    return { url: `https://api.tvmaze.com/lookup/shows?thetvdb=${integer(p, 'tvdb', 1, 2147483647)}`, ttl: 86400 };
  }
  if (url.pathname === '/artwork/tvmaze-episodes') {
    return { url: `https://api.tvmaze.com/shows/${integer(p, 'id', 1, 2147483647)}/episodes?specials=1`, ttl: 3600, episodeList: true };
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

export async function handleRequest(request, ctx, { fetcher = fetch, cache = caches.default, tmdbToken = '' } = {}) {
  const url = new URL(request.url);
  if (request.method !== 'GET') return json({ error: 'Only GET requests are supported' }, 405, { Allow: 'GET' });
  if (url.pathname === '/health') return json({ ok: true, service: 'nuvio-native-gateway', version: '1.0.0' });
  let target;
  try { target = providerRequest(url); } catch (e) { return json({ error: e.message }, 400); }
  if (!target) return json({ error: 'Unknown route' }, 404);
  // Server-only bearer authentication: never put credentials in URLs or cache keys.
  if (target.tmdb && !tmdbToken) return json({ error: 'Artwork provider is not configured' }, 503);
  // Ignore unrecognized query parameters and canonicalize requests for a shared cache.
  const keyUrl = new URL('/cache/v2', url.origin);
  keyUrl.searchParams.set('provider', target.url);
  if (target.body) keyUrl.searchParams.set('body', JSON.stringify(target.body));
  const key = new Request(keyUrl);
  const cached = await cache.match(key);
  if (cached) {
    const headers = new Headers(cached.headers);
    headers.set('X-Nuvio-Cache', 'HIT');
    return new Response(cached.body, { status: cached.status, headers });
  }
  const staleKey = new Request(key.url + '&stale=1');
  const cooldownKey = new Request(new URL(`/cooldown/v1/${new URL(target.url).hostname}`, url.origin));
  async function unavailable(status, headers = new Headers(), recordCooldown = true) {
    const seconds = Math.max(60, Number(headers.get('Retry-After')) ||
      Math.ceil((Date.parse(headers.get('Retry-After')) - Date.now()) / 1000) || 0,
      (Number(headers.get('X-RateLimit-Reset')) || 0) - Math.floor(Date.now() / 1000));
    if ((status === 429 || status === 403 || status >= 500) && recordCooldown) {
      // Keep the provider cooldown separate from successful response cache entries.
      await cache.put(cooldownKey, json({ until: Date.now() + seconds * 1000, status }, 200,
        { 'Cache-Control': `public, max-age=${seconds}` })).catch(() => {});
    }
    const stale = await cache.match(staleKey);
    if (stale) {
      const headers = new Headers(stale.headers);
      headers.set('Cache-Control', 'public, max-age=60');
      headers.set('X-Nuvio-Stale', '1');
      headers.set('X-Nuvio-Cache', 'STALE');
      headers.set('Retry-After', String(seconds));
      return new Response(stale.body, { headers });
    }
    return json({ error: 'Provider unavailable', providerStatus: status }, status === 429 ? 429 : 503,
      { 'Retry-After': String(seconds) });
  }
  const cooldown = await cache.match(cooldownKey);
  if (cooldown) {
    const { until, status = 429 } = await cooldown.json();
    if (until > Date.now()) return unavailable(status, new Headers({ 'Retry-After': String(Math.ceil((until - Date.now()) / 1000)) }), false);
  }
  try {
    const options = {
      method: target.body ? 'POST' : 'GET',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'User-Agent': 'NuvioNative/1.0',
        ...(target.tmdb ? { Authorization: `Bearer ${tmdbToken}` } : {}) },
      body: target.body ? JSON.stringify(target.body) : undefined,
      redirect: 'manual', signal: AbortSignal.timeout(12000),
    };
    let upstream = await fetcher(target.url, options);
    // TVmaze lookup documents a redirect to the canonical show. Follow exactly
    // that fixed-host shape, never a provider-supplied arbitrary redirect.
    if (url.pathname === '/artwork/tvmaze-lookup' && [301, 302].includes(upstream.status)) {
      const location = upstream.headers.get('Location');
      await upstream.body?.cancel();
      if (!/^https:\/\/api\.tvmaze\.com\/shows\/\d+$/.test(location || '')) return unavailable(503);
      upstream = await fetcher(location, options);
    }
    if (!upstream.ok) {
      await upstream.body?.cancel();
      // A missing anime is not a provider outage. Share a short, ID-specific miss
      // across both aliases without replacing valid stale data or a global cooldown.
      if (upstream.status === 404 && ['api.ani.zip', 'api.themoviedb.org', 'api.tvmaze.com'].includes(new URL(target.url).hostname)) {
        const missing = json({ error: 'Episode metadata not found', providerStatus: 404 }, 404,
          { 'Cache-Control': 'public, max-age=60', 'X-Nuvio-Cache': 'MISS' });
        ctx.waitUntil(cache.put(key, missing.clone()).catch(() => {}));
        return missing;
      }
      console.warn(JSON.stringify({ event: 'provider_error', provider: new URL(target.url).hostname, status: upstream.status }));
      return unavailable(upstream.status, upstream.headers);
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
    const parsed = JSON.parse(new TextDecoder().decode(bytes));
    if (target.episodeList && !Array.isArray(parsed)) return unavailable(503);
    const payload = target.episodeList ? { episodes: parsed } : parsed;
    if (payload.error || payload.errors?.length) return unavailable(payload.errors?.some(error => error.status === 429) ? 429 : 503, upstream.headers);
    const response = json(payload, 200, { 'Cache-Control': `public, max-age=${target.ttl}`, 'X-Nuvio-Gateway': '1', 'X-Nuvio-Cache': 'MISS' });
    const stale = json(payload, 200, { 'Cache-Control': 'public, max-age=86400', 'X-Nuvio-Gateway': '1', 'X-Nuvio-Cache': 'MISS' });
    ctx.waitUntil(Promise.all([cache.put(key, response.clone()), cache.put(staleKey, stale)]).catch(() => {}));
    return response;
  } catch (e) {
    console.warn(JSON.stringify({ event: 'provider_request_failed', provider: new URL(target.url).hostname, name: e.name, message: String(e.message).slice(0, 160) }));
    return unavailable(503);
  }
}

export default { fetch(request, env, ctx) { return handleRequest(request, ctx, { tmdbToken: env.TMDB_READ_ACCESS_TOKEN }); } };
