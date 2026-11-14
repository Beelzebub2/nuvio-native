import assert from "node:assert/strict";
import { test } from "node:test";
import {
  LEGACY_RELEASE_REPO,
  RENAMED_RELEASE_REPO,
  releaseRepoForBuild
} from "../scripts/release-repository.mjs";

test("Tizen packages use the old repository before cutover and new one afterward", () => {
  assert.equal(releaseRepoForBuild(null), LEGACY_RELEASE_REPO);
  assert.equal(releaseRepoForBuild(LEGACY_RELEASE_REPO), LEGACY_RELEASE_REPO);
  assert.equal(releaseRepoForBuild(RENAMED_RELEASE_REPO), RENAMED_RELEASE_REPO);
  assert.equal(releaseRepoForBuild("attacker/untrusted"), LEGACY_RELEASE_REPO);
});
