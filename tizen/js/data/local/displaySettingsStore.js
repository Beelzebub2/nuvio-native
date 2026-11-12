import { LocalStore } from "../../core/storage/localStore.js";
// Settings > Display & scaling. These describe this TV's panel (overscan,
// viewing distance), not the person watching, so they are stored per device:
// not per profile and never synced to the account.
const KEY = "tvDisplaySettings";
export const INTERFACE_SIZE_OPTIONS = Object.freeze(["auto", "compact", "standard", "large"]);
export const OVERSCAN_OPTIONS = Object.freeze([0, 0.025, 0.05]);
export const TEXT_SIZE_OPTIONS = Object.freeze(["default", "large", "xlarge"]);
export const DEFAULT_DISPLAY_SETTINGS = Object.freeze({
  interfaceSize: "auto",
  overscan: 0,
  textSize: "default",
  reduceMotion: false
});
const listeners = new Set();
function nearestOverscan(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return DEFAULT_DISPLAY_SETTINGS.overscan;
  }
  return OVERSCAN_OPTIONS.reduce((nearest, option) =>
    Math.abs(option - number) < Math.abs(nearest - number) ? option : nearest
  );
}
export function normalizeDisplaySettings(settings = {}) {
  const source = settings && typeof settings === "object" ? settings : {};
  const interfaceSize = String(source.interfaceSize || "").toLowerCase();
  const textSize = String(source.textSize || "").toLowerCase();
  return {
    interfaceSize: INTERFACE_SIZE_OPTIONS.includes(interfaceSize)
      ? interfaceSize
      : DEFAULT_DISPLAY_SETTINGS.interfaceSize,
    overscan: nearestOverscan(source.overscan),
    textSize: TEXT_SIZE_OPTIONS.includes(textSize) ? textSize : DEFAULT_DISPLAY_SETTINGS.textSize,
    reduceMotion: source.reduceMotion === true
  };
}
export const DisplaySettingsStore = {
  get() {
    return normalizeDisplaySettings(LocalStore.get(KEY, null));
  },
  set(partial = {}) {
    const next = normalizeDisplaySettings({ ...this.get(), ...partial });
    LocalStore.set(KEY, next);
    listeners.forEach((listener) => {
      try {
        listener(next);
      } catch (error) {
        console.warn("Display settings listener failed", error);
      }
    });
    return next;
  },
  reset() {
    return this.set(DEFAULT_DISPLAY_SETTINGS);
  },
  subscribe(listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  }
};
