// Starlit account sync on Cloudflare Workers. It speaks the subset of Supabase Auth (GoTrue) and
// PostgREST that the Starlit clients use, so desktop and mobile need no special client code.
import { FUNCTIONS, resolveArguments } from './user-data.mjs';
import { PgError, pgBody, signJwt, verifyJwt, looksLikeJwt } from './shared.mjs';
import { sendApprovalEmail, reviewPage, escapeHtml } from './approval.mjs';

export { AccountsDO } from './accounts.mjs';
export { UserDataDO } from './user-data.mjs';

const DEFAULT_PUBLISHABLE_KEY = 'sb_publishable_starlit_cloudflare';
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MIN_PASSWORD = 12;
const MAX_PASSWORD = 256;
const MAX_BODY_BYTES = 2 * 1024 * 1024;
const TABLES = ['addons', 'plugins'];
// Upstream Nuvio membership and catalog calls. An empty list means "no membership, no catalog" to
// the clients, which otherwise retry on 404 and spend the free daily request quota.
const EMPTY_LIST_FUNCTIONS = ['get_my_member_access', 'get_my_membership_overview', 'get_avatar_catalog',
  'get_member_profile_avatar_catalog', 'get_member_profile_background_catalog'];
const TABLE_COLUMNS = ['user_id', 'profile_id', 'url', 'name', 'enabled', 'sort_order'];

const jsonResponse = (status, body, headers = {}) => new Response(body === null || body === undefined ? null : JSON.stringify(body), {
  status,
  headers: { ...(body === null || body === undefined ? {} : { 'Content-Type': 'application/json; charset=utf-8' }), 'X-Content-Type-Options': 'nosniff', ...headers },
});
const pgResponse = (status, code, message) => jsonResponse(status, pgBody(code, message));
const authResponse = ({ status, error_code, msg, ...extra }) => jsonResponse(status, { code: status, error_code, msg, ...extra }, { 'X-Sb-Error-Code': error_code });

const LOCAL_HOSTS = ['127.0.0.1', 'localhost', '[::1]'];

function settings(env, url) {
  const iterations = Number(env.PASSWORD_HASH_ITERATIONS || 100000);
  let signupMode = String(env.SIGNUP_MODE || 'approval').toLowerCase();
  if (!['approval', 'auto', 'closed'].includes(signupMode)) signupMode = 'approval';
  // Instant sign-up exists for local tests only; a deployed server always asks the owner.
  if (signupMode === 'auto' && !LOCAL_HOSTS.includes(url.hostname)) signupMode = 'approval';
  return {
    iterations: Number.isInteger(iterations) && iterations >= 10000 && iterations <= 100000 ? iterations : 100000,
    accessTokenSeconds: Math.min(Math.max(Number(env.ACCESS_TOKEN_SECONDS || 3600), 300), 86400),
    publishableKey: (env.PUBLISHABLE_KEY || DEFAULT_PUBLISHABLE_KEY).trim(),
    signupMode,
  };
}

const accounts = env => env.ACCOUNTS.get(env.ACCOUNTS.idFromName('accounts'));
const userData = (env, userId) => env.USER_DATA.get(env.USER_DATA.idFromName(userId));

async function readJson(request) {
  const raw = await request.text();
  if (raw.length > MAX_BODY_BYTES) throw new PgError('PGRST102', 'Request body is too large', 413);
  if (!raw.trim()) return {};
  try {
    return JSON.parse(raw);
  } catch {
    throw new PgError('PGRST102', 'Empty or invalid json');
  }
}

async function sessionBody(env, origin, { user, sessionId, refreshToken }) {
  const { accessTokenSeconds } = settings(env, new URL(origin));
  const now = Math.floor(Date.now() / 1000);
  const claims = {
    aud: 'authenticated', exp: now + accessTokenSeconds, iat: now, iss: `${origin}/auth/v1`, sub: user.id, email: user.email, phone: '',
    app_metadata: user.app_metadata, user_metadata: user.user_metadata, role: 'authenticated', aal: 'aal1',
    amr: [{ method: 'password', timestamp: now }], session_id: sessionId, is_anonymous: false,
  };
  return {
    access_token: await signJwt(claims, env.JWT_SECRET), token_type: 'bearer', expires_in: accessTokenSeconds,
    expires_at: now + accessTokenSeconds, refresh_token: refreshToken, user,
  };
}

/** Resolves the caller from the bearer token. Publishable keys (and no token) mean the anon role. */
async function caller(request, env) {
  const header = request.headers.get('Authorization') || '';
  const token = /^bearer\s+/i.test(header) ? header.replace(/^bearer\s+/i, '').trim() : '';
  if (!token || !looksLikeJwt(token)) return { role: 'anon' };
  const claims = await verifyJwt(token, env.JWT_SECRET);
  if (!claims) return { failure: pgResponse(401, 'PGRST301', 'JWT could not be verified') };
  if (claims.expired) return { failure: pgResponse(401, 'PGRST303', 'JWT expired') };
  if (claims.role !== 'authenticated' || typeof claims.sub !== 'string' || claims.is_anonymous) return { role: 'anon' };
  return { role: 'authenticated', userId: claims.sub, sessionId: claims.session_id };
}

function credentialsFrom(body) {
  const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
  const password = typeof body.password === 'string' ? body.password : '';
  return { email, password };
}

async function handleAuth(request, env, ctx, url, origin) {
  const path = url.pathname.replace(/^\/auth\/v1/, '').replace(/\/$/, '') || '/';
  const config = settings(env, url);
  if (request.method === 'GET' && path === '/health') return jsonResponse(200, { version: 'starlit-cloudflare', name: 'GoTrue', description: 'Starlit account service' });
  if (request.method === 'GET' && path === '/settings') {
    return jsonResponse(200, {
      external: { email: true, phone: false, anonymous_users: false },
      disable_signup: config.signupMode === 'closed', mailer_autoconfirm: config.signupMode === 'auto', phone_autoconfirm: false, sms_provider: '', saml_enabled: false,
    });
  }
  if (request.method === 'POST' && path === '/signup') {
    const body = await readJson(request);
    const { email, password } = credentialsFrom(body);
    if (!EMAIL.test(email) || email.length > 254) return authResponse({ status: 400, error_code: 'validation_failed', msg: 'Unable to validate email address: invalid format' });
    if (password.length < MIN_PASSWORD || password.length > MAX_PASSWORD) {
      return authResponse({ status: 422, error_code: 'weak_password', msg: `Password should be between ${MIN_PASSWORD} and ${MAX_PASSWORD} characters.`, weak_password: { reasons: ['length'] } });
    }
    const metadata = body.data && typeof body.data === 'object' && !Array.isArray(body.data) && JSON.stringify(body.data).length <= 16384 ? body.data : {};
    if (config.signupMode === 'approval' && !env.APPROVAL_EMAIL) {
      return authResponse({ status: 503, error_code: 'signup_disabled', msg: 'Sign-up approval email is not configured on this server' });
    }
    const result = await accounts(env).signup({
      email, password, metadata, iterations: config.iterations, mode: config.signupMode,
      country: typeof request.cf?.country === 'string' ? request.cf.country : null,
      client: (request.headers.get('User-Agent') || '').slice(0, 120) || null,
    });
    if (result.error) return authResponse(result.error);
    if (!result.pending) return jsonResponse(200, await sessionBody(env, origin, result));
    if (result.reviewToken) {
      // Sent after the response, so neither its duration nor a failure reveals anything about the
      // request. A failed send lets the next same-password request email the owner again.
      ctx.waitUntil(sendApprovalEmail(env, {
        reviewUrl: `${origin}/admin/signups/review?token=${encodeURIComponent(result.reviewToken)}`,
        email, requestedAt: Date.now(), country: request.cf?.country, client: request.headers.get('User-Agent'),
      }).catch(() => accounts(env).notificationFailed(email)));
    }
    // GoTrue's "confirmation required" shape: the account exists but has no session yet.
    return jsonResponse(200, result.pending);
  }
  if (request.method === 'POST' && path === '/token') {
    const body = await readJson(request);
    const grant = url.searchParams.get('grant_type');
    let result;
    if (grant === 'password') {
      const { email, password } = credentialsFrom(body);
      if (!email || !password) return authResponse({ status: 400, error_code: 'validation_failed', msg: 'Missing email or password' });
      result = await accounts(env).passwordLogin({ email, password, iterations: config.iterations });
    } else if (grant === 'refresh_token') {
      result = await accounts(env).refresh({ refreshToken: body.refresh_token });
    } else {
      return authResponse({ status: 400, error_code: 'unsupported_grant_type', msg: 'Unsupported grant type' });
    }
    return result.error ? authResponse(result.error) : jsonResponse(200, await sessionBody(env, origin, result));
  }
  if (path === '/user' && (request.method === 'GET' || request.method === 'PUT')) {
    const who = await caller(request, env);
    if (who.role !== 'authenticated') return authResponse({ status: 401, error_code: 'no_authorization', msg: 'This endpoint requires a valid Bearer token' });
    if (request.method === 'PUT') {
      const body = await readJson(request);
      if (body.email !== undefined || body.phone !== undefined) return authResponse({ status: 422, error_code: 'email_change_not_supported', msg: 'Changing the account email is not supported by this server' });
      if (typeof body.password === 'string') {
        if (body.password.length < MIN_PASSWORD || body.password.length > MAX_PASSWORD) {
          return authResponse({ status: 422, error_code: 'weak_password', msg: `Password should be between ${MIN_PASSWORD} and ${MAX_PASSWORD} characters.`, weak_password: { reasons: ['length'] } });
        }
        const user = await accounts(env).updatePassword({ userId: who.userId, password: body.password, iterations: config.iterations });
        if (user) return jsonResponse(200, user);
      }
    }
    const user = await accounts(env).getUser(who.userId);
    return user ? jsonResponse(200, user) : authResponse({ status: 404, error_code: 'user_not_found', msg: 'User not found' });
  }
  if (request.method === 'POST' && path === '/logout') {
    const who = await caller(request, env);
    if (who.role !== 'authenticated') return authResponse({ status: 401, error_code: 'no_authorization', msg: 'This endpoint requires a valid Bearer token' });
    const scope = ['global', 'others'].includes(url.searchParams.get('scope')) ? url.searchParams.get('scope') : 'local';
    await accounts(env).logout({ userId: who.userId, sessionId: who.sessionId, scope });
    return jsonResponse(204, null);
  }
  if (request.method === 'POST' && (path === '/recover' || path === '/otp' || path === '/magiclink' || path === '/resend')) {
    return authResponse({ status: 501, error_code: 'email_provider_disabled', msg: 'This server does not send email. Sign in with your password.' });
  }
  return authResponse({ status: 404, error_code: 'not_found', msg: 'Unsupported authentication endpoint' });
}

/**
 * The page behind the link in the owner's approval email. Viewing it changes nothing: approval
 * needs a POST from its button, so mail scanners that prefetch links cannot approve requests.
 */
async function handleReview(request, env, url) {
  const gone = reviewPage({ title: 'Request not found', status: 404, body: '<h1>Request not found</h1><p>This sign-up request was already handled or has expired.</p>' });
  if (request.method === 'GET') {
    const token = url.searchParams.get('token') || '';
    const row = await accounts(env).reviewRequest(token);
    if (!row) return gone;
    return reviewPage({
      title: 'Approve Starlit account?',
      body: `<h1>Approve this account?</h1><p>Approving lets this person sign in and sync with your Starlit server.</p>
<dl><dt>Email</dt><dd>${escapeHtml(row.email)}</dd><dt>Requested</dt><dd>${escapeHtml(new Date(row.requested_at).toUTCString())}</dd>
<dt>Country</dt><dd>${escapeHtml(row.country || 'Unknown')}</dd><dt>App</dt><dd>${escapeHtml(row.client || 'Unknown')}</dd></dl>
<form method="post"><input type="hidden" name="token" value="${escapeHtml(token)}">
<button name="decision" value="approve">Approve</button><button class="deny" name="decision" value="deny">Deny</button></form>`,
    });
  }
  if (request.method !== 'POST') return reviewPage({ title: 'Not allowed', status: 405, body: '<h1>Not allowed</h1>' });
  const form = await request.formData().catch(() => null);
  const decision = form?.get('decision');
  if (!form || !['approve', 'deny'].includes(decision)) return reviewPage({ title: 'Invalid request', status: 400, body: '<h1>Invalid request</h1>' });
  const result = await accounts(env).decide(String(form.get('token') || ''), decision === 'approve');
  if (!result) return gone;
  return reviewPage({
    title: result.approved ? 'Account approved' : 'Request denied',
    body: result.approved
      ? `<h1>Account approved</h1><p>${escapeHtml(result.email)} can now sign in to Starlit with the password they chose.</p>`
      : `<h1>Request denied</h1><p>No account was created for ${escapeHtml(result.email)}.</p>`,
  });
}

function openApi(origin) {
  const paths = { '/': { get: { summary: 'OpenAPI description (this document)' } } };
  for (const table of TABLES) paths[`/${table}`] = { get: { summary: `Rows of ${table} for the signed-in account` } };
  for (const [name, fn] of Object.entries(FUNCTIONS)) {
    const properties = Object.fromEntries(Object.entries(fn.params).map(([key, spec]) => [key, { format: spec.type, type: spec.type === 'jsonb' ? 'object' : spec.type === 'text' ? 'string' : 'integer' }]));
    const required = Object.entries(fn.params).filter(([, spec]) => !('default' in spec)).map(([key]) => key);
    paths[`/rpc/${name}`] = { post: { parameters: [{ in: 'body', name: 'args', required: true, schema: { type: 'object', required, properties } }] } };
  }
  return { swagger: '2.0', info: { title: 'Starlit sync', version: '1' }, host: new URL(origin).host, basePath: '/rest/v1', schemes: [new URL(origin).protocol.replace(':', '')], paths };
}

async function handleRest(request, env, url, origin) {
  const profile = request.headers.get('Accept-Profile') || request.headers.get('Content-Profile');
  if (profile && profile !== 'public') return pgResponse(406, 'PGRST106', 'The schema must be one of the following: public');
  const path = url.pathname.replace(/^\/rest\/v1/, '').replace(/\/$/, '') || '/';
  if (path === '/') {
    if (request.method !== 'GET' && request.method !== 'HEAD') return pgResponse(405, 'PGRST117', 'Unsupported HTTP method');
    return jsonResponse(200, openApi(origin), { 'Content-Type': 'application/openapi+json; charset=utf-8' });
  }
  const rpc = path.match(/^\/rpc\/([A-Za-z0-9_]+)$/);
  if (rpc) {
    let body;
    if (request.method === 'POST') body = await readJson(request);
    else if (request.method === 'GET' || request.method === 'HEAD') body = Object.fromEntries(url.searchParams);
    else return pgResponse(405, 'PGRST117', 'Unsupported HTTP method');
    if (!body || typeof body !== 'object' || Array.isArray(body)) return pgResponse(400, 'PGRST102', 'Expected a JSON object of named arguments');
    if (EMPTY_LIST_FUNCTIONS.includes(rpc[1]) && Object.keys(body).length === 0) {
      const who = await caller(request, env);
      if (who.failure) return who.failure;
      if (who.role !== 'authenticated') return pgResponse(401, '42501', `permission denied for function ${rpc[1]}`);
      return jsonResponse(200, []);
    }
    const { args } = resolveArguments(rpc[1], body);
    const who = await caller(request, env);
    if (who.failure) return who.failure;
    if (who.role !== 'authenticated') return pgResponse(401, '42501', `permission denied for function ${rpc[1]}`);
    const result = await userData(env, who.userId).call(who.userId, rpc[1], args);
    return jsonResponse(result.status, result.status === 204 ? null : result.body);
  }
  const table = path.match(/^\/([A-Za-z0-9_]+)$/)?.[1];
  if (!table || !TABLES.includes(table)) return pgResponse(404, 'PGRST205', `Could not find the table 'public.${table ?? path}' in the schema cache`);
  const who = await caller(request, env);
  if (who.failure) return who.failure;
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return pgResponse(who.role === 'authenticated' ? 403 : 401, '42501', `permission denied for table ${table}`);
  }
  if (who.role !== 'authenticated') return pgResponse(401, '42501', `permission denied for table ${table}`);
  const rows = selectRows(await userData(env, who.userId).extensionRows(who.userId, table), url.searchParams, table);
  return jsonResponse(200, request.method === 'HEAD' ? null : rows);
}

/** The PostgREST read features clients use on addons/plugins: select, filters, order, limit/offset. */
function selectRows(rows, params, table) {
  const column = name => {
    if (!TABLE_COLUMNS.includes(name)) throw new PgError('42703', `column ${table}.${name} does not exist`);
    return name;
  };
  const compare = (a, b) => (typeof a === 'number' && typeof b === 'number' ? a - b : String(a).localeCompare(String(b)));
  for (const [key, raw] of params) {
    if (['select', 'order', 'limit', 'offset'].includes(key)) continue;
    const name = column(key);
    const [, negate, op, operand] = raw.match(/^(not\.)?(eq|neq|gt|gte|lt|lte|is|in)\.(.*)$/s) ?? [];
    if (!op) throw new PgError('PGRST100', `"failed to parse filter (${raw})" (line 1, column 1)`);
    const matches = row => {
      const value = row[name];
      const text = value === null ? 'null' : String(value);
      switch (op) {
        case 'eq': return text === operand;
        case 'neq': return text !== operand;
        case 'gt': return compare(value, typeof value === 'number' ? Number(operand) : operand) > 0;
        case 'gte': return compare(value, typeof value === 'number' ? Number(operand) : operand) >= 0;
        case 'lt': return compare(value, typeof value === 'number' ? Number(operand) : operand) < 0;
        case 'lte': return compare(value, typeof value === 'number' ? Number(operand) : operand) <= 0;
        case 'is': return text === operand.toLowerCase();
        case 'in': return operand.replace(/^\(|\)$/g, '').split(',').map(x => x.trim().replace(/^"|"$/g, '')).includes(text);
        default: return false;
      }
    };
    rows = rows.filter(row => (negate ? !matches(row) : matches(row)));
  }
  const order = params.get('order');
  if (order) {
    const keys = order.split(',').map(part => {
      const [name, ...modifiers] = part.split('.');
      return { name: column(name), descending: modifiers.includes('desc') };
    });
    rows = [...rows].sort((a, b) => {
      for (const { name, descending } of keys) {
        const difference = compare(a[name], b[name]);
        if (difference !== 0) return descending ? -difference : difference;
      }
      return 0;
    });
  }
  const offset = Math.max(Number(params.get('offset') || 0) || 0, 0);
  const limit = params.has('limit') ? Math.max(Number(params.get('limit')) || 0, 0) : undefined;
  rows = rows.slice(offset, limit === undefined ? undefined : offset + limit);
  const select = params.get('select');
  if (select && select.trim() !== '*') {
    const names = select.split(',').map(x => column(x.trim()));
    rows = rows.map(row => Object.fromEntries(names.map(name => [name, row[name]])));
  }
  return rows;
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const origin = url.origin;
    try {
      if (!env.JWT_SECRET || env.JWT_SECRET.length < 32) return jsonResponse(503, { error: 'server_not_configured', message: 'JWT_SECRET is missing' });
      if (url.pathname === '/.well-known/nuvio') {
        if (request.method !== 'GET' && request.method !== 'HEAD') return jsonResponse(405, null, { Allow: 'GET, HEAD' });
        return jsonResponse(200, {
          version: 1,
          service: 'nuvio', // Existing client protocol discriminator; this is Starlit's independent backend.
          self_hosted: true,
          backend_url: origin,
          publishable_key: settings(env, url).publishableKey,
          capabilities: { email_password_auth: true, tv_login: false },
        }, { 'Cache-Control': 'public, max-age=60', 'Access-Control-Allow-Origin': '*' });
      }
      if (url.pathname === '/healthz' && url.searchParams.get('deep') === '1') {
        const check = await accounts(env).selfTest(settings(env, url).iterations);
        return jsonResponse(200, { status: 'ok', service: 'starlit-sync', password_hash_ms: check.hashMs, iterations: check.iterations });
      }
      if (url.pathname === '/' || url.pathname === '/healthz') return jsonResponse(200, { status: 'ok', service: 'starlit-sync' });
      if (url.pathname === '/admin/signups/review') return await handleReview(request, env, url);
      if (url.pathname.startsWith('/auth/v1/')) return await handleAuth(request, env, ctx, url, origin);
      if (url.pathname === '/rest/v1' || url.pathname.startsWith('/rest/v1/')) return await handleRest(request, env, url, origin);
      if (url.pathname.startsWith('/functions/v1/')) return jsonResponse(404, { code: 'NOT_FOUND', message: 'Requested function was not found' });
      if (url.pathname.startsWith('/storage/v1/')) return jsonResponse(404, { statusCode: '404', error: 'not_found', message: 'File storage is not available on this server' });
      return jsonResponse(404, { error: 'not_found' });
    } catch (error) {
      if (error instanceof PgError) return pgResponse(error.status, error.code, error.message);
      console.error('Unhandled request failure', error?.name, error?.message);
      return pgResponse(500, 'XX000', 'Internal server error');
    }
  },
};
