import test from "node:test";
import assert from "node:assert/strict";

const values = new Map();
globalThis.localStorage = { getItem: key => values.get(key) ?? null,
  setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) };
const { AuthManager } = await import("../js/core/auth/authManager.js");
const { ServerConfigurationStore } = await import("../js/data/local/serverConfigurationStore.js");
const { QrLoginService } = await import("../js/core/auth/qrLoginService.js");
const base = "https://account.example";
ServerConfigurationStore.getActive = () => ({ backendUrl: base });
const document = { capabilities: { tv_login: true }, device_authorization: { version: 1,
  start_endpoint: `${base}/start`, poll_endpoint: `${base}/poll`, revoke_endpoint: `${base}/revoke` } };
const response = row => ({ ok: true, text: async () => JSON.stringify(row) });
function challenge(code) {
  return { device_code: `secret-device-code-123456789-${code}`, user_code: code,
    verification_uri_complete: `https://web.example/account/tv#code=${code}`, expires_in: 600 };
}

test("a superseded discovery cannot replace the displayed QR client", async () => {
  AuthManager.sessionGeneration = 1;
  let finishDiscovery, discoveries = 0, starts = 0;
  globalThis.fetch = async url => {
    if (url.endsWith("/.well-known/nuvio")) {
      if (++discoveries === 1) return new Promise(done => { finishDiscovery = done; });
      return response(document);
    }
    assert.equal(url, `${base}/start`);
    starts++;
    return response(challenge("CURRENT"));
  };
  const stale = QrLoginService.start();
  const current = await QrLoginService.start();
  finishDiscovery(response(document));
  assert.equal(await stale, null);
  assert.equal(starts, 1);
  assert.equal((await QrLoginService.poll(current.code)).status, "pending");
  assert.equal(QrLoginService.getLastError(), null);
});

test("session switches during discovery and challenge creation discard stale starts", async () => {
  for (const stalledEndpoint of ["/.well-known/nuvio", "/start"]) {
    AuthManager.sessionGeneration++;
    let finish, reached;
    const waiting = new Promise(done => { reached = done; });
    globalThis.fetch = url => {
      if (url.endsWith(stalledEndpoint)) {
        reached();
        return new Promise(done => { finish = done; });
      }
      return Promise.resolve(response(document));
    };
    const start = QrLoginService.start();
    await waiting;
    AuthManager.sessionGeneration++;
    finish(response(stalledEndpoint === "/start" ? challenge("STALE") : document));
    assert.equal(await start, null);
    assert.equal(await QrLoginService.exchange("secret-device-code-123456789-STALE"), false);
    assert.equal(QrLoginService.getLastError(), null);
  }
});
