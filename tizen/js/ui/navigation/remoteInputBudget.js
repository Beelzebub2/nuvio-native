// A TV with a blocked render thread may deliver several held D-pad repeats
// after the user has released the key. Replaying every outdated repeat makes
// remote navigation appear to run several steps behind. Never discard the
// initial press, Enter/Back, or a recent repeat.
const STALE_REPEAT_AFTER_MS = 240;
const MAX_TRUSTED_EVENT_AGE_MS = 30_000;

export function isStaleDirectionalRepeat(event, normalizedKeyCode, clocks = null) {
  if (!event?.repeat) return false;
  const code = Number(normalizedKeyCode || 0);
  if (code < 37 || code > 40) return false;
  const timestamp = Number(event.timeStamp);
  if (!Number.isFinite(timestamp) || timestamp <= 0) return false;

  // Older TV Chromium builds may expose epoch-based event timestamps while
  // modern builds use the monotonic performance clock. Treat an implausible
  // difference as a clock mismatch and preserve the input rather than lose it.
  const epochTimestamp = timestamp >= 1e12;
  const now = epochTimestamp
    ? Number(clocks?.epochNow ?? Date.now())
    : Number(clocks?.monotonicNow ?? globalThis.performance?.now?.());
  if (!Number.isFinite(now)) return false;
  const ageMs = now - timestamp;
  return ageMs >= STALE_REPEAT_AFTER_MS && ageMs <= MAX_TRUSTED_EVENT_AGE_MS;
}
