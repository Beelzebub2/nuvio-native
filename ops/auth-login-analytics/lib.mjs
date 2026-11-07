import { BlockList, isIP } from 'node:net';
import { open as openFile } from 'node:fs/promises';

export const RETENTION_MS = 30 * 24 * 60 * 60 * 1000;
const ZERO_UUID = '00000000-0000-0000-0000-000000000000';
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const REQUEST_ID_RE = /^[0-9a-f]{32}$/i;
const MAX_LOG_READ = 256 * 1024;
const reservations = new BlockList();

for (const [ip, mask] of [
  ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10],
  ['127.0.0.0', 8], ['169.254.0.0', 16], ['172.16.0.0', 12],
  ['192.0.0.0', 24], ['192.0.2.0', 24], ['192.168.0.0', 16],
  ['198.18.0.0', 15], ['198.51.100.0', 24], ['203.0.113.0', 24],
  ['224.0.0.0', 4], ['240.0.0.0', 4],
]) reservations.addSubnet(ip, mask, 'ipv4');
for (const [ip, mask] of [
  ['::', 128], ['::1', 128], ['fc00::', 7], ['fe80::', 10],
  ['ff00::', 8], ['2001:db8::', 32],
]) reservations.addSubnet(ip, mask, 'ipv6');

export function publicClientIp(value) {
  if (typeof value !== 'string' || value.length > 64) return null;
  let ip = value.trim();
  if (/^::ffff:/i.test(ip) && isIP(ip.slice(7)) === 4) ip = ip.slice(7);
  const family = isIP(ip);
  if (!family || reservations.check(ip, family === 4 ? 'ipv4' : 'ipv6')) return null;
  return ip;
}

function cleanLocationPart(value) {
  if (typeof value !== 'string') return null;
  const part = value.replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, 80);
  return part || null;
}

export function coarseGeoLocation(record) {
  if (!record || typeof record !== 'object') return null;
  const parts = [
    cleanLocationPart(record.city?.names?.en),
    cleanLocationPart(record.subdivisions?.[0]?.names?.en),
    cleanLocationPart(record.country?.names?.en),
  ].filter(Boolean);
  return parts.length ? [...new Set(parts)].join(', ').slice(0, 240) : null;
}

export function auditIngestUrl(base) {
  const url = new URL(base);
  const privateHost = ['127.0.0.1', 'localhost', '[::1]', 'openobserve', 'host.docker.internal'].includes(url.hostname);
  if (!(url.protocol === 'https:' || (url.protocol === 'http:' && privateHost)) ||
      url.username || url.password || url.search || url.hash ||
      !/^\/api\/[a-zA-Z0-9_-]+\/starlit_(?:usage|auth)\/_json$/.test(url.pathname)) {
    throw new Error('Untrusted OpenObserve destination');
  }
  url.pathname = url.pathname.replace(/\/starlit_(?:usage|auth)\/_json$/, '/starlit_auth/_json');
  return url.toString();
}

function timestamp(value, now) {
  const date = value instanceof Date ? value : new Date(value);
  const ms = date.getTime();
  return Number.isSafeInteger(ms) && ms > 0 && ms >= now - RETENTION_MS &&
    ms <= now + 5 * 60_000 ? ms : null;
}

function annotate(eventId, at, method, outcome, source, ipValue, geoLookup, statusCode, now) {
  const ms = timestamp(at, now);
  if (ms === null) return null;
  const ip = publicClientIp(ipValue);
  let location = null;
  if (ip) {
    try { location = cleanLocationPart(geoLookup(ip)); } catch { /* GeoIP is optional. */ }
  }
  return {
    _timestamp: ms * 1000,
    app: 'starlit', schema_version: 1, event: 'auth_login',
    event_id: eventId,
    outcome,
    login_method: method,
    source,
    ip_address: ip,
    approximate_location: location,
    http_status: statusCode,
    event_time: new Date(ms).toISOString(),
  };
}

const SUCCESS_METHODS = new Set(['email', 'google', 'apple', 'github', 'passkey', 'sso']);
const FAILURE_METHODS = new Map([
  ['password', 'email'], ['otp', 'otp'], ['pkce', 'oauth_pkce'],
  ['id_token', 'id_token'], ['webauthn', 'passkey'], ['passkey', 'passkey'],
]);

export function successfulLogin(row, geoLookup = () => null, now = Date.now()) {
  if (!UUID_RE.test(row?.event_id || '')) return null;
  const method = SUCCESS_METHODS.has(row.login_method) ? row.login_method : 'unknown';
  return annotate('success:' + row.event_id.toLowerCase(), row.login_at, method,
    'success', 'gotrue_audit', row.ip_address, geoLookup, null, now);
}

export function failedLogin(row, geoLookup = () => null, now = Date.now()) {
  if (!REQUEST_ID_RE.test(row?.request_id || '') || !Number.isInteger(Number(row.status)) ||
      Number(row.status) < 400 || Number(row.status) > 499 ||
      !FAILURE_METHODS.has(row.grant)) return null;
  return annotate('failed:' + row.request_id.toLowerCase(), row.at,
    FAILURE_METHODS.get(row.grant), 'failure', 'gateway_token_response', row.ip,
    geoLookup, Number(row.status), now);
}

export class AuthAuditReporter {
  constructor({ ingestUrl, authorization, fetcher = fetch }) {
    this.ingestUrl = auditIngestUrl(ingestUrl);
    if (!/^Basic [A-Za-z0-9+/]+={0,2}$/.test(authorization || '')) {
      throw new Error('A dedicated OpenObserve ingestion authorization is required');
    }
    this.authorization = authorization;
    this.fetcher = fetcher;
  }

  async send(records) {
    if (!records.length) return;
    const response = await this.fetcher(this.ingestUrl, {
      method: 'POST',
      headers: { Authorization: this.authorization, 'Content-Type': 'application/json' },
      body: JSON.stringify(records),
      signal: AbortSignal.timeout(4000),
      redirect: 'error',
    });
    if (!response.ok) throw new Error('Auth audit ingestion unavailable');
    const ack = await response.json();
    const entries = ack?.status;
    const accepted = Array.isArray(entries) &&
      entries.reduce((count, entry) => count + Number(entry.successful || 0), 0) === records.length &&
      entries.every(entry => entry.failed === 0);
    if (ack?.code !== 200 || !accepted) {
      throw new Error('Auth audit ingestion did not accept every record');
    }
  }
}

export async function exportSuccessfulLogins(db, reporter, geoLookup = () => null, now = Date.now()) {
  const state = (await db.query(
    "SELECT cursor_time, cursor_id FROM starlit_analytics.export_cursor WHERE source='success'",
  )).rows[0];
  if (!state) throw new Error('Missing success audit cursor');
  const rows = (await db.query(
    'SELECT event_id,login_at,login_method,ip_address FROM starlit_analytics.login_successes($1,$2,$3)',
    [state.cursor_time, state.cursor_id || ZERO_UUID, 100],
  )).rows;
  if (!rows.length) return 0;
  const records = rows.map(row => successfulLogin(row, geoLookup, now)).filter(Boolean);
  // Never advance the cursor if OpenObserve fails or accepts only part of a batch.
  await reporter.send(records);
  const last = rows.at(-1);
  await db.query(
    "UPDATE starlit_analytics.export_cursor SET cursor_time=$1,cursor_id=$2,updated_at=now() WHERE source='success'",
    [last.login_at, last.event_id],
  );
  return records.length;
}

export async function exportFailedLogins(db, reporter, path, geoLookup = () => null,
  now = Date.now(), open = openFile) {
  const state = (await db.query(
    "SELECT file_inode,file_offset FROM starlit_analytics.export_cursor WHERE source='failure'",
  )).rows[0];
  if (!state) throw new Error('Missing failure audit cursor');
  const handle = await open(path, 'r');
  try {
    const st = await handle.stat();
    const inode = String(st.dev) + ':' + String(st.ino);
    const oldOffset = Number(state.file_offset || 0);
    const offset = state.file_inode === inode && oldOffset <= st.size ? oldOffset : 0;
    const count = Math.min(MAX_LOG_READ, Math.max(0, st.size - offset));
    if (count === 0) {
      if (state.file_inode !== inode || oldOffset !== offset) {
        await db.query(
          "UPDATE starlit_analytics.export_cursor SET file_inode=$1,file_offset=$2,updated_at=now() WHERE source='failure'",
          [inode, offset],
        );
      }
      return 0;
    }
    const data = Buffer.alloc(count);
    const { bytesRead } = await handle.read(data, 0, count, offset);
    const lastBreak = data.lastIndexOf(10, bytesRead - 1);
    if (lastBreak < 0) {
      if (bytesRead >= MAX_LOG_READ) throw new Error('Overlong gateway audit record');
      return 0;
    }
    const records = [];
    for (const line of data.subarray(0, lastBreak + 1).toString('utf8').split('\n')) {
      if (!line.trim()) continue;
      let parsed;
      try { parsed = JSON.parse(line); } catch { continue; }
      const record = failedLogin(parsed, geoLookup, now);
      if (record) records.push(record);
    }
    await reporter.send(records);
    await db.query(
      "UPDATE starlit_analytics.export_cursor SET file_inode=$1,file_offset=$2,updated_at=now() WHERE source='failure'",
      [inode, offset + lastBreak + 1],
    );
    return records.length;
  } finally {
    await handle.close();
  }
}
