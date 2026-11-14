import test from "node:test";
import assert from "node:assert/strict";
import { legacyCorsFetch, cancelLegacyPluginRequest } from "../js/platform/pluginServiceClient.js";

function fixtureResponse(url, body = "{}") {
  const bytes = new TextEncoder().encode(body);
  return {
    status: 200,
    statusText: "OK",
    url,
    headers: { get: (name) => name === "content-length" ? String(bytes.length) : null },
    body: new ReadableStream({
      start(controller) { controller.enqueue(bytes); controller.close(); }
    })
  };
}

test("legacy CORS transport uses bounded credential-free HTTPS requests", async () => {
  const original = globalThis.fetch;
  const calls = [];
  try {
    globalThis.fetch = async (url, options) => {
      calls.push({ url, options });
      return fixtureResponse(url);
    };
    const result = await legacyCorsFetch({
      requestId: "legacy-bounded-test",
      url: "https://example.org/manifest.json",
      headers: { Accept: "application/json", Cookie: "must-not-forward", "User-Agent": "test" },
      timeoutMs: 1000,
      maxBodyBytes: 1024
    });
    assert.equal(result.ok, true);
    assert.equal(result.body, "{}");
    assert.equal(calls.length, 1);
    assert.equal(calls[0].options.credentials, "omit");
    assert.equal(calls[0].options.redirect, "error");
    assert.equal(calls[0].options.mode, "cors");
    assert.equal(calls[0].options.headers.Accept, "application/json");
    assert.equal(Object.keys(calls[0].options.headers).some((name) => /^(?:cookie|user-agent)$/i.test(name)), false);
    await assert.rejects(
      legacyCorsFetch({ url: "http://example.org/manifest.json" }),
      /public HTTPS/
    );
  } finally { globalThis.fetch = original; }
});

test("legacy transport rejects redirects and oversized responses", async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async () => fixtureResponse("https://redirect.example.org/");
    await assert.rejects(
      legacyCorsFetch({ url: "https://example.org/", requestId: "legacy-redirect" }),
      /Redirected or unsafe/
    );
    globalThis.fetch = async (url) => fixtureResponse(url, "x".repeat(600));
    await assert.rejects(
      legacyCorsFetch({ url: "https://example.org/", requestId: "legacy-oversize", maxBodyBytes: 256 }),
      /exceeds legacy limit/
    );
  } finally { globalThis.fetch = original; }
});

test("legacy fetch caps abandoned connections and cancels pending calls", async () => {
  const originalFetch = globalThis.fetch;
  const originalAbort = globalThis.AbortController;
  const pending = [];
  try {
    globalThis.AbortController = undefined;
    globalThis.fetch = (url) => new Promise((resolve) => {
      pending.push(() => resolve(fixtureResponse(url)));
    });
    const requests = Array.from({ length: 3 }, (_, index) =>
      legacyCorsFetch({
        requestId: "legacy-slot-" + index,
        url: "https://example.org/",
        timeoutMs: 5000
      })
    );
    await assert.rejects(
      legacyCorsFetch({ requestId: "legacy-overflow", url: "https://example.org/" }),
      /Plugin network service unavailable/
    );
    assert.equal(cancelLegacyPluginRequest("legacy-slot-0"), true);
    await assert.rejects(requests[0], /Plugin execution cancelled/);
    // The old browser cannot abort a fetch pending its first response. Its
    // slot must remain occupied after cancellation until it really settles.
    await assert.rejects(
      legacyCorsFetch({ requestId: "legacy-overflow-retry", url: "https://example.org/" }),
      /Plugin network service unavailable/
    );
    assert.equal(cancelLegacyPluginRequest("no-such-request"), false);
    for (const resolve of pending) resolve();
    await Promise.allSettled(requests);
    await new Promise((resolve) => setImmediate(resolve));
    globalThis.fetch = async (url) => fixtureResponse(url);
    const recovered = await legacyCorsFetch({
      requestId: "legacy-after-cleanup", url: "https://example.org/"
    });
    assert.equal(recovered.ok, true);
  } finally {
    globalThis.fetch = originalFetch;
    globalThis.AbortController = originalAbort;
  }
});
