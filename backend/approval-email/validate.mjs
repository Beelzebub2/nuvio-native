const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43,128}$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Reject forged review destinations even when the caller signs its payload. */
export function validateNotification(body, origin) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return null;
  if (typeof body.email !== 'string' || body.email.length > 254 || !EMAIL_PATTERN.test(body.email)) return null;
  if (typeof body.reviewUrl !== 'string' || body.reviewUrl.length > 1024) return null;
  const url = (() => { try { return new URL(body.reviewUrl); } catch { return null; } })();
  if (!url || url.origin !== origin || url.pathname !== '/admin/signups/review' || url.username || url.password || url.hash) return null;
  if ([...url.searchParams.keys()].length !== 1 || !TOKEN_PATTERN.test(url.searchParams.get('token') || '')) return null;
  const requestedAt = Number(body.requestedAt);
  if (!Number.isSafeInteger(requestedAt) || requestedAt < 1 || Math.abs(Date.now() - requestedAt) > 7 * 24 * 60 * 60 * 1000) return null;
  return { email: body.email, reviewUrl: url.toString(), requestedAt, country: null, client: 'Starlit VPS' };
}

// The signing key never leaves the VPS. Cloudflare stores only the public
// Ed25519 SPKI DER key, so deploying the notifier needs no shared secret.
function decodeBase64Url(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+$/.test(value)) return null;
  try {
    const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
    const decoded = atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '='));
    return Uint8Array.from(decoded, ch => ch.charCodeAt(0));
  } catch {
    return null;
  }
}

export async function verifyNotificationSignature(rawBody, timestamp, signature, publicSpki, now = Date.now()) {
  if (typeof rawBody !== 'string' || typeof timestamp !== 'string' || !/^\d{13}$/.test(timestamp)) return false;
  const sentAt = Number(timestamp);
  if (!Number.isSafeInteger(sentAt) || Math.abs(now - sentAt) > 5 * 60 * 1000) return false;
  const signatureBytes = decodeBase64Url(signature);
  const keyBytes = decodeBase64Url(publicSpki);
  if (signatureBytes?.length !== 64 || !keyBytes || keyBytes.length < 40 || keyBytes.length > 100) return false;
  try {
    const key = await crypto.subtle.importKey('spki', keyBytes, { name: 'Ed25519' }, false, ['verify']);
    const message = new TextEncoder().encode(`${timestamp}.${rawBody}`);
    return await crypto.subtle.verify('Ed25519', key, signatureBytes, message);
  } catch {
    return false;
  }
}
