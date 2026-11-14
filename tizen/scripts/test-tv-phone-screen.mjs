import { createServer } from "node:http";
import { readFile, mkdir } from "node:fs/promises";
import path from "node:path";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { chromium } from "@playwright/test";

const root = fileURLToPath(new URL("..", import.meta.url));
await mkdir(path.join(root, ".cache"), { recursive:true });
await mkdir(path.join(root, "test-results"), { recursive:true });
const bundledApp = path.join(root, ".cache", "test-tv-phone-screen.js");
await build({
  stdin:{
    contents:'import "./js/app.js"; import { Router } from "./js/ui/navigation/router.js"; import { ExperienceModeStore } from "./js/data/local/experienceModeStore.js"; ExperienceModeStore.set({ mode:"ADVANCED", addonSetupSkipped:true }); globalThis.__starlitPhoneTVTest={ Router };',
    resolveDir:root
  },
  outfile:bundledApp,
  bundle:true,
  format:"iife",
  target:"chrome63",
  define:{ __NUVIO_APP_VERSION__:'"0.1.0"' }
});
const mime = { ".html":"text/html", ".js":"text/javascript", ".json":"application/json",
  ".css":"text/css", ".svg":"image/svg+xml", ".png":"image/png", ".woff2":"font/woff2" };
const server = createServer(async (req, res) => {
  const filename = new URL(req.url || "/", "http://local.invalid").pathname;
  const relative = filename === "/" ? "index.html" : filename.slice(1);
  const target = path.resolve(root, "dist", relative);
  const base = path.resolve(root, "dist");
  if (!target.startsWith(base + path.sep)) { res.writeHead(403); return res.end(); }
  try {
    const bytes = await readFile(relative === "app.bundle.js" ? bundledApp : target);
    res.setHeader("Content-Type", mime[path.extname(target)] || "application/octet-stream");
    res.end(bytes);
  } catch (_) { res.writeHead(404); res.end(); }
});
await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
const origin = "http://127.0.0.1:" + server.address().port;
let browser;
const errors = [];
let claimed = false;
let approved = false;
let stopCount = 0;
try {
  browser = await chromium.launch({ headless:true });
  const context = await browser.newContext({ viewport:{ width:1920, height:1080 } });
  const page = await context.newPage();
  page.on("pageerror", error => errors.push(error.message));
  const blocked = [];
  page.on("requestfailed", request => blocked.push(request.url() + ": " + request.failure()?.errorText));
  await page.route("https://**/*", route => route.abort());
  await page.route("https://starlit.rrmtools.uk/tv/api/**", async route => {
    const req = route.request();
    const action = new URL(req.url()).pathname.split("/").pop();
    const headers = {
      "Access-Control-Allow-Origin":"*",
      "Access-Control-Allow-Methods":"GET,POST,OPTIONS",
      "Access-Control-Allow-Headers":"Content-Type,X-TV-Token"
    };
    if (req.method() === "OPTIONS") return route.fulfill({ status:204, headers });
    const payload = {
      start:{ id:"a".repeat(64), code:"ABC12345", claimKey:"b".repeat(64), tvToken:"c".repeat(64) },
      tv:{ claimed, verification:claimed ? "3H8PYK" : "", commands:[] },
      approve:{ approved:true },
      state:{ ok:true },
      ack:{ ok:true },
      stop:{ revoked:true }
    }[action];
    if (action === "approve") approved = true;
    if (action === "stop") stopCount++;
    assert.ok(payload, "Unexpected action " + action);
    return route.fulfill({ status:200, headers, contentType:"application/json", body:JSON.stringify(payload) });
  });
  await page.addInitScript(() => {
    localStorage.setItem("skipAuthQrGate", "true");
    localStorage.setItem("activeProfileId", '"1"');
    localStorage.setItem("rememberLastProfile", "true");
    localStorage.setItem("hasEverSelectedProfile", "true");
  });
  await page.goto(origin);
  await page.waitForFunction(() => window.__starlitPhoneTVTest?.Router?.navigate);
  await page.evaluate(() => window.__starlitPhoneTVTest.Router.navigate("tvPhoneSetup"));
  try {
    await page.waitForFunction(() =>
      document.querySelector("#tvPhoneSetup .tv-phone-code")?.textContent.includes("ABC12345"),
      undefined, { timeout:10000 });
  } catch (error) {
    console.error("TV screen content:", await page.locator("#tvPhoneSetup").innerText());
    console.error("TV router route:", await page.evaluate(() => window.__starlitPhoneTVTest.Router.getCurrent()));
    console.error("Page errors:", errors);
    console.error("Network errors:", blocked.slice(-15));
    throw error;
  }
  const qr = page.locator("#tv-phone-qr");
  await qr.waitFor();
  await page.waitForFunction(() =>
    document.querySelector("#tv-phone-qr")?.width === 408);
  claimed = true;
  await page.waitForFunction(() =>
    document.querySelector('#tvPhoneSetup [data-phone-action="approve"]'));
  assert.match(await page.locator(".tv-phone-verify").innerText(), /3H8PYK/);
  await page.keyboard.press("ArrowUp");
  assert.equal(await page.locator("#tvPhoneSetup .focusable.focused").getAttribute("data-phone-action"), "approve");
  await page.keyboard.press("Enter");
  await page.waitForFunction(() => document.querySelector("#tvPhoneSetup")?.textContent.includes("Phone connected"));
  assert.equal(approved, true);
  await page.screenshot({ path:path.join(root, "test-results", "tv-phone-1920.png") });
  await page.evaluate(() => window.__starlitPhoneTVTest.Router.navigate("home"));
  await page.waitForFunction(() => window.__starlitPhoneTVTest.Router.getCurrent() === "home");
  await page.waitForTimeout(250);
  assert.ok(stopCount >= 1, "Leaving TV setup must revoke the pairing token");
  assert.deepEqual(errors, [], "Tizen browser should have no unhandled page errors");
  await context.close();
  console.log("TV browser: QR rendered at 1920x1080, approval, connected state and revocation passed");
} finally {
  if (browser) await browser.close();
  await new Promise(resolve => server.close(resolve));
}
