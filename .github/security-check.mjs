// Fail closed before packaging a public release: the backend and private keys
// must not appear in any public client source snapshot or reachable Git history.
import { execFileSync } from 'node:child_process';
import { readFileSync, statSync } from 'node:fs';

const git = (...args) => execFileSync('git', args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
const fail = (message) => { console.error(`Security check failed: ${message}`); process.exitCode = 1; };

const tracked = git('ls-files', '-z').split('\0').filter(Boolean);
const forbiddenNames = /(^|\/)(?:backend\/|\.env(?:\.|$)|\.dev\.vars$|[^/]+\.(?:p12|pfx|jks|keystore|key)$)/i;
const allowedExamples = /\.env\.example$/i;
for (const path of tracked) {
  if (forbiddenNames.test(path) && !allowedExamples.test(path)) fail(`private path committed: ${path}`);
}

const signatures = [
  /-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----/,
  /\bghp_[A-Za-z0-9]{36,}\b/,
  /\bgithub_pat_[A-Za-z0-9_]{60,}\b/,
  /\bAKIA[0-9A-Z]{16}\b/,
  /\bsk_live_[A-Za-z0-9]{24,}\b/,
];
for (const path of tracked) {
  if (!statSync(path).isFile() || statSync(path).size > 2 * 1024 * 1024) continue;
  const bytes = readFileSync(path);
  // Bundled native libraries may contain literal PEM parser markers, not keys.
  if (bytes.includes(0)) continue;
  const content = bytes.toString('utf8');
  if (signatures.some(pattern => pattern.test(content))) fail(`credential signature in ${path}`);
}

// Search all history reachable by the runner, not only the current HEAD.
// Print commit IDs only: never print secret-bearing lines to CI logs.
const historicalPattern = '-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----|ghp_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{60,}|AKIA[0-9A-Z]{16}';
const foundInHistory = git('log', '--all', '--no-merges', `-G${historicalPattern}`, '--format=%h', '--', '.', ':!vendor');
if (foundInHistory.trim()) fail('credentials appear in reachable Git history; rewrite affected refs and rotate exposed keys');

if (process.exitCode !== 1) console.log('Public source and reachable-history security checks passed.');
