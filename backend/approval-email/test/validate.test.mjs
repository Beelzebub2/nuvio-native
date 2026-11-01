import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import { validateNotification, verifyNotificationSignature } from '../validate.mjs';

const origin = 'https://starlit-api.rrmtools.uk';
const good = () => ({
  email: 'test@example.com',
  reviewUrl: `${origin}/admin/signups/review?token=${'a'.repeat(43)}`,
  requestedAt: Date.now(),
});

test('accepts one owner-review link without exposing a chosen email recipient', () => {
  const payload = good();
  assert.deepEqual(validateNotification(payload, origin), { ...payload, country: null, client: 'Starlit VPS' });
});

test('rejects review links pointing elsewhere or containing extra arguments', () => {
  for (const reviewUrl of [
    `https://attacker.example/admin/signups/review?token=${'a'.repeat(43)}`,
    `${origin}/admin/signups/review?token=${'a'.repeat(43)}&next=https://attacker.example`,
    `${origin}/admin/signups/review?token=short`,
    `${origin}/admin/signups/review?token=${'a'.repeat(43)}#fragment`,
    `${origin}/admin/users?token=${'a'.repeat(43)}`,
  ]) assert.equal(validateNotification({ ...good(), reviewUrl }, origin), null);
});

test('rejects invalid signup metadata and stale webhook deliveries', () => {
  assert.equal(validateNotification({ ...good(), requestedAt: Date.now() - 8 * 86400e3 }, origin), null);
  assert.equal(validateNotification({ ...good(), email: 'invalid\nfoo@example.com' }, origin), null);
  assert.equal(validateNotification({ ...good(), email: 'invalid' }, origin), null);
});

test('accepts only VPS Ed25519 signatures for the exact fresh JSON payload', async () => {
  const { publicKey, privateKey } = generateKeyPairSync('ed25519');
  const verifyKey = publicKey.export({ format: 'der', type: 'spki' }).toString('base64url');
  const timestamp = String(Date.now());
  const raw = JSON.stringify(good());
  const signature = sign(null, Buffer.from(`${timestamp}.${raw}`), privateKey).toString('base64url');
  assert.equal(await verifyNotificationSignature(raw, timestamp, signature, verifyKey), true);
  assert.equal(await verifyNotificationSignature(raw + ' ', timestamp, signature, verifyKey), false);
  assert.equal(await verifyNotificationSignature(raw, String(Number(timestamp) - 6 * 60 * 1000), signature, verifyKey), false);
  assert.equal(await verifyNotificationSignature(raw, timestamp, signature, 'invalid'), false);
  assert.equal(await verifyNotificationSignature(raw, timestamp, '', verifyKey), false);
  const other = generateKeyPairSync('ed25519').publicKey.export({ format: 'der', type: 'spki' }).toString('base64url');
  assert.equal(await verifyNotificationSignature(raw, timestamp, signature, other), false);
});
