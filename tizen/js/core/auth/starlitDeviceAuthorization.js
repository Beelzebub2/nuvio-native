// Device secrets stay on the TV; the verification URL contains only the human code.
export function validateDeviceEndpoints(document, backendUrl) {
  const root = new URL(backendUrl);
  if (root.protocol !== "https:") throw new Error("HTTPS account server required");
  const config = document?.device_authorization;
  if (document?.capabilities?.tv_login !== true || config?.version !== 1) {
    throw new Error("TV authorization is unavailable on this account server");
  }
  const endpoints = {};
  for (const key of ["start_endpoint", "poll_endpoint", "revoke_endpoint"]) {
    const url = new URL(config[key]);
    if (url.origin !== root.origin || url.protocol !== "https:" || url.username || url.password || url.hash || url.search) {
      throw new Error("Invalid account authorization endpoint");
    }
    endpoints[key] = url.href;
  }
  return endpoints;
}
export function createDeviceAuthorizationClient({ endpoints, fetchImpl = globalThis.fetch, now = Date.now }) {
  let challenge = null;
  let approved = null;
  let nextPollAt = 0;
  let interval = 5;
  async function request(endpoint, body, signal, bearer) {
    const response = await fetchImpl(endpoint, {
      method: "POST", credentials: "omit", cache: "no-store", redirect: "error", signal,
      headers: { "Content-Type": "application/json", ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}) },
      body: JSON.stringify(body)
    });
    const text = await response.text();
    if (text.length > 65536) throw new Error("Invalid authorization response");
    let payload;
    try { payload = JSON.parse(text); } catch (_) { throw new Error("Account server unavailable"); }
    if (!response.ok && response.status !== 429 && !payload.status) throw new Error("Account authorization failed");
    return payload;
  }
  return {
    async start(deviceName, signal) {
      challenge = null; approved = null;
      const row = await request(endpoints.start_endpoint, { device_name: deviceName }, signal);
      const uri = new URL(row.verification_uri_complete);
      if (uri.protocol !== "https:" || uri.username || uri.password || uri.search || uri.hash !== `#code=${row.user_code}` || /access_token|refresh_token|device_code/i.test(uri.href)) {
        throw new Error("Unsafe verification URL");
      }
      if (typeof row.device_code !== "string" || row.device_code.length < 20 || !/^[A-Z0-9-]{4,32}$/.test(row.user_code)) {
        throw new Error("Invalid authorization challenge");
      }
      interval = Math.min(60, Math.max(5, Number(row.interval) || 5));
      challenge = { code: row.device_code, displayCode: row.user_code, loginUrl: uri.href,
        verificationUri: row.verification_uri, expiresAt: now() + Math.min(600, Math.max(1, Number(row.expires_in) || 600)) * 1000,
        pollIntervalSeconds: interval, deviceNonce: "" };
      nextPollAt = now() + interval * 1000;
      return { ...challenge };
    },
    async poll(code, signal) {
      if (!challenge || code !== challenge.code || now() >= challenge.expiresAt) return { status: "expired" };
      if (now() < nextPollAt) return { status: "pending", pollIntervalSeconds: interval };
      nextPollAt = now() + interval * 1000;
      const row = await request(endpoints.poll_endpoint, { device_code: code }, signal);
      if (row.status === "slow_down") interval = Math.min(60, Math.max(interval + 5, Number(row.interval) || 0));
      nextPollAt = now() + interval * 1000;
      if (row.status === "approved") {
        if (!row.session?.access_token || !row.session?.refresh_token || !row.device_id) throw new Error("Invalid authorization session");
        approved = { code, session: row.session, deviceId: row.device_id };
      }
      const status = { authorization_pending: "pending", slow_down: "pending", expired_token: "expired", access_denied: "cancelled", consumed: "used" }[row.status] || row.status;
      return { status, pollIntervalSeconds: interval, expiresAt: challenge.expiresAt };
    },
    takeSession(code) {
      if (!approved || approved.code !== code) return null;
      const result = approved; approved = null; challenge = null;
      return result;
    },
    cancel() { challenge = null; approved = null; }
  };
}
