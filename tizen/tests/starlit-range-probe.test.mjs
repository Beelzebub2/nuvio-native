import test from "node:test";
import assert from "node:assert/strict";
import { verifyTizenRangeProbe } from "../js/platform/tizen/tizenRangeProbe.js";

function response({ status = 206, range = "bytes 1-3/22", bytes = "tar" } = {}) {
  return {
    status,
    headers: { get: () => range },
    text: async () => bytes,
  };
}

test("range probe accepts exact bytes when legacy Tizen hides Content-Range", async () => {
  await assert.doesNotReject(verifyTizenRangeProbe(response({ range: null })));
  await assert.doesNotReject(verifyTizenRangeProbe(response({ range: "" })));
  await assert.doesNotReject(verifyTizenRangeProbe(response()));
});

test("range probe rejects full responses, mismatched ranges and wrong bytes", async () => {
  for (const candidate of [
    response({ status: 200 }),
    response({ status: 404 }),
    response({ range: "bytes 0-2/22" }),
    response({ range: null, bytes: "xxx" }),
    response({ range: null, bytes: "starlit-range-probe-v1" }),
    response({ bytes: "ta" }),
  ]) {
    await assert.rejects(verifyTizenRangeProbe(candidate), /Local streaming Range probe failed/);
  }
});
