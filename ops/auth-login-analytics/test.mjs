import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  AuthAuditReporter, auditIngestUrl, coarseGeoLocation,
  exportFailedLogins, exportSuccessfulLogins, failedLogin,
  publicClientIp, successfulLogin,
} from './lib.mjs';

const now = Date.UTC(2026, 10, 7, 1, 0, 0);
const at = new Date(now - 10_000);
const eventId = '123e4567-e89b-42d3-a456-426614174000';
const requestId = 'a'.repeat(32);
const validAck = count => ({
  code: 200, status: [{ successful: count, failed: 0 }],
});

test('client IP validation discards proxies, forged headers, reserved ranges and test IPs', () => {
  for (const ip of ['127.0.0.1', '10.1.2.3', '192.168.0.2', '203.0.113.99',
    '::1', '2001:db8::1', '8.8.8.8, 1.1.1.1', '8.8.8.8\nHost: evil']) {
    assert.equal(publicClientIp(ip), null);
  }
  assert.equal(publicClientIp('8.8.8.8'), '8.8.8.8');
  assert.equal(publicClientIp('::ffff:8.8.8.8'), '8.8.8.8');
  assert.equal(publicClientIp('2606:4700:4700::1111'), '2606:4700:4700::1111');
});

test('location uses city, region and country, never precise coordinates or street', () => {
  const location = coarseGeoLocation({
    city: { names: { en: 'Porto' } },
    subdivisions: [{ names: { en: 'Porto District' } }],
    country: { names: { en: 'Portugal' } },
    latitude: 12.3, longitude: 40.2, street: 'Private street',
  });
  assert.equal(location, 'Porto, Porto District, Portugal');
  assert.doesNotMatch(location, /Private|12.3/);
});

test('only the configured OpenObserve usage or auth endpoint can be the ingestion destination', () => {
  assert.equal(auditIngestUrl('http://127.0.0.1:5080/api/default/starlit_usage/_json'),
    'http://127.0.0.1:5080/api/default/starlit_auth/_json');
  assert.equal(auditIngestUrl('https://observe.rrmtools.uk/api/default/starlit_auth/_json'),
    'https://observe.rrmtools.uk/api/default/starlit_auth/_json');
  for (const u of ['http://evil.example/api/default/starlit_usage/_json',
    'http://127.0.0.1:5080/api/default/other/_json',
    'http://127.0.0.1:5080/api/default/starlit_usage/_json?key=bad',
    'http://admin:secret@127.0.0.1/api/default/starlit_usage/_json']) {
    assert.throws(() => auditIngestUrl(u), /Untrusted/);
  }
});

test('successful login records contain no user identity, password, token or hidden payload', () => {
  const record = successfulLogin({
    event_id: eventId, login_at: at, login_method: 'passkey',
    ip_address: '8.8.8.8', email: 'private@example.org', access_token: 'SECRET',
    payload: { secret: 'SECRET' },
  }, () => 'Lisbon, Portugal', now);
  assert.equal(record.outcome, 'success');
  assert.equal(record.login_method, 'passkey');
  assert.equal(record.approximate_location, 'Lisbon, Portugal');
  assert.equal(record.ip_address, '8.8.8.8');
  assert.equal(record.event_id, 'success:' + eventId);
  assert.equal(record._timestamp, at.getTime() * 1000);
  assert.doesNotMatch(JSON.stringify(record), /private@example|access_token|SECRET|payload/);
  assert.equal(successfulLogin({ event_id: 'invalid', login_at: at }, () => null, now), null);
});

test('failed token requests are type checked, scoped and contain no raw request data', () => {
  const record = failedLogin({
    request_id: requestId, at: at.toISOString(), grant: 'password',
    status: 401, ip: '8.8.8.8', url: '/auth/v1/token?email=private',
    request_body: 'SECRET',
  }, () => 'Lisbon, Portugal', now);
  assert.equal(record.outcome, 'failure');
  assert.equal(record.login_method, 'email');
  assert.equal(record.http_status, 401);
  assert.doesNotMatch(JSON.stringify(record), /private|SECRET|request_body|url/);
  for (const bad of [
    { grant: 'refresh_token' }, { grant: 'nonsense' }, { status: 200 },
    { status: 500 }, { request_id: 'not-a-request-id' },
    { at: '2001-01-01T00:00:00Z' },
  ]) {
    const entry = { request_id: requestId, at, grant: 'password', status: 401,
      ip: '8.8.8.8', ...bad };
    assert.equal(failedLogin(entry, () => null, now), null);
  }
});

test('OpenObserve must acknowledge all records before an export is considered delivered', async () => {
  const reporter = new AuthAuditReporter({
    ingestUrl: 'http://127.0.0.1:5080/api/default/starlit_usage/_json',
    authorization: 'Basic YTpi',
    fetcher: async (_url, init) => {
      assert.equal(JSON.parse(init.body).length, 2);
      assert.equal(init.redirect, 'error');
      return new Response(JSON.stringify(validAck(2)), { status: 200 });
    },
  });
  await reporter.send([{ event_id: 'one' }, { event_id: 'two' }]);
  reporter.fetcher = async () => new Response(JSON.stringify(validAck(1)), { status: 200 });
  await assert.rejects(reporter.send([{ event_id: 'one' }, { event_id: 'two' }]), /every record/);
  assert.throws(() => new AuthAuditReporter({
    ingestUrl: 'http://127.0.0.1:5080/api/default/starlit_usage/_json',
    authorization: 'Bearer token',
  }), /authorization/);
});

test('success exporter persists cursor only after full upstream acknowledgement', async () => {
  const calls = [];
  const state = { cursor_time: new Date(now - 60_000), cursor_id: eventId };
  const db = {
    async query(sql, values) {
      calls.push({ sql, values });
      if (sql.includes('SELECT cursor_time')) return { rows: [state] };
      if (sql.includes('login_successes')) return { rows: [{
        event_id: eventId, login_at: at, login_method: 'email', ip_address: '8.8.8.8',
      }] };
      return { rows: [], rowCount: 1 };
    },
  };
  await assert.rejects(exportSuccessfulLogins(db, {
    async send() { throw new Error('upstream unavailable'); },
  }, () => null, now), /upstream/);
  assert.equal(calls.filter(c => c.sql.includes('UPDATE ')).length, 0);
  await exportSuccessfulLogins(db, { async send(records) {
    assert.equal(records.length, 1);
    assert.equal(records[0].outcome, 'success');
  } }, () => null, now);
  assert.equal(calls.filter(c => c.sql.includes('UPDATE ')).length, 1);
});

test('failure exporter handles incomplete lines, rejects bad records and resumes from saved offset', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'starlit-audit-'));
  const path = join(dir, 'failures.jsonl');
  const line = JSON.stringify({
    request_id: requestId, at: at.toISOString(), status: 401, grant: 'password', ip: '8.8.8.8',
  }) + '\n';
  const unfinished = JSON.stringify({
    request_id: 'b'.repeat(32), at: at.toISOString(), status: 429, grant: 'pkce', ip: '1.1.1.1',
  });
  try {
    await writeFile(path, line + 'not-json\n' + unfinished, 'utf8');
    const cursor = { file_inode: null, file_offset: 0 };
    let delivered = [];
    const updates = [];
    const db = { async query(sql, values) {
      if (sql.includes('SELECT file_inode')) return { rows: [cursor] };
      updates.push(values);
      cursor.file_inode = values[0];
      cursor.file_offset = values[1];
      return { rowCount: 1, rows: [] };
    } };
    const reporter = { async send(records) { delivered.push(...records); } };
    const first = await exportFailedLogins(db, reporter, path, () => null, now);
    assert.equal(first, 1);
    assert.equal(delivered.length, 1);
    assert.equal(cursor.file_offset, Buffer.byteLength(line + 'not-json\n'));
    const second = await exportFailedLogins(db, reporter, path, () => null, now);
    assert.equal(second, 0);
    assert.equal(delivered.length, 1);
    assert.equal(updates.length, 1);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
