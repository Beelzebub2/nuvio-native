// This Worker can send mail only to the single verified owner address configured in Wrangler.
// The VPS calls it with the private SIGNUP_NOTIFY_TOKEN; public callers cannot choose a recipient.
import { sendApprovalEmail } from '../cloudflare/src/approval.mjs';
import { validateNotification } from './validate.mjs';

const response = (status, body) => new Response(JSON.stringify(body), {
  status,
  headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== '/notify' || request.method !== 'POST') return response(404, { error: 'not_found' });
    const expectedToken = typeof env.SIGNUP_NOTIFY_TOKEN === 'string' ? env.SIGNUP_NOTIFY_TOKEN.trim() : '';
    if (!expectedToken || request.headers.get('Authorization') !== `Bearer ${expectedToken}`) {
      return response(401, { error: 'unauthorized' });
    }
    if (Number(request.headers.get('Content-Length') || 0) > 4096) return response(413, { error: 'too_large' });
    let payload;
    try {
      const raw = await request.text();
      if (raw.length > 4096) return response(413, { error: 'too_large' });
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
