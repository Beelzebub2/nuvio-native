import test from "node:test";
import assert from "node:assert/strict";
import { detectTizenUsbStorage, TizenUsbTorrentCache } from "./tizenUsbTorrentCache.js";

function withMock(api, run) {
  const previous = globalThis.tizen;
  globalThis.tizen = api;
  return Promise.resolve().then(run).finally(() => { globalThis.tizen = previous; });
}
function mock(units, mounts, paths = {}) {
  return {
    filesystem: {
      listStorages: (good) => good(mounts),
      resolve: (label, good, bad, mode) => {
        assert.equal(mode, "rw");
        if (paths[label]) good({
          path: label,
          toURI: () => String(paths[label]).startsWith("file:")
            ? paths[label] : "file://" + paths[label],
          isDirectory: true, readOnly: false
        });
        else bad(new Error("Read-only or inaccessible"));
      }
    },
    systeminfo: {
      getPropertyValue: (key, good) => {
        assert.equal(key, "STORAGE");
        good({ units });
      }
    }
  };
}
const GiB = 1024 ** 3;
test("select mounted external drives and ignore internal storage", async () => {
  const api = mock(
    [{ type: "INTERNAL", capacity: 32 * GiB, availableCapacity: 20 * GiB },
     { type: "USB_HOST", capacity: 4 * GiB, availableCapacity: 3 * GiB, isRemovable: true }],
    [{ type: "INTERNAL", state: "MOUNTED", label: "internal0" },
     { type: "EXTERNAL", state: "MOUNTED", label: "removable2" },
     { type: "EXTERNAL", state: "REMOVED", label: "removable3" }],
    { removable2: "/opt/storage/usb0" }
  );
  await withMock(api, async () => {
    const { drives } = await detectTizenUsbStorage();
    assert.equal(drives.length, 1);
    assert.equal(drives[0].root, "/opt/storage/usb0");
    assert.equal(drives[0].freeBytes, 3 * GiB);
  });
});
test("multiple storages reserve using conservative capacity", async () => {
  const api = mock(
    [{ type: "USB_HOST", capacity: 8 * GiB, availableCapacity: 6 * GiB, isRemovable: true },
     { type: "USB_HOST", capacity: 4 * GiB, availableCapacity: 2 * GiB, isRemovable: true }],
    [{ type: "EXTERNAL", state: "MOUNTED", label: "A" },
     { type: "EXTERNAL", state: "MOUNTED", label: "B" }],
    { A: "/mnt/a", B: "/mnt/b" }
  );
  await withMock(api, async () => {
    const { drives } = await detectTizenUsbStorage();
    assert.equal(drives.length, 2);
    assert(drives.every((item) => item.totalBytes === 4 * GiB && item.freeBytes === 2 * GiB));
  });
});
test("ignore USB with no safe capacity mapping or writable native path", async () => {
  await withMock(mock([], [{ type: "EXTERNAL", state: "MOUNTED", label: "A" }]), async () => {
    assert.equal((await detectTizenUsbStorage()).drives.length, 0);
  });
  await withMock(mock(
    [{ type: "USB_HOST", capacity: 4 * GiB, availableCapacity: 3 * GiB, isRemovable: true }],
    [{ type: "EXTERNAL", state: "MOUNTED", label: "A" }],
    { A: "virtualRoot" }
  ), async () => {
    assert.equal((await detectTizenUsbStorage()).drives.length, 0);
  });
});
test("physical file URI is decoded; virtual paths and unsafe file URIs are rejected", async () => {
  const units = [{ type: "USB_HOST", capacity: 4 * GiB, availableCapacity: 3 * GiB, isRemovable: true }];
  const mounts = [{ type: "EXTERNAL", state: "MOUNTED", label: "A" }];
  await withMock(mock(units, mounts, { A: "file:///opt/media/USB%20Drive" }), async () => {
    const { drives } = await detectTizenUsbStorage();
    assert.equal(drives[0].root, "/opt/media/USB Drive");
  });
  await withMock(mock(units, mounts, { A: "file:///opt/media/USB%00Drive" }), async () => {
    assert.equal((await detectTizenUsbStorage()).drives.length, 0);
  });
  await withMock(mock(units, mounts, { A: "http://example.com/file" }), async () => {
    assert.equal((await detectTizenUsbStorage()).drives.length, 0);
  });
});
test("unsupported Tizen API leaves torrent playback on RAM", async () => {
  await withMock({}, async () => {
    const result = await detectTizenUsbStorage();
    assert.equal(result.drives.length, 0);
  });
});
test("USB notifications are available before a cache opt-in and can be unsubscribed", async () => {
  let storageHandler;
  let registrations = 0;
  const seen = [];
  await withMock({
    filesystem: {
      addStorageStateChangeListener(handler) {
        registrations++;
        storageHandler = handler;
        return 1;
      }
    }
  }, async () => {
    const unsubscribe = TizenUsbTorrentCache.subscribeStorageChanges((storage) => seen.push(storage.label));
    assert.equal(registrations, 1);
    storageHandler({ type: "INTERNAL", label: "system", state: "MOUNTED" });
    storageHandler({ type: "EXTERNAL", label: "usb-1", state: "MOUNTED" });
    assert.deepEqual(seen, ["usb-1"]);
    unsubscribe();
    storageHandler({ type: "EXTERNAL", label: "usb-2", state: "MOUNTED" });
    assert.deepEqual(seen, ["usb-1"], "disposed UI must not keep receiving events");
  });
});
