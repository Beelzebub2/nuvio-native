import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { verifyArPackage } from "./starlit-webos-artifacts.mjs";
function member(name, body) {
  const header = Buffer.from(
    name.padEnd(16, " ") + "0".padEnd(12, " ") + "0".padEnd(6, " ") +
    "0".padEnd(6, " ") + "100644".padEnd(8, " ") + String(body.length).padEnd(10, " ") + "\x60\n",
    "ascii"
  );
  return Buffer.concat([header, body, body.length % 2 ? Buffer.from("\n") : Buffer.alloc(0)]);
}
test("IPK reader rejects incomplete archive and accepts valid members", () => {
  assert.throws(() => verifyArPackage(Buffer.from("wrong")));
  const archive = Buffer.concat([Buffer.from("!<arch>\n"), member("control.tar.gz/", Buffer.from("a")), member("data.tar.gz/", Buffer.from("abc"))]);
  assert.doesNotThrow(() => verifyArPackage(archive));
  assert.throws(() => verifyArPackage(archive.subarray(0, archive.length - 2)));
});
test("webOS service IDs and installer match Starlit", async () => {
  const companion = await readFile(new URL("../js/platform/webos/webosCompanionService.js", import.meta.url), "utf8");
  const plugin = await readFile(new URL("../js/platform/webos/webosPluginService.js", import.meta.url), "utf8");
  const packageScript = await readFile(new URL("./package-webos.mjs", import.meta.url), "utf8");
  const config = JSON.parse(await readFile(new URL("../../tv-installer/installer.config.json", import.meta.url), "utf8"));
  assert.match(companion, /app\.starlit\.webos\.service/);
  assert.match(plugin, /app\.starlit\.webos\.plugin\.service/);
  assert.match(packageScript, /const appId = "app\.starlit\.webos"/);
  assert.equal(config.webos.appId, "app.starlit.webos");
  assert.equal(config.githubRepo, "Beelzebub2/nuvio-native");
});

test("webOS packaging tools are locked for clean installs", async () => {
  const manifest = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"));
  const lock = JSON.parse(await readFile(new URL("../package-lock.json", import.meta.url), "utf8"));
  const require = createRequire(import.meta.url);
  for (const [name, version] of [
    ["@resvg/resvg-js", "2.6.2"],
    ["pngjs", "7.0.0"]
  ]) {
    assert.equal(manifest.devDependencies[name], version);
    assert.equal(lock.packages[""].devDependencies[name], version);
    assert.ok(require.resolve(name), name + " must be installed by npm ci");
  }
  const installer = JSON.parse(await readFile(new URL("../../tv-installer/package.json", import.meta.url), "utf8"));
  const installerLock = JSON.parse(await readFile(new URL("../../tv-installer/package-lock.json", import.meta.url), "utf8"));
  assert.equal(installer.dependencies["@webos-tools/cli"], "3.2.6");
  assert.equal(installerLock.packages[""].dependencies["@webos-tools/cli"], "3.2.6");
  const installerRequire = createRequire(new URL("../../tv-installer/package.json", import.meta.url));
  assert.ok(installerRequire.resolve("@webos-tools/cli/package.json"));
});
