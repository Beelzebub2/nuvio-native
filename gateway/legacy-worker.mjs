// Compatibility for installed releases. All provider work and caching live on the VPS.
export default {
  async fetch(request) {
    if (request.method !== 'GET') return new Response(null, { status: 405, headers: { Allow: 'GET' } });
    const original = new URL(request.url);
    // Encoded separators/dot segments must not escape Nginx's metadata prefix.
    if (!/^\/(?:[a-z-]+\/)*[a-z-]*$/.test(original.pathname)) return new Response(null, { status: 404 });
    const target = new URL('https://starlit-api.rrmtools.uk');
    target.pathname = `/gateway${original.pathname}`;
    target.search = original.search;
    try {
      return await fetch(target, { method: 'GET', redirect: 'manual', signal: AbortSignal.timeout(15000) });
    } catch {
      return Response.json({ error: 'Gateway unavailable' }, { status: 503, headers: { 'Cache-Control': 'no-store', 'Retry-After': '60' } });
    }
  },
};
