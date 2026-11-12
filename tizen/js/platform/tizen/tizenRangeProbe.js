const RANGE_PROBE_ERROR = "Local streaming Range probe failed";

// Older Tizen WebKit versions can hide Content-Range on cross-origin fetches,
// even when the loopback service exposes it. Verify the exact bounded bytes
// as well as HTTP 206; do not mistake a full HTTP 200 response for Range support.
export async function verifyTizenRangeProbe(response) {
  if (response?.status !== 206) {
    throw new Error(RANGE_PROBE_ERROR);
  }
  const contentRange = response.headers?.get?.("content-range");
  if (contentRange && contentRange !== "bytes 1-3/22") {
    throw new Error(RANGE_PROBE_ERROR);
  }
  if ((await response.text()) !== "tar") {
    throw new Error(RANGE_PROBE_ERROR);
  }
}
