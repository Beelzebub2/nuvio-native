// Run with: node composeApp/src/desktopTest/js/player-prompts.test.mjs
// Exercise the shipped WebView prompt renderer without adding a DOM dependency.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const root = new URL("../../desktopMain/resources/player-ui/", import.meta.url);
const source = readFileSync(new URL("controls.js", root), "utf8");
const css = readFileSync(new URL("controls.css", root), "utf8");
const html = readFileSync(new URL("controls.html", root), "utf8");
const start = source.indexOf("const renderNativePlaybackPrompts = () => {");
const end = source.indexOf("const isOpeningOverlayActive", start);
assert.ok(start >= 0 && end > start, "The real prompt renderer must be exercised");
assert.match(html, /id="nextEpisodeCard"/);
for (const [, selectors, declarations] of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
  for (const selector of selectors.split(",")) {
    if (selector.includes(".chrome-hidden") && selector.includes(".next-episode-card")) {
      assert.doesNotMatch(declarations, /opacity:\s*0\b|visibility:\s*hidden|display:\s*none/,
        "Auto-hiding controls must not hide the next-episode prompt");
    }
  }
}
const element = () => ({
  attributes: {}, classes: new Set(), textContent: "", hidden: false,
  setAttribute(key, value) { this.attributes[key] = value; },
  get classList() { return { toggle: (key, on) => on ? this.classes.add(key) : this.classes.delete(key) }; },
});
const context = {
  state: { controlsVisible: false, skipPromptVisible: false, nextEpisodeVisible: true,
    nextEpisodeTitle: "Episode 2", nextEpisodePlayable: true },
  window: { clearTimeout() {} },
  skipPromptKey: "", skipPromptWasDismissed: false, skipPromptAutoHidden: false,
  skipPromptAutoHideTimer: 0, skipPromptAutoHideActive: false,
  resetSkipPromptAutoHide() {}, syncSkipPromptPlacement() {}, startSkipPromptAutoHide() {},
  setImageSource() { return ""; },
};
for (const name of ["skipPrompt", "skipPromptLabel", "nextEpisodeThumb", "nextEpisodeHeader",
  "nextEpisodeTitle", "nextEpisodeStatus", "nextEpisodeAction", "nextEpisodeCard"]) context[name] = element();
vm.createContext(context);
vm.runInContext(source.slice(start, end), context);
vm.runInContext("renderNativePlaybackPrompts()", context);
assert.equal(context.nextEpisodeCard.attributes["aria-hidden"], "false");
assert.ok(context.nextEpisodeCard.classes.has("visible"));
assert.ok(context.nextEpisodeCard.classes.has("playable"));
assert.equal(context.nextEpisodeTitle.textContent, "Episode 2");
context.state.nextEpisodeVisible = false;
vm.runInContext("renderNativePlaybackPrompts()", context);
assert.equal(context.nextEpisodeCard.attributes["aria-hidden"], "true");
assert.ok(!context.nextEpisodeCard.classes.has("visible"));
console.log("Native next-episode prompt: hidden-controls visibility and dismissal passed.");
