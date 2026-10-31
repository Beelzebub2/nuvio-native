// Write secrets directly to a new private file; never print them or overwrite it.
import { randomBytes, createHmac } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export function signJwt(secret, claims) {
  const data = [ { alg: 'HS256', typ: 'JWT' }, claims ].map(v => Buffer.from(JSON.stringify(v)).toString('base64url')).join('.');
  return `${data}.${createHmac('sha256', secret).update(data).digest('base64url')}`;
}

export function generateEnv(origin, now = Math.floor(Date.now() / 1000)) {
  const url = new URL(origin);
  if (url.protocol !== 'https:' || url.origin !== origin || url.username || url.password) throw new Error('Use the HTTPS origin without a trailing slash');
  const secret = randomBytes(48).toString('hex');
  const authPassword = randomBytes(32).toString('hex');
  const restPassword = randomBytes(32).toString('hex');
  const anon = signJwt(secret, { role: 'anon', iss: 'starlit', aud: 'authenticated', iat: now, exp: now + 10 * 365 * 86400 });
  return `# Private: keep root-owned mode 0600 and include in encrypted/restricted backups.
STARLIT_SUPABASE_URL=${origin}
STARLIT_SUPABASE_PUBLISHABLE_KEY=${anon}
JWT_SECRET=${secret}
STARLIT_AUTH_DB_PASSWORD=${authPassword}
STARLIT_REST_DB_PASSWORD=${restPassword}
AUTH_DATABASE_URL=postgres://starlit_auth_admin:${authPassword}@127.0.0.1:5432/starlit?sslmode=disable
REST_DATABASE_URL=postgres://starlit_authenticator:${restPassword}@127.0.0.1:5432/starlit?sslmode=disable
# Closed registration until the account policy and email delivery are configured.
AUTH_DISABLE_SIGNUP=true
AUTH_EMAIL_AUTOCONFIRM=false
AUTH_REDIRECT_URLS=
SMTP_HOST=
SMTP_PORT=587
SMTP_USER=
SMTP_PASS=
SMTP_ADMIN_EMAIL=
`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [origin, file] = process.argv.slice(2);
  if (!origin || !file) throw new Error('Usage: node generate-env.mjs https://starlit-api.example.com /etc/starlit/runtime.env');
  writeFileSync(file, generateEnv(origin), { flag: 'wx', mode: 0o600 });
  console.log(`Created private configuration at ${file}`);
}
