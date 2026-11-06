const { test } = require("node:test");
const assert = require("node:assert/strict");
const { normalize, report, createSession } = require("../composeApp/src/desktopMain/resources/player-ui/diagnostics.js");

test("normalizes measured mpv playback metrics without inferring absent values", () => {
  const metrics = normalize({
    duration: 7200.6,
    position: 100.8,
    loading: false,
    paused: false,
    videoWidth: 3840,
    videoHeight: 2160,
    bufferAheadSeconds: 22.6,
    playbackSpeed: 1.25,
    decoderMode: 1,
  }, 3);
  assert.deepEqual(metrics, {
    phase: "Playing",
    resolution: "3840 x 2160",
    positionSeconds: "100",
    durationSeconds: "7200",
    bufferAheadSeconds: "22",
    playbackSpeed: "1.25x",
    rebufferEvents: "3",
    decoder: "Hardware",
    throughput: "Unavailable",
    networkHint: "Unavailable",
    advice: "For stutters, compare an authorized direct stream and include this sanitized report.",
  });
  assert.match(report(metrics), /Rebuffer events while diagnostics open: 3/);
  assert.match(report(metrics), /Decoder: Hardware/);
});

test("unavailable and corrupt native values do not turn into fabricated metrics", () => {
  const metrics = normalize({
    duration: NaN,
    position: -1000,
    loading: true,
    paused: true,
    videoWidth: 50000,
    videoHeight: 4,
    bufferAheadSeconds: Infinity,
    playbackSpeed: -3,
    decoderMode: "hardware",
  }, Infinity);
  assert.equal(metrics.phase, "Buffering");
  assert.equal(metrics.resolution, "Unavailable");
  assert.equal(metrics.positionSeconds, "Unavailable");
  assert.equal(metrics.durationSeconds, "Unavailable");
  assert.equal(metrics.bufferAheadSeconds, "Unavailable");
  assert.equal(metrics.playbackSpeed, "Unavailable");
  assert.equal(metrics.decoder, "Unavailable");
  assert.equal(metrics.rebufferEvents, "0");
  assert.equal(metrics.networkHint, "Unavailable");
  assert.equal(metrics.advice, "Buffering detected. Try another authorized source and check whether the network is stable.");
});

test("copy report uses a strict schema and never forwards secrets from native or caller", () => {
  const secret = "super-secret-token-123";
  const fields = {
    duration: 3600,
    position: 15,
    paused: false,
    videoWidth: 1920,
    videoHeight: 1080,
    sourceUrl: "https://private.example/watch?token=" + secret,
    headers: { Authorization: "Bearer " + secret },
    mediaTitle: "private-movie " + secret,
    log: "C:\\Users\\private\\movie.mp4",
  };
  const metrics = normalize(fields, 4);
  const copied = report({
    ...metrics, extra: secret, decoder: secret, phase: secret,
    networkHint: secret, advice: secret,
  });
  for (const forbidden of [secret, "Authorization", "Bearer", "movie.mp4", "https://", "private-movie"]) {
    assert.equal(copied.includes(forbidden), false, forbidden);
  }
  assert.match(copied, /State: Unavailable/);
  assert.match(copied, /Decoder: Unavailable/);
  assert.match(copied, /Resolution: 1920 x 1080/);
  assert.match(copied, /Source throughput: unavailable/);
  assert.match(copied, /Network link hint: Unavailable/);
  assert.match(copied, /Troubleshooting: Unavailable/);
});

test("rejects malicious data injected into the fields of an otherwise safe metrics object", () => {
  const payload = "https://host/?token=TOP_SECRET\nAuthorization: Bearer TOP_SECRET";
  const copied = report({
    phase: "Playing" + payload,
    resolution: "1920 x 1080" + payload,
    positionSeconds: "20" + payload,
    durationSeconds: payload,
    bufferAheadSeconds: payload,
    playbackSpeed: "1x" + payload,
    rebufferEvents: payload,
    decoder: payload,
    networkHint: payload,
    advice: payload,
  });
  assert.equal(copied.includes("TOP_SECRET"), false);
  assert.equal(copied.includes("https://"), false);
  assert.equal(copied.includes("Authorization"), false);
});

test("network status is only a passive link hint, not a source reachability claim", () => {
  const offline = normalize({ networkOnline: false, loading: true }, 0);
  const online = normalize({ networkOnline: true, paused: false }, 0);
  const absent = normalize({ networkOnline: "online", paused: false }, 0);
  assert.equal(offline.networkHint, "Device reports offline");
  assert.equal(offline.advice, "The device reports offline. Reconnect to the network and try again.");
  assert.equal(online.networkHint, "Device reports connected; media reachability not checked");
  assert.equal(absent.networkHint, "Unavailable");
  assert.match(report(online), /media reachability not checked/);
});

test("buffer and software decoder hints are fixed text and never include source metadata", () => {
  const slow = normalize({
    loading: true, paused: true, bufferAheadSeconds: 0.5,
    sourceUrl: "https://sensitive.example/path?token=redact-me",
  }, 2);
  assert.match(slow.advice, /Buffering with little media ahead/);
  const software = normalize({ decoderMode: 0, videoWidth: 3840, videoHeight: 2160,
    networkOnline: true, paused: false }, 0);
  assert.match(software.advice, /software decoding/);
  assert.equal(report(software).includes("redact-me"), false);
  assert.equal(report(slow).includes("redact-me"), false);
});

test("session collects only after opt-in and clears measurements and count when closed", () => {
  const session = createSession();
  const playing = { paused: false, loading: false, position: 20, videoWidth: 1920, videoHeight: 1080 };
  session.update(playing);
  assert.equal(session.metrics().positionSeconds, "Unavailable");
  assert.equal(session.metrics().phase, "Unavailable");
  session.setEnabled(true);
  session.update({ loading: true, paused: false });
  assert.equal(session.metrics().rebufferEvents, "0", "startup is not rebuffering");
  session.update(playing);
  session.update({ loading: true, paused: false });
  session.update({ loading: true, paused: false });
  assert.equal(session.metrics().rebufferEvents, "1");
  session.setEnabled(false);
  session.update(playing);
  assert.equal(session.metrics().positionSeconds, "Unavailable");
  session.setEnabled(true);
  assert.equal(session.metrics().rebufferEvents, "0");
  assert.equal(session.metrics().positionSeconds, "Unavailable");
});

test("seeking and paused transitions do not inflate native rebuffer count", () => {
  const session = createSession();
  session.setEnabled(true);
  const playing = { loading: false, paused: false };
  session.update(playing);
  session.update({ loading: true, seeking: true, paused: false });
  session.update({ loading: true, seeking: false, paused: false });
  assert.equal(session.metrics().rebufferEvents, "0");
  session.update(playing);
  session.update({ loading: true, paused: true });
  assert.equal(session.metrics().rebufferEvents, "0");
  session.update(playing);
  session.update({ loading: true, paused: false });
  assert.equal(session.metrics().rebufferEvents, "1");
});

test("negative native missing-value sentinels remain unavailable", () => {
  const metrics = normalize({ bufferAheadSeconds: -1, playbackSpeed: -1 });
  assert.equal(metrics.bufferAheadSeconds, "Unavailable");
  assert.equal(metrics.playbackSpeed, "Unavailable");
  assert.equal(normalize({ bufferAheadSeconds: 0 }).bufferAheadSeconds, "0");
});
