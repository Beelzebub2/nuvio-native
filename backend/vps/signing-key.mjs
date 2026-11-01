// Ed25519 notifier signing utilities. The PKCS8 private key stays exclusively
// in the root-owned VPS runtime environment; Cloudflare receives only SPKI.
import { createPrivateKey, createPublicKey, generateKeyPairSync, sign } from 'node:crypto';
import { closeSync, constants, fstatSync, fsyncSync, openSync, readFileSync, writeSync } from 'node:fs';

export function generateSigningPrivateKey() {
  const { privateKey } = generateKeyPairSync('ed25519');
  return privateKey.export({ format: 'der', type: 'pkcs8' }).toString('base64url');
}

export function importSigningPrivateKey(encoded) {
  if (typeof encoded !== 'string' || !/^[A-Za-z0-9_-]{50,512}$/.test(encoded)) {
    throw new Error('SIGNUP_SIGNING_KEY must contain an Ed25519 PKCS8 DER private key encoded as base64url');
  }
  const der = Buffer.from(encoded, 'base64url');
  if (der.toString('base64url') !== encoded) throw new Error('SIGNUP_SIGNING_KEY must be canonical base64url');
  let privateKey;
  try {
    privateKey = createPrivateKey({ key: der, format: 'der', type: 'pkcs8' });
  } catch {
    throw new Error('SIGNUP_SIGNING_KEY must be valid PKCS8 DER');
  }
  if (privateKey.asymmetricKeyType !== 'ed25519') throw new Error('SIGNUP_SIGNING_KEY must be an Ed25519 key');
  return privateKey;
}

export function publicSigningKey(encoded) {
  return createPublicKey(importSigningPrivateKey(encoded)).export({ format: 'der', type: 'spki' }).toString('base64url');
}

export function signNotification(privateKey, timestamp, rawBody) {
  if (privateKey?.asymmetricKeyType !== 'ed25519') throw new Error('Expected an Ed25519 private key');
  if (typeof timestamp !== 'string' || !/^\d{13}$/.test(timestamp) || typeof rawBody !== 'string') {
    throw new Error('Notification signing requires decimal millisecond timestamp and raw UTF-8 JSON body');
  }
  const payload = Buffer.from(`${timestamp}.${rawBody}`, 'utf8');
  return sign(null, payload, privateKey).toString('base64url');
}

export function signingKeyAppend(existing, encoded) {
  if (typeof existing !== 'string') throw new Error('Expected existing runtime.env text');
  // Match even a value set to empty; never duplicate or replace an existing key.
  if (/^[ \t]*(?:export[ \t]+)?SIGNUP_SIGNING_KEY[ \t]*=/m.test(existing)) {
    throw new Error('SIGNUP_SIGNING_KEY already exists; refusing to overwrite or duplicate');
  }
  importSigningPrivateKey(encoded);
  return `${existing.endsWith('\n') ? '' : '\n'}SIGNUP_SIGNING_KEY=${encoded}\n`;
}

export function signingKeyFromEnvironmentFile(existing) {
  const matches = [...existing.matchAll(/^SIGNUP_SIGNING_KEY=([^\r\n]*)\r?$/gm)];
  if (matches.length !== 1) throw new Error('Expected exactly one SIGNUP_SIGNING_KEY in private runtime.env');
  return matches[0][1];
}

export function openPrivateRuntimeEnv(path, writable = false) {
  if (process.getuid?.() !== 0) throw new Error('Must run as root');
  const flags = (writable ? constants.O_RDWR | constants.O_APPEND : constants.O_RDONLY) | constants.O_NOFOLLOW;
  const fd = openSync(path, flags);
  try {
    const stat = fstatSync(fd);
    if (!stat.isFile() || stat.uid !== 0 || stat.nlink !== 1 || (stat.mode & 0o077) !== 0) {
      throw new Error('runtime.env must be a root-owned single-link private file (mode 0600)');
    }
  } catch (error) {
    closeSync(fd);
    throw error;
  }
  return fd;
}

export function appendSigningKeyToRuntimeEnv(path) {
  const fd = openPrivateRuntimeEnv(path, true);
  try {
    const existing = readFileSync(fd, 'utf8');
    // Generate only after checking the current file; never print or write the key
    // anywhere except the already-private file.
    const addition = signingKeyAppend(existing, generateSigningPrivateKey());
    if (writeSync(fd, addition, null, 'utf8') !== Buffer.byteLength(addition)) {
      throw new Error('Partial signing-key append: inspect the private file before retrying');
    }
    fsyncSync(fd);
  } finally {
    closeSync(fd);
  }
}

export function publicSigningKeyFromRuntimeEnv(path) {
  const fd = openPrivateRuntimeEnv(path);
  try {
    const current = readFileSync(fd, 'utf8');
    return publicSigningKey(signingKeyFromEnvironmentFile(current));
  } finally {
    closeSync(fd);
  }
}
