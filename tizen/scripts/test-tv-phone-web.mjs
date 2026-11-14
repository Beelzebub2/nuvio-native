import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";
import { createTvSetupServer } from "../../tv-setup-server/server.mjs";

const port = 28751;
const origin = "http://127.0.0.1:" + port;
const server = createTvSetupServer({ origin });
await new Promise((resolve, reject) =>
  server.once("error", reject).listen(port, "127.0.0.1", resolve));
const http = async (path, method = "GET", token = "", body) => {
  const response = await fetch(origin + path, {
    method,
    headers: {
      ...(method === "POST" ? { "Content-Type": "application/json" } : {}),
      ...(token ? { "X-TV-Token": token } : {})
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
  assert.equal(response.status, 200, method + " " + path + ": " + response.status);
  return response.json();
};
let browser;
try {
  const tv = await http("/tv/api/start", "POST", "", { name:"Living Room TV", profile:"2" });
  browser = await chromium.launch({ headless:true });
  const context = await browser.newContext({ viewport:{ width:390, height:844 }, deviceScaleFactor:1 });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(origin + "/tv/connect#code=" + tv.claimKey);
  await page.locator("#pending-screen:not([hidden])").waitFor();
  const claim = await http("/tv/api/tv?id=" + tv.id, "GET", tv.tvToken);
  assert.equal(claim.claimed, true);
  assert.equal(await page.locator("#verify-code").innerText(), claim.verification);
  assert.equal(await page.locator("#manage-screen").isVisible(), false);
  await http("/tv/api/approve?id=" + tv.id, "POST", tv.tvToken, { accept:true });
  await http("/tv/api/state?id=" + tv.id, "POST", tv.tvToken, {
    profile:"2", canEditAddons:true, canEditPlugins:false,
    pluginExecutable:false, pluginReason:"Plugins unavailable on this TV",
    revision:1,
    addons:[{ id:"addon:0", name:"Existing example", enabled:true }],
    repositories:[], providers:[]
  });
  await page.locator("#manage-screen:not([hidden])").waitFor({ timeout:15000 });
  assert.match(await page.locator("#addons-list").innerText(), /Existing example/);
  const input = page.locator("#addon-url");
  await input.fill("https://approved.example.org/manifest.json");
  await page.locator("#addon-form button").click();
  async function waitCommand(op) {
    for (let i = 0; i < 50; i++) {
      const inspected = await http("/tv/api/tv?id=" + tv.id, "GET", tv.tvToken);
      if (inspected.commands?.[0]?.op === op) return inspected.commands[0];
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw new Error("TV did not receive " + op);
  }
  const preview = await waitCommand("previewAddon");
  assert.equal(preview.url, "https://approved.example.org/manifest.json");
  await http("/tv/api/ack?id=" + tv.id, "POST", tv.tvToken, {
    id:preview.id, ok:true, message:"Preview ready",
    detail:{ name:"Approved test add-on", description:"Authorized test fixture", items:["stream"] }
  });
  await page.locator("#preview-addon:not([hidden])").waitFor({ timeout:15000 });
  assert.match(await page.locator("#preview-addon").innerText(), /Approved test add-on/);
  await page.locator("#preview-addon button").click();
  const install = await waitCommand("installAddon");
  assert.equal(install.url, preview.url);
  await http("/tv/api/ack?id=" + tv.id, "POST", tv.tvToken, {
    id:install.id, ok:true, message:"Installed on TV"
  });
  await http("/tv/api/state?id=" + tv.id, "POST", tv.tvToken, {
    profile:"2", canEditAddons:true, canEditPlugins:false,
    pluginExecutable:false, pluginReason:"Plugins unavailable on this TV",
    revision:2,
    addons:[
      { id:"addon:0", name:"Existing example", enabled:true },
      { id:"addon:1", name:"Approved test add-on", enabled:true }
    ], repositories:[], providers:[]
  });
  await page.waitForFunction(() =>
    document.querySelector("#addon-count")?.textContent === "2" &&
    document.querySelector("#preview-addon")?.hidden === true);
  await page.locator("#tab-plugins").click();
  assert.equal(await page.locator("#plugin-limitation").isVisible(), true);
  assert.equal(await page.locator("#plugin-form button").isDisabled(), true);
  await page.locator("#tab-addons").click();
  const pageWidth = await page.evaluate(() => document.documentElement.scrollWidth);
  assert.ok(pageWidth <= 391, "phone page must not overflow horizontally: " + pageWidth);
  const screenshotDir = path.join(path.dirname(fileURLToPath(import.meta.url)), "../test-results");
  await mkdir(screenshotDir, { recursive:true });
  await page.screenshot({ path:path.join(screenshotDir, "tv-phone-mobile.png"), fullPage:true });
  assert.deepEqual(errors, [], "mobile page JavaScript errors");
  await http("/tv/api/stop?id=" + tv.id, "POST", tv.tvToken, {});
  await page.locator("#error-screen:not([hidden])").waitFor({ timeout:15000 });
  await context.close();
  console.log("TV phone mobile browser: pairing, TV approval, preview, install, unsupported plugin and revocation passed");
} finally {
  if (browser) await browser.close();
  await new Promise(resolve => server.close(resolve));
}
