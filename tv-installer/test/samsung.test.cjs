"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const os = require("node:os");
const fs = require("node:fs/promises");
const { Readable } = require("node:stream");
const { createHash } = require("node:crypto");
const JSZip = require("jszip");
const { inspectWgt } = require("../src/wgt.cjs");
const { isLanIPv4, localLanIps, platformInfo } = require("../src/platforms.cjs");
const { operate } = require("../src/installer.cjs");
const { expectedReleaseAssets, fetchVerifiedRelease } = require("../src/releases.cjs");
const {
  validateSamsungTarget, validateSigning, operateSamsung, ensureSamsungCli,
  cliFailure, ZIP_SHA256, EXE_SHA256
} = require("../src/samsung.cjs");

async function createWgt({
  appId = "StarlitTV1.StarlitTV", packageId = "StarlitTV1", version = "1.24.0", signed = false
} = {}) {
  const zip = new JSZip();
  zip.file("config.xml", '<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" version="' +
    version + '"><tizen:application id="' + appId +
    '" package="' + packageId + '" required_version="4.0"/><name>Starlit TV</name></widget>');
  for (const file of [
    "index.html", "main.js", "app.bundle.js", "services/tizen/enginefs-service.js",
    "services/tizen/plugin-service.js", "services/tizen/runtime/media-http.cjs",
    "LICENSE", "UPSTREAM.json"
  ]) zip.file(file, "testing");
  if (signed) zip.file("author-signature.xml", "<signature/>");
  return zip.generateAsync({ type: "nodebuffer", compression: "DEFLATE" });
}
test("WGT inspection enforces Starlit's Samsung identity and streaming payload", async () => {
  assert.deepEqual(await inspectWgt(await createWgt()), {
    appId: "StarlitTV1.StarlitTV", packageId: "StarlitTV1", version: "1.24.0", signed: false
  });
  assert.equal((await inspectWgt(await createWgt({ signed: true }))).signed, true);
  await assert.rejects(inspectWgt(await createWgt({ appId: "Another1.OtherApp" })), /not a Starlit/);
  await assert.rejects(inspectWgt(await createWgt({ packageId: "NuvioTV1" })), /not a Starlit/);
  await assert.rejects(inspectWgt(await createWgt({ version: "invalid" })), /not a Starlit/);
  await assert.rejects(inspectWgt(Buffer.from("not a ZIP")), /valid WGT/);
  const spoofed = new JSZip();
  const comment = '<!-- <widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" version="1.24.0">' +
    '<tizen:application id="StarlitTV1.StarlitTV" package="StarlitTV1"/><name>Starlit TV</name></widget> -->';
  spoofed.file("config.xml", comment + '<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" version="1.24.0">' +
    '<tizen:application id="NuvioTV1.NuvioTV" package="NuvioTV1"/><name>Starlit TV</name></widget>');
  for (const file of ["index.html", "main.js", "app.bundle.js", "services/tizen/enginefs-service.js",
    "services/tizen/plugin-service.js", "services/tizen/runtime/media-http.cjs", "LICENSE", "UPSTREAM.json"]) spoofed.file(file, "testing");
  await assert.rejects(inspectWgt(await spoofed.generateAsync({ type: "nodebuffer" })), /not a Starlit/);
  const incomplete = new JSZip();
  incomplete.file("config.xml", "test");
  await assert.rejects(inspectWgt(await incomplete.generateAsync({ type: "nodebuffer" })), /missing required/);
});
test("Platform validation and LAN address display are scoped", () => {
  assert.equal(platformInfo("tizen").appId, "StarlitTV1.StarlitTV");
  assert.equal(platformInfo("webos").extension, ".ipk");
  assert.throws(() => platformInfo("nuvio"), /Choose/);
  assert.equal(isLanIPv4("10.20.30.40"), true);
  for (const ip of ["127.0.0.1", "8.8.8.8", "0.0.0.0", "192.168.1.10;bad"]) {
    assert.throws(() => validateSamsungTarget({ ip }), /private LAN/);
  }
  assert.deepEqual(localLanIps({
    Ethernet: [{ family: "IPv4", address: "192.168.1.30", internal: false }],
    VPN: [{ family: "IPv4", address: "10.8.0.6", internal: false }],
    Loopback: [{ family: "IPv4", address: "127.0.0.1", internal: true }]
  }), ["10.8.0.6", "192.168.1.30"]);
  assert.equal(validateSigning("samsung"), "samsung");
  assert.equal(validateSigning("tizen-public"), "tizen-public");
  assert.throws(() => validateSigning("unsigned"), /certificate/);
});
test("Samsung commands target only the Starlit TV package and preserve data on update", async () => {
  const tmp = await fs.mkdtemp(path.join(os.tmpdir(), "starlit-dual-tv-tests-"));
  const file = path.join(tmp, "local.wgt");
  const calls = [], statuses = [];
  const deps = {
    ensureTool: async () => path.join(tmp, "fake-samsung-cli.exe"),
    run: async (_exe, args) => { calls.push(args); return { ok: true }; },
    onStatus: message => statuses.push(message)
  };
  const target = { ip: "192.168.1.40" };
  try {
    await fs.writeFile(file, await createWgt());
    assert.equal((await operateSamsung({ action: "pair", target }, deps)).connected, true);
    const installed = await operateSamsung({
      action: "install", target, source: "local", filePath: file, signing: "samsung"
    }, deps);
    assert.equal(installed.version, "1.24.0");
    assert.deepEqual(calls[1], ["install", file, "--tv", target.ip, "--samsung-cert"]);
    await operateSamsung({ action: "install", target, source: "local", filePath: file, signing: "tizen-public" }, deps);
    assert.deepEqual(calls[2], ["install", file, "--tv", target.ip, "--tizen-cert", "public"]);
    for (const args of calls) {
      assert.equal(args.includes("--remove-first"), false);
      assert.equal(args.includes("--yes"), false);
      assert.equal(args.includes("--allow-remove"), false);
      assert.equal(args.includes("--new-id"), false);
    }
    assert.equal((await operateSamsung({ action: "launch", target }, deps)).launched, true);
    assert.deepEqual(calls[3], ["launch", "StarlitTV1.StarlitTV", "--tv", target.ip]);
    assert.equal((await operateSamsung({ action: "uninstall", target }, deps)).uninstalled, true);
    assert.deepEqual(calls[4], ["uninstall", "StarlitTV1.StarlitTV", "--tv", target.ip]);
    assert.ok(statuses.some(x => x.includes("Signing Starlit")));
    await assert.rejects(operateSamsung({
      action: "install", target, source: "local", filePath: file, signing: "invalid"
    }, deps), /certificate/);
    await assert.rejects(operateSamsung({ action: "install", target, source: "local",
      filePath: path.join(tmp, "other.ipk") }, deps), /wgt/);
    const viaTvStore = await operateSamsung({
      action: "install", target, source: "local", filePath: file
    }, { ...deps, run: async () => ({ via: "tvstore" }) });
    assert.equal(viaTvStore.pendingTvConfirmation, true);
    const routed = await operate({ platform: "tizen", action: "launch", target }, {
      samsungOps: deps
    });
    assert.equal(routed.appId, "StarlitTV1.StarlitTV");
  } finally {
    await fs.rm(tmp, { recursive: true, force: true });
  }
});
test("Samsung CLI errors describe safe recovery, never bypass security checks", () => {
  assert.match(cliFailure(3, "warning").message, /Host PC IP/);
  assert.match(cliFailure(1, "certificate login").message, /Samsung signing/);
  assert.match(cliFailure(1, "connection refused").message, /could not reach/i);
  assert.match(ZIP_SHA256, /^[a-f0-9]{64}$/);
  assert.match(EXE_SHA256, /^[a-f0-9]{64}$/);
});
test("Samsung CLI download fails closed when upstream archive checksum is invalid", { skip: process.platform !== "win32" }, async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "starlit-tv-cli-integrity-test-"));
  let calls = 0;
  try {
    await assert.rejects(ensureSamsungCli({
      toolsDir: dir, fetchImpl: async () => {
        calls += 1;
        return {
          ok: true, status: 200, headers: { get: () => "7" },
          body: Readable.from([Buffer.from("invalid")])
        };
      }
    }), /checksum/);
    assert.equal(calls, 1);
    await assert.rejects(fs.access(path.join(dir, "apps2samsung", "v3.3.0", "apps2samsung-cli.exe")));
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
});
test("Stable Tizen release verification checks the exact WGT and independent checksum", async () => {
  const wgt = await createWgt();
  const hash = createHash("sha256").update(wgt).digest("hex");
  const tag = "v1.24.0", name = "Starlit-Tizen-1.24.0.wgt";
  const base = "https://github.com/Beelzebub2/nuvio-native/releases/download/" + tag + "/";
  const release = {
    tag_name: tag, prerelease: false, draft: false,
    assets: [{ name, browser_download_url: base + name }, {
      name: "SHA256SUMS-Tizen", browser_download_url: base + "SHA256SUMS-Tizen"
    }]
  };
  const assets = expectedReleaseAssets(release, "tizen");
  assert.equal(assets.filename, name);
  assert.throws(() => expectedReleaseAssets({
    ...release, assets: [{ name, browser_download_url: base + name }]
  }, "tizen"), /accepted Samsung Tizen/);
  const responses = [Buffer.from(JSON.stringify(release)), Buffer.from(hash + "  " + name + "\n"), wgt];
  const fetchImpl = async () => {
    const body = responses.shift();
    return { ok: true, status: 200, headers: { get: () => String(body.length) }, body: Readable.from([body]) };
  };
  const verified = await fetchVerifiedRelease({ platform: "tizen", fetchImpl });
  assert.equal(verified.manifest.appId, "StarlitTV1.StarlitTV");
  assert.equal(verified.sha256, hash);
  assert.equal(verified.filename, name);
});
