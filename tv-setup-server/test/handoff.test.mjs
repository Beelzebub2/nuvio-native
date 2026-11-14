import test from "node:test";
import assert from "node:assert/strict";
import { TvSetupRelay, RelayError } from "../relay.mjs";

const id = "2d14af31-67b2-47b5-b680-a8226a51565a";
const media = {
  requestId: id, contentType: "series", videoId: "tt123:2:3",
  parentMetaId: "tt123", title: "Episode test", season: 2,
  episode: 3, resumePositionMs: 3500
};
const setup = () => {
  const relay = new TvSetupRelay();
  const tv = relay.start({ name: "TV", profile: "1" });
  const phone = relay.claim(tv.code);
  return { relay, tv, phone };
};
const denied = (action, status) => assert.throws(action, e => e instanceof RelayError && e.status === status);

test("TV handoff is forbidden before approval; accepts only typed metadata after", () => {
  const { relay, tv, phone } = setup();
  denied(() => relay.enqueue(tv.id, phone.claimToken, { op: "handoffSearch", media }), 403);
  relay.approve(tv.id, tv.tvToken, true);
  const sent = relay.enqueue(tv.id, phone.claimToken, { op: "handoffSearch", media, url: "https://secret.invalid" });
  const command = relay.inspectTV(tv.id, tv.tvToken).commands[0];
  assert.equal(command.id, sent.id);
  assert.deepEqual(command.media, media);
  assert.equal(JSON.stringify(command).includes("secret.invalid"), false);
  relay.acknowledge(tv.id, tv.tvToken, { id: sent.id, ok: true });
  const selected = relay.enqueue(tv.id, phone.claimToken, { op: "handoffSelect", requestId: id, sourceId: "source:1" });
  assert.equal(relay.inspectTV(tv.id, tv.tvToken).commands[0].id, selected.id);
  assert.equal(relay.inspectTV(tv.id, tv.tvToken).commands[0].sourceId, "source:1");
});

test("reject arbitrary media paths, invalid handles, illegal positions and credentials", () => {
  const { relay, tv, phone } = setup();
  relay.approve(tv.id, tv.tvToken, true);
  for (const value of [
    { videoId: "https://example.test/watch" }, { title: "a\nsource" },
    { episode: -1 }, { season: null }, { contentType: "file" },
    { resumePositionMs: 999999999 }, { requestId: "not-uuid" },
  ]) denied(() => relay.enqueue(tv.id, phone.claimToken, {
    op: "handoffSearch", media: { ...media, ...value }
  }), 400);
  for (const sourceId of ["https://secret.test", "source:0", "source:01", "source:1/../2", "source:0001"])
    denied(() => relay.enqueue(tv.id, phone.claimToken, {
      op: "handoffSelect", requestId: id, sourceId
    }), 400);
  denied(() => relay.enqueue(tv.id, phone.claimToken, {
    op: "handoffCancel", requestId: "not-a-request"
  }), 400);
});

test("source metadata crossing relay contains opaque handles, not signed links or tokens", () => {
  const { relay, tv, phone } = setup();
  relay.approve(tv.id, tv.tvToken, true);
  relay.updateState(tv.id, tv.tvToken, {
    sourcePicker: {
      requestId: id, phase: "ready", title: "Movie",
      message: "token=secret-value", auth: "must never appear",
      sources: [
        { id: "source:1", label: "4K https://protected.invalid/?token=abc",
          provider: "Provider api_key=abc", quality: "2160p", url: "https://secret.invalid" },
        { id: "https://bad.invalid", label: "invalid handle" },
      ]
    }
  });
  const picked = relay.inspectPhone(tv.id, phone.claimToken).state.sourcePicker;
  assert.equal(picked.sources.length, 1);
  assert.deepEqual(picked.sources[0], {
    id: "source:1", label: "4K [source]", quality: "2160p", provider: "Provider [private]"
  });
  assert.equal(picked.message, "[private]");
  assert.equal(JSON.stringify(picked).includes("secret.invalid"), false);
  assert.equal(JSON.stringify(picked).includes("abc"), false);
  assert.equal(JSON.stringify(picked).includes("auth"), false);
});
