// Email/password accounts, owner-approved sign-up requests and refresh-token sessions, in one
// SQLite Durable Object. Access tokens are stateless JWTs signed by the Worker, never here.
import { DurableObject } from 'cloudflare:workers';
import { base64UrlEncode, base64UrlDecode, randomToken, sha256, nowIso } from './shared.mjs';

const encoder = new TextEncoder();
const REFRESH_REUSE_GRACE_MS = 30_000; // Tolerates a retried refresh whose response was lost.
const SESSION_IDLE_MS = 180 * 24 * 60 * 60 * 1000;
const FAILURE_WINDOW_MS = 15 * 60 * 1000;
const MAX_FAILURES = 10;
export const REQUEST_LIFETIME_MS = 7 * 24 * 60 * 60 * 1000;
const RENOTIFY_AFTER_MS = 60 * 60 * 1000;
const MAX_PENDING_REQUESTS = 20;
const MAX_EMAILS_PER_HOUR = 10;

const authError = (status, error_code, msg, extra = {}) => ({ error: { status, error_code, msg, ...extra } });
export const PENDING_MESSAGE = 'Your account is waiting for approval. You can sign in once the server owner approves it.';

async function pbkdf2(password, salt, iterations) {
  const key = await crypto.subtle.importKey('raw', encoder.encode(password), 'PBKDF2', false, ['deriveBits']);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, key, 256));
}

export async function hashPassword(password, iterations) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  return `pbkdf2_sha256$${iterations}$${base64UrlEncode(salt)}$${base64UrlEncode(await pbkdf2(password, salt, iterations))}`;
}

export async function verifyPassword(password, stored) {
  const [scheme, iterations, salt, expected] = stored.split('$');
  if (scheme !== 'pbkdf2_sha256') return false;
  const actual = await pbkdf2(password, base64UrlDecode(salt), Number(iterations));
  const wanted = base64UrlDecode(expected);
  let difference = actual.length ^ wanted.length;
  for (let i = 0; i < actual.length; i++) difference |= actual[i] ^ (wanted[i] ?? 0);
  return difference === 0;
}

export function userJson(row) {
  const identity = {
    identity_id: row.id, id: row.id, user_id: row.id, provider: 'email', email: row.email,
    identity_data: { email: row.email, email_verified: true, phone_verified: false, sub: row.id },
    last_sign_in_at: row.last_sign_in_at, created_at: row.created_at, updated_at: row.updated_at,
  };
  return {
    id: row.id, aud: 'authenticated', role: 'authenticated', email: row.email, phone: '',
    email_confirmed_at: row.created_at, confirmed_at: row.created_at, last_sign_in_at: row.last_sign_in_at,
    app_metadata: { provider: 'email', providers: ['email'] }, user_metadata: JSON.parse(row.user_metadata),
    identities: [identity], created_at: row.created_at, updated_at: row.updated_at, is_anonymous: false,
  };
}

/** GoTrue's sign-up response when confirmation is still required: a user, but no session. */
function pendingUserJson(row) {
  return {
    id: row.id, aud: 'authenticated', role: '', email: row.email, phone: '', confirmation_sent_at: row.created_at,
    app_metadata: { provider: 'email', providers: ['email'] }, user_metadata: JSON.parse(row.user_metadata),
    identities: [], created_at: row.created_at, updated_at: row.created_at, is_anonymous: false,
  };
}

export class AccountsDO extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    ctx.blockConcurrencyWhile(async () => {
      for (const statement of [
        `CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL,
          user_metadata TEXT NOT NULL DEFAULT '{}', created_at TEXT NOT NULL, updated_at TEXT NOT NULL, last_sign_in_at TEXT)`,
        `CREATE TABLE IF NOT EXISTS signup_requests (email TEXT PRIMARY KEY, id TEXT NOT NULL, password_hash TEXT NOT NULL,
          user_metadata TEXT NOT NULL, created_at TEXT NOT NULL, requested_at INTEGER NOT NULL, review_token_hash TEXT NOT NULL UNIQUE,
          notified_at INTEGER NOT NULL DEFAULT 0, country TEXT, client TEXT)`,
        `CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, created_at TEXT NOT NULL, refreshed_at INTEGER NOT NULL)`,
        'CREATE INDEX IF NOT EXISTS sessions_user ON sessions (user_id)',
        `CREATE TABLE IF NOT EXISTS refresh_tokens (token_hash TEXT PRIMARY KEY, session_id TEXT NOT NULL, created_at INTEGER NOT NULL, rotated_at INTEGER)`,
        'CREATE INDEX IF NOT EXISTS refresh_tokens_session ON refresh_tokens (session_id)',
        'CREATE TABLE IF NOT EXISTS login_failures (email TEXT PRIMARY KEY, count INTEGER NOT NULL, window_start INTEGER NOT NULL)',
      ]) this.sql.exec(statement);
    });
  }

  first(query, ...bindings) { return this.sql.exec(query, ...bindings).toArray()[0] ?? null; }

  /** One password hash at the configured cost, at most once a minute, to prove CPU limits allow it. */
  async selfTest(iterations) {
    if (this.lastSelfTest && Date.now() - this.lastSelfTest.at < 60_000) return this.lastSelfTest.result;
    const started = Date.now();
    await hashPassword(randomToken(), iterations);
    const result = { ok: true, iterations, hashMs: Date.now() - started };
    this.lastSelfTest = { at: Date.now(), result };
    return result;
  }

  async issueSession(userId) {
    const sessionId = crypto.randomUUID();
    const refreshToken = randomToken();
    const tokenHash = await sha256(refreshToken);
    const now = Date.now();
    this.ctx.storage.transactionSync(() => {
      this.sql.exec('INSERT INTO sessions (id, user_id, created_at, refreshed_at) VALUES (?, ?, ?, ?)', sessionId, userId, nowIso(), now);
      this.sql.exec('INSERT INTO refresh_tokens (token_hash, session_id, created_at) VALUES (?, ?, ?)', tokenHash, sessionId, now);
      this.sql.exec('UPDATE users SET last_sign_in_at = ? WHERE id = ?', nowIso(), userId);
    });
    return { sessionId, refreshToken, user: userJson(this.first('SELECT * FROM users WHERE id = ?', userId)) };
  }

  /**
   * `mode` is `approval` (the owner must approve by email), `auto` (local development only) or
   * `closed`. An approval request returns `{ pending, reviewToken? }`; the token is only returned
   * when the Worker should email the owner, so repeated requests cannot flood the inbox.
   */
  async signup({ email, password, metadata, iterations, mode, country, client }) {
    if (mode === 'closed') return authError(422, 'signup_disabled', 'Signups not allowed for this instance');
    const now = Date.now();
    this.sql.exec('DELETE FROM signup_requests WHERE requested_at < ?', now - REQUEST_LIFETIME_MS);
    const taken = () => (this.first('SELECT 1 AS taken FROM users WHERE email = ?', email)
      ? authError(422, 'user_already_exists', 'User already registered') : null);
    if (taken()) return taken();
    const existing = this.first('SELECT * FROM signup_requests WHERE email = ?', email);
    if (mode === 'approval' && !existing && this.first('SELECT COUNT(*) AS total FROM signup_requests').total >= MAX_PENDING_REQUESTS) {
      return authError(429, 'over_request_rate_limit', 'Too many sign-up requests are waiting for approval. Try again later.');
    }
    const passwordHash = await hashPassword(password, iterations);
    if (taken()) return taken(); // Another request may have finished while hashing.
    if (mode === 'auto') {
      const id = crypto.randomUUID();
      const created = nowIso();
      this.sql.exec('INSERT INTO users (id, email, password_hash, user_metadata, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)',
        id, email, passwordHash, JSON.stringify(metadata), created, created);
      return this.issueSession(id);
    }
    // At most one email per request per hour, and a global hourly cap, so nobody can flood the owner.
    const recentEmails = this.first('SELECT COUNT(*) AS total FROM signup_requests WHERE notified_at > ?', now - RENOTIFY_AFTER_MS).total;
    const notify = (!existing || now - existing.notified_at >= RENOTIFY_AFTER_MS) && recentEmails < MAX_EMAILS_PER_HOUR;
    const reviewToken = randomToken();
    const reviewHash = await sha256(reviewToken);
    // A repeated request keeps the first password: nobody else can swap it before the owner approves.
    this.sql.exec(`INSERT INTO signup_requests (email, id, password_hash, user_metadata, created_at, requested_at, review_token_hash, notified_at, country, client)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (email) DO UPDATE SET
        review_token_hash = CASE WHEN ? THEN excluded.review_token_hash ELSE review_token_hash END,
        notified_at = CASE WHEN ? THEN excluded.notified_at ELSE notified_at END`,
      email, crypto.randomUUID(), passwordHash, JSON.stringify(metadata), nowIso(), now, reviewHash,
      notify ? now : 0, country, client, notify ? 1 : 0, notify ? 1 : 0);
    const pending = pendingUserJson(this.first('SELECT * FROM signup_requests WHERE email = ?', email));
    return notify ? { pending, reviewToken } : { pending };
  }

  /** Lets a later sign-up attempt email the owner again when sending the approval email failed. */
  async notificationFailed(email) {
    this.sql.exec('UPDATE signup_requests SET notified_at = 0 WHERE email = ?', email);
  }

  async reviewRequest(reviewToken) {
    if (typeof reviewToken !== 'string' || reviewToken.length < 20 || reviewToken.length > 200) return null;
    const row = this.first('SELECT email, created_at, requested_at, country, client FROM signup_requests WHERE review_token_hash = ?', await sha256(reviewToken));
    if (!row || Date.now() - row.requested_at > REQUEST_LIFETIME_MS) return null;
    return row;
  }

  /** Approves (creating the account) or denies one request. Single use: the token stops working. */
  async decide(reviewToken, approve) {
    if (typeof reviewToken !== 'string' || reviewToken.length < 20 || reviewToken.length > 200) return null;
    const hash = await sha256(reviewToken);
    return this.ctx.storage.transactionSync(() => {
      const row = this.first('SELECT * FROM signup_requests WHERE review_token_hash = ?', hash);
      if (!row || Date.now() - row.requested_at > REQUEST_LIFETIME_MS) return null;
      this.sql.exec('DELETE FROM signup_requests WHERE email = ?', row.email);
      if (approve && !this.first('SELECT 1 AS taken FROM users WHERE email = ?', row.email)) {
        const now = nowIso();
        this.sql.exec('INSERT INTO users (id, email, password_hash, user_metadata, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)',
          row.id, row.email, row.password_hash, row.user_metadata, now, now);
      }
      return { email: row.email, approved: approve };
    });
  }

  async passwordLogin({ email, password, iterations }) {
    const now = Date.now();
    const failures = this.first('SELECT count, window_start FROM login_failures WHERE email = ?', email);
    if (failures && now - failures.window_start < FAILURE_WINDOW_MS && failures.count >= MAX_FAILURES) {
      return authError(429, 'over_request_rate_limit', 'Too many failed sign-in attempts. Try again in 15 minutes.');
    }
    const user = this.first('SELECT id, password_hash FROM users WHERE email = ?', email);
    const pending = user ? null : this.first('SELECT password_hash, requested_at FROM signup_requests WHERE email = ?', email);
    // Hash even for unknown accounts so response time does not reveal which emails exist.
    this.dummyHash ??= await hashPassword(randomToken(), iterations);
    const valid = await verifyPassword(password, user?.password_hash ?? pending?.password_hash ?? this.dummyHash);
    if (pending && valid && now - pending.requested_at <= REQUEST_LIFETIME_MS) {
      return authError(400, 'email_not_confirmed', PENDING_MESSAGE);
    }
    if (!user || !valid) {
      const fresh = !failures || now - failures.window_start >= FAILURE_WINDOW_MS;
      this.sql.exec(`INSERT INTO login_failures (email, count, window_start) VALUES (?, 1, ?)
        ON CONFLICT (email) DO UPDATE SET count = CASE WHEN ? THEN 1 ELSE count + 1 END, window_start = CASE WHEN ? THEN ? ELSE window_start END`,
        email, now, fresh ? 1 : 0, fresh ? 1 : 0, now);
      return authError(400, 'invalid_credentials', 'Invalid login credentials');
    }
    this.sql.exec('DELETE FROM login_failures WHERE email = ?', email);
    return this.issueSession(user.id);
  }

  async refresh({ refreshToken }) {
    const notFound = authError(400, 'refresh_token_not_found', 'Invalid Refresh Token: Refresh Token Not Found');
    if (typeof refreshToken !== 'string' || refreshToken.length < 20 || refreshToken.length > 200) return notFound;
    const tokenHash = await sha256(refreshToken);
    const next = randomToken();
    const nextHash = await sha256(next);
    const now = Date.now();
    const result = this.ctx.storage.transactionSync(() => {
      const row = this.first(`SELECT t.session_id, t.rotated_at, s.user_id, s.refreshed_at FROM refresh_tokens t JOIN sessions s ON s.id = t.session_id
        WHERE t.token_hash = ?`, tokenHash);
      if (!row || now - row.refreshed_at > SESSION_IDLE_MS) return notFound;
      if (row.rotated_at !== null && now - row.rotated_at > REFRESH_REUSE_GRACE_MS) {
        return authError(400, 'refresh_token_already_used', 'Invalid Refresh Token: Already Used');
      }
      this.sql.exec('UPDATE refresh_tokens SET rotated_at = COALESCE(rotated_at, ?) WHERE token_hash = ?', now, tokenHash);
      this.sql.exec('INSERT INTO refresh_tokens (token_hash, session_id, created_at) VALUES (?, ?, ?)', nextHash, row.session_id, now);
      this.sql.exec('UPDATE sessions SET refreshed_at = ? WHERE id = ?', now, row.session_id);
      // Rotated tokens past the reuse grace can never be accepted again.
      this.sql.exec('DELETE FROM refresh_tokens WHERE session_id = ? AND rotated_at IS NOT NULL AND rotated_at < ?', row.session_id, now - 60 * 60 * 1000);
      return { sessionId: row.session_id, userId: row.user_id };
    });
    if (result.error) return result;
    const user = this.first('SELECT * FROM users WHERE id = ?', result.userId);
    if (!user) return notFound;
    return { sessionId: result.sessionId, refreshToken: next, user: userJson(user) };
  }

  async getUser(userId) {
    const row = this.first('SELECT * FROM users WHERE id = ?', userId);
    return row ? userJson(row) : null;
  }

  async logout({ userId, sessionId, scope }) {
    const sessions = this.sql.exec('SELECT id FROM sessions WHERE user_id = ?', userId).toArray().map(row => row.id)
      .filter(id => (scope === 'global' ? true : scope === 'others' ? id !== sessionId : id === sessionId));
    this.ctx.storage.transactionSync(() => {
      for (const id of sessions) {
        this.sql.exec('DELETE FROM refresh_tokens WHERE session_id = ?', id);
        this.sql.exec('DELETE FROM sessions WHERE id = ?', id);
      }
    });
  }

  async updatePassword({ userId, password, iterations }) {
    const passwordHash = await hashPassword(password, iterations);
    this.sql.exec('UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?', passwordHash, nowIso(), userId);
    return this.getUser(userId);
  }
}
