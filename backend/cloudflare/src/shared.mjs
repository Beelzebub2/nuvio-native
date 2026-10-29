// Helpers shared by the Worker and its Durable Objects. No secrets or account data are logged.

const encoder = new TextEncoder();

/** A PostgreSQL/PostgREST error carried back to the client in PostgREST's error shape. */
export class PgError extends Error {
  constructor(code, message, status = statusForCode(code)) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

export function statusForCode(code) {
  if (code === '42501') return 403;
  if (code === '0A000') return 501;
  if (code === 'PGRST202' || code === 'PGRST205') return 404;
  if (code === 'PGRST106') return 406;
  return 400;
}

export const pgBody = (code, message) => ({ code, details: null, hint: null, message });

export function jsonType(value) {
  if (value === null || value === undefined) return 'null';
  if (Array.isArray(value)) return 'array';
  return typeof value === 'object' ? 'object' : typeof value;
}

export const byteLength = value => encoder.encode(typeof value === 'string' ? value : JSON.stringify(value)).length;

/** Key-order independent JSON, so equal jsonb values compare equal as text. */
export function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value ?? null);
}

/** PostgreSQL jsonb_strip_nulls: drop null object fields at every depth. */
export function stripNulls(value) {
  if (Array.isArray(value)) return value.map(stripNulls);
  if (value && typeof value === 'object') {
    const out = {};
    for (const [key, item] of Object.entries(value)) if (item !== null) out[key] = stripNulls(item);
    return out;
  }
  return value;
}

/** PostgreSQL `->>`: text for scalars, JSON text for containers, SQL NULL for JSON null/missing. */
export function textOf(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') return value;
  return typeof value === 'object' ? canonicalJson(value) : String(value);
}

export const nowIso = () => new Date().toISOString();

export function base64UrlEncode(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function base64UrlDecode(text) {
  const padded = text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (text.length % 4)) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, char => char.charCodeAt(0));
}

export const randomToken = (bytes = 32) => base64UrlEncode(crypto.getRandomValues(new Uint8Array(bytes)));

export async function sha256(text) {
  return base64UrlEncode(new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(text))));
}

const hmacKeys = new Map();
async function hmacKey(secret) {
  let key = hmacKeys.get(secret);
  if (!key) {
    key = await crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
    hmacKeys.set(secret, key);
  }
  return key;
}

export async function signJwt(claims, secret) {
  const header = base64UrlEncode(encoder.encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' })));
  const payload = base64UrlEncode(encoder.encode(JSON.stringify(claims)));
  const signature = await crypto.subtle.sign('HMAC', await hmacKey(secret), encoder.encode(`${header}.${payload}`));
  return `${header}.${payload}.${base64UrlEncode(new Uint8Array(signature))}`;
}

/** Returns claims for a valid, unexpired HS256 token; `null` for anything else. */
export async function verifyJwt(token, secret, nowSeconds = Math.floor(Date.now() / 1000)) {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    const header = JSON.parse(new TextDecoder().decode(base64UrlDecode(parts[0])));
    if (header.alg !== 'HS256') return null;
    const valid = await crypto.subtle.verify('HMAC', await hmacKey(secret), base64UrlDecode(parts[2]), encoder.encode(`${parts[0]}.${parts[1]}`));
    if (!valid) return null;
    const claims = JSON.parse(new TextDecoder().decode(base64UrlDecode(parts[1])));
    if (typeof claims.exp !== 'number' || claims.exp <= nowSeconds) return { expired: true };
    return claims;
  } catch {
    return null;
  }
}

export const looksLikeJwt = token => token.split('.').length === 3;
