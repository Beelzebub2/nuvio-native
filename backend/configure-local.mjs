// Pipe `supabase status -o json` here. Only public connection fields are persisted.
import { writeFile } from 'node:fs/promises';
import { discoveryDocument } from './discovery.mjs';

let input = '';
for await (const chunk of process.stdin) input += chunk;
const status = JSON.parse(input.replace(/^\uFEFF/, ''));
const env = {
  STARLIT_SUPABASE_URL: status.API_URL,
  STARLIT_SUPABASE_PUBLISHABLE_KEY: status.PUBLISHABLE_KEY || status.ANON_KEY,
};
const document = discoveryDocument(env);
await writeFile('.env', [
  `STARLIT_SUPABASE_URL=${document.backend_url}`,
  `STARLIT_SUPABASE_PUBLISHABLE_KEY=${document.publishable_key}`,
  'HOST=127.0.0.1',
  'PORT=8787',
  '',
].join('\n'), { flag: process.argv.includes('--force') ? 'w' : 'wx', mode: 0o600 });
console.log('Wrote .env with the local API URL and publishable key only.');
