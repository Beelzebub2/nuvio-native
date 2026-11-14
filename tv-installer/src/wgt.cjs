"use strict";
const JSZip = require("jszip");
const { DOMParser } = require("@xmldom/xmldom");
const { PLATFORMS } = require("./platforms.cjs");

const MAX_WGT = 128 * 1024 * 1024;
const MAX_UNPACKED = 512 * 1024 * 1024;
const REQUIRED = [
  "config.xml", "index.html", "main.js", "app.bundle.js",
  "services/tizen/enginefs-service.js", "services/tizen/plugin-service.js",
  "services/tizen/runtime/media-http.cjs", "LICENSE", "UPSTREAM.json"
];

const WIDGET_NS = "http://www.w3.org/ns/widgets";
const TIZEN_NS = "http://tizen.org/ns/widgets";
function parseManifest(config) {
  const parser = new DOMParser({ onError: (level, message) => {
    if (level !== "warning") throw new Error("Invalid Samsung XML manifest: " + message);
  } });
  const xml = parser.parseFromString(config, "application/xml");
  const widget = xml.documentElement;
  const apps = xml.getElementsByTagNameNS(TIZEN_NS, "application");
  const names = xml.getElementsByTagNameNS(WIDGET_NS, "name");
  if (!widget || widget.localName !== "widget" || widget.namespaceURI !== WIDGET_NS ||
      apps.length !== 1 || apps.item(0).parentNode !== widget ||
      names.length !== 1 || names.item(0).parentNode !== widget) {
    throw new Error("Invalid Starlit Samsung widget manifest");
  }
  return {
    appId: apps.item(0).getAttribute("id"),
    packageId: apps.item(0).getAttribute("package"),
    version: widget.getAttribute("version"),
    title: names.item(0).textContent.trim()
  };
}

async function inspectWgt(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length === 0 || bytes.length > MAX_WGT) {
    throw new Error("Invalid or oversized Samsung WGT (128 MiB limit)");
  }
  let zip;
  try {
    zip = await JSZip.loadAsync(bytes, { checkCRC32: false, createFolders: false });
  } catch {
    throw new Error("Samsung package is not a valid WGT ZIP archive");
  }
  const entries = Object.values(zip.files);
  if (entries.length > 12000) throw new Error("WGT has too many entries");
  let unpacked = 0;
  for (const entry of entries) {
    const size = Number(entry._data?.uncompressedSize || 0);
    if (size < 0 || size > MAX_UNPACKED || (unpacked += size) > MAX_UNPACKED) {
      throw new Error("WGT contains oversized uncompressed content");
    }
    if (entry.name.startsWith("/") || entry.name.includes("\\") ||
        entry.name.split("/").includes("..")) {
      throw new Error("WGT contains an unsafe package path");
    }
  }
  for (const filename of REQUIRED) {
    if (!zip.file(filename)) throw new Error("WGT missing required Starlit file: " + filename);
  }
  const configEntry = zip.file("config.xml");
  if (Number(configEntry._data?.uncompressedSize || 0) > 64 * 1024) {
    throw new Error("Oversized Tizen package manifest");
  }
  const config = await configEntry.async("string");
  if (config.length > 64 * 1024) throw new Error("Oversized Tizen package manifest");
  const { appId, packageId, version, title } = parseManifest(config);
  if (appId !== PLATFORMS.tizen.appId || packageId !== "StarlitTV1" ||
      !/^\d+\.\d+\.\d+$/.test(version) || title !== "Starlit TV") {
    throw new Error("This is not a Starlit Samsung Tizen TV package");
  }
  return { appId, packageId, version, signed: Boolean(zip.file("author-signature.xml")) };
}

module.exports = { MAX_WGT, inspectWgt };
