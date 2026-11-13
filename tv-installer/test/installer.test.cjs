"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { gzipSync } = require("node:zlib");
const { mkdtemp, writeFile, rm } = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const { inspectIpk } = require("../src/ipk.cjs");
const { parseChecksum, approvedAssetUrl, expectedReleaseAssets } = require("../src/releases.cjs");
const { isLanIPv4, validateTarget, pairTv, operate } = require("../src/installer.cjs");

function arEntry(name, data) {
  const header = Buffer.alloc(60, 0x20);
  header.write(name.padEnd(16, " "), 0, "ascii");
  header.write(String(data.length).padEnd(10, " "), 48, "ascii");
  header[58] = 0x60; header[59] = 10;
  return Buffer.concat([header, data, data.length % 2 ? Buffer.from("\n") : Buffer.alloc(0)]);
}
function makePackage(appId = "app.starlit.webos") {
  const data = Buffer.from(JSON.stringify({
    id: appId, version: "0.1.0",
    services: ["app.starlit.webos.service", "app.starlit.webos.plugin.service"]
  }));
  const header = Buffer.alloc(512);
  header.write("usr/palm/applications/app.starlit.webos/appinfo.json");
  header.write(data.length.toString(8).padStart(11, "0") + "\0", 124);
  header[156] = 0x30;
  const tar = Buffer.concat([header, data, Buffer.alloc(Math.ceil(data.length / 512) * 512 - data.length + 1024)]);
  return Buffer.concat([
    Buffer.from("!<arch>\n"), arEntry("debian-binary/", Buffer.from("2.0\n")),
    arEntry("control.tar.gz/", Buffer.from("placeholder")), arEntry("data.tar.gz/", gzipSync(tar))
  ]);
}
test("Only Starlit LG IPKs are accepted", () => {
  assert.deepEqual(inspectIpk(makePackage()), { appId: "app.starlit.webos", version: "0.1.0" });
  assert.throws(() => inspectIpk(makePackage("space.nuvio.webos")), /not a Starlit/);
  assert.throws(() => inspectIpk(Buffer.from("wrong")), /valid webOS/);
});
test("Pairing validates the private LAN and never logs the passphrase", async () => {
  for (const ip of ["127.0.0.1", "8.8.8.8", "1.2.3.4", "192.168.0.1;rm"]) {
    assert.equal(isLanIPv4(ip), false);
    assert.throws(() => validateTarget({ ip, deviceName: "lg" }), /private LAN/);
  }
  assert.equal(isLanIPv4("192.168.0.8"), true);
  assert.throws(() => validateTarget({ ip: "192.168.0.8", deviceName: "../oops" }), /device name/);
  const calls = [], status = [];
  const result = await pairTv({ ip: "192.168.0.8", deviceName: "lg", passphrase: "123456" }, {
    listDevices: async () => [],
    run: async (command, args) => calls.push([command, args]),
    onStatus: text => status.push(text)
  });
  assert.equal(result.paired, true);
  assert.equal(calls[0][0], "ares-setup-device");
  assert.equal(calls[1][0], "ares-novacom");
  assert.ok(calls[1][1].includes("123456"));
  assert.ok(!status.join(" ").includes("123456"));
});
test("Install performs in-place update; launch and removal use Starlit app ID", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "starlit-installer-test-"));
  const file = path.join(dir, "test.ipk");
  const calls = [], target = { ip: "192.168.2.9", deviceName: "lg" };
  const deps = {
    listDevices: async () => [{ name: "lg", host: "192.168.2.9" }],
    run: async (command, args) => calls.push([command, args])
  };
  try {
    await writeFile(file, makePackage());
    assert.equal((await operate({ action: "install", source: "local", filePath: file, target }, deps)).installed, true);
    assert.deepEqual(calls[0], ["ares-install", ["--device", "lg", file]]);
    await operate({ action: "launch", target }, deps);
    assert.deepEqual(calls[1], ["ares-launch", ["--device", "lg", "app.starlit.webos"]]);
    await operate({ action: "uninstall", target }, deps);
    assert.deepEqual(calls[2], ["ares-install", ["--device", "lg", "--remove", "app.starlit.webos"]]);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
test("Release assets require a stable tag and an exact SHA-256", () => {
  const name = "Starlit-WebOS-0.1.0.ipk", hash = "a".repeat(64);
  assert.equal(parseChecksum(hash + "  " + name + "\n", name), hash);
  assert.throws(() => parseChecksum(hash + "  other.ipk", name), /SHA-256/);
  assert.throws(() => expectedReleaseAssets({ tag_name: "v0.1.0", assets: [] }), /accepted webOS/);
  assert.equal(approvedAssetUrl('https://github.com/Beelzebub2/starlit/releases/download/v0.1.0/' + name, 'v0.1.0', name), 'https://github.com/Beelzebub2/starlit/releases/download/v0.1.0/' + name);
  assert.throws(() => approvedAssetUrl('https://github.com/attacker/starlit/releases/download/v0.1.0/' + name, 'v0.1.0', name), /Untrusted/);
  assert.throws(() => expectedReleaseAssets({ tag_name: "v0.1.0", prerelease: true, assets: [] }), /verified stable/);
});
