// Runs the shared Supabase-contract integration test and the Cloudflare-specific tests against two
// disposable local `wrangler dev` servers: one with instant sign-up, one requiring owner approval.
// Local email is simulated by wrangler; nothing is sent and no remote resources are touched.
import { spawn } from 'node:child_process';
import { createWriteStream, existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const wrangler = join(root, 'node_modules', 'wrangler', 'bin', 'wrangler.js');
const state = mkdtempSync(join(tmpdir(), 'starlit-sync-test-'));
if (!existsSync(join(root, '.dev.vars'))) {
  writeFileSync(join(root, '.dev.vars'), `JWT_SECRET=${randomBytes(48).toString('base64url')}\n`);
}

function startServer(name, port, inspectorPort, mode) {
  const logPath = join(state, `${name}.log`);
  const log = createWriteStream(logPath);
  const child = spawn(process.execPath, [wrangler, 'dev', '--ip', '127.0.0.1', '--port', String(port), '--inspector-port', String(inspectorPort),
    '--persist-to', join(state, name), '--var', `SIGNUP_MODE:${mode}`, '--show-interactive-dev-session=false'], { cwd: root, env: { ...process.env, CI: '1' } });
  child.stdout.pipe(log);
  child.stderr.pipe(log);
  return { child, logPath, url: `http://127.0.0.1:${port}` };
}

async function waitFor(url) {
  for (let attempt = 0; attempt < 120; attempt++) {
    try {
      if ((await fetch(`${url}/healthz`)).ok) return;
    } catch { /* still starting */ }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error(`${url} did not start`);
}

function run(args, env) {
  return new Promise(resolve => {
    const child = spawn(process.execPath, args, { cwd: root, stdio: 'inherit', env: { ...process.env, ...env } });
    child.on('exit', code => resolve(code ?? 1));
  });
}

const servers = [startServer('auto', 8788, 9231, 'auto'), startServer('approval', 8789, 9232, 'approval')];
let code = 1;
try {
  await Promise.all(servers.map(server => waitFor(server.url)));
  const key = 'sb_publishable_starlit_cloudflare';
  code = await run(['--test', '--test-concurrency=1', join(root, 'test', 'accounts.test.mjs'),
    join(root, '..', 'test', 'sync.integration.test.mjs'), join(root, 'test', 'cloudflare.test.mjs')], {
    STARLIT_SUPABASE_URL: servers[0].url,
    STARLIT_SUPABASE_PUBLISHABLE_KEY: key,
    STARLIT_APPROVAL_URL: servers[1].url,
    STARLIT_APPROVAL_LOG: servers[1].logPath,
  });
} finally {
  for (const server of servers) server.child.kill();
  await new Promise(resolve => setTimeout(resolve, 1000));
  if (code !== 0) console.error(`Server logs kept in ${state}`);
  else try { rmSync(state, { recursive: true, force: true }); } catch { /* Windows may still hold a file briefly */ }
}
process.exit(code);
