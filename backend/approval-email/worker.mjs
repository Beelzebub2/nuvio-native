// This Worker can send mail only to the single verified owner address configured in Wrangler.
// The VPS signs each request with its private Ed25519 key; Cloudflare holds only the public key.
import { sendApprovalEmail } from '../cloudflare/src/approval.mjs';
import { validateNotification, verifyNotificationSignature } from './validate.mjs';

const response = (status, body) => new Response(JSON.stringify(body), {
  status,
  headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== '/notify' || request.method !== 'POST') return response(404, { error: 'not_found' });
    if (Number(request.headers.get('Content-Length') || 0) > 4096) return response(413, { error: 'too_large' });
    let payload;
    try {
      const raw = await request.text();
      if (new TextEncoder().encode(raw).length > 4096) return response(413, { error: 'too_large' });
      const authorized = await verifyNotificationSignature(
        raw,
        request.headers.get('X-Starlit-Time'),
        request.headers.get('X-Starlit-Signature'),
        env.SIGNUP_VERIFY_KEY,
      );
      if (!authorized) return response(401, { error: 'unauthorized' });
      payload = validateNotification(JSON.parse(raw), env.SIGNUP_SERVICE_ORIGIN);
    } catch {
      return response(400, { error: 'invalid_request' });
    }
    if (!payload) return response(400, { error: 'invalid_request' });
    try {
      await sendApprovalEmail(env, payload);
      return response(202, { accepted: true });
    } catch {
      return response(502, { error: 'send_failed' });
    }
  },
};
