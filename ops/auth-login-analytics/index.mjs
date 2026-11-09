import pg from 'pg';
import { open } from 'maxmind';
import { setTimeout as pause } from 'node:timers/promises';
import { pathToFileURL } from 'node:url';
import {
  AuthAuditReporter, coarseGeoLocation, exportFailedLogins, exportSuccessfulLogins,
  retryDelayMs,
} from './lib.mjs';

async function localGeoIp(path) {
  if (!path) return () => null;
  try {
    const db = await open(path);
    return ip => coarseGeoLocation(db.get(ip));
  } catch {
    console.error('Auth audit GeoIP database unavailable; location will be omitted');
    return () => null;
  }
}

export async function run(env = process.env) {
  if (!env.AUTH_LOGIN_DATABASE_URL || !env.AUTH_FAILED_LOG_PATH) {
    throw new Error('Restricted auth audit database and log file must be configured');
  }
  const reporter = new AuthAuditReporter({
    ingestUrl: env.OPENOBSERVE_INGEST_URL,
    authorization: env.OPENOBSERVE_INGEST_AUTH,
  });
  const db = new pg.Pool({
    connectionString: env.AUTH_LOGIN_DATABASE_URL,
    max: 1,
    connectionTimeoutMillis: 5000,
    query_timeout: 10000,
    application_name: 'starlit-auth-login-analytics',
  });
  db.on('error', () => console.error('Auth audit database temporarily unavailable'));
  const geo = await localGeoIp(env.GEOIP_DB_PATH);
  let stopping = false;
  const stop = () => { stopping = true; };
  const states = {
    success: { failures: 0, nextAt: 0, lastWarningAt: 0 },
    failure: { failures: 0, nextAt: 0, lastWarningAt: 0 },
  };
  async function pollSource(key, label, task) {
    const state = states[key];
    if (Date.now() < state.nextAt) return;
    try {
      await task();
      if (state.failures) console.info(label + ' recovered');
      state.failures = 0;
      state.nextAt = 0;
      state.lastWarningAt = 0;
    } catch (error) {
      // Never advance a failed cursor. Avoid retry storms and log flooding.
      state.failures = Math.min(8, state.failures + 1);
      const now = Date.now();
      state.nextAt = now + retryDelayMs(state.failures);
      if (!state.lastWarningAt || now - state.lastWarningAt >= 30 * 60_000) {
        // Driver errors may contain database URLs, client IPs and credentials.
        const reason = error?.code === 'AUTH_AUDIT_INGEST_WINDOW'
          ? ' (OpenObserve ingest-age window)' : '';
        console.error(label + ' temporarily unavailable' + reason);
        state.lastWarningAt = now;
      }
    }
  }
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
  try {
    await db.query('SELECT 1');
    while (!stopping) {
      await pollSource('success', 'Successful login analytics', () =>
        exportSuccessfulLogins(db, reporter, geo));
      await pollSource('failure', 'Failed-login gateway analytics', () =>
        exportFailedLogins(db, reporter, env.AUTH_FAILED_LOG_PATH, geo));
      if (!stopping) await pause(5000);
    }
  } finally {
    await db.end();
    process.off('SIGTERM', stop);
    process.off('SIGINT', stop);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  run().catch(() => { console.error('Auth audit exporter failed to initialize'); process.exitCode = 1; });
}
