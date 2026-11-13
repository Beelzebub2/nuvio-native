"use strict";
const { createHash } = require("node:crypto");
const { inspectIpk, MAX_IPK } = require("./ipk.cjs");
const settings = require("../installer.config.json");

const REPO = settings.githubRepo;
const MAX_CHECKSUMS = 16 * 1024;
const RELEASE_API = "https://api.github.com/repos/" + REPO + "/releases/latest";

function expectedReleaseAssets(release) {
  if (!release || release.draft || release.prerelease || !/^v\d+\.\d+\.\d+$/.test(String(release.tag_name || ""))) {
    throw new Error("No verified stable Starlit release is available");
  }
  const name = "Starlit-WebOS-" + release.tag_name.slice(1) + ".ipk";
  const assets = Array.isArray(release.assets) ? release.assets : [];
  const ipk = assets.find(x => x.name === name);
  const sums = assets.find(x => x.name === "SHA256SUMS-WebOS");
  if (!ipk || !sums) throw new Error("No accepted webOS build in the latest Starlit release. Select a local IPK instead.");
  return { filename: name, packageUrl: approvedAssetUrl(ipk.browser_download_url, release.tag_name, name),
    checksumUrl: approvedAssetUrl(sums.browser_download_url, release.tag_name, "SHA256SUMS-WebOS") };
}
function approvedAssetUrl(raw, tag, filename) {
  const address = new URL(String(raw || ""));
  const prefix = "/" + REPO + "/releases/download/" + tag + "/";
  if (address.protocol !== "https:" || address.hostname !== "github.com" ||
      address.pathname !== prefix + filename || address.username || address.password) {
    throw new Error("Untrusted Starlit release asset URL");
  }
  return address.toString();
}
function parseChecksum(text, filename) {
  const matches = String(text).split(/\r?\n/).map(line => /^([0-9a-fA-F]{64})  (.+)$/.exec(line.trim()))
    .filter(match => match && match[2] === filename);
  if (matches.length !== 1) throw new Error("No unambiguous SHA-256 value for the selected webOS package");
  return matches[0][1].toLowerCase();
}
async function fetchBounded(fetchImpl, url, limit, timeoutMs = 30000) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const result = await fetchImpl(url, {
      signal: controller.signal, redirect: "follow", cache: "no-store",
      headers: { "User-Agent": "Starlit-TV-Installer", Accept: "application/vnd.github+json" }
    });
    if (!result.ok) throw new Error("Starlit download failed (HTTP " + result.status + ")");
    const contentLength = Number(result.headers?.get("content-length") || 0);
    if (contentLength > limit) throw new Error("Response exceeds the safe size limit");
    const chunks = [];
    let size = 0;
    for await (const chunk of result.body) {
      size += chunk.length;
      if (size > limit) throw new Error("Response exceeds the safe size limit");
      chunks.push(Buffer.from(chunk));
    }
    return Buffer.concat(chunks, size);
  } finally {
    clearTimeout(timeout);
  }
}
async function fetchVerifiedRelease({ fetchImpl = globalThis.fetch, onStatus = () => {} } = {}) {
  const metadataBuffer = await fetchBounded(fetchImpl, RELEASE_API, 256 * 1024, 10000);
  const release = JSON.parse(metadataBuffer.toString("utf8"));
  const assets = expectedReleaseAssets(release);
  onStatus("Checking Starlit's published SHA-256 checksum");
  const sums = await fetchBounded(fetchImpl, assets.checksumUrl, MAX_CHECKSUMS, 15000);
  const expected = parseChecksum(sums.toString("utf8"), assets.filename);
  onStatus("Downloading accepted Starlit webOS package");
  const bytes = await fetchBounded(fetchImpl, assets.packageUrl, MAX_IPK, 60000);
  if (createHash("sha256").update(bytes).digest("hex") !== expected) {
    throw new Error("Package SHA-256 verification failed; nothing was installed");
  }
  const manifest = inspectIpk(bytes);
  if (manifest.version !== release.tag_name.slice(1)) {
    throw new Error("webOS package version does not match its release tag");
  }
  return { bytes, filename: assets.filename, sha256: expected, manifest };
}
module.exports = { expectedReleaseAssets, approvedAssetUrl, parseChecksum, fetchBounded, fetchVerifiedRelease };
