// One-time migration: append a fresh private Ed25519 PKCS8 key to the existing
// root-owned runtime.env; never generate a new JWT, database or signup AES key.
import { pathToFileURL } from 'node:url';
import { appendSigningKeyToRuntimeEnv } from './signing-key.mjs';

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const file = process.argv[2];
  if (!file || process.argv.length !== 3) throw new Error('Usage: node vps/add-signing-key.mjs /etc/starlit/runtime.env');
  appendSigningKeyToRuntimeEnv(file);
  console.log('Added a new private Ed25519 notifier signing key; no secret material was displayed.');
}
