import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { createTvSetupRelayClient } from "../js/core/phoneSetup/tvSetupRelayClient.js";

const values = new Map();
globalThis.localStorage = {
  getItem: key => values.get(key) || null,
  setItem: (key, value) => values.set(key, String(value)),
  removeItem: key => values.delete(key)
};
const { validateExtensionUrl, readExtensionManifest } = await import(
  "../js/core/phoneSetup/tvSetupActions.js"
);
const root = join(dirname(fileURLToPath(import.meta.url)), "../res");
const expectedKeys = [
  "tv_phone_manage_title", "tv_phone_scan_instruction", "tv_phone_pairing_code",
  "tv_phone_preparing", "tv_phone_waiting", "tv_phone_approval_required",
  "tv_phone_verify_title", "tv_phone_verify_code", "tv_phone_approve",
  "tv_phone_connected", "tv_phone_connected_hint", "tv_phone_change_applied",
  "tv_phone_action_failed", "tv_phone_unavailable", "tv_phone_disconnect",
  "tv_phone_connection_error"
];

test("every supported TV locale translates all phone setup strings exactly once", () => {
  const dirs = readdirSync(root, { withFileTypes: true })
    .filter(entry => entry.isDirectory() && entry.name.startsWith("values"))
    .map(entry => entry.name);
  assert.equal(dirs.length, 38);
  for (const directory of dirs) {
    const xml = readFileSync(join(root, directory, "strings.xml"), "utf8");
    for (const key of expectedKeys) {
      const matches = [...xml.matchAll(new RegExp('<string name="' + key + '">([^<]+)</string>', "g"))];
      assert.equal(matches.length, 1, directory + ": " + key);
      assert.ok(matches[0][1].trim(), directory + ": " + key + " is empty");
      if (key === "tv_phone_pairing_code")
        assert.match(matches[0][1], /\{\{code\}\}/, directory);
    }
  }
});

test("only safe public HTTPS repository URLs are valid", () => {
  assert.equal(
    validateExtensionUrl(" https://addons.example.net/test ", { addon: true }),
    "https://addons.example.net/test/manifest.json"
  );
  for (const input of [
    "http://example.net/", "file:///etc/passwd", "https://localhost/test",
    "https://10.0.0.7/", "https://myhost.local/", "https://[::1]/",
    "https://example.org:8080/", "https://user:password@example.org/",
    "https://example.org/#token"
  ]) assert.throws(() => validateExtensionUrl(input), /public HTTPS|Invalid/);
});

test("manifest preview validates add-ons and plugins without running scripts", async () => {
  const requests = [];
  const fetchImpl = async (url, options) => {
    requests.push({ url, options });
    const addon = url.includes("addon");
    return {
      ok: true,
      headers: { get: () => null },
      text: async () => JSON.stringify(addon
        ? { id:"org.example", name:"Authorized Test", resources:["stream"], description:"Example" }
        : { name:"Example Plugin", scrapers:[{ id:"example", name:"Sample Provider" }] })
    };
  };
  const addon = await readExtensionManifest("https://addon.example/manifest.json",
    { addon:true, fetchImpl });
  assert.equal(addon.name, "Authorized Test");
  assert.deepEqual(addon.items, ["stream"]);
  const plugin = await readExtensionManifest("https://plugin.example/manifest.json",
    { fetchImpl });
  assert.deepEqual(plugin.items, ["Sample Provider"]);
  assert.equal(requests.length, 2);
  assert.ok(requests.every(item => item.options.redirect === "error" && item.options.credentials === "omit"));
  await assert.rejects(
    () => readExtensionManifest("https://plugin.example/manifest.json", {
      fetchImpl: async () => ({
        ok:true, headers:{ get:()=>null }, text:async () => '{"name":"Missing scrapers"}'
      })
    }), /Invalid repository manifest/
  );
  await assert.rejects(
    () => readExtensionManifest("https://addon.example/manifest.json", {
      addon:true, fetchImpl: async () => ({
        ok:true, headers:{ get:()=>String(300000) }, text:async () => ""
      })
    }), /exceeds size limit/
  );
});

test("TV relay client sends scoped token only to the configured HTTPS endpoint", async () => {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options });
    return {
      ok:true,
      json:async () => url.endsWith("/start")
        ? { id:"a".repeat(64), code:"ABCDEF12", claimKey:"b".repeat(64), tvToken:"c".repeat(64) }
        : { claimed:false, commands:[] }
    };
  };
  const client = createTvSetupRelayClient(fetchImpl, "https://starlit.rrmtools.uk/tv/api/");
  const pairing = await client.start("Living Room", "1");
  assert.match(pairing.qrUrl, /^https:\/\/starlit\.rrmtools\.uk\/tv\/connect#code=/);
  assert.ok(!pairing.qrUrl.includes("c".repeat(64)));
  await client.inspect();
  assert.equal(calls[0].options.credentials, "omit");
  assert.equal(calls[0].options.headers["X-TV-Token"], undefined);
  assert.equal(calls[1].options.headers["X-TV-Token"], "c".repeat(64));
  await client.stop();
  assert.equal(client.active, false);
});
