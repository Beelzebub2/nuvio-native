import test from "node:test";
import assert from "node:assert/strict";
import {
  getTizenCapabilities,
  canOfferTizen5PluginPreview,
  isTizen5PluginPreviewEnabled,
  setTizen5PluginPreviewEnabled,
  resetTizenCapabilitiesCache
} from "../js/platform/tizen/tizenCapabilities.js";
import { validateLegacyCorsPluginUrl } from "../js/core/player/pluginSecurity.js";

function legacyRuntime(version = "5.0", worker = true) {
  const storage = new Map();
  return {
    __NUVIO_PLATFORM__: "tizen",
    __NUVIO_TIZEN_VERSION__: version,
    navigator: { userAgent: "Mozilla/5.0 (Tizen " + version + ") Chrome/63.0.3239.84" },
    Worker: worker ? function Worker() {} : undefined,
    localStorage: {
      getItem(key) { return storage.get(key) || null; },
      setItem(key, value) { storage.set(key, String(value)); }
    }
  };
}

test("Tizen 5 plugin preview defaults off and cannot opt in without Worker", () => {
  const tv = legacyRuntime();
  assert.equal(getTizenCapabilities(tv).tizenPluginVersionSupported, false);
  assert.equal(canOfferTizen5PluginPreview(tv), true);
  assert.equal(isTizen5PluginPreviewEnabled(tv), false);
  assert.equal(setTizen5PluginPreviewEnabled(true, tv), true);
  assert.equal(isTizen5PluginPreviewEnabled(tv), true);
  assert.equal(setTizen5PluginPreviewEnabled(false, tv), true);
  assert.equal(isTizen5PluginPreviewEnabled(tv), false);
  const missingWorker = legacyRuntime("5.0", false);
  assert.equal(canOfferTizen5PluginPreview(missingWorker), false);
  assert.equal(setTizen5PluginPreviewEnabled(true, missingWorker), false);
  const old = legacyRuntime("4.0");
  assert.equal(canOfferTizen5PluginPreview(old), false);
  assert.equal(setTizen5PluginPreviewEnabled(true, old), false);
});

test("legacy CORS transport accepts public HTTPS only", () => {
  for (const url of [
    "https://example.com/manifest.json",
    "https://cdn.example.org/provider.js"
  ]) assert.equal(validateLegacyCorsPluginUrl(url).ok, true);
  for (const url of [
    "http://example.com",
    "https://127.0.0.1/",
    "https://[::1]/",
    "https://router.local/",
    "https://user:pass@example.com/",
    "https://example.org:8443/",
    "https://localhost/",
    "https://192.168.1.1/",
    "https://example.internal/"
  ]) assert.equal(validateLegacyCorsPluginUrl(url).ok, false, url);
});

test("Tizen 5 preview changes capability to limited asm.js only after opt in", async () => {
  const previous = {
    platform: globalThis.__NUVIO_PLATFORM__,
    version: globalThis.__NUVIO_TIZEN_VERSION__,
    worker: globalThis.Worker,
    wasm: globalThis.WebAssembly,
    storage: globalThis.localStorage
  };
  const { Platform } = await import("../js/platform/index.js");
  const oldAdapter = Platform.current;
  const tv = legacyRuntime();
  try {
    globalThis.__NUVIO_PLATFORM__ = "tizen";
    globalThis.__NUVIO_TIZEN_VERSION__ = "5.0";
    globalThis.Worker = tv.Worker;
    globalThis.WebAssembly = undefined;
    globalThis.localStorage = tv.localStorage;
    Platform.current = { name: "tizen" };
    resetTizenCapabilitiesCache();
    const { getPluginCapabilitySnapshot, PLUGIN_QUOTAS } =
      await import("../js/core/player/pluginPolicy.js");
    const off = getPluginCapabilitySnapshot();
    assert.equal(off.candidate, false);
    assert.equal(off.legacyPreviewEligible, true);
    assert.equal(setTizen5PluginPreviewEnabled(true), true);
    const enabled = getPluginCapabilitySnapshot();
    assert.equal(enabled.candidate, true);
    assert.equal(enabled.runtimeVariant, "asmjs");
    assert.equal(enabled.quota, PLUGIN_QUOTAS.limited);
    assert.equal(enabled.quota.maxConcurrent, 1);
    assert.equal(enabled.pluginServicePackaged, false);
  } finally {
    if (previous.platform === undefined) delete globalThis.__NUVIO_PLATFORM__;
    else globalThis.__NUVIO_PLATFORM__ = previous.platform;
    if (previous.version === undefined) delete globalThis.__NUVIO_TIZEN_VERSION__;
    else globalThis.__NUVIO_TIZEN_VERSION__ = previous.version;
    if (previous.worker === undefined) delete globalThis.Worker;
    else globalThis.Worker = previous.worker;
    globalThis.WebAssembly = previous.wasm;
    if (previous.storage === undefined) delete globalThis.localStorage;
    else globalThis.localStorage = previous.storage;
    Platform.current = oldAdapter;
    resetTizenCapabilitiesCache();
  }
});

test("the QuickJS asm.js release variant executes a fixture without WASM", async () => {
  const previous = globalThis.WebAssembly;
  try {
    globalThis.WebAssembly = undefined;
    const { default: variant } = await import("@jitl/quickjs-asmjs-mjs-release-sync");
    const { newQuickJSWASMModuleFromVariant } = await import("quickjs-emscripten-core");
    const quickJS = await newQuickJSWASMModuleFromVariant(variant);
    const context = quickJS.newContext();
    try {
      const evaluation = context.evalCode(
        'var module = { exports: {} }; module.exports.getStreams = function() { return [{ url: "https://media.example.com/sample.mp4" }]; }; JSON.stringify(module.exports.getStreams());'
      );
      const result = context.unwrapResult(evaluation);
      try {
        assert.match(String(context.dump(result)), /media\.example\.com/);
      } finally {
        result.dispose();
      }
    } finally {
      context.dispose();
    }
  } finally {
    globalThis.WebAssembly = previous;
  }
});
