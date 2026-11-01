// One-time root-only migration: configure the fixed notifier HTTPS endpoint
// without touching the JWT, database, AES or Ed25519 secrets in runtime.env.
import { closeSync, fsyncSync, readFileSync, writeSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { openPrivateRuntimeEnv } from './signing-key.mjs';

export const SIGNUP_NOTIFY_URL = 'https://starlit-approval-email.cloudflaredomainrrm.workers.dev/notify';

export function notifierUrlAppend(existing) {
  if (typeof existing !== 'string') throw new Error('Expected current runtime.env text');
  const assignments = existing.match(/^[ \t]*(?:export[ \t]+)?SIGNUP_NOTIFY_URL[ \t]*=.*$/gm) || [];
  if (assignments.length > 1) throw new Error('Duplicate SIGNUP_NOTIFY_URL entries: inspect the private file');
  if (assignments.length === 1) {
    const current = assignments[0].slice(assignments[0].indexOf('=') + 1).trim();
    if (current !== SIGNUP_NOTIFY_URL) throw new Error('SIGNUP_NOTIFY_URL has another value: refusing to replace it');
    return ''; // Safe idempotent no-op for an already configured VPS.
  }
  return `${existing.endsWith('\n') ? '' : '\n'}SIGNUP_NOTIFY_URL=${SIGNUP_NOTIFY_URL}\n`;
}

export function addNotifierUrl(path) {
  const fd = openPrivateRuntimeEnv(path, true);
  try {
    const additional = notifierUrlAppend(readFileSync(fd, 'utf8'));
    if (!additional) return false;
    if (writeSync(fd, additional, null, 'utf8') !== Buffer.byteLength(additional)) {
      throw new Error('Partial notifier URL append: inspect private runtime.env before retrying');
    }
    fsyncSync(fd);
    return true;
  } finally {
    closeSync(fd);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const file = process.argv[2];
  if (!file || process.argv.length !== 3) throw new Error('Usage: node vps/add-notify-url.mjs /etc/starlit/runtime.env');
  const added = addNotifierUrl(file);
  console.log(added ? 'Configured notifier HTTPS URL without changing existing secrets.'
    : 'Notifier HTTPS URL already configured; no changes made.');
}
