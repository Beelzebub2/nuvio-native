import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createDiscoveryServer, discoveryDocument } from '../discovery.mjs';

const env = { STARLIT_SUPABASE_URL: 'http://127.0.0.1:54321', STARLIT_SUPABASE_PUBLISHABLE_KEY: 'sb_publishable_test' };
test('discovery implements the mobile protocol without advertising unsupported TV login', async t => {
  const server = createDiscoveryServer(env);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  const base = `http://127.0.0.1:${server.address().port}`;
  const response = await fetch(`${base}/.well-known/nuvio`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    version: 1, service: 'nuvio', self_hosted: true,
    backend_url: env.STARLIT_SUPABASE_URL, publishable_key: env.STARLIT_SUPABASE_PUBLISHABLE_KEY,
    capabilities: { email_password_auth: true, tv_login: false },
  });
  assert.equal((await fetch(base + '/.well-known/nuvio', { method: 'POST' })).status, 405);
  assert.equal((await fetch(base + '/missing')).status, 404);
});
test('discovery refuses secret keys and non-root API URLs', () => {
  for (const key of ['sb_secret_test', '', 'invalid', `a.${Buffer.from('{"role":"service_role"}').toString('base64url')}.c`]) {
    assert.throws(() => discoveryDocument({ ...env, STARLIT_SUPABASE_PUBLISHABLE_KEY: key }));
  }
  for (const url of ['ftp://localhost', 'https://user:pass@example.com', 'https://example.com/rest/v1', 'https://example.com/?token=x']) {
    assert.throws(() => discoveryDocument({ ...env, STARLIT_SUPABASE_URL: url }));
  }
  assert.equal(discoveryDocument({ ...env, STARLIT_SUPABASE_PUBLISHABLE_KEY: `a.${Buffer.from('{"role":"anon"}').toString('base64url')}.c` }).service, 'nuvio');
});
