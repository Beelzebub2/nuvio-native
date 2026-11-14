import test from "node:test";
import assert from "node:assert/strict";
import { createTvMediaHandoff, isValidTvMediaRequest } from "../js/core/phoneSetup/tvMediaHandoff.js";

const requestId = "81a6a77e-2cf7-4479-9c9f-59fb608a6531";
const request = {
  requestId, contentType: "movie", videoId: "tt0003",
  parentMetaId: "tt0003", title: "Sample title", resumePositionMs: 1300
};

test("phone requests opaque TV sources, then TV plays only the selected current source", async () => {
  let route = "settings";
  let params = {};
  let played = "";
  const source = { id: "internal:id", title: "1080p", url: "https://private.example/secret?token=1", addonName: "Provider" };
  const streamScreen = {
    streams: [], streamSearchCompleted: false, error: "", params,
    playStream: async id => { played = id; route = "player"; }
  };
  const router = {
    getCurrent: () => route,
    navigate: async (next, options) => {
      route = next;
      params = options;
      streamScreen.params = options;
    }
  };
  const handoff = createTvMediaHandoff({ router, streamScreen });
  assert.equal(isValidTvMediaRequest(request), true);
  await handoff.search(request);
  assert.equal(params.manualSelection, true, "TV auto-play must not override phone choice");
  assert.equal(handoff.state().phase, "searching");
  streamScreen.streams = [source];
  streamScreen.streamSearchCompleted = true;
  const state = handoff.state();
  assert.equal(state.phase, "ready");
  assert.deepEqual(state.sources, [{ id: "source:1", label: "1080p", quality: "", provider: "Provider" }]);
  assert.equal(JSON.stringify(state).includes("private.example"), false);
  await assert.rejects(() => handoff.select(requestId, "internal:id"), /expired/);
  await handoff.select(requestId, "source:1");
  assert.equal(played, "internal:id");
  assert.equal(handoff.state().phase, "playing");
  await assert.rejects(() => handoff.select(requestId, "source:1"), /expired/);
  handoff.cancel(requestId);
  assert.equal(handoff.state().phase, "idle");
});

test("an outdated request and different TV route cannot select a new title", async () => {
  let current = "settings";
  const router = {
    getCurrent: () => current,
    navigate: async next => { current = next; }
  };
  const screen = { params: { remoteHandoffRequestId: requestId }, streams: [{ id: "local", title: "Stream" }],
    streamSearchCompleted: true, playStream: async () => assert.fail("untrusted source played") };
  const handoff = createTvMediaHandoff({ router, streamScreen: screen });
  await handoff.search(request);
  assert.equal(handoff.state().sources.length, 1);
  await assert.rejects(() => handoff.select("00000000-0000-4000-8000-000000000000", "source:1"), /expired/);
  current = "home";
  await assert.rejects(() => handoff.select(requestId, "source:1"), /expired/);
  assert.equal(handoff.state().phase, "error");
});

test("reject source URLs and arbitrary profile IDs in playback metadata", () => {
  assert.equal(isValidTvMediaRequest({ ...request, videoId: "https://example.test/watch" }), false);
  assert.equal(isValidTvMediaRequest({ ...request, title: "bad\nvalue" }), false);
  assert.equal(isValidTvMediaRequest({ ...request, resumePositionMs: 43_200_001 }), false);
});
