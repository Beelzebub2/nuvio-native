import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { generateEnv, signJwt } from '../vps/generate-env.mjs';
// Keep the backend's existing npm test command covering the VPS approval flow.
import './vps-approval.test.mjs';

test('production configuration creates distinct private passwords and a signed public anon JWT', () => {
  const env = Object.fromEntries(generateEnv('https://starlit-api.example.com', 100).split('\n').filter(line => line && !line.startsWith('#')).map(line => {
    const i = line.indexOf('=');
    return [line.slice(0, i), line.slice(i + 1)];
  }));
  assert.equal(env.AUTH_DISABLE_SIGNUP, 'true');
  assert.equal(env.AUTH_EMAIL_AUTOCONFIRM, 'false');
  assert.equal(new Set([env.JWT_SECRET, env.STARLIT_AUTH_DB_PASSWORD, env.STARLIT_REST_DB_PASSWORD, env.SIGNUP_ENCRYPTION_KEY, env.SIGNUP_NOTIFY_TOKEN]).size, 5);
  assert.match(env.SIGNUP_ENCRYPTION_KEY, /^[A-Za-z0-9_-]{43}$/);
  assert.match(env.SIGNUP_NOTIFY_TOKEN, /^[A-Za-z0-9_-]{43}$/);
  assert.ok(env.JWT_SECRET.length >= 64);
  for (const [field, role] of [['AUTH_DATABASE_URL', 'starlit_auth_admin'], ['REST_DATABASE_URL', 'starlit_authenticator']]) {
    const uri = new URL(env[field]);
    assert.equal(uri.username, role);
    assert.equal(uri.hostname, '127.0.0.1');
    assert.equal(uri.pathname, '/starlit');
  }
  const [header, payload, signature] = env.STARLIT_SUPABASE_PUBLISHABLE_KEY.split('.');
  const claims = JSON.parse(Buffer.from(payload, 'base64url'));
  assert.equal(claims.role, 'anon');
  assert.equal(claims.aud, 'authenticated');
  assert.ok(claims.exp > claims.iat);
  assert.equal(signature, createHmac('sha256', env.JWT_SECRET).update(`${header}.${payload}`).digest('base64url'));
  assert.equal(signJwt(env.JWT_SECRET, claims), env.STARLIT_SUPABASE_PUBLISHABLE_KEY);
  assert.notEqual(generateEnv('https://starlit-api.example.com'), generateEnv('https://starlit-api.example.com'));
});

test('production configuration refuses unsafe or ambiguous public origins', () => {
  for (const origin of ['http://example.com', 'https://example.com/', 'https://example.com/path', 'https://user:pass@example.com', 'https://example.com?query=1', 'https://example.com#fragment']) {
    assert.throws(() => generateEnv(origin));
  }
});
