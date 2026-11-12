import test from "node:test";
import assert from "node:assert/strict";
import { createDeviceAuthorizationClient, validateDeviceEndpoints } from "../js/core/auth/starlitDeviceAuthorization.js";
const base = "https://account.example";
const endpoints = { start_endpoint: `${base}/tv/v1/start`, poll_endpoint: `${base}/tv/v1/poll`, revoke_endpoint: `${base}/tv/v1/revoke` };
const discovery = { capabilities: { tv_login: true }, device_authorization: { version: 1, ...endpoints } };
test("discovery requires explicit capability, HTTPS and same-origin endpoints", () => {
 assert.deepEqual(validateDeviceEndpoints(discovery, base), endpoints);
 for (const url of ["http://account.example", "https://evil.example", "https://u:p@account.example", "https://account.example/a#secret"]) assert.throws(() => validateDeviceEndpoints({ ...discovery, device_authorization: { ...discovery.device_authorization, poll_endpoint: url } }, base));
 assert.throws(() => validateDeviceEndpoints({ ...discovery, capabilities: { tv_login: false } }, base));
});
test("device code stays off QR; polling backs off and consumes approved session once", async () => {
 let now=0; const calls=[]; const responses=[{device_code:"secret-device-code-123456789",user_code:"ABCD-EFGH",verification_uri_complete:"https://web.example/account/tv#code=ABCD-EFGH",expires_in:600,interval:5},{status:"slow_down",interval:15},{status:"approved",session:{access_token:"access",refresh_token:"refresh"},device_id:"device-1"}];
 const client=createDeviceAuthorizationClient({endpoints,now:()=>now,fetchImpl:async(url,options)=>{calls.push({url,options});return{ok:true,status:200,text:async()=>JSON.stringify(responses.shift())}}});
 const challenge=await client.start("TV");assert(!challenge.loginUrl.includes(challenge.code));
 assert.equal((await client.poll(challenge.code)).status,"pending");assert.equal(calls.length,1);
 now=5000;assert.equal((await client.poll(challenge.code)).pollIntervalSeconds,15);
 now=10000;await client.poll(challenge.code);assert.equal(calls.length,2);
 now=20000;assert.equal((await client.poll(challenge.code)).status,"approved");
 assert.equal(client.takeSession("wrong"),null);assert.equal(client.takeSession(challenge.code).deviceId,"device-1");assert.equal(client.takeSession(challenge.code),null);
 assert(calls.every(c=>c.options.redirect==="error" && c.options.credentials==="omit"));
});
test("expiry and cancellation stop polling without network requests", async()=>{
 let now=0,calls=0;const client=createDeviceAuthorizationClient({endpoints,now:()=>now,fetchImpl:async()=>{calls++;return{ok:true,text:async()=>JSON.stringify({device_code:"secret-device-code-123456789",user_code:"ABCD-EFGH",verification_uri_complete:"https://web.example/account/tv#code=ABCD-EFGH",expires_in:1})}}});
 const c=await client.start("TV");now=1001;assert.equal((await client.poll(c.code)).status,"expired");client.cancel();assert.equal(client.takeSession(c.code),null);assert.equal(calls,1);
});
test("QR containing a token or device secret is rejected",async()=>{
 const client=createDeviceAuthorizationClient({endpoints,fetchImpl:async()=>({ok:true,text:async()=>JSON.stringify({device_code:"secret-device-code-123456789",user_code:"ABCD-EFGH",verification_uri_complete:"https://web.example/account/tv?access_token=secret#code=ABCD-EFGH"})})});
 await assert.rejects(client.start("TV"),/Unsafe/);
});

const challengeResponse = suffix => ({
 device_code: `secret-device-code-123456789-${suffix}`, user_code: `CODE-${suffix}`,
 verification_uri_complete: `https://web.example/account/tv#code=CODE-${suffix}`, expires_in: 60
});
const response = payload => ({ ok: true, text: async () => JSON.stringify(payload) });
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return {promise, resolve}; };

test("cancelled and superseded starts cannot restore a challenge", async () => {
 const first = deferred(), second = deferred();
 const replies = [first, second];
 const client = createDeviceAuthorizationClient({ endpoints, fetchImpl: () => replies.shift().promise });
 const oldStart = client.start("TV");
 const newStart = client.start("TV");
 second.resolve(response(challengeResponse("NEW")));
 const current = await newStart;
 first.resolve(response(challengeResponse("OLD")));
 assert.equal(await oldStart, null);
 assert.equal((await client.poll(current.code)).status, "pending");
 client.cancel();
 assert.equal((await client.poll(current.code)).status, "expired");

 const pending = deferred();
 const cancelled = createDeviceAuthorizationClient({ endpoints, fetchImpl: () => pending.promise });
 const start = cancelled.start("TV");
 cancelled.cancel();
 pending.resolve(response(challengeResponse("OLD")));
 assert.equal(await start, null);
});

test("late poll approvals cannot survive cancellation, restart or expiry", async () => {
 for (const action of ["cancel", "restart", "expire"]) {
  let now = 0;
  const pending = deferred();
  let starts = 0;
  const client = createDeviceAuthorizationClient({ endpoints, now: () => now, fetchImpl: url =>
   url === endpoints.start_endpoint ? Promise.resolve(response(challengeResponse(starts++ ? "NEW" : "OLD"))) : pending.promise
  });
  const old = await client.start("TV"); now = 5000;
  const poll = client.poll(old.code);
  let current;
  if (action === "cancel") client.cancel();
  if (action === "restart") current = await client.start("TV");
  if (action === "expire") now = 60000;
  pending.resolve(response({ status: "approved", session: { access_token: "access", refresh_token: "refresh" }, device_id: "device" }));
  assert.equal((await poll).status, action === "expire" ? "expired" : "cancelled");
  assert.equal(client.takeSession(old.code), null);
  if (current) assert.equal((await client.poll(current.code)).status, "pending");
 }
});
