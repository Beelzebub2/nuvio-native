import assert from "node:assert/strict";
import test from "node:test";

globalThis.__NUVIO_PLATFORM__ = "tizen";
globalThis.__NUVIO_TIZEN_VERSION__ = "10.0";
globalThis.__NUVIO_TIZEN_ENGINEFS_SERVICE_ENABLED__ = true;
globalThis.__NUVIO_TIZEN_PLUGIN_SERVICE_ENABLED__ = true;
globalThis.__NUVIO_TIZEN_ENGINEFS_SERVICE_ID__ = "TestPkg.EngineFsService";
globalThis.__NUVIO_TIZEN_PLUGIN_SERVICE_ID__ = "TestPkg.PluginService";

const { Platform } = await import("../js/platform/index.js");
const { resetTizenCapabilitiesCache } = await import("../js/platform/tizen/tizenCapabilities.js");
const { getWrtServiceClass, startWithWrtServiceClass } = await import("../js/platform/tizen/tizenWrtService.js");
const { TizenEngineFsService } = await import("../js/platform/tizen/tizenEngineFsService.js");
const { TizenPluginService } = await import("../js/platform/tizen/tizenPluginService.js");

const original = {
  fetch: globalThis.fetch,
  tizen: globalThis.tizen,
  bridge: globalThis.__NUVIO_TIZEN_WRT_SERVICE__
};
let activeService = "";
let wrtStarts = 0;
let appLaunches = 0;
let appControls = 0;
const replies = {
  engine: { version: "test", memoryOnly: true },
  plugin: { returnValue: true, service: "nuvio-plugin-network", protocolVersion: 1 }
};
function installDevice({ major = 10, webService = false } = {}) {
  activeService = "";
  wrtStarts = 0;
  appLaunches = 0;
  appControls = 0;
  globalThis.__NUVIO_TIZEN_WRT_SERVICE__ = undefined;
  globalThis.__NUVIO_TIZEN_VERSION__ = major + ".0";
  globalThis.tizen = {
    systeminfo: {
      getCapability(name) {
        if (name.includes("platform.version")) return major + ".0";
        if (name.includes("web.service")) return webService;
        return false;
      }
    },
    application: {
      getCurrentApplication: () => ({ appInfo: { packageId: "TestPkg" } }),
      launch(id, success) {
        appLaunches++;
        // Tizen 9/10 can acknowledge a launch without starting the service.
        if (major < 9) activeService = id;
        success?.();
      },
      launchAppControl(control, id, success) {
        assert(control);
        appControls++;
        if (major < 9) activeService = id;
        success?.();
      }
    },
    ApplicationControl: class { constructor(operation) { this.operation = operation; } },
    messageport: {
      requestTrustedLocalMessagePort() {
        let listener;
        return {
          addMessagePortListener(callback) { listener = callback; return 1; },
          removeMessagePortListener() {},
          dispatch(data, remote) { listener(data, remote); }
        };
      },
      requestTrustedRemoteMessagePort(id) {
        return {
          isTrusted: true,
          sendMessage(data, local) {
            local.dispatch(
              [{ key: "nonce", value: data[0].value }, { key: "token", value: "b".repeat(64) }],
              { isTrusted: true, appId: id }
            );
          }
        };
      }
    }
  };
  globalThis.fetch = async (requestUrl) => {
    const url = String(requestUrl);
    const expectedService = url.includes(":2711/") ? "TestPkg.PluginService" : "TestPkg.EngineFsService";
    if (activeService !== expectedService) throw new TypeError("Service not listening");
    if (url.endsWith("/range-probe"))
      return new Response("tar", { status: 206, headers: { "Content-Range": "bytes 1-3/22" } });
    if (url.endsWith("/settings"))
      return new Response(JSON.stringify(replies.engine), { status: 200 });
    if (url.endsWith("/health"))
      return new Response(JSON.stringify(replies.plugin), { status: 200 });
    throw new Error("Unexpected request " + url);
  };
  Platform.current = null;
  resetTizenCapabilitiesCache();
}
try {
  await test("the service helper detects namespace and default classes, but no unrelated exports", () => {
    class Service {}
    assert.equal(getWrtServiceClass({ Service }), Service);
    assert.equal(getWrtServiceClass({ default: { Service } }), Service);
    assert.equal(getWrtServiceClass({ startService() {} }), null);
    assert.equal(getWrtServiceClass(null), null);
  });

  await test("a rejected Service.start fails rather than masquerading as startup", async () => {
    class BadService { start() { return Promise.reject(new Error("denied")); } }
    await assert.rejects(startWithWrtServiceClass(BadService, "Test", 10), /denied/);
    class PendingService { start() { return new Promise(() => {}); } }
    assert.equal(await startWithWrtServiceClass(PendingService, "Test", 10), "pending");
  });

  await test("Tizen 10 EngineFS uses the class, waits for real health and authorizes P2P", async () => {
    installDevice({ major: 10, webService: true });
    globalThis.__NUVIO_TIZEN_WRT_SERVICE__ = {
      Service: class Service {
        constructor(id) { assert.equal(id, "TestPkg.EngineFsService"); }
        start() {
          wrtStarts++;
          activeService = "TestPkg.EngineFsService";
          return new Promise(() => {});
        }
      }
    };
    const result = await TizenEngineFsService.ensureStarted({ purpose: "p2p" });
    assert.equal(result.status, "success", result.detail);
    assert.equal(result.startMethod, "wrt-service-legacy");
    assert.equal(wrtStarts, 1);
    assert.equal(appLaunches, 0);
    assert.equal(appControls, 0);
  });

  await test("Tizen 10 PluginService starts through a default-exported class", async () => {
    installDevice({ major: 10, webService: false });
    globalThis.__NUVIO_TIZEN_WRT_SERVICE__ = {
      default: {
        Service: class Service {
          constructor(id) { assert.equal(id, "TestPkg.PluginService"); }
          start() {
            wrtStarts++;
            activeService = "TestPkg.PluginService";
            return new Promise(() => {});
          }
        }
      }
    };
    const result = await TizenPluginService.ensureStarted();
    assert.equal(result.status, "success", result.detail);
    assert.equal(result.startMethod, "wrt-service");
    assert.equal(wrtStarts, 1);
    assert.equal(appLaunches, 0);
    assert.equal(appControls, 0);
  });

  await test("older Tizen retains the legacy startService path", async () => {
    installDevice({ major: 5 });
    globalThis.__NUVIO_TIZEN_WRT_SERVICE__ = {
      startService(id, success) {
        wrtStarts++;
        activeService = id;
        success?.();
      }
    };
    const result = await TizenEngineFsService.ensureStarted({ purpose: "p2p" });
    assert.equal(result.status, "success", result.detail);
    assert.equal(result.startMethod, "wrt-service-legacy");
    assert.equal(wrtStarts, 1);
    assert.equal(appLaunches, 0);
  });
} finally {
  globalThis.fetch = original.fetch;
  if (original.tizen === undefined) delete globalThis.tizen;
  else globalThis.tizen = original.tizen;
  if (original.bridge === undefined) delete globalThis.__NUVIO_TIZEN_WRT_SERVICE__;
  else globalThis.__NUVIO_TIZEN_WRT_SERVICE__ = original.bridge;
  Platform.current = null;
  resetTizenCapabilitiesCache();
}
