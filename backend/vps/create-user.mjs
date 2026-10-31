// Explicit operator-created accounts. Input JSON {email,password} on stdin so
// passwords do not enter command arguments/history. No emails are sent.
import { signJwt } from './generate-env.mjs';

const secret = process.env.JWT_SECRET;
if (!secret || secret.length < 32) throw new Error('Load the private runtime.env');
let input = '';
for await (const chunk of process.stdin) {
  input += chunk;
  if (input.length > 4096) throw new Error('Account input is too large');
}
const { email, password } = JSON.parse(input);
if (typeof email !== 'string' || !email.includes('@') || typeof password !== 'string' || password.length < 12) throw new Error('Provide an email and a password of at least 12 characters');
const now = Math.floor(Date.now() / 1000);
const token = signJwt(secret, { role: 'service_role', aud: 'authenticated', iat: now, exp: now + 60 });
const response = await fetch('http://127.0.0.1:9999/admin/users', {
  method: 'POST',
  headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  body: JSON.stringify({ email, password, email_confirm: true }),
});
if (!response.ok) throw new Error(`Account creation failed (${response.status}); inspect private auth logs`);
console.log(`Created account ${(await response.json()).id}`);
