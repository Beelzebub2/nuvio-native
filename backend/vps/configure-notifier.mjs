// One-time root-only VPS upgrade. Accepts the previously provisioned Cloudflare
// notifier bearer token over stdin, never through argv or a file in the repo.
// Preserves all existing runtime.env values and refuses replacement/duplication.
import { closeSync, constants, fstatSync, fsyncSync, openSync, readFileSync, writeSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const NOTIFIER_URL = 'https://starlit-approval-email.cloudflaredomainrrm.workers.dev/notify';

export function notificationAppend(existing, token) {
  if (typeof existing !== 'string') throw new Error('Expected the existing private environment file');
  if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{43,256}$/.test(token)) {
    throw new Error('Expected a strong 43+ character URL-safe bearer token on stdin');
  }
  if (/^[ \t]*(?:export[ \t]+)?SIGNUP_NOTIFY_(?:URL|TOKEN)[ \t]*=/m.test(existing)) {
    throw new Error('Notifier configuration already exists; refusing to overwrite or duplicate it');
  }
  return `${existing.endsWith('\n') ? '' : '\n'}SIGNUP_NOTIFY_URL=${NOTIFIER_URL}\nSIGNUP_NOTIFY_TOKEN=${token}\n`;
}

async function main() {
  const file = process.argv[2];
  if (!file || process.argv.length !== 3) throw new Error('Usage: node vps/configure-notifier.mjs /etc/starlit/runtime.env');
  if (process.getuid?.() !== 0) throw new Error('Run this upgrade helper as root');

  let input = '';
  for await (const piece of process.stdin) {
    input += piece.toString('utf8');
    if (input.length > 2048) throw new Error('Token input is too large');
  }
  const token = input.replace(/\r?\n$/, '');

  // O_NOFOLLOW rejects symlinks, O_APPEND guarantees the old bytes are preserved.
  const handle = openSync(file, constants.O_RDWR | constants.O_APPEND | constants.O_NOFOLLOW);
  try {
    const stat = fstatSync(handle);
    if (!stat.isFile() || stat.nlink !== 1 || stat.uid !== 0 || (stat.mode & 0o077) !== 0) {
      throw new Error('Configuration must be a root-owned, single-link private file (mode 0600)');
    }
    const additionalLines = notificationAppend(readFileSync(handle, 'utf8'), token);
    // One small append and fsync; no rewrite, secret output, or replacement of old keys.
    if (writeSync(handle, additionalLines, null, 'utf8') !== Buffer.byteLength(additionalLines)) {
      throw new Error('Partial configuration append; inspect the private file before retrying');
    }
    fsyncSync(handle);
    console.log(`Configured the Starlit notification endpoint in ${file} without displaying its token`);
  } finally {
    closeSync(handle);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
