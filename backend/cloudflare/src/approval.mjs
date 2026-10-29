// Owner approval for new accounts: an email to the owner's verified address with a review link,
// and the small review page it opens. The requester's email is untrusted input everywhere here.
import { EmailMessage } from 'cloudflare:email';
import { base64UrlEncode } from './shared.mjs';

const encoder = new TextEncoder();
const DEFAULT_FROM = 'starlit-sync@rrmtools.uk';
const DEFAULT_TO = 'ricardo.approvals@rrmtools.uk';

export const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
const oneLine = (value, max = 200) => String(value ?? '').replace(/[\r\n\t]+/g, ' ').slice(0, max);
const utc = value => new Date(value).toISOString().replace('T', ' ').slice(0, 16) + ' UTC';
const base64 = text => {
  let binary = '';
  for (const byte of encoder.encode(text)) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/.{1,76}/g, line => `${line}\r\n`);
};

export function approvalEmail({ reviewUrl, email, requestedAt, country, client }) {
  const subject = `Approve Starlit account for ${oneLine(email, 254)}?`;
  const rows = [['Email', email], ['Requested', utc(requestedAt)], ['Country', country || 'Unknown'], ['App', client || 'Unknown']]
    .map(([label, value]) => [label, oneLine(value)]);
  const text = [
    'Someone asked to create a Starlit sync account on your server.', '',
    ...rows.map(([label, value]) => `${label}: ${value}`), '',
    'Review it and choose Approve or Deny here (the link expires in 7 days):', reviewUrl, '',
    'Nothing happens until you choose Approve on that page. If you do not recognise this request, choose Deny or ignore this email.',
  ].join('\n');
  const html = `<!doctype html><html><body style="margin:0;padding:24px;background:#f4f4f7;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1d1d24">
<div style="max-width:480px;margin:0 auto;background:#ffffff;border-radius:14px;padding:28px">
<h1 style="margin:0 0 8px;font-size:20px">New Starlit account request</h1>
<p style="margin:0 0 20px;line-height:1.5;color:#4a4a57">Someone asked to create a sync account on your Starlit server.</p>
<table style="width:100%;border-collapse:collapse;margin-bottom:24px;font-size:14px">${rows.map(([label, value]) =>
    `<tr><td style="padding:6px 0;color:#6b6b78;width:96px">${label}</td><td style="padding:6px 0;word-break:break-all">${escapeHtml(value)}</td></tr>`).join('')}</table>
<a href="${escapeHtml(reviewUrl)}" style="display:inline-block;background:#5b4bff;color:#ffffff;text-decoration:none;padding:12px 20px;border-radius:10px;font-weight:600">Review request</a>
<p style="margin:20px 0 0;font-size:13px;line-height:1.5;color:#6b6b78">Nothing happens until you choose Approve on that page. The link expires in 7 days. If you do not recognise this request, choose Deny or ignore this email.</p>
</div></body></html>`;
  return { subject, text, html };
}

function mimeMessage({ from, to, subject, text, html }) {
  const boundary = `starlit-${base64UrlEncode(crypto.getRandomValues(new Uint8Array(12)))}`;
  const domain = from.split('@')[1];
  return [
    `From: Starlit Sync <${from}>`, `To: <${to}>`,
    `Subject: =?UTF-8?B?${base64(subject).replace(/\r\n/g, '')}?=`,
    `Date: ${new Date().toUTCString()}`, `Message-ID: <${crypto.randomUUID()}@${domain}>`,
    'MIME-Version: 1.0', `Content-Type: multipart/alternative; boundary="${boundary}"`, '',
    `--${boundary}`, 'Content-Type: text/plain; charset=UTF-8', 'Content-Transfer-Encoding: base64', '', base64(text),
    `--${boundary}`, 'Content-Type: text/html; charset=UTF-8', 'Content-Transfer-Encoding: base64', '', base64(html),
    `--${boundary}--`, '',
  ].join('\r\n');
}

/** Sends through the Email Routing binding, which may only deliver to the owner's verified address. */
export async function sendApprovalEmail(env, details) {
  const from = env.APPROVAL_FROM || DEFAULT_FROM;
  const to = env.APPROVAL_TO || DEFAULT_TO;
  const content = approvalEmail(details);
  try {
    await env.APPROVAL_EMAIL.send({ from: { email: from, name: 'Starlit Sync' }, to, ...content });
  } catch (structuredError) {
    // Older runtimes and Routing-only accounts accept the raw MIME form.
    try {
      await env.APPROVAL_EMAIL.send(new EmailMessage(from, to, mimeMessage({ from, to, ...content })));
    } catch (mimeError) {
      console.error('Approval email failed', structuredError?.code ?? structuredError?.name, mimeError?.code ?? mimeError?.name, mimeError?.message);
      throw mimeError;
    }
  }
}

export function reviewPage({ title, body, status = 200 }) {
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>${escapeHtml(title)}</title><style>
:root{color-scheme:light dark;--bg:#f4f4f7;--card:#fff;--text:#1d1d24;--muted:#6b6b78;--accent:#5b4bff;--danger:#c62828}
@media (prefers-color-scheme:dark){:root{--bg:#111116;--card:#1c1c24;--text:#ededf2;--muted:#a3a3b2;--accent:#8c80ff;--danger:#ef6b6b}}
body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,Segoe UI,Roboto,sans-serif;padding:16px;box-sizing:border-box}
main{width:100%;max-width:440px;background:var(--card);border-radius:16px;padding:28px;box-sizing:border-box}
h1{font-size:20px;margin:0 0 8px}p{color:var(--muted);margin:0 0 16px}dl{display:grid;grid-template-columns:96px 1fr;gap:6px 12px;margin:0 0 24px}
dt{color:var(--muted)}dd{margin:0;word-break:break-all}form{display:flex;gap:12px;flex-wrap:wrap}
button{flex:1;min-width:120px;border:0;border-radius:10px;padding:12px 16px;font:600 15px system-ui,sans-serif;cursor:pointer;color:#fff;background:var(--accent)}
button.deny{background:transparent;color:var(--danger);box-shadow:inset 0 0 0 1.5px var(--danger)}
</style></head><body><main>${body}</main></body></html>`;
  return new Response(html, {
    status,
    headers: {
      'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer', 'X-Frame-Options': 'DENY',
      'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
    },
  });
}
