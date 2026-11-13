"use strict";
const { gunzipSync } = require("node:zlib");

const MAX_IPK = 128 * 1024 * 1024;
const MAX_UNPACKED = 220 * 1024 * 1024;
const STABLE_APP_ID = "app.starlit.webos";

function arMembers(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 8 || bytes.subarray(0, 8).toString() !== "!<arch>\n") {
    throw new Error("Not a valid webOS .ipk archive");
  }
  const members = new Map();
  let offset = 8;
  while (offset < bytes.length) {
    if (offset + 60 > bytes.length) throw new Error("Truncated .ipk header");
    const header = bytes.subarray(offset, offset + 60);
    if (header[58] !== 0x60 || header[59] !== 0x0a) throw new Error("Invalid .ipk header");
    const name = header.subarray(0, 16).toString("ascii").trim().replace(/\/$/, "");
    const sizeText = header.subarray(48, 58).toString("ascii").trim();
    if (!/^\d+$/.test(sizeText)) throw new Error("Invalid .ipk member size");
    const size = Number(sizeText);
    if (!Number.isSafeInteger(size) || offset + 60 + size > bytes.length) {
      throw new Error("Truncated .ipk payload");
    }
    members.set(name, bytes.subarray(offset + 60, offset + 60 + size));
    offset += 60 + size + size % 2;
  }
  return members;
}
function tarTextEntry(tar, filename) {
  let offset = 0;
  for (let entries = 0; offset + 512 <= tar.length && entries < 100000; entries++) {
    const header = tar.subarray(offset, offset + 512);
    if (header.every(b => b === 0)) break;
    const name = header.subarray(0, 100).toString("utf8").replace(/\0.*$/, "");
    const prefix = header.subarray(345, 500).toString("utf8").replace(/\0.*$/, "");
    const fullPath = (prefix ? prefix + "/" : "") + name;
    const sizeText = header.subarray(124, 136).toString("ascii").replace(/\0/g, "").trim();
    if (!/^[0-7]+$/.test(sizeText)) throw new Error("Invalid .ipk tar entry length");
    const size = parseInt(sizeText, 8);
    if (size > MAX_UNPACKED || offset + 512 + size > tar.length) throw new Error("Truncated .ipk tar entry");
    const type = header[156];
    if ((type === 0 || type === 0x30) && fullPath.endsWith("/" + filename) && size <= 65536) {
      return tar.subarray(offset + 512, offset + 512 + size).toString("utf8");
    }
    offset += 512 + Math.ceil(size / 512) * 512;
  }
  throw new Error("webOS package does not contain " + filename);
}
function inspectIpk(buffer) {
  if (!Buffer.isBuffer(buffer) || buffer.length === 0 || buffer.length > MAX_IPK) {
    throw new Error("webOS IPK is empty or exceeds 128 MiB");
  }
  const members = arMembers(buffer);
  const data = members.get("data.tar.gz");
  if (!data) throw new Error("webOS IPK has no supported data.tar.gz payload");
  if (!members.has("control.tar.gz")) throw new Error("webOS IPK is missing control metadata");
  const extracted = gunzipSync(data, { maxOutputLength: MAX_UNPACKED });
  const appinfo = JSON.parse(tarTextEntry(extracted, "appinfo.json"));
  if (appinfo.id !== STABLE_APP_ID || !/^\d+\.\d+\.\d+$/.test(String(appinfo.version || ""))) {
    throw new Error("This is not a Starlit LG webOS application package");
  }
  if (!Array.isArray(appinfo.services) ||
      !appinfo.services.includes(STABLE_APP_ID + ".service") ||
      !appinfo.services.includes(STABLE_APP_ID + ".plugin.service")) {
    throw new Error("webOS package lacks required Starlit companion services");
  }
  return { appId: appinfo.id, version: appinfo.version };
}
module.exports = { inspectIpk, arMembers, tarTextEntry, STABLE_APP_ID, MAX_IPK };
