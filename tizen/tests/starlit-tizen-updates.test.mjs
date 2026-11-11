import assert from "node:assert/strict";
import { test } from "node:test";
import {
  TIZEN_RELEASES_URL,
  getLatestAppUpdate,
  getLatestAppUpdateWithRetry,
  parseLatestRelease,
  selectLatestTizenRelease
} from "../js/core/update/appUpdateService.js";

function release(version, {
  hasPackage = true,
  hasChecksum = true,
  draft = false,
  prerelease = false,
  packageVersion = version
} = {}) {
  const assets = [
    ...(hasPackage ? [{ name: `Starlit-Tizen-${packageVersion}.wgt` }] : []),
    ...(hasChecksum ? [{ name: "SHA256SUMS-Tizen" }] : [])
  ];
  return {
    tag_name: `v${version}`,
    name: `Starlit ${version}`,
    body: "Stable release",
    draft,
    prerelease,
    assets,
    html_url: "https://untrusted.invalid/redirect"
  };
}

test("Tizen update discovery ignores the latest native-only release", async () => {
  const releases = [
    release("1.24.2", { hasPackage: false }),
    release("1.24.1", { hasPackage: false }),
    release("1.24.0"),
    release("1.23.9")
  ];
  const seen = [];
  const result = await getLatestAppUpdate({
    currentVersion: "1.23.0",
    fetchImpl: async (url, options) => {
      seen.push([url, options?.method]);
      return { ok: true, status: 200, json: async () => releases };
    }
  });
  assert.deepEqual(seen, [[TIZEN_RELEASES_URL, "GET"]]);
  assert.equal(result.tag, "v1.24.0");
  assert.equal(result.assetName, "Starlit-Tizen-1.24.0.wgt");
  assert.equal(result.releaseUrl,
    "https://github.com/Beelzebub2/nuvio-native/releases/tag/v1.24.0");
});

test("Tizen updates cannot be announced for mismatched, draft, prerelease or incomplete assets", () => {
  assert.equal(parseLatestRelease(release("1.25.0", { hasChecksum: false })), null);
  assert.equal(parseLatestRelease(release("1.25.0", { packageVersion: "1.24.0" })), null);
  assert.equal(parseLatestRelease(release("1.25.0", { draft: true })), null);
  assert.equal(parseLatestRelease(release("1.25.0", { prerelease: true })), null);
  assert.equal(parseLatestRelease({ ...release("1.25.0"), tag_name: "v1.25.0-rc1" }), null);
  assert.equal(parseLatestRelease({ ...release("1.25.0"), tag_name: "v1.25.0/evil" }), null);
  assert.equal(selectLatestTizenRelease([release("1.25.0")], "1.25.0"), null);
  assert.equal(selectLatestTizenRelease([release("1.25.0")], "1.26.0"), null);
  assert.equal(selectLatestTizenRelease({ latest: release("1.25.0") }, "1.0.0"), null);
});

test("newest eligible version wins even when GitHub returns an older republished tag first", () => {
  const latest = selectLatestTizenRelease([
    release("1.25.0"), release("1.27.0"), release("1.26.0")
  ], "1.24.0");
  assert.equal(latest.tag, "v1.27.0");
});

test("a non-Tizen GitHub release is not mistaken for an update", async () => {
  assert.equal(await getLatestAppUpdate({
    currentVersion: "0.1.0",
    fetchImpl: async () => ({
      ok: true,
      json: async () => [release("1.30.0", { hasPackage: false })]
    })
  }), null);
});

test("404 and malformed responses do not trigger an update notification", async () => {
  await assert.rejects(
    getLatestAppUpdate({
      currentVersion: "1.0.0",
      fetchImpl: async () => ({ ok: false, status: 404 })
    }),
    /HTTP 404/
  );
  assert.equal(await getLatestAppUpdate({
    currentVersion: "1.0.0",
    fetchImpl: async () => ({ ok: true, json: async () => ({ tag_name: "v1.30.0" }) })
  }), null);
});

test("release checks retry a transient HTTP 503 without using an unverified package", async () => {
  let attempts = 0;
  const found = await getLatestAppUpdateWithRetry({
    currentVersion: "1.21.0",
    retryDelayMs: 0,
    maxAttempts: 2,
    fetchImpl: async () => {
      attempts += 1;
      if (attempts === 1) return { ok: false, status: 503 };
      return { ok: true, status: 200, json: async () => [release("1.22.0")] };
    }
  });
  assert.equal(attempts, 2);
  assert.equal(found.tag, "v1.22.0");
});
