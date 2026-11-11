import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, stat } from "node:fs/promises";
import { test } from "node:test";
import {
  isLanIp,
  parseExpectedWgtSha256,
  parseInstallCliResult,
  releaseAssetUrls,
  updateSideloadedTv,
  downloadVerifiedWgt
} from "../scripts/update-sideloaded-tv.mjs";

const TV_IP = "192.168.50.20";
const WGT = Buffer.from("synthetic test-only unsigned package");
const HASH = createHash("sha256").update(WGT).digest("hex");
const RELEASE = {
  tag_name: "v1.31.0",
  name: "Starlit TV 1.31.0",
  body: "Synthetic fixture",
  draft: false,
  prerelease: false,
  assets: [{ name: "Starlit-Tizen-1.31.0.wgt" }, { name: "SHA256SUMS-Tizen" }]
};
const URLS = releaseAssetUrls({ tag: "v1.31.0", assetName: "Starlit-Tizen-1.31.0.wgt" });

function response(body, { status = 200, headers } = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name) => headers?.[name] || null },
    json: async () => body,
    arrayBuffer: async () => Buffer.isBuffer(body) ? body : Buffer.from(body)
  };
}

function fetchFixture({ active = false, badDigest = false, missingPackage = false } = {}) {
  const log = [];
  let installed = false;
  const fetchImpl = async (url) => {
    log.push(url);
    if (url.includes("/api/v2/applications/")) {
      return response({
        id: "StarlitTV1.StarlitTV",
        version: installed ? "1.31.0" : "1.30.0",
        running: active,
        visible: active
      });
    }
    if (url.includes("/releases?per_page=")) {
      return response(missingPackage ? [{ ...RELEASE, assets: [] }] : [RELEASE]);
    }
    if (url === URLS.checksums) {
      const digest = badDigest ? "f".repeat(64) : HASH;
      return response(Buffer.from(`${digest}  Starlit-Tizen-1.31.0.wgt\n`));
    }
    if (url === URLS.wgt) return response(WGT);
    throw new Error(`Unexpected synthetic request: ${url}`);
  };
  return { log, fetchImpl, markInstalled: () => { installed = true; } };
}

test("only local IPv4 addresses can be passed as automatic update targets", () => {
  assert.equal(isLanIp(TV_IP), true);
  assert.equal(isLanIp("10.5.0.1"), true);
  assert.equal(isLanIp("172.16.0.10"), true);
  for (const ip of ["8.8.8.8", "127.0.0.1", "169.254.0.1", "::1",
    "192.168.50.1@github.com", "192.168.50.256"]) assert.equal(isLanIp(ip), false, ip);
});

test("release filenames and SHA-256SUMS must match exactly once", () => {
  assert.equal(parseExpectedWgtSha256(`${HASH}  Starlit-Tizen-1.31.0.wgt\n`,
    "Starlit-Tizen-1.31.0.wgt"), HASH);
  assert.throws(() => parseExpectedWgtSha256(
    `${HASH}  Starlit-Tizen-1.31.0.wgt\n${HASH}  Starlit-Tizen-1.31.0.wgt\n`,
    "Starlit-Tizen-1.31.0.wgt"), /exactly one/);
  assert.throws(() => parseExpectedWgtSha256(`${HASH}  other.wgt`,
    "Starlit-Tizen-1.31.0.wgt"), /exactly one/);
  assert.throws(() => releaseAssetUrls({
    tag: "v1.31.0", assetName: "Starlit-Tizen-1.30.0.wgt"
  }), /do not match/);
});

test("installer result parser accepts progress output followed by final JSON", () => {
  assert.deepEqual(parseInstallCliResult(
    "Checking TV…\r\nInstalling… 100%\r\n{\r\n  \"ok\": true,\r\n  \"appId\": \"StarlitTV1.StarlitTV\"\r\n}\r\n"
  ), { ok: true, appId: "StarlitTV1.StarlitTV" });
  assert.throws(() => parseInstallCliResult("Installing… 100%\nInvalid JSON"),
    /did not return JSON/);
});

test("scheduled update check never downloads or runs an installer without --install", async () => {
  const fixture = fetchFixture();
  const outcome = await updateSideloadedTv({ tvIp: TV_IP, fetchImpl: fixture.fetchImpl });
  assert.deepEqual(outcome, {
    status: "available",
    currentVersion: "1.30.0",
    nextVersion: "v1.31.0"
  });
  assert.equal(fixture.log.includes(URLS.wgt), false);
  assert.equal(fixture.log.includes(URLS.checksums), false);
});

test("updater skips TV playback and ignores releases without a Tizen package", async () => {
  const busy = fetchFixture({ active: true });
  assert.equal((await updateSideloadedTv({ tvIp: TV_IP,
    install: true, fetchImpl: busy.fetchImpl })).reason, "APP_IN_USE");
  assert.equal(busy.log.length, 1, "skip even the release fetch while app is active");
  const nativeOnly = fetchFixture({ missingPackage: true });
  assert.equal((await updateSideloadedTv({ tvIp: TV_IP,
    install: true, fetchImpl: nativeOnly.fetchImpl })).status, "current");
});

test("bad package digest blocks the installer before it can touch the TV", async () => {
  const fixture = fetchFixture({ badDigest: true });
  let called = false;
  await assert.rejects(updateSideloadedTv({
    tvIp: TV_IP, install: true, fetchImpl: fixture.fetchImpl,
    sdbReadyImpl: async () => true,
    runCliImpl: async () => { called = true; }
  }), /checksum mismatch/);
  assert.equal(called, false);
  await assert.rejects(downloadVerifiedWgt({
    tag: "v1.31.0", assetName: "Starlit-Tizen-1.31.0.wgt"
  }, fixture.fetchImpl), /checksum mismatch/);
});

test("verified WGT update uses hidden, non-destructive in-place Apps2Samsung install", async () => {
  const fixture = fetchFixture();
  let temporaryPackage;
  let cliCalls = 0;
  const result = await updateSideloadedTv({
    tvIp: TV_IP,
    localIp: "192.168.50.10",
    install: true,
    cli: "apps2samsung-cli",
    fetchImpl: fixture.fetchImpl,
    sdbReadyImpl: async () => true,
    runCliImpl: async (command, args) => {
      cliCalls++;
      assert.equal(command, "apps2samsung-cli");
      assert.equal(args[0], "install");
      temporaryPackage = args[1];
      assert.deepEqual(await readFile(temporaryPackage), WGT);
      for (const forbidden of ["--launch", "--yes", "--allow-remove",
        "--remove-first", "--new-id", "--own-id", "--via"]) {
        assert.equal(args.includes(forbidden), false, forbidden);
      }
      assert.equal(args.includes("--no-browser"), true);
      assert.equal(args.includes("--tizen-cert"), true);
      assert.deepEqual(args.slice(-2), ["--local-ip", "192.168.50.10"]);
      fixture.markInstalled();
      return "Installing… 100%\n" + JSON.stringify({ ok: true, appId: "StarlitTV1.StarlitTV" }, null, 2) + "\n";
    }
  });
  assert.deepEqual(result, { status: "installed", version: "1.31.0", sha256: HASH });
  assert.equal(cliCalls, 1);
  await assert.rejects(stat(temporaryPackage), { code: "ENOENT" },
    "remove only the package temporarily created by this updater");
});

test("success without an installed version change is not reported as completion", async () => {
  const fixture = fetchFixture();
  await assert.rejects(updateSideloadedTv({
    tvIp: TV_IP, install: true, fetchImpl: fixture.fetchImpl,
    sdbReadyImpl: async () => true,
    runCliImpl: async () => JSON.stringify({ ok: true, appId: "StarlitTV1.StarlitTV" })
  }), /not verified/);
});

test("closed SDB port skips an update instead of opening TV-Store or launching the app", async () => {
  const fixture = fetchFixture();
  let ranCli = false;
  const result = await updateSideloadedTv({
    tvIp: TV_IP,
    install: true,
    fetchImpl: fixture.fetchImpl,
    sdbReadyImpl: async () => false,
    runCliImpl: async () => { ranCli = true; }
  });
  assert.equal(result.reason, "SDB_NOT_READY");
  assert.equal(ranCli, false);
  assert.equal(fixture.log.includes(URLS.wgt), false);
});

test("an app started while the package downloads blocks the pending installation", async () => {
  const fixture = fetchFixture();
  let appQueries = 0;
  let cliWasCalled = false;
  const responseFetch = async (url, options) => {
    if (url.includes("/api/v2/applications/")) {
      appQueries++;
      return response({
        id: "StarlitTV1.StarlitTV", version: "1.30.0",
        running: appQueries > 1, visible: appQueries > 1
      });
    }
    return fixture.fetchImpl(url, options);
  };
  const outcome = await updateSideloadedTv({
    tvIp: TV_IP, install: true, fetchImpl: responseFetch,
    sdbReadyImpl: async () => true,
    runCliImpl: async () => { cliWasCalled = true; }
  });
  assert.equal(outcome.reason, "APP_IN_USE");
  assert.equal(appQueries, 2);
  assert.equal(cliWasCalled, false);
});
