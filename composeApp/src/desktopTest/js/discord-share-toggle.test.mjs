// Run with: node composeApp/src/desktopTest/js/discord-share-toggle.test.mjs
// Exercise the shipped toggle logic using the same tiny VM harness as player-prompts.test.mjs.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const root = new URL("../../desktopMain/resources/player-ui/", import.meta.url);
const source = readFileSync(new URL("controls.js", root), "utf8");
const html = readFileSync(new URL("controls.html", root), "utf8");
const css = readFileSync(new URL("controls.css", root), "utf8");
assert.match(html, /id="discordShareButton"[^>]+data-command="setDiscordShareEnabled"[^>]+aria-pressed="false"/);
assert.match(html, /id="surroundUpmixButton"[^>]+data-command="setSurroundUpmixEnabled"[^>]+aria-pressed="false"/);
assert.match(html, /<symbol id="icon-discord"[^>]*>[\s\S]*?fill="currentColor"/);
assert.ok(html.indexOf('data-command="audio"') < html.indexOf('id="discordShareButton"'));
assert.ok(css.includes(".option-row.toggle-action[aria-pressed=\"true\"] .option-switch"),
  "playback toggles show their on state as a switch in Playback options");
const mediumWindowRule = css.match(/@media \(min-width: 761px\) and \(max-width: 959px\) \{[\s\S]*?^\}/m)?.[0];
assert.ok(mediumWindowRule, "medium-size player rules must be present");
assert.doesNotMatch(mediumWindowRule, /discordShareButton|\.action\.toggle-action/,
  "the Discord button stays visible at medium desktop widths");

const button = () => ({
  hidden: true, attributes: {}, text: { textContent: "" },
  setAttribute(key, value) { this.attributes[key] = value; },
  querySelector() { return this.text; },
});
const sent = [];
const toasts = [];
const context = {
  autoSkipIntroButton: button(), autoNextEpisodeButton: button(),
  discordShareButton: button(), surroundUpmixButton: button(),
  state: {
    showAutoToggles: false,
    showDiscordShare: false,
    discordShareEnabled: false,
    showSurroundUpmix: false,
    surroundUpmixEnabled: false,
    surroundUpmixStatus: 0,
  },
  setVisible(element, visible) { element.hidden = !visible; },
  showPlayerToast(message) { toasts.push(message); },
  send(command, value) { sent.push([command, value]); },
};
const start = source.indexOf("const autoToggles = [");
const end = source.indexOf("const setActionButtonLabel =", start);
assert.ok(start >= 0 && end > start);
vm.createContext(context);
vm.runInContext(source.slice(start, end), context);
vm.runInContext("syncAutoToggleButtons()", context);
assert.equal(context.discordShareButton.hidden, true, "unsupported platforms hide Discord control");

context.state.showDiscordShare = true;
vm.runInContext("syncAutoToggleButtons()", context);
assert.equal(context.discordShareButton.hidden, false, "supported Windows players show the button");
assert.equal(context.autoSkipIntroButton.hidden, true, "Discord visibility is independent of auto toggles");
assert.equal(context.discordShareButton.attributes["aria-pressed"], "false");
assert.equal(vm.runInContext('toggleAutoSetting("setDiscordShareEnabled")', context), true);
assert.deepEqual(sent[0], ["setDiscordShareEnabled", 1]);
assert.equal(context.discordShareButton.attributes["aria-pressed"], "true");
assert.equal(context.discordShareButton.attributes.title, "Discord audio: On");
assert.equal(toasts[0], "Discord audio: On");

// Advanced Settings (or helper failure) can update the true state from Kotlin.
context.state.discordShareEnabled = false;
vm.runInContext("syncAutoToggleButtons()", context);
assert.equal(context.discordShareButton.attributes["aria-pressed"], "false");
assert.equal(context.discordShareButton.attributes.title, "Discord audio: Off");
assert.equal(vm.runInContext('toggleAutoSetting("setDiscordShareEnabled")', context), true);
assert.equal(vm.runInContext('toggleAutoSetting("setDiscordShareEnabled")', context), true);
assert.deepEqual(sent.at(-1), ["setDiscordShareEnabled", 0]);

// Upmix status is separate from the saved opt-in. A requested but incompatible
// 5.1 route must not be advertised as actually outputting surround.
assert.equal(context.surroundUpmixButton.hidden, true);
context.state.showSurroundUpmix = true;
vm.runInContext("syncAutoToggleButtons()", context);
assert.equal(context.surroundUpmixButton.hidden, false);
assert.equal(context.surroundUpmixButton.attributes["data-upmix-status"], "off");
assert.equal(vm.runInContext('toggleAutoSetting("setSurroundUpmixEnabled")', context), true);
assert.deepEqual(sent.at(-1), ["setSurroundUpmixEnabled", 1]);
assert.equal(context.surroundUpmixButton.attributes["data-upmix-status"], "waiting");
assert.match(context.surroundUpmixButton.attributes.title, /requires a Windows output/);
context.state.surroundUpmixStatus = 2;
vm.runInContext("syncAutoToggleButtons()", context);
assert.equal(context.surroundUpmixButton.attributes["data-upmix-status"], "active");
assert.match(context.surroundUpmixButton.attributes.title, /Stereo upmixed to 5.1 PCM/);
context.state.surroundUpmixStatus = -1;
vm.runInContext("syncAutoToggleButtons()", context);
assert.equal(context.surroundUpmixButton.attributes["data-upmix-status"], "failed");
context.state.surroundUpmixEnabled = false;
context.state.surroundUpmixStatus = 0;
vm.runInContext("syncAutoToggleButtons()", context);
assert.equal(context.surroundUpmixButton.attributes["data-upmix-status"], "off");

// Virtual headset mode uses the same single toggle but must not claim that
// the vendor's headphone spatializer is enabled or physically verified.
context.state.surroundUpmixLabel = "7.1";
context.state.surroundUpmixHeadsetMode = true;
context.state.surroundUpmixEnabled = true;
context.state.surroundUpmixStatus = 1;
vm.runInContext("syncAutoToggleButtons()", context);
assert.equal(context.surroundUpmixButton.text.textContent, "7.1…");
assert.match(context.surroundUpmixButton.attributes.title, /audio driver that accepts 7\.1 PCM/);
context.state.surroundUpmixStatus = 2;
vm.runInContext("syncAutoToggleButtons()", context);
assert.equal(context.surroundUpmixButton.text.textContent, "7.1 ✓");
assert.match(context.surroundUpmixButton.attributes.title, /headset virtualization is not verified/);
context.state.surroundUpmixEnabled = false;
context.state.surroundUpmixStatus = 0;
vm.runInContext("syncAutoToggleButtons()", context);
assert.equal(context.surroundUpmixButton.text.textContent, "7.1");
// The new virtual 5.1 mode must not display a physical-speaker status.
context.state.surroundUpmixLabel = "5.1";
context.state.surroundUpmixEnabled = true;
context.state.surroundUpmixStatus = 2;
vm.runInContext("syncAutoToggleButtons()", context);
assert.equal(context.surroundUpmixButton.text.textContent, "5.1 ✓");
assert.match(context.surroundUpmixButton.attributes.title, /5\.1 PCM sent to the audio driver/);
context.state.surroundUpmixStatus = 1;
vm.runInContext("syncAutoToggleButtons()", context);
assert.match(context.surroundUpmixButton.attributes.title, /audio driver that accepts 5\.1 PCM/);
context.state.surroundUpmixHeadsetMode = false;
context.state.surroundUpmixStatus = 2;
vm.runInContext("syncAutoToggleButtons()", context);
assert.match(context.surroundUpmixButton.attributes.title, /Stereo upmixed to 5\.1 PCM/);
console.log("Discord player button: visibility, on/off commands, and state re-sync passed.");
