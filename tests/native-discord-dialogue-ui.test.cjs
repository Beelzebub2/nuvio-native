const { test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { join } = require("node:path");

const resources = join(__dirname, "../composeApp/src/desktopMain/resources/player-ui");
const html = readFileSync(join(resources, "controls.html"), "utf8");
const script = readFileSync(join(resources, "controls.js"), "utf8");
const bridge = readFileSync(
  join(__dirname, "../composeApp/src/desktopMain/native/windows/player_bridge.cpp"), "utf8"
);

test("Discord dialogue toggle appears directly after Audio and stays hidden by default", () => {
  assert.match(
    html,
    /data-command="audio"[^>]*>[\s\S]*?<\/button>\s*<button[^>]*id="discordDialogueButton"[^>]*data-command="discordDialogue"[^>]*hidden/
  );
  assert.match(html, /id="discordDialogueButton"[^>]*aria-pressed="false"/);
  assert.match(script, /discordDialogueAvailable:\s*false/);
  assert.match(script, /discordDialogueEnabled:\s*false/);
  assert.match(script, /const showDiscordDialogue = Boolean\(state\.discordDialogueAvailable/);
});

test("toggle is user-driven and sent only if explicitly available", () => {
  assert.match(script, /if \(command === "discordDialogue"\)\s*\{\s*if \(!state\.discordDialogueAvailable\) return;/);
  assert.match(script, /send\("discordDialogue", enabled \? 1 : 0\)/);
  assert.match(script, /setAttribute\("aria-pressed", active \? "true" : "false"\)/);
});

test("native sidecar uses only FC, pins WASAPI output and fails closed", () => {
  assert.match(bridge, /option\("audio-device", wasapiDevice\.c_str\(\)\)/);
  assert.match(bridge, /option\("af", "lavfi=\[pan=stereo\|c0=FC\|c1=FC\]"\)/);
  assert.match(bridge, /const bool safe = filterConfigured && outputChannels == 2 && helperAudioId == mainAudioId/);
  assert.match(bridge, /stopDiscordDialogueLocked\(\); \/\/ No FC channel/);
  assert.doesNotMatch(bridge, /option\("audio-device", "auto"\)/);
});
