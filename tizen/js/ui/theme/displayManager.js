import { Platform } from "../../platform/index.js";
import {
  TV_INTERFACE_SCALES,
  applyTvViewport,
  getTvViewportState
} from "../../platform/tvViewport.js";
import { DisplaySettingsStore } from "../../data/local/displaySettingsStore.js";
// Applies Settings > Display & scaling: the canvas scale (interface size and
// screen edges) plus the root classes the stylesheet keys text size and motion
// off. Kept apart from ThemeManager because none of this is per profile.
const TEXT_SIZE_CLASSES = Object.freeze({
  default: "",
  large: "tv-text-large",
  xlarge: "tv-text-xlarge"
});
const REDUCE_MOTION_CLASS = "tv-reduce-motion";
const RESIZE_DEBOUNCE_MS = 120;
let initialized = false;
let resizeTimer = null;
function setRootClass(name, enabled) {
  // The legacy-engine fallbacks are keyed off <body> as well as <html>.
  [document.documentElement, document.body].forEach((node) => {
    if (node && node.classList) {
      node.classList.toggle(name, Boolean(enabled));
    }
  });
}
export const DisplayManager = {
  apply(settings = DisplaySettingsStore.get()) {
    const state = applyTvViewport({
      isTizen: Platform.isTizen(),
      interfaceSize: TV_INTERFACE_SCALES[settings.interfaceSize] || 1,
      overscan: settings.overscan
    });
    Object.keys(TEXT_SIZE_CLASSES).forEach((key) => {
      const className = TEXT_SIZE_CLASSES[key];
      if (className) {
        setRootClass(className, settings.textSize === key);
      }
    });
    setRootClass(REDUCE_MOTION_CLASS, settings.reduceMotion);
    return state;
  },
  init() {
    const state = this.apply();
    if (initialized) {
      return state;
    }
    initialized = true;
    DisplaySettingsStore.subscribe((settings) => {
      this.apply(settings);
    });
    // Browsers resize; a TV panel does not, but a Smart Monitor can change
    // input mode (and with it the aspect ratio) while the app is open.
    window.addEventListener("resize", () => {
      if (resizeTimer) {
        clearTimeout(resizeTimer);
      }
      resizeTimer = setTimeout(() => {
        resizeTimer = null;
        this.apply();
      }, RESIZE_DEBOUNCE_MS);
    });
    return state;
  },
  /**
   * What Settings shows under "Detected": the panel, the canvas the interface
   * is drawn on, and how many panel pixels back each canvas pixel.
   */
  describe() {
    const state = getTvViewportState() || this.apply();
    const sharpness = state.panelScale * state.scale;
    return {
      screenWidth: Math.round(state.screenWidth),
      screenHeight: Math.round(state.screenHeight),
      canvasWidth: state.appWidth,
      canvasHeight: state.appHeight,
      sharpness: Math.round(sharpness * 100) / 100,
      isWide: state.isWide
    };
  }
};
