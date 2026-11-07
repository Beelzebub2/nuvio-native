import pg from 'pg';
import { open } from 'maxmind';
import { setTimeout as pause } from 'node:timers/promises';
import { pathToFileURL } from 'node:url';
import {
  AuthAuditReporter, coarseGeoLocation, exportFailedLogins, exportSuccessfulLogins,
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
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
  try {
    await db.query('SELECT 1');
    while (!stopping) {
      try {
        await exportSuccessfulLogins(db, reporter, geo);
      } catch {
        // Driver/network errors can contain credentials or private addresses.
        console.error('Successful login analytics temporarily unavailable');
      }
      try {
        await exportFailedLogins(db, reporter, env.AUTH_FAILED_LOG_PATH, geo);
      } catch {
        console.error('Failed-login gateway analytics temporarily unavailable');
      }
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
