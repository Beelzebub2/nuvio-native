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

test("idle pairing state is not rebuilt or uploaded every poll; searching remains responsive", async () => {
  const original = {
    client: TvPhoneSetupScreen.client,
    executor: TvPhoneSetupScreen.executor,
    lastPublishedState: TvPhoneSetupScreen.lastPublishedState,
    lastStateCheckAt: TvPhoneSetupScreen.lastStateCheckAt,
    lastPublishedSourcePhase: TvPhoneSetupScreen.lastPublishedSourcePhase
  };
  let built = 0;
  let sent = 0;
  let phase = "idle";
  TvPhoneSetupScreen.client = { state: async () => { sent++; } };
  TvPhoneSetupScreen.executor = { state: () => {
    built++;
    return { profile:"1", sourcePicker:{ phase } };
  } };
  TvPhoneSetupScreen.lastPublishedState = "";
  TvPhoneSetupScreen.lastStateCheckAt = 0;
  TvPhoneSetupScreen.lastPublishedSourcePhase = "idle";
  try {
    await TvPhoneSetupScreen.publishState(true);
    for (let i = 0; i < 8; i++) await TvPhoneSetupScreen.publishState();
    assert.equal(built, 1);
    assert.equal(sent, 1);
    TvPhoneSetupScreen.lastStateCheckAt -= 10000;
    await TvPhoneSetupScreen.publishState();
    assert.equal(built, 2);
    assert.equal(sent, 1, "identical state should not be uploaded again");
    phase = "searching";
    await TvPhoneSetupScreen.publishState(true);
    assert.equal(sent, 2);
    TvPhoneSetupScreen.lastStateCheckAt -= 1700;
    phase = "ready";
    await TvPhoneSetupScreen.publishState();
    assert.equal(sent, 3, "live source changes must reach the phone");
  } finally {
    TvPhoneSetupScreen.client = original.client;
    TvPhoneSetupScreen.executor = original.executor;
    TvPhoneSetupScreen.lastPublishedState = original.lastPublishedState;
    TvPhoneSetupScreen.lastStateCheckAt = original.lastStateCheckAt;
    TvPhoneSetupScreen.lastPublishedSourcePhase = original.lastPublishedSourcePhase;
  }
});
