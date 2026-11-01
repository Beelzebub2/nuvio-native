// Private Starlit signup-request service. Public signup never calls GoTrue: an account is
// created through its loopback-only admin API only after the owner clicks Approve.
import { createServer } from 'node:http';
import { createCipheriv, createDecipheriv, createHmac, createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { isIP } from 'node:net';
import { DatabaseSync } from 'node:sqlite';
import { pathToFileURL } from 'node:url';

const DAY = 86_400_000;
const HOUR = 3_600_000;
const REQUEST_TTL = 7 * DAY;
const NOTIFY_RETRY_DELAY = 30_000;
const NOTIFY_REMINDER_DELAY = HOUR;
const MAX_BODY = 16 * 1024;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
const HTML_HEADERS = {
  'Content-Type': 'text/html; charset=utf-8',
  'Cache-Control': 'no-store, max-age=0',
  'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Cross-Origin-Resource-Policy': 'same-origin',
  'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
};

const encode = bytes => Buffer.from(bytes).toString('base64url');
const fingerprint = (key, kind, value) => createHmac('sha256', key).update(`${kind}\0${value}`).digest('hex');
const hashReviewToken = token => createHash('sha256').update(token).digest('hex');
const generateReviewToken = () => encode(randomBytes(32));
const escapeHtml = value => String(value).replace(/[&<>"']/g, match => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
})[match]);

function sealPassword(key, email, password) {
  const nonce = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(Buffer.from(`starlit-signup-v1\0${email}`));
  const encrypted = Buffer.concat([cipher.update(password, 'utf8'), cipher.final()]);
  return [encode(nonce), encode(cipher.getAuthTag()), encode(encrypted)].join('.');
}

function openPassword(key, email, sealed) {
  const parts = sealed.split('.');
  if (parts.length !== 3) throw new Error('Invalid encrypted signup record');
  const nonce = Buffer.from(parts[0], 'base64url');
  const tag = Buffer.from(parts[1], 'base64url');
  if (nonce.length !== 12 || tag.length !== 16) throw new Error('Invalid encrypted signup record');
  const decipher = createDecipheriv('aes-256-gcm', key, nonce);
  decipher.setAAD(Buffer.from(`starlit-signup-v1\0${email}`));
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(Buffer.from(parts[2], 'base64url')), decipher.final()]).toString('utf8');
}

function passwordVerifier(key, email, password) {
  return fingerprint(key, 'password:' + email, password);
}

function equalVerifier(a, b) {
  const first = Buffer.from(a, 'hex');
  const second = Buffer.from(b, 'hex');
  return first.length === 32 && second.length === 32 && timingSafeEqual(first, second);
}

class HttpFailure extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

function notificationDeliveryError(seconds = 30) {
  // Keep the pending encrypted request: the applicant must not start over or
  // choose a different password after a mail outage.
  const error = new HttpFailure(503, 'signup_notification_failed',
    'Your account request was saved, but the owner could not be notified. Retry with the same email and password after 30 seconds.');
  error.retryAfter = Math.max(1, Math.ceil(seconds));
  return error;
}

function validUrl(value, name, { localhostHttp = false } = {}) {
  const url = new URL(value);
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
  if ((url.protocol !== 'https:' && !(localhostHttp && url.protocol === 'http:' && loopback)) ||
      url.username || url.password || url.search || url.hash) throw new Error(`${name} must be an HTTPS URL without credentials or query parameters`);
  return url;
}

/** Refuse to start with weak/missing secrets or accidentally enabled GoTrue public signup. */
export function loadSignupConfig(env = process.env) {
  if (env.AUTH_DISABLE_SIGNUP !== 'true' || env.AUTH_EMAIL_AUTOCONFIRM !== 'false') {
    throw new Error('GoTrue public signup must be disabled and autoconfirm must be false');
  }
  if (!env.JWT_SECRET || env.JWT_SECRET.length < 32) throw new Error('JWT_SECRET must already be configured');
  if (!env.SIGNUP_ENCRYPTION_KEY || !TOKEN.test(env.SIGNUP_ENCRYPTION_KEY)) throw new Error('SIGNUP_ENCRYPTION_KEY must be 32 random base64url bytes');
  const encryptionKey = Buffer.from(env.SIGNUP_ENCRYPTION_KEY, 'base64url');
  if (encryptionKey.length !== 32) throw new Error('Invalid signup encryption key');
  if (!env.SIGNUP_NOTIFY_TOKEN || env.SIGNUP_NOTIFY_TOKEN.length < 32) throw new Error('SIGNUP_NOTIFY_TOKEN must be configured');
  const publicOrigin = validUrl(env.STARLIT_SUPABASE_URL, 'STARLIT_SUPABASE_URL').origin;
  if (publicOrigin !== env.STARLIT_SUPABASE_URL) throw new Error('STARLIT_SUPABASE_URL must be the HTTPS origin');
  const notifyUrl = validUrl(env.SIGNUP_NOTIFY_URL, 'SIGNUP_NOTIFY_URL', { localhostHttp: true });
  if (!notifyUrl.pathname.endsWith('/notify')) throw new Error('SIGNUP_NOTIFY_URL must end in /notify');
  const adminUrl = validUrl(env.SIGNUP_ADMIN_URL || 'http://127.0.0.1:9999', 'SIGNUP_ADMIN_URL', { localhostHttp: true });
  if (adminUrl.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(adminUrl.hostname) || adminUrl.pathname !== '/') {
    throw new Error('SIGNUP_ADMIN_URL must be a loopback HTTP origin');
  }
  const port = Number(env.SIGNUP_PORT || 8789);
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('Invalid SIGNUP_PORT');
  if (!env.SIGNUP_DB_PATH) throw new Error('SIGNUP_DB_PATH is required');
  return {
    encryptionKey, jwtSecret: env.JWT_SECRET, notifyToken: env.SIGNUP_NOTIFY_TOKEN,
    publicOrigin, notifyUrl: notifyUrl.href, adminUrl: adminUrl.origin,
    dbPath: env.SIGNUP_DB_PATH, port, host: '127.0.0.1',
  };
}

function pendingUser(row) {
  const timestamp = new Date(row.requested_at).toISOString();
  return {
    id: row.id, aud: 'authenticated', role: '', email: row.email, phone: '',
    confirmation_sent_at: timestamp, app_metadata: { provider: 'email', providers: ['email'] },
    user_metadata: {}, identities: [], created_at: timestamp, updated_at: timestamp, is_anonymous: false,
  };
}

export class SignupStore {
  constructor(dbPath, encryptionKey, now = Date.now) {
    this.db = new DatabaseSync(dbPath);
    this.key = encryptionKey;
    this.now = now;
    this.db.exec(`
      PRAGMA journal_mode = WAL;
      PRAGMA synchronous = FULL;
      PRAGMA busy_timeout = 5000;
      CREATE TABLE IF NOT EXISTS signup_requests (
        email TEXT PRIMARY KEY, id TEXT NOT NULL UNIQUE,
        password_enc TEXT NOT NULL, password_verifier TEXT NOT NULL,
        token_hash TEXT NOT NULL UNIQUE,
        next_token_hash TEXT,
        requested_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
        notification_state TEXT NOT NULL DEFAULT 'new',
        last_notify_attempt_at INTEGER, notified_at INTEGER,
        decision_state TEXT NOT NULL DEFAULT 'pending'
      );
      CREATE INDEX IF NOT EXISTS signup_expiry ON signup_requests (expires_at);
      CREATE TABLE IF NOT EXISTS rate_limits (
        fingerprint TEXT PRIMARY KEY, window_start INTEGER NOT NULL, counter INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS notification_attempts (at INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS notification_at ON notification_attempts (at);
    `);
    // Pre-deployment development databases may have the previous schema.
    if (!this.db.prepare('PRAGMA table_info(signup_requests)').all().some(column => column.name === 'next_token_hash')) {
      this.db.exec('ALTER TABLE signup_requests ADD COLUMN next_token_hash TEXT');
    }
    this.db.exec('CREATE UNIQUE INDEX IF NOT EXISTS signup_next_token_hash ON signup_requests(next_token_hash) WHERE next_token_hash IS NOT NULL');
    // An interrupted owner approval might have succeeded remotely even if the
    // GoTrue response was lost. Never automatically retry after a process crash.
    this.db.prepare("UPDATE signup_requests SET decision_state = 'needs_attention' WHERE decision_state = 'approving'").run();
  }

  close() { this.db.close(); }
  row(email) { return this.db.prepare('SELECT * FROM signup_requests WHERE email = ?').get(email); }
  byToken(tokenHash) { return this.db.prepare('SELECT * FROM signup_requests WHERE token_hash = ?').get(tokenHash); }

  prune(now) {
    this.db.prepare("DELETE FROM signup_requests WHERE expires_at <= ? AND decision_state IN ('pending','needs_attention')").run(now);
    this.db.prepare('DELETE FROM notification_attempts WHERE at < ?').run(now - HOUR);
    this.db.prepare('DELETE FROM rate_limits WHERE window_start < ?').run(now - DAY);
  }

  allowRate(kind, value, max, window, now) {
    const key = fingerprint(this.key, kind, value);
    const current = this.db.prepare('SELECT window_start, counter FROM rate_limits WHERE fingerprint = ?').get(key);
    if (current && now - current.window_start < window && current.counter >= max) return false;
    this.db.prepare(`INSERT INTO rate_limits (fingerprint, window_start, counter) VALUES (?, ?, 1)
      ON CONFLICT(fingerprint) DO UPDATE SET
      window_start = CASE WHEN excluded.window_start - rate_limits.window_start >= ? THEN excluded.window_start ELSE rate_limits.window_start END,
      counter = CASE WHEN excluded.window_start - rate_limits.window_start >= ? THEN 1 ELSE rate_limits.counter + 1 END`).run(
      key, now, window, window,
    );
    return true;
  }

  reserveNotification(row, now) {
    const recent = this.db.prepare('SELECT COUNT(*) AS total FROM notification_attempts WHERE at > ?').get(now - HOUR).total;
    if (recent >= 30) return null;
    const token = generateReviewToken();
    const tokenHash = hashReviewToken(token);
    // Keep the previously emailed review token usable while sending a new one.
    // Only after the notifier accepts the email do we swap to the new token.
    const claimed = this.db.prepare(`UPDATE signup_requests SET next_token_hash = ?, last_notify_attempt_at = ?, notification_state = 'sending'
      WHERE email = ? AND decision_state = 'pending'`).run(tokenHash, now, row.email);
    if (!claimed.changes) return null;
    this.db.prepare('INSERT INTO notification_attempts (at) VALUES (?)').run(now);
    return { reviewToken: token, tokenHash };
  }

  /** Synchronous operations run without an await, so concurrent HTTP calls cannot change a request halfway through. */
  requestSignup(email, password, ip) {
    const now = this.now();
    this.prune(now);
    if (!this.allowRate('ip-signup', ip, 60, HOUR, now) || !this.allowRate('global-signup', '*', 600, HOUR, now)) {
      throw new HttpFailure(429, 'over_request_rate_limit', 'Too many sign-up requests. Try again later.');
    }
    const existing = this.row(email);
    if (existing) {
      if (existing.decision_state !== 'pending') throw new HttpFailure(429, 'over_request_rate_limit', 'The account request is being reviewed.');
      const samePassword = equalVerifier(passwordVerifier(this.key, email, password), existing.password_verifier);
      if (!samePassword) {
        if (!this.allowRate('wrong-pending-password', email, 10, 15 * 60_000, now)) {
          throw new HttpFailure(429, 'over_request_rate_limit', 'Too many attempts for this account. Try again later.');
        }
        // Do not claim successful submission if the original owner email never
        // made it out. A wrong password must still never trigger notification.
        return { user: pendingUser(existing), deliveryUnconfirmed: existing.notified_at == null,
          retryAfter: Math.max(1, (NOTIFY_RETRY_DELAY - (now - (existing.last_notify_attempt_at ?? now))) / 1000) };
      }
      const canRetry = existing.notification_state === 'failed' || existing.notification_state === 'sending' || existing.notification_state === 'deferred';
      const delay = canRetry ? NOTIFY_RETRY_DELAY : NOTIFY_REMINDER_DELAY;
      const elapsed = now - (existing.last_notify_attempt_at ?? 0);
      const notification = elapsed >= delay ? this.reserveNotification(existing, now) : null;
      return { user: pendingUser(existing), email, requestedAt: existing.requested_at, notification,
        deliveryUnconfirmed: !notification && existing.notified_at == null,
        retryAfter: Math.max(1, (delay - elapsed) / 1000) };
    }
    if (this.db.prepare("SELECT COUNT(*) AS total FROM signup_requests WHERE decision_state = 'pending'").get().total >= 200) {
      throw new HttpFailure(429, 'over_request_rate_limit', 'Too many sign-up requests are pending. Try again later.');
    }
    if (!this.allowRate('new-signup-ip', ip, 12, HOUR, now)) {
      throw new HttpFailure(429, 'over_request_rate_limit', 'Too many new sign-up requests. Try again later.');
    }
    // Do not accept a brand-new request silently when the owner cannot be notified.
    if (this.db.prepare('SELECT COUNT(*) AS total FROM notification_attempts WHERE at > ?').get(now - HOUR).total >= 30) {
      throw new HttpFailure(429, 'over_request_rate_limit', 'The signup queue is busy. Try again later.');
    }
    const id = randomUUID();
    const initialToken = generateReviewToken();
    const record = {
      email, id, requested_at: now,
      password_enc: sealPassword(this.key, email, password),
      password_verifier: passwordVerifier(this.key, email, password),
    };
    this.db.prepare(`INSERT INTO signup_requests (email, id, password_enc, password_verifier, token_hash, requested_at, expires_at,
      notification_state) VALUES (?, ?, ?, ?, ?, ?, ?, 'deferred')`).run(
      email, id, record.password_enc, record.password_verifier, hashReviewToken(initialToken), now, now + REQUEST_TTL,
    );
    const notification = this.reserveNotification(record, now);
    return { user: pendingUser(record), email, requestedAt: now, notification,
      deliveryUnconfirmed: !notification, retryAfter: 30 };
  }

  notificationResult(email, tokenHash, success) {
    if (success) {
      this.db.prepare(`UPDATE signup_requests SET token_hash = next_token_hash,
        next_token_hash = NULL, notification_state = 'sent', notified_at = ?
        WHERE email = ? AND next_token_hash = ? AND decision_state = 'pending'`).run(this.now(), email, tokenHash);
    } else {
      // The previously delivered token was never replaced, so it remains
      // usable even if the notifier fails or times out while sending a reminder.
      this.db.prepare(`UPDATE signup_requests SET next_token_hash = NULL, notification_state = 'failed'
        WHERE email = ? AND next_token_hash = ? AND decision_state = 'pending'`).run(email, tokenHash);
    }
  }

  review(token) {
    if (typeof token !== 'string' || !TOKEN.test(token)) return null;
    const row = this.byToken(hashReviewToken(token));
    if (!row || row.decision_state !== 'pending' || row.expires_at <= this.now()) return null;
    return { email: row.email, requestedAt: row.requested_at };
  }

  decide(token, approve) {
    if (typeof token !== 'string' || !TOKEN.test(token)) return null;
    const tokenHash = hashReviewToken(token);
    const row = this.byToken(tokenHash);
    if (!row || row.decision_state !== 'pending' || row.expires_at <= this.now()) return null;
    if (!approve) {
      this.db.prepare("DELETE FROM signup_requests WHERE token_hash = ? AND decision_state = 'pending'").run(tokenHash);
      return { email: row.email, approved: false };
    }
    // Claim before the network call: retries can never create a second user if the response is lost.
    this.db.prepare("UPDATE signup_requests SET decision_state = 'approving' WHERE token_hash = ? AND decision_state = 'pending'").run(tokenHash);
    return { email: row.email, password: openPassword(this.key, row.email, row.password_enc), tokenHash, approved: true };
  }

  approvalSucceeded(tokenHash) {
    this.db.prepare("DELETE FROM signup_requests WHERE token_hash = ? AND decision_state = 'approving'").run(tokenHash);
  }

  approvalFailed(tokenHash) {
    // A network timeout may mean GoTrue created the user. Require private operator reconciliation.
    this.db.prepare("UPDATE signup_requests SET decision_state = 'needs_attention' WHERE token_hash = ? AND decision_state = 'approving'").run(tokenHash);
  }
}

function jwt(secret) {
  const now = Math.floor(Date.now() / 1000);
  const part = value => encode(Buffer.from(JSON.stringify(value)));
  const signingInput = `${part({ alg: 'HS256', typ: 'JWT' })}.${part({ role: 'service_role', aud: 'authenticated', iat: now, exp: now + 60 })}`;
  const signature = createHmac('sha256', secret).update(signingInput).digest('base64url');
  return `${signingInput}.${signature}`;
}

async function createConfirmedUser(config, email, password, fetcher) {
  const response = await fetcher(`${config.adminUrl}/admin/users`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${jwt(config.jwtSecret)}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password, email_confirm: true }),
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error(`GoTrue account create failed with status ${response.status}`);
  const body = await response.json();
  if (!body?.id || typeof body.id !== 'string' || body.email?.toLowerCase() !== email) {
    throw new Error('GoTrue account response did not identify the approved email');
  }
}

async function postNotification(config, email, requestedAt, token, fetcher) {
  const url = `${config.publicOrigin}/admin/signups/review?token=${encodeURIComponent(token)}`;
  const response = await fetcher(config.notifyUrl, {
    method: 'POST',
    headers: { Authorization: `Bearer ${config.notifyToken}`, 'Content-Type': 'application/json' },
    // The separate email Worker validates requestedAt as Unix milliseconds.
    body: JSON.stringify({ email, reviewUrl: url, requestedAt }),
    signal: AbortSignal.timeout(6_000),
  });
  if (!response.ok) throw new Error(`Approval notification failed with status ${response.status}`);
  await response.body?.cancel();
}

function html(body, status = 200) {
  return { status, headers: HTML_HEADERS, body: `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex"><title>Starlit account review</title><style>
  :root{color-scheme:light dark}body{font:16px/1.5 system-ui,sans-serif;background:#f5f5f6;color:#191923;min-height:100vh;display:grid;place-items:center;margin:0;padding:16px;box-sizing:border-box}
  main{width:100%;max-width:440px;background:white;border-radius:14px;padding:28px;box-sizing:border-box}h1{font-size:22px;margin:0 0 12px}p{margin:12px 0;color:#494954}form{display:flex;gap:12px;margin-top:24px}
  button{flex:1;border:0;border-radius:8px;background:#5145d7;color:white;font:600 15px system-ui;padding:12px;cursor:pointer}button.deny{background:#fff;color:#ad2727;box-shadow:inset 0 0 0 1px #ad2727}
  @media(prefers-color-scheme:dark){body{background:#15151a;color:#eee}main{background:#24242e}p{color:#bcbcc9}button.deny{background:#24242e;color:#ff9797;box-shadow:inset 0 0 0 1px #ff9797}}
  </style></head><body><main>${body}</main></body></html>` };
}

function send(res, { status, headers, body }) {
  res.writeHead(status, headers);
  res.end(body);
}

function json(res, status, value, extraHeaders = {}) {
  send(res, { status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', ...extraHeaders }, body: JSON.stringify(value) });
}

async function readBody(req, limit = MAX_BODY) {
  const advertised = Number(req.headers['content-length']);
  if (advertised > limit) throw new HttpFailure(413, 'request_too_large', 'Request body is too large');
  let size = 0;
  const parts = [];
  for await (const piece of req) {
    size += piece.length;
    if (size > limit) throw new HttpFailure(413, 'request_too_large', 'Request body is too large');
    parts.push(piece);
  }
  return Buffer.concat(parts).toString('utf8');
}

function clientAddress(req) {
  const remote = req.socket.remoteAddress || '';
  const trustedProxy = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(remote);
  const forwarded = trustedProxy && typeof req.headers['x-real-ip'] === 'string' ? req.headers['x-real-ip'] : '';
  return isIP(forwarded) ? forwarded : remote;
}

/** Can be started against a temporary database, fake notifier and fake GoTrue in integration tests. */
export function createSignupServer(config, { now = Date.now, fetcher = fetch, logger = console } = {}) {
  const store = new SignupStore(config.dbPath, config.encryptionKey, now);
  const server = createServer(async (req, res) => {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    try {
      if (url.pathname === '/healthz' && req.method === 'GET') return json(res, 200, { status: 'ok', service: 'starlit-signup' });
      if (url.pathname === '/auth/v1/signup') {
        if (req.method !== 'POST') return json(res, 405, { code: 405, error_code: 'method_not_allowed', msg: 'Method not allowed' }, { Allow: 'POST' });
        if (!String(req.headers['content-type'] || '').toLowerCase().startsWith('application/json')) {
          throw new HttpFailure(415, 'invalid_request', 'Expected JSON body');
        }
        let body;
        try { body = JSON.parse(await readBody(req)); } catch (error) {
          if (error instanceof HttpFailure) throw error;
          throw new HttpFailure(400, 'validation_failed', 'Invalid JSON');
        }
        if (!body || typeof body !== 'object' || Array.isArray(body)) throw new HttpFailure(400, 'validation_failed', 'Invalid signup request');
        const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
        const password = typeof body.password === 'string' ? body.password : '';
        if (email.length > 254 || !EMAIL.test(email)) throw new HttpFailure(400, 'validation_failed', 'Invalid email address');
        if (password.length < 12 || password.length > 256) throw new HttpFailure(422, 'weak_password', 'Password must be between 12 and 256 characters');
        const result = store.requestSignup(email, password, clientAddress(req));
        if (result.notification) {
          try {
            await postNotification(config, result.email, result.requestedAt, result.notification.reviewToken, fetcher);
          } catch {
            logger.warn('Signup notification delivery failed');
            store.notificationResult(email, result.notification.tokenHash, false);
            throw notificationDeliveryError();
          }
          store.notificationResult(email, result.notification.tokenHash, true);
        }
        if (result.deliveryUnconfirmed) throw notificationDeliveryError(result.retryAfter);
        return json(res, 200, result.user);
      }
      if (url.pathname === '/admin/signups/review') {
        if (req.method === 'GET') {
          const token = url.searchParams.get('token') || '';
          const request = store.review(token);
          if (!request) return send(res, html('<h1>Request unavailable</h1><p>This link has expired or was already used.</p>', 404));
          const timestamp = new Date(request.requestedAt).toUTCString();
          return send(res, html(`<h1>Approve Starlit account?</h1><p>Account: <strong>${escapeHtml(request.email)}</strong></p><p>Requested: ${escapeHtml(timestamp)}</p><p>Only approving will let this person sign in.</p>
          <form method="post"><input type="hidden" name="token" value="${escapeHtml(token)}"><button name="decision" value="approve">Approve</button><button class="deny" name="decision" value="deny">Deny</button></form>`));
        }
        if (req.method !== 'POST') return send(res, html('<h1>Method not allowed</h1>', 405));
        const origin = req.headers.origin;
        if (origin && origin !== config.publicOrigin || req.headers['sec-fetch-site'] === 'cross-site') {
          return send(res, html('<h1>Request rejected</h1>', 403));
        }
        if (!String(req.headers['content-type'] || '').toLowerCase().startsWith('application/x-www-form-urlencoded')) {
          return send(res, html('<h1>Invalid form</h1>', 400));
        }
        const params = new URLSearchParams(await readBody(req, 4096));
        const decision = params.get('decision');
        if (!['approve', 'deny'].includes(decision)) return send(res, html('<h1>Invalid decision</h1>', 400));
        const result = store.decide(params.get('token'), decision === 'approve');
        if (!result) return send(res, html('<h1>Request unavailable</h1><p>This link has expired or was already used.</p>', 404));
        if (!result.approved) return send(res, html(`<h1>Request denied</h1><p>No account was created for ${escapeHtml(result.email)}.</p>`));
        try {
          await createConfirmedUser(config, result.email, result.password, fetcher);
          store.approvalSucceeded(result.tokenHash);
          return send(res, html(`<h1>Account approved</h1><p>${escapeHtml(result.email)} can now sign in with their chosen password.</p>`));
        } catch {
          store.approvalFailed(result.tokenHash);
          logger.error('Signup account creation failed; private reconciliation required');
          return send(res, html('<h1>Approval needs attention</h1><p>The account operation could not be confirmed. Contact the server owner to check the private account records.</p>', 503));
        }
      }
      return json(res, 404, { error: 'not_found' });
    } catch (error) {
      if (error instanceof HttpFailure) return json(res, error.status,
        { code: error.status, error_code: error.code, msg: error.message },
        { 'X-Sb-Error-Code': error.code, ...(error.retryAfter ? { 'Retry-After': String(error.retryAfter) } : {}) });
      logger.error('Unhandled signup service error', error?.name || 'Unknown');
      return json(res, 500, { code: 500, error_code: 'server_error', msg: 'Internal server error' });
    }
  });
  return { server, store };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const config = loadSignupConfig();
  const { server } = createSignupServer(config);
  server.listen(config.port, config.host, () => {
    console.log(`Starlit signup requests listening on ${config.host}:${config.port}`);
  });
}
