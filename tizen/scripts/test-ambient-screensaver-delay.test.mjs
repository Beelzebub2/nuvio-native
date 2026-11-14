import test from "node:test";
import assert from "node:assert/strict";
globalThis.localStorage ||= { getItem: () => null, setItem() {}, removeItem() {} };
const { AmbientMode } = await import("../js/ui/ambient/ambientMode.js");
const { AmbientModeStore } = await import("../js/data/local/ambientModeStore.js");
const { ProfileManager } = await import("../js/core/profile/profileManager.js");
const { Router } = await import("../js/ui/navigation/routerState.js");

test("the screensaver block is deferred until Home has been idle", () => {
  const originals = {
    get: AmbientModeStore.get,
    current: Router.getCurrent,
    screen: Router.getCurrentScreen,
    allowed: AmbientMode.allowed,
    setBlocked: AmbientMode.setTizenScreenSaverBlocked,
    signature: AmbientMode.signature,
    idleRoute: AmbientMode.idleRoute,
    trailerPlaying: AmbientMode.trailerPlaying,
    lastInteraction: AmbientMode.lastInteraction,
    showing: AmbientMode.showing
  };
  const settings = { enabled: true, timeoutMinutes: 90, catalogKeys: [] };
  const blocked = [];
  try {
    AmbientModeStore.get = () => settings;
    Router.getCurrent = () => "home";
    Router.getCurrentScreen = () => null;
    AmbientMode.allowed = () => true;
    AmbientMode.setTizenScreenSaverBlocked = (value) => blocked.push(value);
    AmbientMode.idleRoute = "home";
    AmbientMode.trailerPlaying = false;
    AmbientMode.showing = false;
    AmbientMode.signature = JSON.stringify([ProfileManager.getActiveProfileId(), settings]);
    AmbientMode.lastInteraction = performance.now() - 2000;
    AmbientMode.tick();
    assert.deepEqual(blocked, [], "first Home paint must not block on the native plugin");
    AmbientMode.lastInteraction = performance.now() - 11000;
    AmbientMode.tick();
    assert.deepEqual(blocked, [true]);
    AmbientMode.allowed = () => false;
    AmbientMode.tick();
    assert.deepEqual(blocked, [true, false], "screensaver must unblock on disallowed routes");
  } finally {
    AmbientModeStore.get = originals.get;
    Router.getCurrent = originals.current;
    Router.getCurrentScreen = originals.screen;
    AmbientMode.allowed = originals.allowed;
    AmbientMode.setTizenScreenSaverBlocked = originals.setBlocked;
    AmbientMode.signature = originals.signature;
    AmbientMode.idleRoute = originals.idleRoute;
    AmbientMode.trailerPlaying = originals.trailerPlaying;
    AmbientMode.lastInteraction = originals.lastInteraction;
    AmbientMode.showing = originals.showing;
  }
});
