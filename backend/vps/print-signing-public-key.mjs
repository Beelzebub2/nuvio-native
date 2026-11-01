// Export only the Ed25519 public key (SPKI DER, base64url) for SIGNUP_VERIFY_KEY
// in the Cloudflare Worker. Never print the PKCS8 private key or runtime.env.
import { pathToFileURL } from 'node:url';
import { publicSigningKeyFromRuntimeEnv } from './signing-key.mjs';

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const file = process.argv[2];
  if (!file || process.argv.length !== 3) throw new Error('Usage: node vps/print-signing-public-key.mjs /etc/starlit/runtime.env');
  console.log(publicSigningKeyFromRuntimeEnv(file));
}
