import assert from 'node:assert/strict';
import { createServer as createHttpServer } from 'node:http';
import { after, before, test } from 'node:test';
import { createGatewayServer } from './server.mjs';
import { OpenObserveTelemetry, validateTelemetryEvent } from './openobserve-telemetry.mjs';

const ingestUrl = 'http://127.0.0.1:5080/api/default/starlit_usage/_json';
const authorization = 'Basic dGVzdDp0ZXN0LW9ubHktc2VjcmV0';
const hashKey = 'test-only-hmac-secret-more-than-32-characters';
const installationId = '0123456789abcdef0123456789abcdef';
const validEvent = { installationId, platform: 'android', version: '1.9.2', event: 'app_open' };
const accepted = () => Response.json({ code: 200, status: [{ name: 'starlit_usage', successful: 1, failed: 0 }] });
const config = { ingestUrl, authorization, hashKey };

let clock = Date.parse('2026-11-05T12:00:00Z');
const deliveries = [];
const reporter = new OpenObserveTelemetry({
  ...config,
  clock: () => clock,
  fetcher: async (url, options) => { deliveries.push({ url, options }); return accepted(); },
});
let server;
let base;

before(async () => {
  server = createGatewayServer({ telemetryReporter: reporter });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  base = 'http://127.0.0.1:' + server.address().port;
});

after(async () => {
  await new Promise(resolve => server.close(resolve));
});

async function send(event = validEvent, url = base + '/telemetry/events') {
  return fetch(url, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(event),
  });
}

test('accepts only fixed event codes, versions and platform fields', () => {
  assert.equal(validateTelemetryEvent(validEvent), true);
  assert.equal(validateTelemetryEvent({ ...validEvent, title: 'Private title' }), false);
  assert.equal(validateTelemetryEvent({ ...validEvent, event: 'search_terms' }), false);
  assert.equal(validateTelemetryEvent({ ...validEvent, installationId: 'user@example.com' }), false);
  assert.equal(validateTelemetryEvent({ ...validEvent, version: '../../../config' }), false);
});

test('forwards one pseudonymous record to OpenObserve using only server credentials', async () => {
  assert.equal((await send()).status, 202);
  assert.equal((await send({ ...validEvent, event: 'screen_search' })).status, 202);
  assert.equal(deliveries.length, 2);
  const [first, second] = deliveries;
  assert.equal(first.url, ingestUrl);
  assert.equal(first.options.headers.Authorization, authorization);
  assert.equal(first.options.redirect, 'error');
  const records = JSON.parse(first.options.body);
  assert.equal(records.length, 1);
  const record = records[0];
  assert.deepEqual(Object.keys(record).sort(), [
    'app', 'event', 'installation_hash', 'platform', 'schema_version', 'version',
  ]);
  assert.equal(record.app, 'starlit');
  assert.equal(record.schema_version, 1);
  assert.equal(record.event, 'app_open');
  assert.equal(record.platform, 'android');
  assert.equal(record.version, '1.9.2');
  assert.match(record.installation_hash, /^[0-9a-f]{64}$/);
  assert.equal(record.installation_hash, JSON.parse(second.options.body)[0].installation_hash);
  assert.ok(!first.options.body.includes(installationId), 'raw installation ID must never reach OpenObserve');
  assert.ok(!first.options.body.includes(authorization));
});

test('invalid fields, excessive bodies, methods and legacy admin summaries are blocked', async () => {
  const count = deliveries.length;
  assert.equal((await send({ ...validEvent, query: 'private text' })).status, 400);
  assert.equal((await send(validEvent, base + '/telemetry/events?anything=1')).status, 404);
  const huge = { ...validEvent, title: 'x'.repeat(4000) };
  assert.equal((await send(huge)).status, 400);
  assert.equal((await fetch(base + '/telemetry/events')).status, 404);
  assert.equal((await fetch(base + '/telemetry/admin/summary')).status, 404);
  const unsupported = await fetch(base + '/telemetry/events', { method: 'POST', body: 'x' });
  assert.equal(unsupported.status, 415);
  assert.equal(deliveries.length, count);
});

test('fails closed without a configured HTTPS or private network ingest URL and credentials', async () => {
  assert.equal(new OpenObserveTelemetry({ ...config, hashKey: '' }).isConfigured, false);
  assert.equal(new OpenObserveTelemetry({ ...config, authorization: '' }).isConfigured, false);
  assert.equal(new OpenObserveTelemetry({ ...config, ingestUrl: 'http://example.com/api/default/starlit_usage/_json' }).isConfigured, false);
  assert.equal(new OpenObserveTelemetry({ ...config, ingestUrl: 'https://observe.rrmtools.uk/admin' }).isConfigured, false);
  const closed = createHttpServer((request, response) => {
    import('./openobserve-telemetry.mjs').then(({ handleTelemetryRequest }) =>
      handleTelemetryRequest(request, response, { reporter: new OpenObserveTelemetry() }));
  });
  await new Promise(resolve => closed.listen(0, '127.0.0.1', resolve));
  try {
    const url = 'http://127.0.0.1:' + closed.address().port + '/telemetry/events';
    assert.equal((await send(validEvent, url)).status, 503);
  } finally {
    await new Promise(resolve => closed.close(resolve));
  }
});

test('does not acknowledge upstream failure or partial OpenObserve ingestion', async () => {
  for (const fetcher of [
    async () => Response.json({ error: 'forbidden' }, { status: 403 }),
    async () => Response.json({ code: 200, status: [{ name: 'starlit_usage', successful: 0, failed: 1 }] }),
    async () => Response.json({ code: 200, status: 'ok', records: 0 }),
    async () => { throw new Error('connection failed'); },
  ]) {
    const instance = new OpenObserveTelemetry({ ...config, fetcher });
    assert.equal((await instance.record(validEvent)).status, 502);
  }
});

test('accepts the older documented OpenObserve success response only for one record', async () => {
  const instance = new OpenObserveTelemetry({
    ...config, fetcher: async () => Response.json({ code: 200, status: 'ok', records: 1 }),
  });
  assert.equal((await instance.record(validEvent)).status, 202);
});

test('per-installation rate limit and daily capacity block bursts', async () => {
  const instance = new OpenObserveTelemetry({ ...config, clock: () => clock, fetcher: async () => accepted() });
  for (let n = 0; n < 30; n++) assert.equal((await instance.record(validEvent)).status, 202);
  assert.equal((await instance.record(validEvent)).status, 429);
  clock += 60_000;
  assert.equal((await instance.record(validEvent)).status, 202);
  instance.dailyCount = 100_000;
  assert.equal((await instance.record(validEvent)).status, 503);
});

test('daily capacity cannot be exceeded by overlapping requests', async () => {
  let release;
  const blocked = new Promise(resolve => { release = resolve; });
  const instance = new OpenObserveTelemetry({ ...config, clock: () => clock, fetcher: async () => {
    await blocked;
    return accepted();
  } });
  instance.day = new Date(clock).toISOString().slice(0, 10);
  instance.dailyCount = 99_999;
  const inFlight = instance.record(validEvent);
  assert.equal((await instance.record({ ...validEvent, installationId: 'ffffffffffffffffffffffffffffffff' })).status, 503);
  release();
  assert.equal((await inFlight).status, 202);
});

test('a blocked metadata request does not block telemetry delivery', async () => {
  let release;
  const blocked = new Promise(resolve => { release = resolve; });
  let started = 0;
  const isolated = createGatewayServer({
    fetcher: async () => {
      started++;
      await blocked;
      return Response.json({ episodes: {} });
    },
    telemetryReporter: new OpenObserveTelemetry({ ...config, fetcher: async () => accepted() }),
  });
  await new Promise(resolve => isolated.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + isolated.address().port;
  try {
    const requests = Array.from({ length: 8 }, (_, n) => fetch(origin + '/episodes?id=' + (n + 100)));
    while (started < 8) await new Promise(resolve => setTimeout(resolve, 5));
    assert.equal((await send({ ...validEvent, event: 'screen_library' }, origin + '/telemetry/events')).status, 202);
    release();
    for (const response of await Promise.all(requests)) assert.equal(response.status, 200);
  } finally {
    release();
    await new Promise(resolve => isolated.close(resolve));
  }
});
