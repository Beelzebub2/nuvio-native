import test from "node:test";
import assert from "node:assert/strict";
import { createTvSetupServer } from "../server.mjs";

const ORIGIN = "https://starlit.rrmtools.uk";
let server;
let base;
async function request(path, { method = "GET", origin, token, phone, body } = {}) {
  const result = await fetch(base + path, {
    method, headers: {
      ...(origin ? { Origin: origin } : {}),
      ...(method === "POST" ? { "Content-Type": "application/json" } : {}),
      ...(token ? { "X-TV-Token": token } : {}),
      ...(phone ? { "X-Phone-Token": phone } : {})
    }, ...(body ? { body: JSON.stringify(body) } : {})
  });
  return { status: result.status, body: await result.json() };
}

test.before(async () => {
  server = createTvSetupServer({ origin: ORIGIN });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  base = "http://127.0.0.1:" + server.address().port;
});
test.after(async () => { await new Promise(resolve => server.close(resolve)); });

test("first-party page is CSP-restricted and requires no login", async () => {
  const html = await fetch(base + "/tv/connect");
  assert.equal(html.status, 200);
  assert.match(html.headers.get("content-security-policy"), /script-src 'self'/);
  assert.match(html.headers.get("content-security-policy"), /frame-ancestors 'none'/);
  assert.match(await html.text(), /Starlit/);
  assert.equal((await fetch(base + "/tv/app.js")).status, 200);
  assert.equal((await fetch(base + "/tv/style.css")).status, 200);
  const logo = await fetch(base + "/tv/logo.svg");
  assert.equal(logo.status, 200);
  assert.match(logo.headers.get("content-type"), /image\/svg\+xml/);
  assert.deepEqual(await (await fetch(base + "/tv/healthz")).json(), { ok: true });
});

test("complete HTTPS-proxy-compatible session with TV and phone", async () => {
  const start = await request("/tv/api/start", { method:"POST", body:{ name:"TV", profile:"1" } });
  assert.equal(start.status, 200);
  const tv = start.body;
  assert.equal(tv.code.length, 8);
  assert.equal(tv.claimKey.length, 64);
  assert.equal((await request("/tv/api/tv?id=" + tv.id, { token: tv.tvToken })).body.claimed, false);
  assert.equal((await request("/tv/api/claim", { method:"POST", body:{ code: tv.code } })).status, 403);
  const claim = await request("/tv/api/claim", { method:"POST", origin:ORIGIN, body:{ code: tv.claimKey } });
  assert.equal(claim.status, 200);
  const phone = claim.body;
  assert.equal((await request("/tv/api/command?id=" + tv.id, {
    method:"POST", origin:ORIGIN, phone:phone.claimToken, body:{ op:"refresh" }
  })).status, 403);
  assert.equal((await request("/tv/api/approve?id=" + tv.id, {
    method:"POST", token:tv.tvToken, body:{ accept:true }
  })).status, 200);
  assert.equal((await request("/tv/api/state?id=" + tv.id, {
    method:"POST", token:tv.tvToken, body:{ profile:"1", canEditAddons:true, addons:[], repositories:[], providers:[], revision:1 }
  })).status, 200);
  assert.equal((await request("/tv/api/phone?id=" + tv.id, { phone:phone.claimToken })).body.state.canEditAddons, true);
  const cmd = await request("/tv/api/command?id=" + tv.id, {
    method:"POST", origin:ORIGIN, phone:phone.claimToken, body:{ op:"refresh", revision:1 }
  });
  assert.equal(cmd.status, 200);
  assert.equal((await request("/tv/api/tv?id=" + tv.id, { token: tv.tvToken })).body.commands[0].id, cmd.body.id);
  await request("/tv/api/ack?id=" + tv.id, {
    method:"POST", token:tv.tvToken, body:{ id:cmd.body.id, ok:true, message:"Done" }
  });
  assert.equal((await request("/tv/api/phone?id=" + tv.id, { phone:phone.claimToken })).body.results[0].message, "Done");
  await request("/tv/api/stop?id=" + tv.id, { method:"POST", token:tv.tvToken, body:{} });
  assert.equal((await request("/tv/api/phone?id=" + tv.id, { phone:phone.claimToken })).status, 410);
});

test("TV preflight is permitted; remote page writes and cookie-based requests are denied", async () => {
  const preflight = await fetch(base + "/tv/api/start", {
    method:"OPTIONS", headers:{ Origin:"null", "Access-Control-Request-Method":"POST",
      "Access-Control-Request-Headers":"content-type,x-tv-token" }
  });
  assert.equal(preflight.status, 204);
  assert.equal(preflight.headers.get("access-control-allow-origin"), "*");
  assert.equal((await request("/tv/api/claim", {
    method:"POST", origin:"https://attacker.invalid", body:{ code:"AAAAAAAA" }
  })).status, 403);
  assert.equal((await request("/tv/api/command?id=" + "a".repeat(64), {
    method:"POST", origin:"https://attacker.invalid", body:{op:"refresh"}
  })).status, 403);
  assert.equal((await request("/tv/api/tv?id=invalid", { token:"A".repeat(64) })).status, 400);
  const invalid = await fetch(base + "/tv/api/claim", {
    method:"POST", headers:{ Origin:ORIGIN, "Content-Type":"text/plain" }, body:"bad"
  });
  assert.equal(invalid.status, 415);
});

test("independent approved sessions do not exhaust one proxy-wide polling rate limit", async () => {
  const clients = [];
  for (let index = 0; index < 2; index++) {
    const started = await request("/tv/api/start", {
      method:"POST", body:{ name:"TV-" + index, profile:"1" }
    });
    assert.equal(started.status, 200);
    const tv = started.body;
    const claim = await request("/tv/api/claim", {
      method:"POST", origin:ORIGIN, body:{ code:tv.code }
    });
    assert.equal(claim.status, 200);
    assert.equal((await request("/tv/api/approve?id=" + tv.id, {
      method:"POST", token:tv.tvToken, body:{accept:true}
    })).status, 200);
    clients.push({ tv, phone:claim.body });
  }
  for (let iteration = 0; iteration < 40; iteration++) {
    for (const {tv, phone} of clients) {
      const inspected = await request("/tv/api/phone?id=" + tv.id, {
        phone:phone.claimToken
      });
      assert.equal(inspected.status, 200, "approved sessions must not share a proxy-wide limit");
    }
  }
  const unauthorized = clients[0];
  assert.equal((await request("/tv/api/phone?id=" + unauthorized.tv.id, {
    phone:"f".repeat(64)
  })).status, 403);
  for (const {tv} of clients) await request("/tv/api/stop?id=" + tv.id, {
    method:"POST", token:tv.tvToken, body:{}
  });
});
