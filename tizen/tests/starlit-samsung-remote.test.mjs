import test from "node:test";
import assert from "node:assert/strict";
import {
  TIZEN_MEDIA_KEY_NAMES,
  getSupportedTizenMediaKeys,
  registerTizenMediaKeys
} from "../js/platform/tizen/tizenRemoteKeys.js";
import { normalizeKeyEvent, isBackEvent } from "../js/platform/sharedKeys.js";
import { tizenAdapter } from "../js/platform/adapters/tizenAdapter.js";

import { FocusEngine } from "../js/ui/navigation/focusEngine.js";
import { Platform } from "../js/platform/index.js";
import { Router } from "../js/ui/navigation/routerState.js";

// Construct the module once with a browser-like storage shim. The player
// resolver is pure; the wider player module has repository singletons.
const previousLocalStorage = globalThis.localStorage;
if (!previousLocalStorage) {
  globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
}
const { createPlayerScreenMethods33 } = await import("../js/ui/screens/player/playerScreenMethods-33-resolve-media-action.js");
if (!previousLocalStorage) delete globalThis.localStorage;
const mandatory = new Set(["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Enter", "Back", "Return"]);

test("only optional Samsung keys are registered, filtered by actual TV support", () => {
  assert(TIZEN_MEDIA_KEY_NAMES.includes("MediaPlayPause"));
  assert(TIZEN_MEDIA_KEY_NAMES.includes("MediaTrackNext"));
  assert(TIZEN_MEDIA_KEY_NAMES.every((name) => !mandatory.has(name)));
  const batches = [];
  const input = {
    getSupportedKeys: () => ["Back", "Enter", "MediaPlayPause", "MediaTrackNext"].map((name) => ({ name })),
    registerKeyBatch(keys) { batches.push([...keys]); }
  };
  assert.deepEqual(getSupportedTizenMediaKeys(input), ["MediaPlayPause", "MediaTrackNext"]);
  registerTizenMediaKeys(input);
  assert.deepEqual(batches, [["MediaPlayPause", "MediaTrackNext"]]);
});

test("batch rejection retries optional keys individually without failing startup", () => {
  const calls = [];
  let failure;
  const input = {
    registerKeyBatch(keys, success, fail) { failure = fail; calls.push("batch"); },
    registerKey(key) { calls.push(key); if (key === "MediaTrackPrevious") throw new Error("unsupported"); }
  };
  registerTizenMediaKeys(input);
  assert.deepEqual(calls, ["batch"]);
  failure(new Error("unsupported"));
  assert.deepEqual(calls.slice(1), TIZEN_MEDIA_KEY_NAMES);
  assert.doesNotThrow(() => registerTizenMediaKeys({
    getSupportedKeys() { throw new Error("older TV"); },
    registerKeyBatch() { throw new Error("no batch"); },
    registerKey() { throw new Error("unsupported key"); }
  }));
});

test("adapter honors successful early registration; retries failed early registration", () => {
  const previousTizen = globalThis.tizen;
  const previousFlag = globalThis.__NUVIO_TIZEN_MEDIA_KEYS_REGISTERED__;
  const batches = [];
  try {
    globalThis.tizen = { tvinputdevice: { registerKeyBatch(keys) { batches.push([...keys]); } } };
    globalThis.__NUVIO_TIZEN_MEDIA_KEYS_REGISTERED__ = true;
    tizenAdapter.init();
    assert.equal(batches.length, 0);
    globalThis.__NUVIO_TIZEN_MEDIA_KEYS_REGISTERED__ = false;
    tizenAdapter.init();
    assert.deepEqual(batches, [[...TIZEN_MEDIA_KEY_NAMES]]);
  } finally {
    if (previousTizen === undefined) delete globalThis.tizen;
    else globalThis.tizen = previousTizen;
    if (previousFlag === undefined) delete globalThis.__NUVIO_TIZEN_MEDIA_KEYS_REGISTERED__;
    else globalThis.__NUVIO_TIZEN_MEDIA_KEYS_REGISTERED__ = previousFlag;
  }
});

test("Samsung D-pad, OK, Back and transport codes normalize without collisions", () => {
  for (const [code, expected] of [[37,37],[38,38],[39,39],[40,40],[13,13],[10009,10009],[10252,10252],[415,415],[19,19],[417,417],[412,412],[10232,10232],[10233,10233]]) {
    assert.equal(normalizeKeyEvent({ keyCode: code }).keyCode, expected, `remote key ${code}`);
  }
  assert.equal(normalizeKeyEvent({ keyName: "Return", keyCode: 0 }).keyCode, 13);
  assert.equal(normalizeKeyEvent({ keyName: "MediaTrackNext", keyCode: 0 }).keyCode, 10233);
  assert.equal(normalizeKeyEvent({ keyName: "MediaTrackPrevious", keyCode: 0 }).keyCode, 10232);
  assert.equal(normalizeKeyEvent({ keyName: "Back", keyCode: 0 }).keyCode, 10009);
  assert.equal(normalizeKeyEvent({ key: "XF86Back", keyCode: 0 }).isBack, true);
  assert.equal(isBackEvent({ keyCode: 10009 }, [10009]), true);
  assert.equal(isBackEvent({ keyCode: 10233 }, [10009]), false);
  assert.equal(isBackEvent({ keyCode: 10232 }, [10009]), false);
  assert.equal(isBackEvent({ keyCode: 8, key: "Backspace", target: { tagName: "INPUT" } }, [8,10009]), false);
});

test("player transport keys map to playback commands using Samsung's real numeric codes", () => {
  const resolve = createPlayerScreenMethods33().resolveMediaAction;
  for (const [keyCode, expected] of [[10252,"toggle"],[415,"play"],[19,"pause"],[413,"stop"],[417,"fastForward"],[412,"rewind"],[10233,"next"],[10232,"previous"],[176,"next"],[177,"previous"]]) {
    assert.equal(resolve({ keyCode }), expected, `playback key ${keyCode}`);
  }
});

test("Samsung Back dispatches once per press and does not swallow subsequent remote focus", () => {
  const previousDocument = globalThis.document;
  const previousPlatform = Platform.current;
  const originals = Object.fromEntries(["getCurrentScreen", "back", "consumeRouteReturnBackGuard", "suppressNextPopstate"].map((name) => [name, Router[name]]));
  const routed = [];
  try {
    Platform.current = tizenAdapter;
    globalThis.document = { contains: () => true, body: { classList: { contains: () => false } } };
    Router.getCurrentScreen = () => ({ onKeyDown: (event) => routed.push(event.keyCode) });
    Router.back = () => routed.push("back");
    Router.consumeRouteReturnBackGuard = () => false;
    Router.suppressNextPopstate = () => {};
    FocusEngine.lastBackHandledAt = 0;
    FocusEngine.activeBackKeyIdentities.clear();
    FocusEngine.activeKeyDownStartedAt.clear();
    const key = (keyCode, name, repeat = false) => ({
      keyCode, key: name, repeat, target: { tagName: "DIV" },
      preventDefault() {}, stopPropagation() {}, stopImmediatePropagation() {}
    });
    const back = key(10009, "Back");
    FocusEngine.handleKey(back);
    FocusEngine.handleKey(key(461, "XF86Back", true));
    FocusEngine.handleKey(key(10009, "Back", true));
    assert.deepEqual(routed, ["back"], "one physical Back hold changes one route");
    // Simulate firmware losing key-up during a route change.
    FocusEngine.handleKey(key(40, "ArrowDown"));
    assert.equal(FocusEngine.activeBackKeyIdentities.size, 0);
    assert.deepEqual(routed, ["back", 40]);
    FocusEngine.lastBackHandledAt = 0; // simulate the next independent press
    FocusEngine.handleKey(back);
    assert.deepEqual(routed, ["back", 40, "back"]);
  } finally {
    Platform.current = previousPlatform;
    if (previousDocument === undefined) delete globalThis.document;
    else globalThis.document = previousDocument;
    for (const [name, value] of Object.entries(originals)) {
      if (value === undefined) delete Router[name];
      else Router[name] = value;
    }
    FocusEngine.activeBackKeyIdentities.clear();
    FocusEngine.activeKeyDownStartedAt.clear();
    FocusEngine.lastBackHandledAt = 0;
  }
});

test("queued stale Samsung D-pad repeats are discarded without delaying new presses or OK", () => {
  const previousDocument = globalThis.document;
  const previousPlatform = Platform.current;
  const originalGetCurrentScreen = Router.getCurrentScreen;
  const originalPerformance = globalThis.performance;
  const received = [];
  const cancelled = [];
  try {
    Platform.current = tizenAdapter;
    globalThis.document = { contains: () => true, body: { classList: { contains: () => false } } };
    globalThis.performance = { now: () => 3_000 };
    Router.getCurrentScreen = () => ({ onKeyDown(event) { received.push(event.keyCode); } });
    const key = (keyCode, repeat, timeStamp) => ({
      keyCode,
      repeat,
      timeStamp,
      target: { tagName: "DIV" },
      preventDefault() { cancelled.push("default"); },
      stopImmediatePropagation() { cancelled.push("propagation"); }
    });
    FocusEngine.handleKey(key(40, true, 2_000));
    FocusEngine.handleKey(key(40, true, 2_900));
    FocusEngine.handleKey(key(39, false, 2_000));
    FocusEngine.handleKey(key(13, true, 2_000));
    assert.deepEqual(received, [40, 39, 13]);
    assert.deepEqual(cancelled, ["default", "propagation"]);
  } finally {
    Platform.current = previousPlatform;
    if (previousDocument === undefined) delete globalThis.document;
    else globalThis.document = previousDocument;
    if (originalPerformance === undefined) delete globalThis.performance;
    else globalThis.performance = originalPerformance;
    Router.getCurrentScreen = originalGetCurrentScreen;
    FocusEngine.activeKeyDownStartedAt.clear();
    FocusEngine.activeBackKeyIdentities.clear();
  }
});
