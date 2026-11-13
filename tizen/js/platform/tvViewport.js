// Starlit TV viewport: one 1080-tall reference canvas whose width follows the
// screen's aspect ratio. 16:9 TVs stay 1920 wide; 21:9 and 32:9 Smart Monitors
// get a wider canvas (more cards per row), never bigger text.
//
// Every CSS length in the app is a px value on that canvas (scripts/build.mjs
// resolves the older vw/vh/clamp() styles to reference px), so this module is
// the only place that maps the canvas onto the panel:
//
//   - Tizen: the meta viewport asks the platform for a `width x 1080` layout
//     viewport and the platform scales it onto the panel (deviceScale = 1).
//   - Browsers, previews and any TV that ignores that request: the canvas is
//     scaled here with a transform.
//
// The scale is always derived from the viewport the engine actually gave us,
// so a firmware that keeps 1920 despite the request still renders correctly.
//
// NOT DEVICE-VERIFIED: that Tizen honours a viewport width other than 1920.
export const TV_REFERENCE_HEIGHT = 1080;
export const TV_MIN_LAYOUT_WIDTH = 1920;
export const TV_MAX_LAYOUT_WIDTH = 3840;
export const TV_INTERFACE_SCALES = Object.freeze({
  auto: 1,
  compact: 0.9,
  standard: 1,
  large: 1.15
});
export const TV_OVERSCAN_STEPS = Object.freeze([0, 0.025, 0.05]);
const WIDE_CLASS = "tv-wide";
const SCALED_CLASS = "tv-canvas-scaled";
const NARROW_CLASS = "tv-narrow";
let currentState = null;
let geometryShimInstalled = false;
let nativeGetBoundingClientRect = null;
let nativeGetClientRects = null;
const listeners = new Set();
const nativeViewportGetters = {};
function toPositiveNumber(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : fallback;
}
function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}
/** Reference canvas for a panel: 1080 tall, width from the aspect ratio. */
export function computeTvLayout(screenWidth, screenHeight) {
  const width = toPositiveNumber(screenWidth, TV_MIN_LAYOUT_WIDTH);
  const height = toPositiveNumber(screenHeight, TV_REFERENCE_HEIGHT);
  return {
    width: clamp(
      Math.round(TV_REFERENCE_HEIGHT * (width / height)),
      TV_MIN_LAYOUT_WIDTH,
      TV_MAX_LAYOUT_WIDTH
    ),
    height: TV_REFERENCE_HEIGHT
  };
}
export function normalizeInterfaceScale(value) {
  const scale = Number(value);
  return Number.isFinite(scale) && scale >= 0.5 && scale <= 2 ? scale : 1;
}
export function normalizeOverscan(value) {
  const overscan = Number(value);
  if (!Number.isFinite(overscan) || overscan <= 0) return 0;
  return Math.min(overscan, 0.1);
}
/**
 * Pure geometry: how the app canvas sits inside a layout viewport.
 *
 * `appWidth`/`appHeight` are the canvas size in CSS px (what the UI lays out
 * against), `scale` and `offsetX`/`offsetY` place it in the viewport. Interface
 * size changes how much fits (a smaller canvas for "Large"); screen edges only
 * shrink the picture, they never change the layout.
 */
export function computeTvCanvas({
  viewportWidth,
  viewportHeight,
  interfaceSize = 1,
  overscan = 0
} = {}) {
  const viewWidth = toPositiveNumber(viewportWidth, TV_MIN_LAYOUT_WIDTH);
  const viewHeight = toPositiveNumber(viewportHeight, TV_REFERENCE_HEIGHT);
  const userScale = normalizeInterfaceScale(interfaceSize);
  const inset = normalizeOverscan(overscan);
  // Fit the 1080-tall canvas; a window narrower than 16:9 letterboxes instead
  // of squeezing the 1920 minimum.
  const deviceScale = Math.min(viewHeight / TV_REFERENCE_HEIGHT, viewWidth / TV_MIN_LAYOUT_WIDTH);
  const layoutWidth = clamp(
    Math.round(viewWidth / deviceScale),
    TV_MIN_LAYOUT_WIDTH,
    TV_MAX_LAYOUT_WIDTH
  );
  const boxWidth = layoutWidth * deviceScale;
  const boxHeight = TV_REFERENCE_HEIGHT * deviceScale;
  const boxX = (viewWidth - boxWidth) / 2;
  const boxY = (viewHeight - boxHeight) / 2;
  const scale = deviceScale * userScale * (1 - 2 * inset);
  return {
    layoutWidth,
    layoutHeight: TV_REFERENCE_HEIGHT,
    appWidth: Math.round(layoutWidth / userScale),
    appHeight: Math.round(TV_REFERENCE_HEIGHT / userScale),
    deviceScale,
    scale,
    offsetX: boxX + boxWidth * inset,
    offsetY: boxY + boxHeight * inset,
    isWide: layoutWidth > TV_MIN_LAYOUT_WIDTH + 1,
    isTransformed:
      Math.abs(scale - 1) > 0.0005 ||
      Math.abs(boxX + boxWidth * inset) > 0.5 ||
      Math.abs(boxY + boxHeight * inset) > 0.5
  };
}
function plainRect(left, top, width, height) {
  return {
    x: left,
    y: top,
    left,
    top,
    width,
    height,
    right: left + width,
    bottom: top + height,
    toJSON() {
      return { x: left, y: top, left, top, width, height, right: left + width, bottom: top + height };
    }
  };
}
/** Viewport (on-screen) rect -> canvas rect. Identity when nothing is scaled. */
export function toCanvasRect(rect, state = currentState) {
  if (!rect || !state || !state.isTransformed) {
    return rect;
  }
  return plainRect(
    (rect.left - state.offsetX) / state.scale,
    (rect.top - state.offsetY) / state.scale,
    rect.width / state.scale,
    rect.height / state.scale
  );
}
/** Canvas point -> viewport (on-screen) point, e.g. for elementFromPoint. */
export function toViewportPoint(x, y, state = currentState) {
  if (!state || !state.isTransformed) {
    return { x, y };
  }
  return { x: x * state.scale + state.offsetX, y: y * state.scale + state.offsetY };
}
/** Viewport point (pointer events) -> canvas point. */
export function toCanvasPoint(x, y, state = currentState) {
  if (!state || !state.isTransformed) {
    return { x, y };
  }
  return { x: (x - state.offsetX) / state.scale, y: (y - state.offsetY) / state.scale };
}
const AVPLAY_NATIVE_VIEWPORT = Object.freeze({ width: 1920, height: 1080 });
function nativeSize(native) {
  return {
    width: toPositiveNumber(native && native.width, AVPLAY_NATIVE_VIEWPORT.width),
    height: toPositiveNumber(native && native.height, AVPLAY_NATIVE_VIEWPORT.height)
  };
}
/**
 * Canvas rect -> AVPlay's native space. Samsung's setDisplayRect always takes
 * 1920x1080 coordinates for the whole panel, independent of the CSS viewport,
 * so a rect measured in the UI has to account for the canvas scale and the
 * screen-edge inset. A rect covering the whole canvas means "full screen": the
 * video plane then uses the entire panel rather than the inset UI box.
 *
 * NOT DEVICE-VERIFIED on 21:9 / 32:9 panels.
 */
export function canvasRectToNative(rect, native = AVPLAY_NATIVE_VIEWPORT, state = currentState) {
  const target = nativeSize(native);
  const canvasWidth = state ? state.appWidth : target.width;
  const canvasHeight = state ? state.appHeight : target.height;
  const x = Number(rect && rect.x) || 0;
  const y = Number(rect && rect.y) || 0;
  const width = toPositiveNumber(rect && rect.width, canvasWidth);
  const height = toPositiveNumber(rect && rect.height, canvasHeight);
  const coversCanvas =
    x <= 1 && y <= 1 && width >= canvasWidth - 1 && height >= canvasHeight - 1;
  if (coversCanvas) {
    return { x: 0, y: 0, width: target.width, height: target.height };
  }
  const scale = state ? state.scale : 1;
  const offsetX = state ? state.offsetX : 0;
  const offsetY = state ? state.offsetY : 0;
  const viewWidth = state ? state.viewportWidth : canvasWidth;
  const viewHeight = state ? state.viewportHeight : canvasHeight;
  return {
    x: ((x * scale + offsetX) / viewWidth) * target.width,
    y: ((y * scale + offsetY) / viewHeight) * target.height,
    width: ((width * scale) / viewWidth) * target.width,
    height: ((height * scale) / viewHeight) * target.height
  };
}
/** AVPlay native rect -> canvas rect, for the element that punches the hole. */
export function nativeRectToCanvas(rect, native = AVPLAY_NATIVE_VIEWPORT, state = currentState) {
  const source = nativeSize(native);
  const viewWidth = state ? state.viewportWidth : source.width;
  const viewHeight = state ? state.viewportHeight : source.height;
  const scale = state ? state.scale : 1;
  const offsetX = state ? state.offsetX : 0;
  const offsetY = state ? state.offsetY : 0;
  return {
    x: ((Number(rect && rect.x) || 0) / source.width * viewWidth - offsetX) / scale,
    y: ((Number(rect && rect.y) || 0) / source.height * viewHeight - offsetY) / scale,
    width: ((Number(rect && rect.width) || 0) / source.width) * viewWidth / scale,
    height: ((Number(rect && rect.height) || 0) / source.height) * viewHeight / scale
  };
}
/**
 * The UI measures elements with getBoundingClientRect() and feeds the result
 * into scroll offsets and focus geometry, which are canvas px. While the canvas
 * is transformed those two spaces differ, so report canvas px everywhere and
 * keep the rest of the app unaware of the scale.
 */
function installGeometryShim() {
  if (geometryShimInstalled || typeof Element === "undefined") {
    return;
  }
  const proto = Element.prototype;
  nativeGetBoundingClientRect = proto.getBoundingClientRect;
  nativeGetClientRects = proto.getClientRects;
  if (typeof nativeGetBoundingClientRect !== "function") {
    return;
  }
  geometryShimInstalled = true;
  proto.getBoundingClientRect = function getCanvasBoundingClientRect() {
    const rect = nativeGetBoundingClientRect.call(this);
    return currentState && currentState.isTransformed ? toCanvasRect(rect) : rect;
  };
  if (typeof nativeGetClientRects === "function") {
    proto.getClientRects = function getCanvasClientRects() {
      const rects = nativeGetClientRects.call(this);
      if (!currentState || !currentState.isTransformed) {
        return rects;
      }
      return Array.prototype.map.call(rects, (rect) => toCanvasRect(rect));
    };
  }
  const defineViewportSize = (name, read) => {
    try {
      const descriptor =
        Object.getOwnPropertyDescriptor(window, name) ||
        Object.getOwnPropertyDescriptor(Object.getPrototypeOf(window), name);
      const nativeGet = descriptor && descriptor.get;
      if (!nativeGet) {
        return;
      }
      Object.defineProperty(window, name, {
        configurable: true,
        enumerable: true,
        get() {
          return currentState && currentState.isTransformed ? read() : nativeGet.call(window);
        },
        set() {}
      });
      nativeViewportGetters[name] = nativeGet;
    } catch (_) {
      // Keep the native value; layout still works, only edge checks drift.
    }
  };
  defineViewportSize("innerWidth", () => currentState.appWidth);
  defineViewportSize("innerHeight", () => currentState.appHeight);
}
function readNativeViewportSize(name, fallback) {
  const getter = nativeViewportGetters[name];
  if (getter) {
    try {
      return Number(getter.call(window)) || fallback;
    } catch (_) {
      return fallback;
    }
  }
  return Number(window[name]) || fallback;
}
/** The untransformed on-screen rect of an element, bypassing the shim. */
export function getNativeBoundingClientRect(element) {
  if (!element) {
    return null;
  }
  return (nativeGetBoundingClientRect || Element.prototype.getBoundingClientRect).call(element);
}
function updateViewportMeta(layout) {
  const meta = document.querySelector("meta[name='viewport']");
  if (!meta) {
    return;
  }
  const content =
    `width=${layout.width}, height=${TV_REFERENCE_HEIGHT}, ` +
    "initial-scale=1.0, maximum-scale=1.0, user-scalable=no";
  if (meta.getAttribute("content") !== content) {
    meta.setAttribute("content", content);
  }
}
/**
 * Lay the app canvas out for the current screen and settings.
 *
 * options.isTizen       index.html's launch detection result.
 * options.interfaceSize 0.9 | 1 | 1.15 (Settings > Display & scaling).
 * options.overscan      0 | 0.025 | 0.05 (Settings > Screen edges).
 */
export function applyTvViewport(options = {}) {
  const root = document.documentElement;
  const app = document.getElementById("app");
  const screenWidth = Number(window.screen && window.screen.width) || 0;
  const screenHeight = Number(window.screen && window.screen.height) || 0;
  if (options.isTizen) {
    updateViewportMeta(computeTvLayout(screenWidth, screenHeight));
  }
  installGeometryShim();
  const viewportWidth = readNativeViewportSize("innerWidth", TV_MIN_LAYOUT_WIDTH);
  const viewportHeight = readNativeViewportSize("innerHeight", TV_REFERENCE_HEIGHT);
  const canvas = computeTvCanvas({
    viewportWidth,
    viewportHeight,
    interfaceSize: options.interfaceSize,
    overscan: options.overscan
  });
  const state = Object.freeze({
    ...canvas,
    viewportWidth,
    viewportHeight,
    screenWidth: screenWidth || viewportWidth,
    screenHeight: screenHeight || viewportHeight,
    interfaceSize: normalizeInterfaceScale(options.interfaceSize),
    overscan: normalizeOverscan(options.overscan),
    // Panel pixels per layout-viewport px (2 on a 4K set laid out at 1920).
    panelScale: screenHeight > 0 && viewportHeight > 0 ? screenHeight / viewportHeight : 1,
    // Multiply a canvas length by this to get layout-viewport px.
    cssToScreen: canvas.scale
  });
  currentState = state;
  root.style.setProperty("--tv-layout-width", `${state.appWidth}px`);
  root.style.setProperty("--tv-layout-height", `${state.appHeight}px`);
  root.classList.toggle(WIDE_CLASS, state.isWide);
  root.classList.toggle(SCALED_CLASS, state.isTransformed);
  // "Large" interface size lays out a canvas narrower than 1920: two-pane
  // screens tighten their side pane (no CSS min() on Tizen 4).
  root.classList.toggle(NARROW_CLASS, state.appWidth < TV_MIN_LAYOUT_WIDTH - 100);
  if (app) {
    app.style.width = `${state.appWidth}px`;
    app.style.height = `${state.appHeight}px`;
    const transform = state.isTransformed
      ? `translate(${state.offsetX}px, ${state.offsetY}px) scale(${state.scale})`
      : "none";
    app.style.webkitTransformOrigin = "0 0";
    app.style.transformOrigin = "0 0";
    app.style.webkitTransform = transform;
    app.style.transform = transform;
  }
  listeners.forEach((listener) => {
    try {
      listener(state);
    } catch (error) {
      console.warn("TV viewport listener failed", error);
    }
  });
  return state;
}
export function getTvViewportState() {
  return currentState;
}
/** Canvas size the UI lays out against, with the 1920x1080 reference fallback. */
export function getTvCanvasSize() {
  return {
    width: currentState ? currentState.appWidth : TV_MIN_LAYOUT_WIDTH,
    height: currentState ? currentState.appHeight : TV_REFERENCE_HEIGHT
  };
}
export function subscribeTvViewport(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
/**
 * Where full-screen overlays (toasts, the screensaver) mount. They have to live
 * inside the canvas or they would ignore the scale and the screen-edge inset.
 */
export function getTvCanvasRoot() {
  return document.getElementById("app") || document.body;
}
/** Test hook: forget the applied state and restore native geometry. */
export function resetTvViewportForTests() {
  currentState = null;
  if (geometryShimInstalled && typeof Element !== "undefined") {
    Element.prototype.getBoundingClientRect = nativeGetBoundingClientRect;
    if (nativeGetClientRects) {
      Element.prototype.getClientRects = nativeGetClientRects;
    }
  }
  geometryShimInstalled = false;
  nativeGetBoundingClientRect = null;
  nativeGetClientRects = null;
  listeners.clear();
}
