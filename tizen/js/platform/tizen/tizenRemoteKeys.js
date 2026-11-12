// Samsung's mandatory D-pad/Enter/Back keys are delivered automatically and
// cannot be registered. Only register optional controls the app actually uses.
export const TIZEN_MEDIA_KEY_NAMES = Object.freeze([
  "Info",
  "MediaPlayPause",
  "MediaPlay",
  "MediaPause",
  "MediaStop",
  "MediaFastForward",
  "MediaRewind",
  "MediaTrackPrevious",
  "MediaTrackNext"
]);

export function getSupportedTizenMediaKeys(inputDevice) {
  if (typeof inputDevice?.getSupportedKeys !== "function") {
    return [...TIZEN_MEDIA_KEY_NAMES];
  }
  try {
    const keys = inputDevice.getSupportedKeys();
    if (!Array.isArray(keys)) {
      return [...TIZEN_MEDIA_KEY_NAMES];
    }
    const available = new Set(keys.map((key) => String(key?.name || "")));
    return TIZEN_MEDIA_KEY_NAMES.filter((key) => available.has(key));
  } catch (_) {
    // Older firmware can omit this API; individual registrations still
    // fail independently and cannot prevent D-pad/Back from working.
    return [...TIZEN_MEDIA_KEY_NAMES];
  }
}

export function registerTizenMediaKeys(inputDevice) {
  const keys = getSupportedTizenMediaKeys(inputDevice);
  if (!keys.length) {
    return;
  }
  const fallback = () => {
    keys.forEach((key) => {
      try {
        inputDevice.registerKey?.(key);
      } catch (_) {
        // One unsupported optional key must not disable other media buttons.
      }
    });
  };
  if (typeof inputDevice?.registerKeyBatch === "function") {
    try {
      inputDevice.registerKeyBatch(keys, undefined, fallback);
      return;
    } catch (_) {
      // Tizen may reject a batch synchronously; retry individual keys.
    }
  }
  fallback();
}
