// Sign-up request rules in AccountsDO, run in plain Node against an in-memory SQLite database with a
// controllable clock, so hour and week windows can be tested. No wrangler server is needed:
//   node --test test/accounts.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import module from 'node:module';
import { DatabaseSync } from 'node:sqlite';

if (!module.registerHooks) throw new Error('These tests need Node.js 22.15 or newer');
// accounts.mjs extends the Workers runtime's DurableObject; a minimal stand-in is enough here.
const durableObjectStub = `data:text/javascript,${encodeURIComponent('export class DurableObject { constructor(ctx, env) { this.ctx = ctx; this.env = env; } }')}`;
module.registerHooks({
  resolve: (specifier, context, next) => (specifier === 'cloudflare:workers' ? { url: durableObjectStub, shortCircuit: true } : next(specifier, context)),
});
const { AccountsDO, REQUEST_LIFETIME_MS } = await import('../src/accounts.mjs');

const ITERATIONS = 1_000;
const HOUR = 60 * 60 * 1000;

/** An AccountsDO over a fresh in-memory database, with `Date.now()` under the test's control. */
function setup(t) {
  const db = new DatabaseSync(':memory:');
  const sql = { exec: (query, ...bindings) => { const rows = db.prepare(query).all(...bindings); return { toArray: () => rows }; } };
  const transactionSync = body => {
    db.exec('BEGIN');
    try {
      const result = body();
      db.exec('COMMIT');
      return result;
    } catch (error) {
      db.exec('ROLLBACK');
      throw error;
    }
  };
  const clock = { now: Date.UTC(2026, 0, 1), advance(ms) { this.now += ms; } };
  t.mock.method(Date, 'now', () => clock.now);
  const accounts = new AccountsDO({ storage: { sql, transactionSync }, blockConcurrencyWhile: body => body() }, {});
  const request = (email, password, extra = {}) => accounts.signup({
    email, password, metadata: {}, iterations: ITERATIONS, mode: 'approval', country: null, client: null, ...extra,
  });
  const login = (email, password) => accounts.passwordLogin({ email, password, iterations: ITERATIONS });
  const row = email => {
    const found = db.prepare('SELECT * FROM signup_requests WHERE email = ?').get(email);
    return found ? { ...found } : null;
  };
  return { accounts, clock, request, login, row };
}

test('a repeat request with a different password changes nothing', async t => {
  const { accounts, clock, request, login, row } = setup(t);
  const email = 'friend@example.test';
  const first = await request(email, 'Owner-password-1');
  assert.ok(first.reviewToken, 'The first request emails the owner');
  const before = row(email);

  clock.advance(2 * HOUR); // Past the re-notify window, when a matching repeat would get a new link.
  const other = await request(email, 'Attacker-password-2', { country: 'XX', client: 'other' });
  assert.equal(other.reviewToken, undefined, 'No owner email, so the emailed link is not replaced');
  assert.equal(other.pending.id, before.id, 'The reply is the existing pending request');
  assert.deepEqual(row(email), before, 'The pending request is untouched');

  await accounts.notificationFailed(email);
  assert.equal((await request(email, 'Attacker-password-2')).reviewToken, undefined, 'Not even after a failed owner email');

  assert.ok(await accounts.decide(first.reviewToken, true), 'The first emailed link still approves');
  assert.ok((await login(email, 'Owner-password-1')).sessionId);
  assert.equal((await login(email, 'Attacker-password-2')).error.error_code, 'invalid_credentials');
});

test('the same person re-submitting refreshes the request, and gets a fresh link after an hour', async t => {
  const { accounts, clock, request, login, row } = setup(t);
  const email = 'returning@example.test';
  const password = 'Owner-password-1';
  const first = await request(email, password);
  assert.ok(first.reviewToken);
  const before = row(email);

  clock.advance(10 * 60 * 1000);
  const soon = await request(email, password, { country: 'PT', client: 'Starlit/2' });
  assert.equal(soon.reviewToken, undefined, 'At most one owner email an hour');
  assert.deepEqual(row(email), { ...before, requested_at: clock.now, country: 'PT', client: 'Starlit/2' },
    'Only the request time, country and client change');

  clock.advance(2 * HOUR);
  const later = await request(email, password);
  assert.ok(later.reviewToken, 'The owner is emailed again');
  const after = row(email);
  assert.equal(after.password_hash, before.password_hash, 'The password hash is never replaced');
  assert.equal(after.id, before.id);
  assert.notEqual(after.review_token_hash, before.review_token_hash);
  assert.equal(await accounts.reviewRequest(first.reviewToken), null, 'The older link stops working');
  assert.ok(await accounts.decide(later.reviewToken, true));
  assert.ok((await login(email, password)).sessionId);
});

test('an expired request is replaced, with a new link that retires the old one', async t => {
  const { accounts, clock, request, login, row } = setup(t);
  const email = 'lapsed@example.test';
  const first = await request(email, 'Old-password-1');
  const before = row(email);

  clock.advance(REQUEST_LIFETIME_MS + 1);
  const renewed = await request(email, 'New-password-2');
  assert.ok(renewed.reviewToken, 'The owner is emailed a new link');
  const after = row(email);
  assert.notEqual(after.id, before.id, 'A new request, not an edit of the old one');
  assert.notEqual(after.review_token_hash, before.review_token_hash);
  assert.equal(after.requested_at, clock.now);
  assert.equal(await accounts.decide(first.reviewToken, true), null, 'The old link cannot approve the new request');
  assert.ok(await accounts.decide(renewed.reviewToken, true));
  assert.ok((await login(email, 'New-password-2')).sessionId);
  assert.equal((await login(email, 'Old-password-1')).error.error_code, 'invalid_credentials');
});

test('two simultaneous first requests store one password and email the owner once', async t => {
  const { accounts, request, login } = setup(t);
  const email = 'race@example.test';
  const passwords = ['First-password-1', 'Second-password-2'];
  const results = await Promise.all(passwords.map(password => request(email, password)));
  assert.equal(results.filter(result => result.reviewToken).length, 1, 'Only one review link exists');
  const winner = results.findIndex(result => result.reviewToken);
  assert.ok(await accounts.decide(results[winner].reviewToken, true));
  assert.ok((await login(email, passwords[winner])).sessionId, 'The account has the password of the emailed request');
  assert.equal((await login(email, passwords[1 - winner])).error.error_code, 'invalid_credentials');
});

test('wrong-password repeats share the sign-in lockout, so they cannot test guesses faster', async t => {
  const { clock, request, login } = setup(t);
  const email = 'guessed@example.test';
  const password = 'Owner-password-1';
  await request(email, password);
  for (let guess = 0; guess < 10; guess++) {
    assert.ok((await request(email, `Wrong-guess-${guess}-pass`)).pending, 'A wrong repeat looks like any other pending reply');
  }
  assert.equal((await request(email, password)).error?.error_code, 'over_request_rate_limit', 'Even the right password waits out the lockout');
  assert.equal((await login(email, password)).error.error_code, 'over_request_rate_limit', 'Sign-in shares the same budget');
  clock.advance(15 * 60 * 1000);
  assert.ok((await request(email, password)).pending, 'The lockout ends after 15 minutes');
});
