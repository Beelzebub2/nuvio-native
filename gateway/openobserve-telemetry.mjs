import { createHmac } from 'node:crypto';

// The public client only sends these fixed events and metadata fields. Reject
// all arbitrary properties before they reach the observability service.
export const TELEMETRY_EVENTS = Object.freeze([
  'app_open', 'screen_home', 'screen_search', 'screen_library',
  'screen_settings', 'playback_opened', 'playback_ready', 'playback_failed',
  'download_queued', 'download_completed', 'download_failed',
]);
const events = new Set(TELEMETRY_EVENTS);
const platforms = new Set(['android', 'desktop', 'ios']);
const MAX_BODY_BYTES = 1024;
const MAX_DAILY_EVENTS = 100_000;
const MAX_EVENTS_PER_INSTALLATION_PER_MINUTE = 30;
const headers = {
  'Content-Type': 'application/json; charset=utf-8',
  'Cache-Control': 'no-store',
  'X-Content-Type-Options': 'nosniff',
};

function json(response, status, body) {
  response.writeHead(status, headers);
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

function validIngestUrl(value) {
  try {
    const url = new URL(value);
    const privateHost = ['127.0.0.1', 'localhost', '[::1]', 'openobserve', 'host.docker.internal'].includes(url.hostname);
    return (url.protocol === 'https:' || (url.protocol === 'http:' && privateHost)) &&
      !url.username && !url.password && !url.search && !url.hash &&
      /^\/api\/[a-zA-Z0-9_-]+\/[a-zA-Z0-9_-]+\/_json$/.test(url.pathname);
  } catch {
    return false;
  }
}

/**
 * Server-side OpenObserve adapter. No ingestion credential or raw installation ID
 * ever reaches the response or disk. Rate-limit state exists only in memory.
 */
export class OpenObserveTelemetry {
  constructor({ ingestUrl, authorization, hashKey, fetcher = fetch, clock = Date.now } = {}) {
    this.ingestUrl = ingestUrl;
    this.authorization = authorization;
    this.hashKey = hashKey || '';
    this.fetcher = fetcher;
    this.clock = clock;
    this.rate = new Map();
    this.day = '';
    this.dailyCount = 0;
  }

  get isConfigured() {
    return this.hashKey.length >= 32 && validIngestUrl(this.ingestUrl) &&
      typeof this.authorization === 'string' &&
      /^Basic [A-Za-z0-9+/]+={0,2}$/.test(this.authorization);
  }

  async record(event) {
    const now = this.clock();
    const day = new Date(now).toISOString().slice(0, 10);
    if (day !== this.day) {
      this.day = day;
      this.dailyCount = 0;
      this.rate.clear();
    }
    if (this.dailyCount >= MAX_DAILY_EVENTS) {
      return { status: 503, error: 'Daily capacity reached' };
    }
    const idHash = createHmac('sha256', this.hashKey).update(event.installationId).digest('hex');
    const minute = Math.floor(now / 60_000);
    const previous = this.rate.get(idHash);
    const used = previous?.minute === minute ? previous.count : 0;
    if (used >= MAX_EVENTS_PER_INSTALLATION_PER_MINUTE) {
      return { status: 429, error: 'Rate limit exceeded' };
    }
    this.rate.set(idHash, { minute, count: used + 1 });
    if (this.rate.size > 30_000) this.rate.clear();
    // Reserve capacity synchronously, before yielding to the network. This
    // bounds concurrent attempts and includes failed upstream requests.
    this.dailyCount++;

    // OpenObserve assigns the timestamp on ingestion. A stable server-side hash
    // supports per-version active installation counts without storing raw IDs.
    const body = JSON.stringify([{
      app: 'starlit', schema_version: 1, event: event.event,
      platform: event.platform, version: event.version,
      installation_hash: idHash,
    }]);
    try {
      const response = await this.fetcher(this.ingestUrl, {
        method: 'POST',
        headers: { Authorization: this.authorization, 'Content-Type': 'application/json' },
        body,
        signal: AbortSignal.timeout(4000),
        redirect: 'error',
      });
      if (!response.ok) return { status: 502, error: 'Ingestion unavailable' };
      const result = await response.json();
      // A 200 response can still report that OpenObserve rejected the event.
      const acceptedBatch = Array.isArray(result?.status) &&
        result.status.length === 1 && result.status[0].successful === 1 &&
        result.status[0].failed === 0;
      const acceptedLegacy = result?.status === 'ok' && result.records === 1;
      if (result?.code !== 200 || (!acceptedBatch && !acceptedLegacy)) {
        return { status: 502, error: 'Ingestion unavailable' };
      }
      return { status: 202 };
    } catch {
      return { status: 502, error: 'Ingestion unavailable' };
    }
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

export async function handleTelemetryRequest(request, response, { reporter }) {
  const url = new URL(request.url, 'http://gateway.internal');
  if (!url.pathname.startsWith('/telemetry/')) return false;
  if (url.pathname === '/telemetry/events' && request.method === 'POST' && !url.search) {
    if (!reporter.isConfigured) { json(response, 503, { error: 'Diagnostics are not configured' }); return true; }
    if (!(request.headers['content-type'] || '').startsWith('application/json')) {
      json(response, 415, { error: 'JSON required' }); return true;
    }
    const event = await bodyJson(request);
    if (!validateTelemetryEvent(event)) { json(response, 400, { error: 'Invalid event' }); return true; }
    const result = await reporter.record(event);
    json(response, result.status, result.error ? { error: result.error } : { accepted: true });
    return true;
  }
  json(response, 404, { error: 'Unknown route' });
  return true;
}
