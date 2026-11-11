import { AuthManager } from "./authManager.js";
import { AuthState } from "./authState.js";
import { SessionStore } from "../storage/sessionStore.js";
import { ServerConfigurationStore } from "../../data/local/serverConfigurationStore.js";
import { createDeviceAuthorizationClient, validateDeviceEndpoints } from "./starlitDeviceAuthorization.js";
import { withRequestTimeout } from "../network/requestTimeout.js";
let client = null;
let generation = -1;
let lastError = null;
export const QrLoginService = {
  getLastError() { return lastError; },
  async start() {
    lastError = null; client?.cancel(); client = null;
    generation = AuthManager.sessionGeneration;
    try {
      const config = ServerConfigurationStore.getActive();
      const discovery = new URL(config.discoveryUrl || `${config.backendUrl}/.well-known/nuvio`);
      if (discovery.protocol !== "https:" || discovery.origin !== new URL(config.backendUrl).origin) throw new Error("HTTPS account server required");
      const document = await withRequestTimeout(async signal => {
        const response = await fetch(discovery.href, { signal, credentials: "omit", redirect: "error", cache: "no-store" });
        if (!response.ok) throw new Error("Account server unavailable");
        const text = await response.text();
        if (text.length > 65536) throw new Error("Invalid account discovery");
        return JSON.parse(text);
      }, 15000, AuthManager.getSessionSignal());
      const endpoints = validateDeviceEndpoints(document, config.backendUrl);
      client = createDeviceAuthorizationClient({ endpoints, fetchImpl: (url, options) => withRequestTimeout(signal => fetch(url, { ...options, signal }), 15000, options.signal) });
      const result = await client.start("Starlit Samsung TV", AuthManager.getSessionSignal());
      if (generation !== AuthManager.sessionGeneration) { client.cancel(); return null; }
      return result;
    } catch (_) { lastError = "TV authorization unavailable. Check the Starlit account server or continue as guest."; return null; }
  },
  async poll(code) {
    if (!client || generation !== AuthManager.sessionGeneration) return { status: "cancelled" };
    try { return await client.poll(code, AuthManager.getSessionSignal()); }
    catch (_) { lastError = "Account server unavailable. Check your network and try again."; return null; }
  },
  async exchange(code) {
    if (!client || generation !== AuthManager.sessionGeneration) return false;
    const result = client.takeSession(code);
    if (!result) return false;
    SessionStore.accessToken = result.session.access_token;
    SessionStore.refreshToken = result.session.refresh_token;
    SessionStore.isAnonymousSession = false;
    localStorage.setItem("starlit_tv_device_id", result.deviceId);
    AuthManager.setState(AuthState.AUTHENTICATED);
    return true;
  }
};
