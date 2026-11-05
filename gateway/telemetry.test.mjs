import assert from 'node:assert/strict';
import { createServer as createHttpServer } from 'node:http';
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, test } from 'node:test';
import { createGatewayServer } from './server.mjs';
import { TelemetryStore, validateTelemetryEvent } from './telemetry.mjs';

const adminToken = 'test-admin-token-longer-than-24-chars';
const hashKey = 'test-only-hmac-secret-more-than-32-characters';
const installationId = '0123456789abcdef0123456789abcdef';
const validEvent = { installationId, platform: 'android', version: '1.9.2', event: 'app_open' };
let root;
let server;
let base;
let clock;

before(async () => {
  root = await mkdtemp(join(tmpdir(), 'starlit-telemetry-test-'));
  clock = Date.parse('2026-11-05T12:00:00Z');
  server = createGatewayServer({
    telemetryStore: new TelemetryStore({ directory: root, hashKey, clock: () => clock }),
    telemetryAdminToken: adminToken,
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  base = 'http://127.0.0.1:' + server.address().port;
});

after(async () => {
  await new Promise(resolve => server.close(resolve));
  await rm(root, { recursive: true, force: true });
});

async function send(event = validEvent) {
  return fetch(base + '/telemetry/events', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(event),
  });
}

async function summary(days = 7, token = adminToken) {
  return fetch(base + '/telemetry/admin/summary?days=' + days, {
    headers: { Authorization: 'Bearer ' + token },
  });
}

test('strict allowlist excludes personal attributes and unknown event names', () => {
  assert.equal(validateTelemetryEvent(validEvent), true);
  assert.equal(validateTelemetryEvent({ ...validEvent, title: 'Private title' }), false);
  assert.equal(validateTelemetryEvent({ ...validEvent, event: 'search_terms' }), false);
  assert.equal(validateTelemetryEvent({ ...validEvent, installationId: 'user@example.com' }), false);
  assert.equal(validateTelemetryEvent({ ...validEvent, version: '../../../config' }), false);
});

test('anonymous activity is grouped by version with distinct installation counts', async () => {
  assert.equal((await send()).status, 202);
  assert.equal((await send()).status, 202);
  assert.equal((await send({ ...validEvent, event: 'screen_search' })).status, 202);
  assert.equal((await send({ ...validEvent, version: '1.9.1', installationId: 'ffffffffffffffffffffffffffffffff' })).status, 202);
  const response = await summary();
  assert.equal(response.status, 200);
  const data = await response.json();
  assert.equal(data.periodDays, 7);
  const latest = data.versions.find(entry => entry.version === '1.9.2');
  assert.equal(latest.activeInstallations, 1);
  assert.equal(latest.events.app_open, 2);
  assert.equal(latest.events.screen_search, 1);
  const stored = await readFile(join(root, '2026-11-05.json'), 'utf8');
  assert.ok(!stored.includes(installationId), 'raw installation identifiers must not be stored');
  assert.ok(!stored.includes('search_terms'));
});

test('older active days do not double-count the same installation', async () => {
  clock += 86400000;
  await send();
  const data = await (await summary()).json();
  assert.equal(data.versions.find(entry => entry.version === '1.9.2').activeInstallations, 1);
});

test('admin summary requires an exact bearer token and bounded window', async () => {
  assert.equal((await fetch(base + '/telemetry/admin/summary')).status, 401);
  assert.equal((await summary(7, 'wrong')).status, 401);
  assert.equal((await summary(31)).status, 400);
  assert.equal((await summary(30)).status, 200);
});

test('rejects unexpected data and oversized requests', async () => {
  assert.equal((await send({ ...validEvent, query: 'secret' })).status, 400);
  const response = await fetch(base + '/telemetry/events', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...validEvent, extra: 'x'.repeat(4096) }),
  });
  assert.equal(response.status, 400);
  assert.equal((await fetch(base + '/telemetry/events')).status, 404);
});

test('ingestion is unavailable without a secret and a durable directory', async () => {
  const bare = createHttpServer((req, res) => {
    import('./telemetry.mjs').then(({ handleTelemetryRequest }) =>
      handleTelemetryRequest(req, res, { store: new TelemetryStore(), adminToken: '' }));
  });
  await new Promise(resolve => bare.listen(0, '127.0.0.1', resolve));
  try {
    const response = await fetch('http://127.0.0.1:' + bare.address().port + '/telemetry/events', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(validEvent),
    });
    assert.equal(response.status, 503);
  } finally {
    await new Promise(resolve => bare.close(resolve));
  }
});
