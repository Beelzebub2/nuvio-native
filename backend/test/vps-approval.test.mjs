// End-to-end, localhost-only approval tests. Fake HTTP servers replace GoTrue's
// privileged admin API and the independent email notifier. No live secrets or mail.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createPublicKey, randomBytes, randomUUID, verify } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { createSignupServer, loadSignupConfig } from '../vps/signup-service.mjs';
import { generateEnv } from '../vps/generate-env.mjs';
import { SIGNUP_NOTIFY_URL, notifierUrlAppend } from '../vps/add-notify-url.mjs';
import {
  generateSigningPrivateKey, importSigningPrivateKey, publicSigningKey,
  signNotification, signingKeyAppend, signingKeyFromEnvironmentFile,
} from '../vps/signing-key.mjs';
import { validateNotification, verifyNotificationSignature } from '../approval-email/validate.mjs';

const HOUR = 3_600_000;
const DAY = 86_400_000;
const EMAIL = 'starlit.tester@example.test';
const PASSWORD = 'Unpredictable-Owner-Password-123';

const listen = server => new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const close = server => new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
async function bodyOf(req) {
  let body = '';
  for await (const chunk of req) body += chunk.toString('utf8');
  return body;
}

async function fixture() {
  const directory = mkdtempSync(join(tmpdir(), 'starlit-approval-test-'));
  const signingPrivate = generateSigningPrivateKey();
  const signingPublic = createPublicKey(importSigningPrivateKey(signingPrivate));
  const fakeAdmin = { calls: [], status: 201, delayMs: 0 };
  const fakeNotifier = { calls: [], status: 202, holdNext: false, resume: null, pauseReached: null };
  const auth = createServer(async (req, res) => {
    const body = JSON.parse(await bodyOf(req));
    const token = (req.headers.authorization || '').split(' ')[1];
    const claims = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
    fakeAdmin.calls.push({ method: req.method, url: req.url, body, claims });
    if (fakeAdmin.delayMs) await new Promise(resolve => setTimeout(resolve, fakeAdmin.delayMs));
    res.writeHead(fakeAdmin.status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(fakeAdmin.status === 201 ? { id: randomUUID(), email: body.email } : { msg: 'fake admin failure' }));
  });
  const notifier = createServer(async (req, res) => {
    const rawBody = await bodyOf(req);
    let body;
    try { body = JSON.parse(rawBody); } catch { body = null; }
    const time = req.headers['x-starlit-time'];
    const encodedSignature = req.headers['x-starlit-signature'];
    const signature = typeof encodedSignature === 'string' && /^[A-Za-z0-9_-]{86}$/.test(encodedSignature)
      ? Buffer.from(encodedSignature, 'base64url') : null;
    const validSignature = typeof time === 'string' && /^\d{13}$/.test(time) &&
      Math.abs(Date.now() - Number(time)) <= 5 * 60_000 && signature?.length === 64 &&
      verify(null, Buffer.from(`${time}.${rawBody}`, 'utf8'), signingPublic, signature);
    fakeNotifier.calls.push({ url: req.url, method: req.method, auth: req.headers.authorization, body,
      rawBody, time, encodedSignature, validSignature });
    if (fakeNotifier.holdNext) {
      fakeNotifier.holdNext = false;
      await new Promise(resolve => {
        fakeNotifier.resume = resolve;
        fakeNotifier.pauseReached?.();
      });
    }
    const valid = validateNotification(body, 'https://starlit-api.rrmtools.uk');
    const status = valid && validSignature && !req.headers.authorization ? fakeNotifier.status : 401;
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ accepted: status === 202 }));
  });
  await Promise.all([listen(auth), listen(notifier)]);
  let milliseconds = Date.now();
  const startedAt = milliseconds;
  const key = randomBytes(32).toString('base64url');
  const env = {
    AUTH_DISABLE_SIGNUP: 'true', AUTH_EMAIL_AUTOCONFIRM: 'false',
    JWT_SECRET: randomBytes(48).toString('base64url'),
    SIGNUP_ENCRYPTION_KEY: key,
    SIGNUP_NOTIFY_URL: `http://127.0.0.1:${notifier.address().port}/notify`,
    SIGNUP_SIGNING_KEY: signingPrivate,
    STARLIT_SUPABASE_URL: 'https://starlit-api.rrmtools.uk',
    SIGNUP_ADMIN_URL: `http://127.0.0.1:${auth.address().port}`,
    SIGNUP_DB_PATH: join(directory, 'requests.sqlite'),
    SIGNUP_PORT: '0',
  };
  const config = loadSignupConfig(env);
  const startApp = async () => {
    const { server, store } = createSignupServer(config, { now: () => milliseconds, logger: { warn() {}, error() {} } });
    await listen(server);
    return { server, store, base: `http://127.0.0.1:${server.address().port}` };
  };
  let app = await startApp();
  return {
    env, config, directory, fakeAdmin, fakeNotifier, startedAt,
    notifierUrl: `http://127.0.0.1:${notifier.address().port}/notify`,
    base: () => app.base,
    advance: amount => { milliseconds += amount; },
    db: () => new DatabaseSync(env.SIGNUP_DB_PATH),
    claimWithoutAdmin: token => app.store.decide(token, true),
    restart: async () => { await close(app.server); app.store.close(); app = await startApp(); },
    stop: async () => {
      fakeNotifier.resume?.();
      await close(app.server); app.store.close();
      await Promise.all([close(auth), close(notifier)]);
      rmSync(directory, { recursive: true, force: true });
    },
  };
}

async function http(f, path, { method = 'GET', json, form, headers = {} } = {}) {
  const response = await fetch(f.base() + path, {
    method, redirect: 'manual',
    headers: { ...(json ? { 'Content-Type': 'application/json' } : {}),
      ...(form ? { 'Content-Type': 'application/x-www-form-urlencoded' } : {}), ...headers },
    ...(json ? { body: JSON.stringify(json) } : {}),
    ...(form ? { body: new URLSearchParams(form) } : {}),
  });
  const raw = await response.text();
  let value;
  try { value = JSON.parse(raw); } catch { value = raw; }
  return { status: response.status, value, headers: response.headers };
}

const signup = (f, email = EMAIL, password = PASSWORD, headers) => http(f, '/auth/v1/signup', {
  method: 'POST', json: { email, password }, headers,
});
const reviewToken = f => new URL(f.fakeNotifier.calls.at(-1).body.reviewUrl).searchParams.get('token');
const review = (f, token) => http(f, `/admin/signups/review?token=${encodeURIComponent(token)}`);
const decide = (f, token, decision, headers) => http(f, '/admin/signups/review', {
  method: 'POST', form: { token, decision }, headers,
});

test('startup requires independent encryption, notifier and closed GoTrue registration', () => {
  const raw = {
    SIGNUP_ENCRYPTION_KEY: randomBytes(32).toString('base64url'),
    SIGNUP_NOTIFY_URL: 'https://notify.example.test/notify',
    SIGNUP_SIGNING_KEY: generateSigningPrivateKey(),
    STARLIT_SUPABASE_URL: 'https://starlit-api.rrmtools.uk',
    JWT_SECRET: randomBytes(48).toString('base64url'),
    AUTH_DISABLE_SIGNUP: 'true', AUTH_EMAIL_AUTOCONFIRM: 'false',
    SIGNUP_DB_PATH: '/tmp/account-requests.sqlite',
  };
  assert.equal(loadSignupConfig(raw).publicOrigin, raw.STARLIT_SUPABASE_URL);
  assert.throws(() => loadSignupConfig({ ...raw, AUTH_DISABLE_SIGNUP: 'false' }), /public signup/);
  assert.throws(() => loadSignupConfig({ ...raw, AUTH_EMAIL_AUTOCONFIRM: 'true' }), /autoconfirm/);
  assert.throws(() => loadSignupConfig({ ...raw, SIGNUP_ENCRYPTION_KEY: raw.JWT_SECRET }), /SIGNUP_ENCRYPTION_KEY/);
  assert.throws(() => loadSignupConfig({ ...raw, SIGNUP_SIGNING_KEY: '' }), /SIGNUP_SIGNING_KEY/);
  assert.throws(() => loadSignupConfig({ ...raw, SIGNUP_SIGNING_KEY: randomBytes(32).toString('base64url') }), /PKCS8/);
  assert.equal(loadSignupConfig(raw).signingKey.asymmetricKeyType, 'ed25519');
  assert.throws(() => loadSignupConfig({ ...raw, SIGNUP_ADMIN_URL: 'https://admin.example.test' }), /loopback/);
  assert.match(generateEnv('https://starlit-api.rrmtools.uk'), /^SIGNUP_ENCRYPTION_KEY=[A-Za-z0-9_-]{43}$/m);
});

test('Ed25519 signing-key helper appends only private key, never replaces existing secrets', async () => {
  const prior = 'JWT_SECRET=existing-secret\nAUTH_DISABLE_SIGNUP=true\n';
  const privateKey = generateSigningPrivateKey();
  const lines = signingKeyAppend(prior, privateKey);
  assert.equal(lines, `SIGNUP_SIGNING_KEY=${privateKey}\n`);
  assert.equal(prior + lines, `JWT_SECRET=existing-secret\nAUTH_DISABLE_SIGNUP=true\n${lines}`);
  assert.equal(signingKeyFromEnvironmentFile(prior + lines), privateKey);
  assert.equal(signingKeyAppend('JWT_SECRET=old', privateKey).startsWith('\nSIGNUP_SIGNING_KEY='), true);
  assert.throws(() => signingKeyAppend(`${prior}SIGNUP_SIGNING_KEY=\n`, privateKey), /already exists/);
  assert.throws(() => signingKeyAppend(`${prior}export SIGNUP_SIGNING_KEY=old\n`, privateKey), /already exists/);
  assert.throws(() => signingKeyAppend(prior, 'too-short'), /SIGNUP_SIGNING_KEY/);
  assert.throws(() => signingKeyAppend(prior, privateKey + '\nINJECT=1'), /SIGNUP_SIGNING_KEY/);
  assert.throws(() => signingKeyFromEnvironmentFile('SIGNUP_SIGNING_KEY=bad\nSIGNUP_SIGNING_KEY=bad2\n'), /exactly one/);
  const publicKey = publicSigningKey(privateKey);
  assert.ok(!publicKey.includes(privateKey));
  assert.equal(createPublicKey(importSigningPrivateKey(privateKey)).export({ format: 'der', type: 'spki' }).toString('base64url'), publicKey);
  const publicObjectFromSpki = createPublicKey({ key: Buffer.from(publicKey, 'base64url'), format: 'der', type: 'spki' });
  assert.equal(publicObjectFromSpki.asymmetricKeyType, 'ed25519');
  assert.notEqual(publicSigningKey(generateSigningPrivateKey()), publicKey);
  const payload = JSON.stringify({ email: EMAIL, reviewUrl: 'https://starlit-api.rrmtools.uk/admin/signups/review?token=abc', requestedAt: Date.now() });
  const timestamp = Date.now().toString();
  const signature = signNotification(importSigningPrivateKey(privateKey), timestamp, payload);
  assert.match(signature, /^[A-Za-z0-9_-]{86}$/);
  const publicObject = createPublicKey(importSigningPrivateKey(privateKey));
  assert.ok(verify(null, Buffer.from(`${timestamp}.${payload}`, 'utf8'), publicObject, Buffer.from(signature, 'base64url')));
  assert.ok(verify(null, Buffer.from(`${timestamp}.${payload}`, 'utf8'), publicObjectFromSpki, Buffer.from(signature, 'base64url')));
  assert.equal(await verifyNotificationSignature(payload, timestamp, signature, publicKey), true,
    'Cloudflare Worker verifier accepts the exact Node Ed25519 signature');
  assert.equal(await verifyNotificationSignature(`${payload} `, timestamp, signature, publicKey), false,
    'Cloudflare Worker rejects a changed raw body');
  assert.equal(verify(null, Buffer.from(`${timestamp}.${payload} `, 'utf8'), publicObject, Buffer.from(signature, 'base64url')), false);
  assert.equal(verify(null, Buffer.from(`${Number(timestamp) - 1000}.${payload}`, 'utf8'), publicObject, Buffer.from(signature, 'base64url')), false);
});

test('notifier URL upgrade helper is idempotent and never overwrites a conflicting value', () => {
  const privateFile = 'JWT_SECRET=do-not-change\nSIGNUP_SIGNING_KEY=do-not-change\nSIGNUP_ENCRYPTION_KEY=do-not-change\n';
  const addition = notifierUrlAppend(privateFile);
  assert.equal(addition, `SIGNUP_NOTIFY_URL=${SIGNUP_NOTIFY_URL}\n`);
  assert.equal(notifierUrlAppend(privateFile + addition), '');
  assert.equal(notifierUrlAppend('JWT_SECRET=keep-this').startsWith('\nSIGNUP_NOTIFY_URL='), true);
  assert.throws(() => notifierUrlAppend(privateFile + 'SIGNUP_NOTIFY_URL=https://example.test/notify\n'), /refusing to replace/);
  assert.throws(() => notifierUrlAppend(privateFile + addition + addition), /Duplicate/);
  assert.ok((privateFile + addition).startsWith(privateFile), 'previous secrets remain byte-identical');
});

test('fake notifier accepts only fresh Ed25519 signatures of exact transmitted JSON with no bearer', async () => {
  const f = await fixture();
  try {
    const reviewUrl = `https://starlit-api.rrmtools.uk/admin/signups/review?token=${randomBytes(32).toString('base64url')}`;
    const rawBody = JSON.stringify({ email: EMAIL, reviewUrl, requestedAt: Date.now() });
    const timestamp = Date.now().toString();
    const key = importSigningPrivateKey(f.env.SIGNUP_SIGNING_KEY);
    const signature = signNotification(key, timestamp, rawBody);
    async function send(headers, body = rawBody) {
      const response = await fetch(f.notifierUrl, {
        method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body,
      });
      return response.status;
    }
    const signedHeaders = { 'X-Starlit-Time': timestamp, 'X-Starlit-Signature': signature };
    assert.equal(await send({}), 401, 'unsigned messages fail');
    assert.equal(await send({ Authorization: 'Bearer obsolete-shared-token' }), 401, 'bearer is never sufficient');
    assert.equal(await send(signedHeaders, `${rawBody} `), 401, 'a valid JSON whitespace change must invalidate signature');
    assert.equal(await send({ ...signedHeaders, 'X-Starlit-Time': String(Number(timestamp) + 1) }), 401, 'altered timestamp fails');
    assert.equal(await send({ ...signedHeaders, 'X-Starlit-Signature': signNotification(importSigningPrivateKey(generateSigningPrivateKey()), timestamp, rawBody) }), 401, 'wrong Ed25519 key fails');
    const oldTimestamp = String(Number(timestamp) - 6 * 60_000);
    assert.equal(await send({ 'X-Starlit-Time': oldTimestamp,
      'X-Starlit-Signature': signNotification(key, oldTimestamp, rawBody) }), 401, 'an otherwise valid signature older than 5 minutes fails');
    assert.equal(await send({ ...signedHeaders, Authorization: 'Bearer obsolete-shared-token' }), 401, 'bearer is not accepted even alongside signature');
    assert.equal(await send(signedHeaders), 202, 'fresh signed exact body accepted');
    assert.equal(f.fakeNotifier.calls.at(-1).validSignature, true);
  } finally { await f.stop(); }
});

test('request stays encrypted and pending until one-use explicit owner approval', async () => {
  const f = await fixture();
  try {
    const first = await signup(f);
    assert.equal(first.status, 200);
    assert.equal(first.value.email, EMAIL);
    assert.equal(first.value.access_token, undefined);
    assert.equal(first.value.refresh_token, undefined);
    assert.deepEqual(first.value.identities, []);
    assert.equal(f.fakeAdmin.calls.length, 0, 'registration must never create GoTrue user');
    assert.equal(f.fakeNotifier.calls.length, 1);
    const sent = f.fakeNotifier.calls[0];
    assert.equal(sent.method, 'POST');
    assert.equal(sent.url, '/notify');
    assert.equal(sent.auth, undefined, 'no shared bearer token must be transmitted');
    assert.equal(sent.validSignature, true, 'fake Worker verifies Ed25519 over exact UTF-8 raw body');
    assert.match(sent.time, /^\d{13}$/);
    assert.match(sent.encodedSignature, /^[A-Za-z0-9_-]{86}$/);
    assert.equal(sent.body.email, EMAIL);
    assert.equal(sent.body.requestedAt, f.startedAt);
    assert.equal(Number.isSafeInteger(sent.body.requestedAt), true);
    assert.ok(validateNotification(sent.body, f.config.publicOrigin), 'independent notifier must accept the exact payload');
    assert.match(sent.body.reviewUrl, /^https:\/\/starlit-api\.rrmtools\.uk\/admin\/signups\/review\?token=[A-Za-z0-9_-]{43}$/);
    const token = reviewToken(f);
    const database = f.db();
    const row = database.prepare('SELECT * FROM signup_requests WHERE email = ?').get(EMAIL);
    assert.ok(row.password_enc);
    assert.equal(row.password_enc.includes(PASSWORD), false);
    assert.equal(row.token_hash.includes(token), false);
    assert.equal(row.password_verifier.includes(PASSWORD), false);
    database.close();
    const page = await review(f, token);
    assert.equal(page.status, 200);
    assert.match(page.value, /Approve Starlit account/);
    assert.match(page.value, /name="decision" value="approve"/);
    assert.equal(page.headers.get('Referrer-Policy'), 'no-referrer');
    assert.equal(page.headers.get('Cache-Control'), 'no-store, max-age=0');
    assert.equal(f.fakeAdmin.calls.length, 0, 'viewing link must not approve');
    const approved = await decide(f, token, 'approve');
    assert.equal(approved.status, 200);
    assert.match(approved.value, /Account approved/);
    assert.equal(f.fakeAdmin.calls.length, 1);
    const call = f.fakeAdmin.calls[0];
    assert.equal(call.url, '/admin/users');
    assert.equal(call.method, 'POST');
    assert.equal(call.claims.role, 'service_role');
    assert.deepEqual(call.body, { email: EMAIL, password: PASSWORD, email_confirm: true });
    assert.equal((await decide(f, token, 'approve')).status, 404);
    assert.equal((await review(f, token)).status, 404);
    assert.equal(f.fakeAdmin.calls.length, 1);
    const verify = f.db();
    assert.equal(verify.prepare('SELECT COUNT(*) AS n FROM signup_requests').get().n, 0);
    verify.close();
  } finally { await f.stop(); }
});

test('wrong-password repeat cannot replace credentials or trigger mail; repeated correct request is throttled', async () => {
  const f = await fixture();
  try {
    const first = await signup(f);
    const token = reviewToken(f);
    const wrong = await signup(f, EMAIL, 'Changed-password-that-attacker-picked');
    assert.equal(wrong.status, 200);
    assert.equal(wrong.value.id, first.value.id);
    assert.equal(f.fakeNotifier.calls.length, 1);
    assert.equal((await signup(f)).value.id, first.value.id);
    assert.equal(f.fakeNotifier.calls.length, 1);
    assert.equal((await review(f, token)).status, 200);
    f.advance(HOUR + 1);
    assert.equal((await signup(f)).status, 200);
    assert.equal(f.fakeNotifier.calls.length, 2, 'only the owner of the original password can refresh link');
    assert.equal((await review(f, token)).status, 404, 'prior review link invalidated');
    const newer = reviewToken(f);
    assert.notEqual(token, newer);
    assert.match((await decide(f, newer, 'approve')).value, /Account approved/);
    assert.equal(f.fakeAdmin.calls[0].body.password, PASSWORD);
  } finally { await f.stop(); }
});

test('pending-password guesses lock out after ten attempts without invalidating the owner link', async () => {
  const f = await fixture();
  try {
    await signup(f);
    const token = reviewToken(f);
    for (let i = 0; i < 10; i++) {
      assert.equal((await signup(f, EMAIL, `Different-password-${i}-12345`)).status, 200);
    }
    assert.equal((await signup(f, EMAIL, 'Different-password-number-eleven')).status, 429);
    assert.equal(f.fakeNotifier.calls.length, 1);
    assert.equal((await review(f, token)).status, 200);
    f.advance(15 * 60_000 + 1);
    assert.equal((await signup(f)).status, 200, 'original password remains usable after lockout window');
  } finally { await f.stop(); }
});

test('failed notification leaves pending durable and a same-password retry can send again', async () => {
  const f = await fixture();
  try {
    f.fakeNotifier.status = 502;
    const first = await signup(f);
    assert.equal(first.status, 503);
    assert.equal(first.value.code, 503);
    assert.equal(first.value.error_code, 'signup_notification_failed');
    assert.match(first.value.msg, /saved.*owner.*notified.*same email and password after 30 seconds/i);
    assert.equal(first.headers.get('X-Sb-Error-Code'), 'signup_notification_failed');
    assert.equal(first.headers.get('Retry-After'), '30');
    assert.equal(f.fakeNotifier.calls.length, 1);
    const database = f.db();
    const row = database.prepare('SELECT * FROM signup_requests WHERE email = ?').get(EMAIL);
    assert.equal(row.notification_state, 'failed');
    assert.equal(row.notified_at, null);
    assert.ok(row.password_enc && !row.password_enc.includes(PASSWORD));
    assert.equal(row.next_token_hash, null);
    database.close();
    const beforeCooldown = await signup(f);
    assert.equal(beforeCooldown.status, 503, 'cannot claim email sent during retry cooldown');
    assert.equal(beforeCooldown.headers.get('Retry-After'), '30');
    assert.equal((await signup(f, EMAIL, 'Wrong-password-123456789')).status, 503);
    assert.equal(f.fakeNotifier.calls.length, 1, 'wrong password never retries notification');
    await f.restart();
    assert.equal((await signup(f)).status, 503, 'restart preserves retry cooldown and pending request');
    f.fakeNotifier.status = 202;
    f.advance(30_001);
    const second = await signup(f);
    assert.equal(second.status, 200);
    assert.equal(second.value.email, EMAIL);
    assert.equal(second.value.access_token, undefined);
    assert.equal(f.fakeNotifier.calls.length, 2);
    await f.restart();
    assert.equal((await review(f, reviewToken(f))).status, 200, 'SQLite request survives service restart');
    assert.equal(f.fakeAdmin.calls.length, 0);
  } finally { await f.stop(); }
});

test('failed reminder preserves the old emailed token throughout delivery and after failure', async () => {
  const f = await fixture();
  try {
    assert.equal((await signup(f)).status, 200);
    const originalToken = reviewToken(f);
    const original = f.db();
    const originalHash = original.prepare('SELECT token_hash FROM signup_requests WHERE email = ?').get(EMAIL).token_hash;
    original.close();

    f.advance(HOUR + 1);
    f.fakeNotifier.status = 502;
    f.fakeNotifier.holdNext = true;
    const inFlight = new Promise(resolve => { f.fakeNotifier.pauseReached = resolve; });
    const reminderRequest = signup(f);
    await inFlight;
    const candidateToken = reviewToken(f);
    assert.notEqual(candidateToken, originalToken);
    assert.equal((await review(f, originalToken)).status, 200, 'old review link works while new email is in flight');
    assert.equal((await review(f, candidateToken)).status, 404, 'new link is unusable until delivery succeeds');
    const sending = f.db();
    assert.equal(sending.prepare('SELECT token_hash FROM signup_requests WHERE email = ?').get(EMAIL).token_hash, originalHash);
    assert.equal(sending.prepare('SELECT notification_state FROM signup_requests WHERE email = ?').get(EMAIL).notification_state, 'sending');
    sending.close();
    f.fakeNotifier.resume();
    const failed = await reminderRequest;
    assert.equal(failed.status, 503);
    assert.equal(failed.value.error_code, 'signup_notification_failed');
    assert.equal((await review(f, originalToken)).status, 200, 'failed delivery must not invalidate prior email');
    assert.equal((await review(f, candidateToken)).status, 404);
    const afterFailure = f.db();
    const row = afterFailure.prepare('SELECT token_hash, next_token_hash, notified_at FROM signup_requests WHERE email = ?').get(EMAIL);
    assert.equal(row.token_hash, originalHash);
    assert.equal(row.next_token_hash, null);
    assert.ok(row.notified_at);
    afterFailure.close();

    await f.restart();
    assert.equal((await review(f, originalToken)).status, 200, 'original token remains valid after container restart');
    f.advance(30_001);
    f.fakeNotifier.status = 202;
    assert.equal((await signup(f)).status, 200);
    const deliveredToken = reviewToken(f);
    assert.equal((await review(f, deliveredToken)).status, 200);
    assert.equal((await review(f, originalToken)).status, 404, 'retire old token only after successful delivery');
    assert.equal((await decide(f, deliveredToken, 'approve')).status, 200);
    assert.equal(f.fakeAdmin.calls.length, 1);
    assert.equal((await decide(f, originalToken, 'approve')).status, 404);
    assert.equal((await decide(f, candidateToken, 'approve')).status, 404);
    assert.equal(f.fakeAdmin.calls.length, 1);
  } finally { await f.stop(); }
});

test('old owner token may approve while reminder is in flight; late delivery never creates a second account', async () => {
  const f = await fixture();
  try {
    await signup(f);
    const originalToken = reviewToken(f);
    f.advance(HOUR + 1);
    f.fakeNotifier.holdNext = true;
    const inFlight = new Promise(resolve => { f.fakeNotifier.pauseReached = resolve; });
    const pending = signup(f);
    await inFlight;
    const candidateToken = reviewToken(f);
    assert.equal((await review(f, originalToken)).status, 200);
    assert.equal((await review(f, candidateToken)).status, 404);
    assert.equal((await decide(f, originalToken, 'approve')).status, 200);
    assert.equal(f.fakeAdmin.calls.length, 1);
    f.fakeNotifier.resume();
    await pending;
    assert.equal((await review(f, candidateToken)).status, 404);
    assert.equal((await decide(f, candidateToken, 'approve')).status, 404);
    assert.equal((await decide(f, originalToken, 'approve')).status, 404);
    assert.equal(f.fakeAdmin.calls.length, 1, 'late notifier result must not mint a second user');
  } finally { await f.stop(); }
});

test('interrupted approval is flagged for manual reconciliation on SQLite restart without privileged retry', async () => {
  const f = await fixture();
  try {
    assert.equal((await signup(f)).status, 200);
    const token = reviewToken(f);
    const claimed = f.claimWithoutAdmin(token);
    assert.equal(claimed.approved, true, 'persist decision before any network operation');
    assert.equal(claimed.password, PASSWORD);
    assert.equal((await review(f, token)).status, 404);
    await f.restart();
    const database = f.db();
    assert.equal(database.prepare('SELECT decision_state FROM signup_requests WHERE email = ?').get(EMAIL).decision_state, 'needs_attention');
    database.close();
    assert.equal((await review(f, token)).status, 404);
    assert.equal((await decide(f, token, 'approve')).status, 404);
    assert.equal(f.fakeAdmin.calls.length, 0, 'startup must never repeat ambiguous GoTrue create');
  } finally { await f.stop(); }
});

test('Nginx keeps owner review query tokens out of error diagnostics', () => {
  const config = readFileSync(new URL('../vps/nginx.conf', import.meta.url), 'utf8');
  const reviewLocation = config.match(/location = \/admin\/signups\/review\s*\{([^}]+)\}/);
  assert.ok(reviewLocation, 'review location must be an exact public route');
  assert.match(reviewLocation[1], /error_log\s+\/dev\/null\s+crit;/);
  assert.match(config, /log_format safe '[^']*\$uri/);
});

test('deny and expiry invalidate review tokens without creating any account', async () => {
  const f = await fixture();
  try {
    await signup(f);
    const firstToken = reviewToken(f);
    assert.equal((await decide(f, firstToken, 'deny')).status, 200);
    assert.equal((await review(f, firstToken)).status, 404);
    assert.equal((await decide(f, firstToken, 'approve')).status, 404);
    assert.equal(f.fakeAdmin.calls.length, 0);
    const second = await signup(f);
    const secondToken = reviewToken(f);
    assert.notEqual(firstToken, secondToken);
    f.advance(7 * DAY + 1);
    assert.equal((await review(f, secondToken)).status, 404);
    assert.equal((await decide(f, secondToken, 'approve')).status, 404);
    const fresh = await signup(f, EMAIL, 'New-Password-After-Expiry-123');
    assert.equal(fresh.status, 200);
    assert.notEqual(fresh.value.id, second.value.id);
    assert.equal(f.fakeAdmin.calls.length, 0);
  } finally { await f.stop(); }
});

test('privileged API failure consumes review link and fails closed, preserving reconciliation record', async () => {
  const f = await fixture();
  try {
    f.fakeAdmin.status = 503;
    await signup(f);
    const token = reviewToken(f);
    const failed = await decide(f, token, 'approve');
    assert.equal(failed.status, 503);
    assert.match(failed.value, /needs attention/);
    assert.equal((await decide(f, token, 'approve')).status, 404);
    assert.equal(f.fakeAdmin.calls.length, 1);
    const database = f.db();
    assert.equal(database.prepare('SELECT decision_state FROM signup_requests').get().decision_state, 'needs_attention');
    database.close();
  } finally { await f.stop(); }
});

test('simultaneous clicks can create only one account and cross-site POST cannot decide', async () => {
  const f = await fixture();
  try {
    await signup(f);
    const token = reviewToken(f);
    assert.equal((await decide(f, token, 'approve', { Origin: 'https://other.example.test' })).status, 403);
    assert.equal((await review(f, token)).status, 200);
    f.fakeAdmin.delayMs = 80;
    const [a, b] = await Promise.all([decide(f, token, 'approve'), decide(f, token, 'approve')]);
    assert.deepEqual([a.status, b.status].sort(), [200, 404]);
    assert.equal(f.fakeAdmin.calls.length, 1);
  } finally { await f.stop(); }
});

test('rate limits stop bursts of unique pending requests and malformed bodies', async () => {
  const f = await fixture();
  try {
    assert.equal((await signup(f, 'invalid-email')).status, 400);
    assert.equal((await signup(f, EMAIL, 'short')).status, 422);
    assert.equal((await http(f, '/auth/v1/signup', { method: 'POST', headers: { 'Content-Type': 'application/json' } })).status, 400);
    for (let i = 0; i < 12; i++) {
      const result = await signup(f, `new-user-${i}@example.test`);
      assert.equal(result.status, 200);
    }
    assert.equal((await signup(f, 'blocked@example.test')).status, 429);
    assert.equal(f.fakeAdmin.calls.length, 0);
    const database = f.db();
    assert.equal(database.prepare('SELECT COUNT(*) AS n FROM signup_requests').get().n, 12);
    database.close();
  } finally { await f.stop(); }
});

test('malicious applicant content is escaped in review page', async () => {
  const f = await fixture();
  try {
    const tricky = '<b>test</b>@example.test';
    assert.equal((await signup(f, tricky)).status, 200);
    const response = await review(f, reviewToken(f));
    assert.equal(response.status, 200);
    assert.match(response.value, /&lt;b&gt;test&lt;\/b&gt;/);
    assert.ok(!response.value.includes('<b>test</b>'));
    assert.equal(response.headers.get('Content-Security-Policy').includes("frame-ancestors 'none'"), true);
  } finally { await f.stop(); }
});
