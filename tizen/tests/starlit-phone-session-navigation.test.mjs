import test from "node:test";
import assert from "node:assert/strict";

globalThis.localStorage = {
  getItem: () => null, setItem() {}, removeItem() {}
};
const { TvPhoneSetupScreen } = await import("../js/ui/screens/settings/tvPhoneSetupScreen.js");
const { ProfileManager } = await import("../js/core/profile/profileManager.js");
const { ScreenUtils } = await import("../js/ui/navigation/screen.js");
const { Router } = await import("../js/ui/navigation/routerState.js");

test("approved TV phone session survives navigation into source picker and playback", async () => {
  const original = {
    hide: ScreenUtils.hide,
    current: Router.getCurrent,
    profile: ProfileManager.getActiveProfileId,
    back: Router.back
  };
  let profile = "1";
  let route = "stream";
  let stopped = 0;
  let wentBack = 0;
  ScreenUtils.hide = () => {};
  Router.getCurrent = () => route;
  Router.back = () => { wentBack++; };
  ProfileManager.getActiveProfileId = () => profile;
  TvPhoneSetupScreen.profile = "1";
  TvPhoneSetupScreen.phase = "connected";
  TvPhoneSetupScreen.generation = 19;
  TvPhoneSetupScreen.client = { active:true, stop:async () => { stopped++; } };
  TvPhoneSetupScreen.container = { removeEventListener() {} };
  try {
    assert.equal(TvPhoneSetupScreen.active(19), true);
    TvPhoneSetupScreen.cleanup();
    assert.equal(TvPhoneSetupScreen.client?.active, true);
    assert.equal(TvPhoneSetupScreen.generation, 19);
    assert.equal(stopped, 0);
    route = "player";
    assert.equal(TvPhoneSetupScreen.active(19), true);

    // A TV profile change revokes phone control even while the Settings page
    // is unmounted and an unrelated player route is foreground.
    profile = "2";
    await TvPhoneSetupScreen.poll(19);
    await Promise.resolve();
    assert.equal(TvPhoneSetupScreen.active(19), false);
    assert.equal(stopped, 1);
    assert.equal(wentBack, 0);
  } finally {
    ScreenUtils.hide = original.hide;
    Router.getCurrent = original.current;
    Router.back = original.back;
    ProfileManager.getActiveProfileId = original.profile;
    TvPhoneSetupScreen.client = null;
    TvPhoneSetupScreen.container = null;
    TvPhoneSetupScreen.phase = "disconnected";
    clearTimeout(TvPhoneSetupScreen.timer);
  }
});
