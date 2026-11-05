// Diagnostics for the native player controls. This module accepts only numeric
// mpv measurements and fixed state flags. It must never copy source metadata,
// log messages, URLs, headers, file paths, or account information.
(function (root) {
  "use strict";

  const finite = (value, min, max) =>
    typeof value === "number" && Number.isFinite(value) && value >= min && value <= max
      ? value : null;

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
    return {
      phase: input.loading === true ? "Buffering" : input.paused === false ? "Playing" : "Paused or idle",
      resolution: width !== null && height !== null ? `${Math.round(width)} x ${Math.round(height)}` : "Unavailable",
      positionSeconds: position === null ? "Unavailable" : Math.floor(position).toString(),
      durationSeconds: duration === null || duration <= 0 ? "Unavailable" : Math.floor(duration).toString(),
      bufferAheadSeconds: buffered === null ? "Unavailable" : Math.floor(buffered).toString(),
      playbackSpeed: playbackSpeed === null ? "Unavailable" : playbackSpeed.toFixed(2).replace(/0+$/, "").replace(/\.$/, "") + "x",
      rebufferEvents: String(Math.floor(finite(rebufferEvents, 0, 1000000) ?? 0)),
      decoder,
      throughput: "Unavailable",
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
    return [
      "Starlit playback diagnostics",
      "State: " + state,
      "Resolution: " + resolution,
      "Position seconds: " + seconds(safe.positionSeconds),
      "Duration seconds: " + seconds(safe.durationSeconds),
      "Buffered ahead seconds: " + seconds(safe.bufferAheadSeconds),
      "Playback speed: " + speed,
      "Rebuffer events since opening player: " + seconds(safe.rebufferEvents),
      "Decoder: " + decoder,
      "Source throughput: unavailable",
    ].join("\n");
  }

  const api = Object.freeze({ normalize, report });
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (root) root.StarlitPlaybackDiagnostics = api;
})(typeof window !== "undefined" ? window : null);
