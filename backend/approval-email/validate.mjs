const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43,128}$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Reject forged review destinations even when the request carries the shared sender token. */
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
