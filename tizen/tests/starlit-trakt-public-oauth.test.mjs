import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import os from "node:os";
import {
  hasTraktClientId,
  traktDeviceTokenRequest,
  traktRefreshTokenRequest,
  traktRevokeTokenRequest
} from "../js/data/repository/traktPublicOAuthRequests.js";
import { normalizeEnvProperties, buildRuntimeEnvScript, readEnvProperties } from "../scripts/envProperties.mjs";

test("TV Trakt login needs only the registered public client ID", () => {
  assert.equal(hasTraktClientId("public-id"), true);
  assert.equal(hasTraktClientId(" "), false);
  assert.equal(hasTraktClientId(undefined), false);
});

test("TV device, refresh and revocation never send secret or OOB redirect", () => {
  const payloads = [
    traktDeviceTokenRequest("one-time-code", "public-id"),
    traktRefreshTokenRequest("rotating-refresh", "public-id"),
    traktRevokeTokenRequest("user-access", "public-id")
  ];
  assert.deepEqual(payloads[0], { code: "one-time-code", client_id: "public-id" });
  assert.deepEqual(payloads[1], {
    refresh_token: "rotating-refresh",
    client_id: "public-id",
    grant_type: "refresh_token"
  });
  assert.deepEqual(payloads[2], { token: "user-access", client_id: "public-id" });
  for (const payload of payloads) {
    const serialized = JSON.stringify(payload);
    assert.equal(serialized.includes("client_secret"), false);
    assert.equal(serialized.includes("redirect_uri"), false);
  }
});

test("TV generated runtime env omits any supplied Trakt client secret", () => {
  const env = normalizeEnvProperties({
    TRAKT_CLIENT_ID: "public-id",
    TRAKT_CLIENT_SECRET: "accidental-private-value"
  });
  assert.equal(env.TRAKT_CLIENT_ID, "public-id");
  assert.equal(Object.hasOwn(env, "TRAKT_CLIENT_SECRET"), false);
  const script = buildRuntimeEnvScript(env);
  assert.equal(script.includes("accidental-private-value"), false);
  assert.equal(script.includes("TRAKT_CLIENT_SECRET"), false);
});

test("TV package uses the public CI Client ID even without a local properties file", async () => {
  const previous = process.env.TRAKT_CLIENT_ID;
  process.env.TRAKT_CLIENT_ID = "ci-public-id";
  try {
    const rootDir = path.join(os.tmpdir(), "starlit-trakt-unconfigured-" + process.pid);
    const result = await readEnvProperties({ rootDir });
    assert.equal(result.sourcePath, "");
    assert.equal(result.env.TRAKT_CLIENT_ID, "ci-public-id");
    assert.equal(Object.hasOwn(result.env, "TRAKT_CLIENT_SECRET"), false);
  } finally {
    if (previous === undefined) delete process.env.TRAKT_CLIENT_ID;
    else process.env.TRAKT_CLIENT_ID = previous;
  }
});
