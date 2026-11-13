const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const source = (filename) => fs.readFileSync(path.join(__dirname, "..", filename), "utf8");

test("native players leave original ASS/SSA styling untouched in libass mode", () => {
  const windows = source("composeApp/src/desktopMain/native/windows/player_bridge.cpp");
  const macos = source("composeApp/src/desktopMain/native/macos/player_bridge.mm");
  const linux = source("composeApp/src/desktopMain/native/linux/player_bridge.cpp");

  // mpv's scale override can alter ASS typesetting and animation geometry.
  // The 'no' mode preserves the script; 'force' remains opt-in custom styling.
  assert.match(windows, /"sub-ass-override",\s*useLibass\s*\?\s*"no"\s*:\s*"force"/);
  assert.match(macos, /"sub-ass-override"\s+value:useLibass\s*\?\s*@"no"\s*:\s*@"force"/);
  assert.match(linux, /"sub-ass-override",\s*preserveAssStyles\s*\?\s*"no"\s*:\s*"force"/);

  assert.match(windows, /double scale = 1\.0;/);
  assert.match(macos, /double scale = 1\.0;/);
  assert.match(linux, /const double subtitleScale = 1\.0;/);
  assert.match(windows, /position = useLibass \? 100 :/);
  assert.match(macos, /position = useLibass \? 100 :/);
  assert.match(linux, /preserveAssStyles \? 100 : subPos/);
});

test("desktop defaults to script styling while preserving saved custom preference", () => {
  const settings = source("composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsRepository.kt");
  assert.match(settings, /val useLibass: Boolean = isDesktop/);
  assert.match(settings, /private var useLibass = isDesktop/);
  assert.match(settings, /loadUseLibass\(\) \?: isDesktop/);
  assert.match(settings, /if \(useLibass == enabled\) return/);
});
