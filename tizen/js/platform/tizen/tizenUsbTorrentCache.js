// Copyright (c) 2026 Starlit contributors. SPDX-License-Identifier: MIT.
// Opt-in, removable torrent cache detection for Samsung Tizen TVs.
import { trustedEngineFsControlHeaders } from "./starlitControlBridge.js";

const KEY = "starlit.tizen.usbTorrentCache.enabled.v1";
let configuredUrl = "";
let lastUrl = "";
let lastCheckedUrl = "";
let lastCheckedAt = 0;
let pending = null;
let listenerId = null;
let generation = 0;
let state = { mode: "memory", reason: "USB cache disabled" };
const storageChangeSubscribers = new Set();

function callTizen(method, args = []) {
  return new Promise((resolve, reject) => {
    try { method(...args, resolve, reject); } catch (error) { reject(error); }
  });
}
function resolveDrive(label) {
  return new Promise((resolve, reject) => {
    try { globalThis.tizen.filesystem.resolve(label, resolve, reject, "rw"); }
    catch (error) { reject(error); }
  });
}
function nativePathFromFile(file) {
  if (!file || file.readOnly === true || file.isDirectory === false ||
      typeof file.toURI !== "function") return "";
  try {
    const url = new URL(file.toURI());
    if (url.protocol !== "file:" || url.hostname || url.search || url.hash) return "";
    const path = decodeURIComponent(url.pathname);
    if (!path.startsWith("/") || path.includes("\0") || path.split("/").includes("..")) return "";
    return path;
  } catch (_) { return ""; }
}
function usableUnit(unit) {
  return unit && Number.isSafeInteger(Number(unit.capacity)) &&
    Number.isSafeInteger(Number(unit.availableCapacity)) &&
    Number(unit.capacity) > 0 && Number(unit.availableCapacity) >= 0 &&
    Number(unit.availableCapacity) <= Number(unit.capacity);
}
async function findDrives() {
  const api = globalThis.tizen;
  if (!api?.filesystem?.listStorages || !api?.filesystem?.resolve ||
      !api?.systeminfo?.getPropertyValue) {
    return { drives: [], reason: "TV external storage API unavailable" };
  }
  const [storages, info] = await Promise.all([
    callTizen(api.filesystem.listStorages.bind(api.filesystem)),
    callTizen(api.systeminfo.getPropertyValue.bind(api.systeminfo), ["STORAGE"])
  ]);
  const candidates = (Array.isArray(storages) ? storages : []).filter(
    (item) => item?.type === "EXTERNAL" && item?.state === "MOUNTED" && item?.label
  );
  const units = (Array.isArray(info?.units) ? info.units : []).filter(
    (unit) => usableUnit(unit) &&
      (unit.isRemovable === true || unit.isRemoveable === true ||
       unit.type === "USB_HOST" || unit.type === "USB_DEVICE" || unit.type === "MMC")
  );
  if (!candidates.length || !units.length) {
    return { drives: [], reason: "No mounted USB with measurable free space" };
  }
  // SystemInfo USB units have no storage labels, so use the most conservative
  // available/total value when the TV reports multiple removable devices.
  const freeBytes = Math.min(...units.map((unit) => Number(unit.availableCapacity)));
  const totalBytes = Math.min(...units.map((unit) => Number(unit.capacity)));
  const drives = [];
  for (const candidate of candidates) {
    try {
      const resolved = await resolveDrive(String(candidate.label));
      const root = nativePathFromFile(resolved);
      if (root) {
        drives.push({ label: String(candidate.label), root, freeBytes, totalBytes });
      }
    } catch (_) {
      // Unwritable, unmounted or inaccessible devices cannot be selected.
    }
  }
  return { drives, reason: drives.length ? "" : "No writable USB storage" };
}
export async function detectTizenUsbStorage() {
  return findDrives();
}
async function control(baseUrl, path, payload, timeoutMs = 5000) {
  let timer = 0;
  const request = fetch(baseUrl + path, {
    method: payload === undefined ? "GET" : "POST",
    headers: trustedEngineFsControlHeaders(baseUrl,
      payload === undefined ? {} : { "Content-Type": "application/json" }),
    body: payload === undefined ? undefined : JSON.stringify(payload),
    cache: "no-store"
  });
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error("USB cache request timed out")), timeoutMs);
  });
  try {
    const response = await Promise.race([request, timeout]);
    if (!response.ok) throw new Error("USB cache HTTP " + response.status);
    return await response.json();
  } finally { clearTimeout(timer); }
}
function watchStorage() {
  if (listenerId != null || !globalThis.tizen?.filesystem?.addStorageStateChangeListener) return;
  try {
    listenerId = globalThis.tizen.filesystem.addStorageStateChangeListener((storage) => {
      if (storage?.type !== "EXTERNAL") return;
      generation++;
      configuredUrl = "";
      lastCheckedUrl = "";
      state = { mode: "memory", reason: "USB storage changed" };
      if (lastUrl) {
        // Recover immediately if the drive disappears. Probe again before
        // the next torrent rather than reconfiguring an active stream.
        void control(lastUrl, "/cache/disable", {}, 4000).catch(() => {});
      }
      if (storage?.state === "MOUNTED" && TizenUsbTorrentCache.isEnabled() && lastUrl) {
        setTimeout(() => {
          if (TizenUsbTorrentCache.isEnabled()) {
            void TizenUsbTorrentCache.configure(lastUrl, { force: true });
          }
        }, 500);
      }
      for (const subscriber of storageChangeSubscribers) {
        try { subscriber(storage); } catch (_) {
          // TV storage notifications must not be blocked by a UI listener.
        }
      }
    });
    if (listenerId == null) listenerId = true;
  } catch (_) { listenerId = null; }
}
export const TizenUsbTorrentCache = {
  subscribeStorageChanges(listener) {
    if (typeof listener !== "function") return () => {};
    storageChangeSubscribers.add(listener);
    // Mount notifications must be available even before caching is enabled.
    watchStorage();
    return () => storageChangeSubscribers.delete(listener);
  },
  isEnabled() {
    try { return globalThis.localStorage?.getItem(KEY) === "true"; }
    catch (_) { return false; }
  },
  async setEnabled(enabled, baseUrl = "") {
    try { globalThis.localStorage?.setItem(KEY, enabled ? "true" : "false"); }
    catch (_) { return { mode: "memory", reason: "Cannot save USB preference" }; }
    generation++;
    configuredUrl = "";
    lastCheckedUrl = "";
    if (!enabled) {
      state = { mode: "memory", reason: "USB cache disabled" };
      if (baseUrl || lastUrl) {
        await control(baseUrl || lastUrl, "/cache/disable", {}).catch(() => {});
      }
      return state;
    }
    state = { mode: "memory", reason: "Checking connected USB storage" };
    watchStorage();
    return baseUrl ? this.configure(baseUrl, { force: true }) : state;
  },
  async configure(baseUrl, { force = false } = {}) {
    if (!this.isEnabled()) return { mode: "memory", reason: "USB cache disabled" };
    if (configuredUrl === baseUrl && !force) {
      try {
        if ((await control(baseUrl, "/cache/status")).configured) return state;
      } catch (_) {}
      configuredUrl = "";
    }
    if (!force && lastCheckedUrl === baseUrl && Date.now() - lastCheckedAt < 10 * 60 * 1000) {
      return state;
    }
    if (pending) return pending;
    const attempt = generation;
    lastUrl = baseUrl;
    watchStorage();
    pending = (async () => {
      const { drives, reason } = await findDrives();
      if (!drives.length) {
        await control(baseUrl, "/cache/disable", {}).catch(() => {});
        configuredUrl = "";
        lastCheckedUrl = baseUrl;
        lastCheckedAt = Date.now();
        state = { mode: "memory", reason };
        return state;
      }
      let best = null;
      let bestDrive = null;
      let busy = false;
      for (const drive of drives) {
        try {
          const result = await control(baseUrl, "/cache/probe", {
            root: drive.root, freeBytes: drive.freeBytes, totalBytes: drive.totalBytes
          }, 30000);
          if (result.mode === "usb" && (!best || result.writeMBps > best.writeMBps)) {
            best = result; bestDrive = drive;
          } else if (!best) {
            state = { ...result, label: drive.label };
          }
        } catch (error) {
          if (String(error?.message || error).includes("HTTP 409")) busy = true;
          if (!best) state = { mode: "memory", reason: String(error?.message || error) };
        }
      }
      if (best && bestDrive) {
        if (bestDrive !== drives[drives.length - 1]) {
          best = await control(baseUrl, "/cache/probe", {
            root: bestDrive.root, freeBytes: bestDrive.freeBytes, totalBytes: bestDrive.totalBytes
          }, 30000);
        }
        state = { ...best, label: bestDrive.label };
        configuredUrl = best.mode === "usb" ? baseUrl : "";
      } else {
        await control(baseUrl, "/cache/disable", {}).catch(() => {});
        configuredUrl = "";
      }
      if (attempt !== generation || !this.isEnabled()) {
        await control(baseUrl, "/cache/disable", {}).catch(() => {});
        configuredUrl = "";
        state = { mode: "memory", reason: "USB storage changed" };
      }
      if (attempt === generation && !busy) {
        lastCheckedUrl = baseUrl;
        lastCheckedAt = Date.now();
      }
      return state;
    })().catch((error) => {
      configuredUrl = "";
      state = { mode: "memory", reason: String(error?.message || error) };
      return state;
    }).finally(() => { pending = null; });
    return pending;
  },
  async getState(baseUrl) {
    lastUrl = baseUrl;
    let server = null;
    try { server = await control(baseUrl, "/cache/status"); } catch (_) {}
    return { ...state, enabled: this.isEnabled(), configured: Boolean(server?.configured),
      active: server?.active || null,
      cacheBytes: server?.cacheBytes || state.cacheBytes || 0,
      profile: server?.profile || state.profile || "memory" };
  }
};
