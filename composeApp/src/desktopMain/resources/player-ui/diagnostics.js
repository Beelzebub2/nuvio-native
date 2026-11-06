// Diagnostics for the native player controls. This module accepts only numeric
// mpv measurements and fixed state flags. It must never copy source metadata,
// log messages, URLs, headers, file paths, or account information.
(function (root) {
  "use strict";

  const finite = (value, min, max) =>
    typeof value === "number" && Number.isFinite(value) && value >= min && value <= max
      ? value : null;
  const networkHints = Object.freeze([
    "Device reports connected; media reachability not checked",
    "Device reports offline",
    "Unavailable",
  ]);
  const adviceOptions = Object.freeze([
    "The device reports offline. Reconnect to the network and try again.",
    "Buffering with little media ahead. Check the connection, or try a lower-bitrate authorized source.",
    "High-resolution video is using software decoding. Check hardware acceleration or try a lower resolution.",
    "Buffering detected. Try another authorized source and check whether the network is stable.",
    "For stutters, compare an authorized direct stream and include this sanitized report.",
    "Unavailable",
  ]);

  function normalize(update, rebufferEvents = 0) {
    const input = update && typeof update === "object" ? update : {};
    const position = finite(input.position, 0, 172800);
    const duration = finite(input.duration, 0, 172800);
    const width = finite(input.videoWidth, 1, 16384);
    const height = finite(input.videoHeight, 1, 16384);
    const buffered = finite(input.bufferAheadSeconds, 0, 3600);
    const playbackSpeed = finite(input.playbackSpeed, 0.1, 8);
    const decoder = input.decoderMode === 1 ? "Hardware" :
      input.decoderMode === 0 ? "Software" : "Unavailable";
    const networkHint = input.networkOnline === true ? networkHints[0] :
      input.networkOnline === false ? networkHints[1] : networkHints[2];
    const advice = input.networkOnline === false ? adviceOptions[0] :
      input.loading === true && buffered !== null && buffered < 3 ? adviceOptions[1] :
      decoder === "Software" && width !== null && width >= 2560 ? adviceOptions[2] :
      input.loading === true ? adviceOptions[3] : adviceOptions[4];
    return {
      phase: input.loading === true ? "Buffering" : input.paused === false ? "Playing" : input.paused === true ? "Paused or idle" : "Unavailable",
      resolution: width !== null && height !== null ? `${Math.round(width)} x ${Math.round(height)}` : "Unavailable",
      positionSeconds: position === null ? "Unavailable" : Math.floor(position).toString(),
      durationSeconds: duration === null || duration <= 0 ? "Unavailable" : Math.floor(duration).toString(),
      bufferAheadSeconds: buffered === null ? "Unavailable" : Math.floor(buffered).toString(),
      playbackSpeed: playbackSpeed === null ? "Unavailable" : playbackSpeed.toFixed(2).replace(/0+$/, "").replace(/\.$/, "") + "x",
      rebufferEvents: String(Math.floor(finite(rebufferEvents, 0, 1000000) ?? 0)),
      decoder,
      throughput: "Unavailable",
      networkHint,
      advice,
    };
  }

  function report(metrics) {
    // Re-normalize callers' values through a fixed allowlist. Never serialize
    // arbitrary objects: diagnostics are often pasted into public bug reports.
    const safe = metrics && typeof metrics === "object" ? metrics : {};
    const text = (value, expression) => typeof value === "string" && expression.test(value)
      ? value : "Unavailable";
    const state = ["Playing", "Buffering", "Paused or idle"].includes(safe.phase) ? safe.phase : "Unavailable";
    const resolution = text(safe.resolution, /^[0-9]{1,5} x [0-9]{1,5}$/);
    const seconds = value => text(value, /^(?:0|[1-9][0-9]{0,6})$/);
    const speed = text(safe.playbackSpeed, /^(?:[0-9]{1,2})(?:\.[0-9]{1,2})?x$/);
    const decoder = ["Hardware", "Software"].includes(safe.decoder) ? safe.decoder : "Unavailable";
    const networkHint = networkHints.includes(safe.networkHint) ? safe.networkHint : "Unavailable";
    const advice = adviceOptions.includes(safe.advice) ? safe.advice : "Unavailable";
    return [
      "Starlit playback diagnostics",
      "State: " + state,
      "Resolution: " + resolution,
      "Position seconds: " + seconds(safe.positionSeconds),
      "Duration seconds: " + seconds(safe.durationSeconds),
      "Buffered ahead seconds: " + seconds(safe.bufferAheadSeconds),
      "Playback speed: " + speed,
      "Rebuffer events while diagnostics open: " + seconds(safe.rebufferEvents),
      "Decoder: " + decoder,
      "Source throughput: unavailable",
      "Network link hint: " + networkHint,
      "Troubleshooting: " + advice,
    ].join("\n");
  }

  function createSession() {
    let enabled = false, last = null, started = false, previousLoading = true, rebuffers = 0;
    return {
      setEnabled(value) {
        enabled = value === true;
        last = null;
        started = false;
        previousLoading = true;
        rebuffers = 0;
      },
      update(input) {
        if (!enabled || !input || typeof input !== "object") return;
        const loading = input.loading === true;
        if (loading && !previousLoading && started && input.paused === false && input.seeking !== true) {
          rebuffers = Math.min(rebuffers + 1, 1000000);
        }
        if (!loading && input.paused === false && input.seeking !== true) started = true;
        previousLoading = loading || input.seeking === true;
        // No track names, URLs, or arbitrary native metadata are retained.
        last = Object.fromEntries([
          "loading", "paused", "duration", "position", "videoWidth", "videoHeight",
          "bufferAheadSeconds", "playbackSpeed", "decoderMode",
        ].map(key => [key, input[key]]));
      },
      metrics(networkOnline) { return normalize({ ...(last || {}), networkOnline }, rebuffers); },
    };
  }

  const api = Object.freeze({ normalize, report, createSession });
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (root) root.StarlitPlaybackDiagnostics = api;
})(typeof window !== "undefined" ? window : null);
