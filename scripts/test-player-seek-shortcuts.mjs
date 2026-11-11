import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(
  new URL("../composeApp/src/desktopMain/resources/player-ui/controls.js", import.meta.url),
  "utf8",
);

// The controls script runs in a WebView. Exercise its real shortcut and feedback
// helpers without creating a DOM or starting the native player.
function loadHelper(name, environment = {}) {
  const start = source.indexOf(`const ${name} =`);
  assert.notEqual(start, -1, `missing helper ${name}`);
  const end = source.indexOf("\n};", start);
  assert.notEqual(end, -1, `missing end of helper ${name}`);
  const context = vm.createContext({ ...environment });
  vm.runInContext(
    `${source.slice(start, end + 3)}\nhelperResult = ${name};`,
    context,
    { timeout: 1000 },
  );
  return context.helperResult;
}

const shortcutCommandForEvent = loadHelper("shortcutCommandForEvent");
const keyEvent = (code, modifiers = {}) => ({
  code,
  shiftKey: false,
  ctrlKey: false,
  altKey: false,
  metaKey: false,
  ...modifiers,
});

for (const [event, expected] of [
  [keyEvent("ArrowLeft"), "keyboardSeekBack"],
  [keyEvent("ArrowRight"), "keyboardSeekForward"],
  [keyEvent("ArrowLeft", { shiftKey: true }), "keyboardMediumSeekBack"],
  [keyEvent("ArrowRight", { shiftKey: true }), "keyboardMediumSeekForward"],
  [keyEvent("ArrowLeft", { ctrlKey: true }), "keyboardLongSeekBack"],
  [keyEvent("ArrowRight", { ctrlKey: true }), "keyboardLongSeekForward"],
  [keyEvent("ArrowLeft", { shiftKey: true, ctrlKey: true }), "keyboardLongSeekBack"],
  [keyEvent("KeyJ", { shiftKey: true }), "keyboardFineSeekBack"],
  [keyEvent("KeyL", { shiftKey: true }), "keyboardFineSeekForward"],
  [keyEvent("KeyJ", { ctrlKey: true }), ""],
  [keyEvent("ArrowRight", { metaKey: true }), ""],
  [keyEvent("ArrowRight", { altKey: true }), ""],
]) {
  assert.equal(shortcutCommandForEvent(event), expected, JSON.stringify(event));
}

const state = { shortSeekSeconds: 12, mediumSeekSeconds: 28, longSeekSeconds: 95 };
const seekToastLabel = loadHelper("seekToastLabel", { state });
for (const [command, expected] of [
  ["seekBack", "-12s"],
  ["seekForward", "+12s"],
  ["keyboardSeekBack", "-12s"],
  ["keyboardSeekForward", "+12s"],
  ["keyboardMediumSeekBack", "-28s"],
  ["keyboardMediumSeekForward", "+28s"],
  ["keyboardLongSeekBack", "-95s"],
  ["keyboardLongSeekForward", "+95s"],
]) {
  assert.equal(seekToastLabel(command), expected);
}

state.shortSeekSeconds = -1;
assert.equal(seekToastLabel("keyboardSeekBack"), "-10s");
console.log("Desktop player seek shortcut and feedback tests passed.");
