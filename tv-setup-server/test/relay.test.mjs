import test from "node:test";
import assert from "node:assert/strict";
import { TvSetupRelay, RelayError } from "../relay.mjs";

const expectError = (fn, code) => assert.throws(fn, error =>
  error instanceof RelayError && error.status === code);
const init = (now = () => Date.now()) => {
  const relay = new TvSetupRelay(now);
  const tv = relay.start({ name: "Living room", profile: "2" });
  return { relay, tv };
};

test("pairing requires physical TV approval; claims cannot be reused", () => {
  const { relay, tv } = init();
  const phone = relay.claim(tv.claimKey);
  assert.equal(phone.id, tv.id);
  assert.equal(phone.name, "Living room");
  assert.equal(phone.profile, "2");
  assert.equal(phone.verification.length, 6);
  expectError(() => relay.claim(tv.code), 409);
  expectError(() => relay.enqueue(tv.id, phone.claimToken, { op: "refresh" }), 403);
  expectError(() => relay.approve(tv.id, phone.claimToken, true), 403);
  assert.equal(relay.inspectTV(tv.id, tv.tvToken).verification, phone.verification);
  assert.equal(relay.approve(tv.id, tv.tvToken, true).approved, true);
  assert.equal(relay.inspectPhone(tv.id, phone.claimToken).approved, true);
  expectError(() => relay.approve(tv.id, tv.tvToken, true), 409);
});

test("queues one typed command and rejects replay, unapproved and unauthorized calls", () => {
  const { relay, tv } = init();
  const phone = relay.claim(tv.code);
  relay.approve(tv.id, tv.tvToken, true);
  expectError(() => relay.inspectTV(tv.id, phone.claimToken), 403);
  expectError(() => relay.inspectPhone(tv.id, tv.tvToken), 403);
  relay.updateState(tv.id, tv.tvToken, {
    profile: "1", canEditAddons: true,
    addons: [{ id: "addon:0", name: "Example", enabled: true }],
    providers: [],
    repositories: [],
    revision: 5,
    secret: "must never be echoed"
  });
  const snapshot = relay.inspectPhone(tv.id, phone.claimToken).state;
  assert.equal(snapshot.profile, "1");
  assert.equal(snapshot.secret, undefined);
  assert.deepEqual(snapshot.addons, [{
    id: "addon:0", name: "Example", enabled: true, repositoryId: "", type: ""
  }]);
  const queued = relay.enqueue(tv.id, phone.claimToken, {
    op: "setAddonEnabled", target: "addon:0", enabled: false, revision: 5,
    arbitrary: { execute: "never" }
  });
  assert.equal(queued.queued, true);
  const next = relay.inspectTV(tv.id, tv.tvToken).commands[0];
  assert.deepEqual(next, {
    op: "setAddonEnabled", target: "addon:0", enabled: false,
    revision: 5, id: queued.id
  });
  relay.acknowledge(tv.id, tv.tvToken, { id: queued.id, ok: true, message: "Updated" });
  assert.equal(relay.inspectTV(tv.id, tv.tvToken).commands.length, 0);
  assert.equal(relay.inspectPhone(tv.id, phone.claimToken).results[0].message, "Updated");
  assert.equal(relay.acknowledge(tv.id, tv.tvToken, { id: queued.id, ok: true }).ok, true);
  expectError(() => relay.acknowledge(tv.id, tv.tvToken, { id: 123456, ok: true }), 409);
});

test("malformed or high-risk commands are rejected before reaching TV", () => {
  const { relay, tv } = init();
  const phone = relay.claim(tv.code);
  relay.approve(tv.id, tv.tvToken, true);
  for (const url of [
    "http://example.org/manifest.json", "https://127.0.0.1/manifest.json",
    "https://localhost/manifest.json", "https://tv.local/manifest.json",
    "https://user:pass@example.org/manifest.json", "https://[::1]/manifest.json",
    "https://example.org:8443/manifest.json", "https://example.org/manifest.json#token"
  ]) expectError(() => relay.enqueue(tv.id, phone.claimToken, {
    op: "installAddon", url
  }), 400);
  expectError(() => relay.enqueue(tv.id, phone.claimToken, {
    op: "eval", source: "alert(1)"
  }), 400);
  expectError(() => relay.enqueue(tv.id, phone.claimToken, {
    op: "setProviderEnabled", target: "../../sensitive", enabled: true
  }), 400);
  expectError(() => relay.enqueue(tv.id, phone.claimToken, {
    op: "setProviderEnabled", target: "provider:0", enabled: "true"
  }), 400);
  expectError(() => relay.enqueue(tv.id, phone.claimToken, {
    op: "refresh", revision: -1
  }), 400);
});

test("bounded pending queue cannot grow indefinitely", () => {
  const { relay, tv } = init();
  const phone = relay.claim(tv.code);
  relay.approve(tv.id, tv.tvToken, true);
  for (let i = 0; i < 5; i++) relay.enqueue(tv.id, phone.claimToken, { op: "refresh" });
  expectError(() => relay.enqueue(tv.id, phone.claimToken, { op: "refresh" }), 429);
  assert.equal(relay.inspectTV(tv.id, tv.tvToken).commands.length, 1);
});

test("rejection, revocation, expiration and active-session lifetime", () => {
  let time = 1000;
  const now = () => time;
  const first = init(now);
  const phone = first.relay.claim(first.tv.code);
  first.relay.approve(first.tv.id, first.tv.tvToken, false);
  expectError(() => first.relay.inspectPhone(first.tv.id, phone.claimToken), 410);
  const second = init(now);
  time += 5 * 60_000 + 1;
  expectError(() => second.relay.claim(second.tv.code), 410);
  const third = init(now);
  const thirdPhone = third.relay.claim(third.tv.code);
  third.relay.approve(third.tv.id, third.tv.tvToken, true);
  time += 20 * 60_000 + 1;
  expectError(() => third.relay.inspectPhone(third.tv.id, thirdPhone.claimToken), 410);
});
