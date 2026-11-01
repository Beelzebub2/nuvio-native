import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateNotification } from '../validate.mjs';

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
