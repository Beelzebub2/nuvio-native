import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';

export function discoveryDocument(env = process.env) {
  const backend = new URL(env.STARLIT_SUPABASE_URL);
  if (!['http:', 'https:'].includes(backend.protocol) || backend.username || backend.password || backend.search || backend.hash || backend.pathname !== '/') {
    throw new Error('STARLIT_SUPABASE_URL must be the HTTP(S) root of your own Supabase API');
  }
  const key = env.STARLIT_SUPABASE_PUBLISHABLE_KEY?.trim();
  if (!key || key.startsWith('sb_secret_')) throw new Error('A public Supabase publishable/anon key is required');
  // Legacy local CLI keys are JWTs. Never publish a service_role JWT.
  if (!key.startsWith('sb_publishable_')) {
    let claims;
    try { claims = JSON.parse(Buffer.from(key.split('.')[1], 'base64url').toString()); } catch { /* invalid key below */ }
    if (claims?.role !== 'anon') throw new Error('Only a publishable key or anon JWT can be advertised');
  }
  return {
    version: 1,
    service: 'nuvio', // Existing client protocol discriminator; this is Starlit's independent backend.
    self_hosted: true,
    backend_url: backend.origin,
    publishable_key: key,
    capabilities: { email_password_auth: true, tv_login: false },
  };
}

export function createDiscoveryServer(env = process.env) {
  const body = JSON.stringify(discoveryDocument(env));
  return createServer((req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Access-Control-Allow-Origin', '*');
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, { Allow: 'GET, HEAD' }).end();
    } else if (req.url === '/.well-known/nuvio') {
      res.writeHead(200, { 'Cache-Control': 'public, max-age=60' }).end(req.method === 'HEAD' ? undefined : body);
    } else if (req.url === '/healthz') {
      res.writeHead(200).end(req.method === 'HEAD' ? undefined : '{"status":"ok","service":"starlit-discovery"}');
    } else {
      res.writeHead(404).end('{"error":"not_found"}');
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  createDiscoveryServer().listen(Number(process.env.PORT || 8787), process.env.HOST || '127.0.0.1', () => {
    console.log(`Starlit discovery listening on ${process.env.HOST || '127.0.0.1'}:${process.env.PORT || 8787}`);
  });
}
