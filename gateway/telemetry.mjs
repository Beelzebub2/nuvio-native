import { createHmac, timingSafeEqual } from 'node:crypto';
import { mkdir, readFile, readdir, rename, unlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

// An allowlist, not a generic analytics event collector. Extra payload fields are rejected.
export const TELEMETRY_EVENTS = Object.freeze([
  'app_open', 'screen_home', 'screen_search', 'screen_library',
  'screen_settings', 'playback_opened', 'playback_ready', 'playback_failed',
  'download_queued', 'download_completed', 'download_failed',
]);
const events = new Set(TELEMETRY_EVENTS);
const platforms = new Set(['android', 'desktop', 'ios']);
const MAX_BODY_BYTES = 1024;
const MAX_DAILY_EVENTS = 100_000;
const MAX_DAILY_INSTALLATIONS = 20_000;
const DAY_MS = 24 * 60 * 60 * 1000;
const jsonHeaders = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' };

function json(response, status, body) {
  response.writeHead(status, jsonHeaders);
  response.end(JSON.stringify(body));
}

export function validateTelemetryEvent(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      Object.keys(value).sort().join(',') !== 'event,installationId,platform,version') return false;
  return typeof value.installationId === 'string' &&
    /^[0-9a-f]{32}$/.test(value.installationId) &&
    typeof value.version === 'string' &&
    /^\d{1,3}\.\d{1,3}\.\d{1,3}(?:[-+][a-zA-Z0-9.-]{1,24})?$/.test(value.version) &&
    platforms.has(value.platform) && events.has(value.event);
}

function authorized(header, secret) {
  if (typeof header !== 'string' || secret.length < 24 || !header.startsWith('Bearer ')) return false;
  const provided = Buffer.from(header.slice(7));
  const expected = Buffer.from(secret);
  return provided.length === expected.length && timingSafeEqual(provided, expected);
}

function dateKey(now) {
  return new Date(now).toISOString().slice(0, 10);
}

function newDay(day) {
  return { day, eventCount: 0, groups: {} };
}

/** Anonymous, bounded, on-disk rollups. No IP addresses, cookies or raw IDs are persisted. */
export class TelemetryStore {
  constructor({ directory, hashKey, clock = Date.now } = {}) {
    this.directory = directory;
    this.hashKey = hashKey || '';
    this.clock = clock;
    this.days = new Map();
    this.pending = Promise.resolve();
    this.rate = new Map();
  }

  get isConfigured() {
    return Boolean(this.directory) && this.hashKey.length >= 32;
  }

  hash(id) {
    return createHmac('sha256', this.hashKey).update(id).digest('hex');
  }

  async loadDay(day) {
    if (this.days.has(day)) return this.days.get(day);
    let payload = newDay(day);
    try {
      const parsed = JSON.parse(await readFile(join(this.directory, day + '.json'), 'utf8'));
      if (parsed.day === day && Number.isSafeInteger(parsed.eventCount) &&
          parsed.groups && typeof parsed.groups === 'object' && !Array.isArray(parsed.groups)) payload = parsed;
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    this.days.set(day, payload);
    return payload;
  }

  async record(event) {
    // Serial writes prevent overlapping requests losing events; no data enters the response cache.
    const task = this.pending.catch(() => {}).then(async () => {
      const now = this.clock();
      const day = dateKey(now);
      const hash = this.hash(event.installationId);
      const minute = Math.floor(now / 60_000);
      const previous = this.rate.get(hash);
      const used = previous?.minute === minute ? previous.count : 0;
      if (used >= 30) return { status: 429, error: 'Rate limit exceeded' };
      this.rate.set(hash, { minute, count: used + 1 });
      if (this.rate.size > 30_000) this.rate.clear();

      const value = await this.loadDay(day);
      if (value.eventCount >= MAX_DAILY_EVENTS) return { status: 503, error: 'Daily capacity reached' };
      const key = event.platform + ':' + event.version;
      const group = value.groups[key] || { platform: event.platform, version: event.version, events: {}, installations: [] };
      if (event.event === 'app_open' && !group.installations.includes(hash)) {
        const uniques = Object.values(value.groups).reduce((n, item) => n + item.installations.length, 0);
        if (uniques >= MAX_DAILY_INSTALLATIONS) return { status: 503, error: 'Daily capacity reached' };
        group.installations.push(hash);
      }
      group.events[event.event] = (group.events[event.event] || 0) + 1;
      value.groups[key] = group;
      value.eventCount++;
      await mkdir(this.directory, { recursive: true, mode: 0o700 });
      const file = join(this.directory, day + '.json');
      const staging = file + '.tmp';
      await writeFile(staging, JSON.stringify(value), { mode: 0o600 });
      await rename(staging, file);
      await this.prune(now);
      return { status: 202 };
    });
    this.pending = task;
    return task;
  }

  async prune(now) {
    // Today plus 29 prior UTC dates: never retain a complete 31st day.
    const oldest = dateKey(now - 29 * DAY_MS);
    for (const day of this.days.keys()) if (day < oldest) this.days.delete(day);
    // Delete stale data only once per day, not for every request.
    if (this.lastPrune === dateKey(now)) return;
    this.lastPrune = dateKey(now);
    for (const filename of await readdir(this.directory)) {
      if (/^\d{4}-\d{2}-\d{2}\.json$/.test(filename) && filename.slice(0, 10) < oldest) {
        await unlink(join(this.directory, filename));
      }
    }
  }

  async summary(days) {
    await this.pending.catch(() => {});
    const total = new Map();
    for (let offset = 0; offset < days; offset++) {
      const day = dateKey(this.clock() - offset * DAY_MS);
      const value = await this.loadDay(day);
      for (const group of Object.values(value.groups)) {
        const key = group.platform + ':' + group.version;
        if (!total.has(key)) total.set(key, { platform: group.platform, version: group.version, events: {}, ids: new Set() });
        const output = total.get(key);
        for (const [event, count] of Object.entries(group.events)) output.events[event] = (output.events[event] || 0) + count;
        for (const hash of group.installations) output.ids.add(hash);
      }
    }
    return {
      periodDays: days,
      generatedAt: new Date(this.clock()).toISOString(),
      versions: [...total.values()].map(group => ({
        platform: group.platform, version: group.version,
        activeInstallations: group.ids.size, events: group.events,
      })).sort((a, b) => b.activeInstallations - a.activeInstallations),
    };
  }
}

async function bodyJson(request) {
  let size = 0;
  const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) return null;
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { return null; }
}

/** Only handles /telemetry routes; undefined means the metadata gateway should handle it. */
export async function handleTelemetryRequest(request, response, { store, adminToken }) {
  const url = new URL(request.url, 'http://gateway.internal');
  if (!url.pathname.startsWith('/telemetry/')) return false;
  if (url.pathname === '/telemetry/events' && request.method === 'POST') {
    if (!store.isConfigured) { json(response, 503, { error: 'Diagnostics are not configured' }); return true; }
    if (!(request.headers['content-type'] || '').startsWith('application/json')) {
      json(response, 415, { error: 'JSON required' }); return true;
    }
    const event = await bodyJson(request);
    if (!validateTelemetryEvent(event)) { json(response, 400, { error: 'Invalid event' }); return true; }
    const result = await store.record(event);
    json(response, result.status, result.error ? { error: result.error } : { accepted: true });
    return true;
  }
  if (url.pathname === '/telemetry/admin/summary' && request.method === 'GET') {
    if (!store.isConfigured || !authorized(request.headers.authorization, adminToken)) {
      json(response, 401, { error: 'Unauthorized' }); return true;
    }
    const daysText = url.searchParams.get('days') || '7';
    if (!/^(?:[1-9]|[12]\d|30)$/.test(daysText) || [...url.searchParams.keys()].some(k => k !== 'days')) {
      json(response, 400, { error: 'Invalid days' }); return true;
    }
    json(response, 200, await store.summary(Number(daysText)));
    return true;
  }
  json(response, 404, { error: 'Unknown route' });
  return true;
}
