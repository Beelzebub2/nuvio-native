// Cloudflare-specific behaviour: discovery, auth edge cases and owner-approved sign-up.
// Runs only against the local wrangler servers started by run-local.mjs.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';

const auto = process.env.STARLIT_SUPABASE_URL;
const approval = process.env.STARLIT_APPROVAL_URL;
const key = process.env.STARLIT_SUPABASE_PUBLISHABLE_KEY;
const approvalLog = process.env.STARLIT_APPROVAL_LOG;
for (const url of [auto, approval]) {
  if (!url || !['127.0.0.1', 'localhost'].includes(new URL(url).hostname)) throw new Error('Run through test/run-local.mjs against localhost only');
}

async function call(base, path, { token, body, form, method = body === undefined && form === undefined ? 'GET' : 'POST' } = {}) {
  const response = await fetch(base + path, {
    method,
    redirect: 'manual',
    headers: {
      apikey: key, ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(form ? { 'Content-Type': 'application/x-www-form-urlencoded' } : { 'Content-Type': 'application/json' }),
    },
    ...(form ? { body: new URLSearchParams(form) } : body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const raw = await response.text();
  let data = raw;
  try { data = raw ? JSON.parse(raw) : null; } catch { /* HTML page */ }
  return { status: response.status, data, headers: response.headers };
}
const credentials = label => ({ email: `starlit-${label}-${randomUUID()}@example.test`, password: `Test-${randomUUID()}!` });

/** Latest review link from the approval server's simulated email files. */
async function latestReviewToken(after) {
  for (let attempt = 0; attempt < 40; attempt++) {
    const log = readFileSync(approvalLog, 'utf8').slice(after);
    const files = [...log.matchAll(/(\S+\.(?:txt|eml|html))/g)].map(match => match[1]);
    for (const file of files.reverse()) {
      try {
        const token = readFileSync(file, 'utf8').match(/token=([A-Za-z0-9_%-]+)/)?.[1];
        if (token) return decodeURIComponent(token);
      } catch { /* not an email file */ }
    }
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  return null;
}

test('discovery advertises this Worker as an email/password Starlit server', async () => {
  const { status, data } = await call(auto, '/.well-known/nuvio');
  assert.equal(status, 200);
  assert.deepEqual({ ...data, backend_url: undefined }, {
    version: 1, service: 'nuvio', self_hosted: true, backend_url: undefined, publishable_key: key,
    capabilities: { email_password_auth: true, tv_login: false },
  });
  assert.equal(data.backend_url, auto);
});

test('auth rejects weak passwords, bad emails, duplicates and wrong passwords', async () => {
  const user = credentials('edge');
  assert.equal((await call(auto, '/auth/v1/signup', { body: { ...user, password: 'short' } })).data.error_code, 'weak_password');
  assert.equal((await call(auto, '/auth/v1/signup', { body: { ...user, email: 'not-an-email' } })).status, 400);
  const created = await call(auto, '/auth/v1/signup', { body: user });
  assert.equal(created.status, 200);
  assert.ok(created.data.access_token);
  assert.equal((await call(auto, '/auth/v1/signup', { body: user })).data.error_code, 'user_already_exists');
  const wrong = await call(auto, '/auth/v1/token?grant_type=password', { body: { ...user, password: 'Wrong-password-123' } });
  assert.equal(wrong.status, 400);
  assert.equal(wrong.data.error_code, 'invalid_credentials');
  const unknown = await call(auto, '/auth/v1/token?grant_type=password', { body: credentials('nobody') });
  assert.equal(unknown.data.error_code, 'invalid_credentials', 'Unknown and wrong-password logins look the same');
  const upper = await call(auto, '/auth/v1/token?grant_type=password', { body: { ...user, email: user.email.toUpperCase() } });
  assert.equal(upper.status, 200, 'Emails are case-insensitive');
});

test('sessions rotate refresh tokens, and logout revokes them', async () => {
  const user = credentials('session');
  const { data: session } = await call(auto, '/auth/v1/signup', { body: user });
  assert.equal((await call(auto, '/auth/v1/user', { token: session.access_token })).data.email, user.email);
  assert.equal((await call(auto, '/auth/v1/user', { token: `${session.access_token}x` })).status, 401);
  const first = await call(auto, '/auth/v1/token?grant_type=refresh_token', { body: { refresh_token: session.refresh_token } });
  assert.equal(first.status, 200);
  assert.notEqual(first.data.refresh_token, session.refresh_token);
  const retried = await call(auto, '/auth/v1/token?grant_type=refresh_token', { body: { refresh_token: session.refresh_token } });
  assert.equal(retried.status, 200, 'A retry within the reuse grace still succeeds');
  assert.equal((await call(auto, '/auth/v1/logout?scope=global', { token: first.data.access_token, method: 'POST' })).status, 204);
  const revoked = await call(auto, '/auth/v1/token?grant_type=refresh_token', { body: { refresh_token: first.data.refresh_token } });
  assert.equal(revoked.status, 400);
  assert.equal((await call(auto, '/rest/v1/rpc/sync_pull_profiles', { token: 'a.b.c', body: {} })).status, 401);
});

test('new accounts need the owner to approve them by email', async () => {
  const user = { ...credentials('friend'), email: `<b>friend</b>-${randomUUID()}@example.test` };
  let logLength = readFileSync(approvalLog, 'utf8').length;
  const requested = await call(approval, '/auth/v1/signup', { body: user });
  assert.equal(requested.status, 200);
  assert.equal(requested.data.access_token, undefined, 'No session before approval');
  assert.equal(requested.data.email, user.email);
  const pending = await call(approval, '/auth/v1/token?grant_type=password', { body: user });
  assert.equal(pending.status, 400);
  assert.equal(pending.data.error_code, 'email_not_confirmed');
  assert.match(pending.data.msg, /waiting for approval/);
  assert.equal((await call(approval, '/auth/v1/token?grant_type=password', { body: { ...user, password: 'Wrong-password-123' } })).data.error_code, 'invalid_credentials');

  const token = await latestReviewToken(logLength);
  assert.ok(token, 'The owner received a review link');
  logLength = readFileSync(approvalLog, 'utf8').length;
  await call(approval, '/auth/v1/signup', { body: user });
  assert.equal(await latestReviewToken(logLength), null, 'Repeated requests do not flood the owner inbox');
  const swapped = { ...user, password: 'Someone-elses-password-123' };
  assert.equal((await call(approval, '/auth/v1/signup', { body: swapped })).status, 200);

  const page = await call(approval, `/admin/signups/review?token=${encodeURIComponent(token)}`);
  assert.equal(page.status, 200);
  assert.ok(page.data.includes('&lt;b&gt;friend&lt;/b&gt;'), 'The requester email is escaped');
  assert.ok(!page.data.includes('<b>friend</b>'));
  assert.equal((await call(approval, '/auth/v1/token?grant_type=password', { body: user })).data.error_code, 'email_not_confirmed', 'Viewing the page approves nothing');
  assert.equal((await call(approval, '/admin/signups/review', { form: { token: 'x'.repeat(43), decision: 'approve' } })).status, 404);

  const approved = await call(approval, '/admin/signups/review', { form: { token, decision: 'approve' } });
  assert.equal(approved.status, 200);
  assert.match(approved.data, /Account approved/);
  assert.equal((await call(approval, '/admin/signups/review', { form: { token, decision: 'approve' } })).status, 404, 'Links are single use');
  const login = await call(approval, '/auth/v1/token?grant_type=password', { body: user });
  assert.equal(login.status, 200);
  assert.equal((await call(approval, '/auth/v1/token?grant_type=password', { body: swapped })).data.error_code, 'invalid_credentials',
    'A later request cannot replace the pending password');
  const profiles = await call(approval, '/rest/v1/rpc/sync_pull_profiles', { token: login.data.access_token, body: {} });
  assert.equal(profiles.data[0].profile_index, 1);
  assert.equal((await call(approval, '/auth/v1/signup', { body: user })).data.error_code, 'user_already_exists');
});

test('a denied request never becomes an account', async () => {
  const user = credentials('denied');
  const logLength = readFileSync(approvalLog, 'utf8').length;
  await call(approval, '/auth/v1/signup', { body: user });
  const token = await latestReviewToken(logLength);
  assert.ok(token);
  assert.match((await call(approval, '/admin/signups/review', { form: { token, decision: 'deny' } })).data, /Request denied/);
  assert.equal((await call(approval, '/auth/v1/token?grant_type=password', { body: user })).data.error_code, 'invalid_credentials');
});

test('approval mode never reports instant sign-up', async () => {
  const settings = await call(approval, '/auth/v1/settings');
  assert.equal(settings.data.mailer_autoconfirm, false);
});
