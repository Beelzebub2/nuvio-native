export async function revokeStarlitDeviceSession({ backendUrl, publishableKey, deviceId, accessToken, refreshToken, fetchImpl = globalThis.fetch, timeoutMs = 5000 }) {
  if (!deviceId || !accessToken) return { confirmed: false };
  const root = new URL(backendUrl);
  if (root.protocol !== "https:") return { confirmed: false };
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const request = (path, body, token) => fetchImpl(`${root.origin}${path}`, { method: "POST", credentials: "omit", redirect: "error", signal: controller.signal,
    headers: { "Content-Type": "application/json", apikey: publishableKey || "", ...(token ? {Authorization:`Bearer ${token}`} : {}) }, body: JSON.stringify(body) });
  try {
    let token = accessToken;
    let result = await request("/tv/v1/revoke", {device_id:deviceId}, token);
    if (result.status === 401 && refreshToken) {
      const refreshed = await request("/auth/v1/token?grant_type=refresh_token", {refresh_token:refreshToken});
      if(refreshed.ok) {
        const session = await refreshed.json();
        if(session.access_token) result = await request("/tv/v1/revoke", {device_id:deviceId}, session.access_token);
      }
    }
    return {confirmed:result.ok};
  } catch (_) { return {confirmed:false}; }
  finally { clearTimeout(timer); }
}
