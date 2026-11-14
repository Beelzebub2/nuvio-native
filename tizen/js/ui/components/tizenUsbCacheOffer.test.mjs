import test from "node:test";
import assert from "node:assert/strict";
// The main Router loads the app store during import; provide browser storage
// before loading the UI controller in this host-only test process.
globalThis.localStorage ??= { getItem: () => null, setItem: () => {} };
const { createTizenUsbCacheOfferController } = await import("./tizenUsbCacheOffer.js");

const GiB = 1024 ** 3;
const drive = (label = "removable1") => ({
  label, totalBytes: 4 * GiB, freeBytes: 3 * GiB, root: "/mnt/usb"
});
const sleep = (ms = 12) => new Promise((resolve) => setTimeout(resolve, ms));

function fixture({ mounted = [], route = "home", enabled = false, existingDialog = false, detectOverride = null } = {}) {
  const dialogs = [];
  const options = new Map();
  const listeners = new Map();
  let storageChange = null;
  let activeRoute = route;
  let mountedDrives = mounted;
  let usbEnabled = enabled;
  let subscribed = 0;
  let enabledCalls = 0;
  let scans = 0;
  class Dialog {
    constructor(config) {
      this.config = config;
      this.destroyed = false;
      dialogs.push(this);
    }
    mount() { this.mounted = true; }
    destroy() { this.destroyed = true; }
    choose(key) {
      const button = this.config.buttons.find((entry) => entry.key === key);
      assert(button, "unknown button: " + key);
      button.onAction();
    }
  }
  const documentRef = {
    body: { contains: () => false },
    visibilityState: "visible",
    activeElement: null,
    querySelector: () => existingDialog ? {} : null,
    addEventListener(name, callback) { listeners.set(name, callback); },
    removeEventListener(name) { listeners.delete(name); }
  };
  const windowRef = {
    addEventListener(name, callback) { listeners.set(name, callback); },
    removeEventListener(name) { listeners.delete(name); }
  };
  const usb = {
    isEnabled: () => usbEnabled,
    setEnabled: async (value) => { enabledCalls++; usbEnabled = value; return { mode: "memory" }; },
    subscribeStorageChanges(callback) {
      subscribed++;
      storageChange = callback;
      return () => { storageChange = null; };
    }
  };
  const controller = createTizenUsbCacheOfferController({
    usb, detect: async () => {
      scans++;
      return detectOverride ? detectOverride() : { drives: [...mountedDrives] };
    },
    Dialog, router: { getCurrent: () => activeRoute },
    documentRef, windowRef,
    storage: {
      getItem(key) { return options.get(key) || null; },
      setItem(key, value) { options.set(key, value); }
    },
    t: (_, fallback) => fallback,
    initialDelayMs: 60_000,
    mountDelayMs: 1,
    unmountDelayMs: 1,
    routeRetryMs: 5
  });
  return {
    controller, dialogs, usb, options, listeners,
    setRoute(next) { activeRoute = next; },
    setMounted(next) { mountedDrives = next; },
    setExistingDialog(value) { existingDialog = value; },
    setVisible(value) { documentRef.visibilityState = value; },
    emit(storageInfo) { storageChange?.({ type: "EXTERNAL", ...storageInfo }); },
    get enabledCalls() { return enabledCalls; },
    get scans() { return scans; },
    get subscribed() { return subscribed; }
  };
}

test("mounted USB at startup offers a remote-safe explicit opt-in once", async () => {
  const f = fixture({ mounted: [drive()] });
  f.controller.start();
  await f.controller.checkNow();
  assert.equal(f.subscribed, 1);
  assert.equal(f.dialogs.length, 1);
  assert(f.dialogs[0].mounted);
  assert.equal(f.dialogs[0].config.suppressEnterUntilKeyUp, true);
  assert.deepEqual(f.dialogs[0].config.buttons.map((button) => button.key),
    ["later", "enable", "never"]);
  assert.equal(f.enabledCalls, 0, "detection must not write to USB or enable caching");
  f.dialogs[0].choose("enable");
  await sleep();
  assert.equal(f.enabledCalls, 1);
  assert.equal(f.usb.isEnabled(), true);
  assert.equal(f.dialogs[0].destroyed, true);
  await f.controller.checkNow();
  assert.equal(f.dialogs.length, 1);
  f.controller.stop();
});

test("hot-plug prompts once, not-now suppresses duplicate events until unplug", async () => {
  const f = fixture();
  f.controller.start();
  await f.controller.checkNow();
  assert.equal(f.dialogs.length, 0);
  f.setMounted([drive()]);
  f.emit({ state: "MOUNTED", label: "removable1" });
  await sleep();
  assert.equal(f.dialogs.length, 1);
  f.dialogs[0].choose("later");
  f.emit({ state: "MOUNTED", label: "removable1" });
  await sleep();
  assert.equal(f.dialogs.length, 1, "repeated mount event must not reopen the offer");
  f.setMounted([]);
  f.emit({ state: "UNMOUNTED", label: "removable1" });
  await sleep();
  f.setMounted([drive()]);
  f.emit({ state: "MOUNTED", label: "removable1" });
  await sleep();
  assert.equal(f.dialogs.length, 2, "a physical reinsert may offer again");
  f.controller.stop();
});

test("Don't ask again persists independently of the USB cache setting", async () => {
  const f = fixture({ mounted: [drive()] });
  f.controller.start();
  await f.controller.checkNow();
  f.dialogs[0].choose("never");
  assert.equal(f.enabledCalls, 0);
  assert.equal(f.usb.isEnabled(), false);
  assert.equal([...f.options.values()].includes("true"), true);
  f.setMounted([]);
  f.emit({ state: "UNMOUNTED", label: "removable1" });
  f.setMounted([drive()]);
  f.emit({ state: "MOUNTED", label: "removable1" });
  await sleep();
  assert.equal(f.dialogs.length, 1);
  f.controller.stop();
});

test("playback, onboarding, and existing dialogs defer the prompt", async () => {
  const f = fixture({ mounted: [drive()], route: "player" });
  f.controller.start();
  await f.controller.checkNow();
  assert.equal(f.dialogs.length, 0);
  f.setRoute("authQrSignIn");
  await sleep(10);
  assert.equal(f.dialogs.length, 0);
  f.setRoute("home");
  f.setExistingDialog(true);
  await sleep(10);
  assert.equal(f.dialogs.length, 0);
  f.setExistingDialog(false);
  await sleep(20);
  assert.equal(f.dialogs.length, 1);
  f.controller.stop();
});

test("unplugging closes a pending offer without enabling or corrupting state", async () => {
  const f = fixture({ mounted: [drive()] });
  f.controller.start();
  await f.controller.checkNow();
  assert.equal(f.dialogs.length, 1);
  f.setMounted([]);
  f.emit({ state: "REMOVED", label: "removable1" });
  assert.equal(f.dialogs[0].destroyed, true);
  await sleep();
  assert.equal(f.enabledCalls, 0);
  f.controller.stop();
});

test("unsupported/read-only drives and enabled caching never open an offer", async () => {
  const f = fixture({ enabled: true, mounted: [drive()] });
  f.controller.start();
  await f.controller.checkNow();
  assert.equal(f.dialogs.length, 0);
  f.controller.stop();
  const missing = fixture();
  missing.controller.start();
  await missing.controller.checkNow();
  assert.equal(missing.dialogs.length, 0);
  missing.controller.stop();
});

test("background and temporary UI changes are non-interrupting", async () => {
  const f = fixture({ mounted: [drive()] });
  f.setVisible("hidden");
  f.controller.start();
  await f.controller.checkNow();
  assert.equal(f.dialogs.length, 0);
  f.setVisible("visible");
  f.listeners.get("visibilitychange")();
  await sleep(20);
  assert.equal(f.dialogs.length, 1);
  f.controller.stop();
});
test("late scan completion after unplug never reopens an obsolete offer", async () => {
  let completeScan;
  const f = fixture({
    detectOverride: () => new Promise((resolve) => { completeScan = resolve; })
  });
  f.controller.start();
  const pending = f.controller.checkNow();
  f.emit({ state: "UNMOUNTED", label: "removable1" });
  completeScan({ drives: [drive()] });
  await pending;
  assert.equal(f.dialogs.length, 0);
  f.controller.stop();
});
