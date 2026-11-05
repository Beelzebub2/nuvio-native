const { test } = require("node:test");
const assert = require("node:assert/strict");
const { normalize, report } = require("../composeApp/src/desktopMain/resources/player-ui/diagnostics.js");

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
  });
  assert.match(report(metrics), /Rebuffer events since opening player: 3/);
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
  const copied = report({ ...metrics, extra: secret, decoder: secret, phase: secret });
  for (const forbidden of [secret, "Authorization", "Bearer", "movie.mp4", "https://", "private-movie"]) {
    assert.equal(copied.includes(forbidden), false, forbidden);
  }
  assert.match(copied, /State: Unavailable/);
  assert.match(copied, /Decoder: Unavailable/);
  assert.match(copied, /Resolution: 1920 x 1080/);
  assert.match(copied, /Source throughput: unavailable/);
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
  });
  assert.equal(copied.includes("TOP_SECRET"), false);
  assert.equal(copied.includes("https://"), false);
  assert.equal(copied.includes("Authorization"), false);
});
