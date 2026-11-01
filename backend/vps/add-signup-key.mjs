// Append a new signup key to an existing root-private runtime.env, never replacing
// any existing keys. Keep the original JWT/database credentials and running sessions.
import { randomBytes } from 'node:crypto';
import { closeSync, constants, fstatSync, fsyncSync, openSync, readFileSync, writeSync } from 'node:fs';

const file = process.argv[2];
if (!file || process.argv.length !== 3) throw new Error('Usage: node vps/add-signup-key.mjs /etc/starlit/runtime.env');
if (process.getuid?.() !== 0) throw new Error('Run this upgrade helper as root');
// O_NOFOLLOW prevents replacing a symlink target, and O_APPEND guarantees a new
// key is added after the existing configuration instead of overwriting bytes.
const handle = openSync(file, constants.O_RDWR | constants.O_APPEND | constants.O_NOFOLLOW);
try {
  const stat = fstatSync(handle);
  if (!stat.isFile() || stat.uid !== 0 || (stat.mode & 0o077) !== 0) throw new Error('Configuration must be a root-owned private file (mode 0600)');
  const existing = readFileSync(handle, 'utf8');
  if (/^\s*SIGNUP_ENCRYPTION_KEY\s*=/m.test(existing)) throw new Error('Signup encryption key already exists; preserve it');
  const separator = existing.endsWith('\n') ? '' : '\n';
  const key = randomBytes(32).toString('base64url');
  writeSync(handle, `${separator}SIGNUP_ENCRYPTION_KEY=${key}\n`, null, 'utf8');
  fsyncSync(handle);
  console.log(`Added SIGNUP_ENCRYPTION_KEY to ${file}; configure SIGNUP_NOTIFY_URL and SIGNUP_NOTIFY_TOKEN privately`);
} finally {
  closeSync(handle);
}
