import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { readAppMetadata } from "./appMetadata.mjs";

const { version } = await readAppMetadata();
const acceptance = JSON.parse(await readFile(new URL("../release/webos-acceptance.json", import.meta.url), "utf8"));
const bytes = await readFile(new URL("../artifacts/Starlit-WebOS-" + version + ".ipk", import.meta.url));
const hash = createHash("sha256").update(bytes).digest("hex");
const checks = [
  "remote_navigation", "developer_install_update", "direct_mp4_hls_dash",
  "torrent_stream_seek_cleanup", "account_two_profile_roundtrip",
  "subtitles_and_audio", "standby_network_recovery", "local_proxy_security", "license_completeness"
];
if (acceptance.status !== "PASS" || acceptance.version !== version || acceptance.ipk_sha256 !== hash) {
  throw new Error("webOS release blocked: exact IPK has no device acceptance");
}
for (const name of checks) {
  if (acceptance.checks?.[name]?.status !== "PASS" || !acceptance.checks[name].evidence) {
    throw new Error("webOS release blocked: missing " + name + " evidence");
  }
}
if (!acceptance.devices?.some(d => Number(d.webos_major) >= 5 &&
    d.model && d.firmware && d.evidence && d.status === "PASS")) {
  throw new Error("webOS release blocked: no accepted physical webOS 5+ TV");
}
if (!acceptance.reviewer || !acceptance.reviewed_at) {
  throw new Error("webOS release blocked: acceptance reviewer missing");
}
console.log("Exact Starlit webOS IPK passed physical-device release acceptance");
