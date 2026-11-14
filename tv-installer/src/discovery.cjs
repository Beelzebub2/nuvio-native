"use strict";

const dgram = require("node:dgram");
const http = require("node:http");
const os = require("node:os");
const { isLanIPv4 } = require("./platforms.cjs");
const { ensureSamsungCli, runSamsungCli } = require("./samsung.cjs");

const SSDP_ADDRESS = "239.255.255.250";
const SSDP_PORT = 1900;
const SEARCH_TARGETS = ["ssdp:all", "urn:schemas-upnp-org:device:Basic:1"];
const MAX_CANDIDATES = 24;
const MAX_DESCRIPTION_BYTES = 32768;

function safeLabel(value, fallback = "", limit = 80) {
  const cleaned = String(value == null ? "" : value)
    .replace(/[\x00-\x1f\x7f]/g, " ").replace(/\s+/g, " ").trim();
  return (cleaned || fallback).slice(0, limit);
}

function normalizeSamsungScan(scan) {
  if (!scan || scan.ok !== true || !Array.isArray(scan.tvs)) {
    throw new Error("Samsung TV scanner did not return a valid device list");
  }
  return scan.tvs.slice(0, 64).filter(tv => tv && isLanIPv4(tv.ip)).map(tv => ({
    platform: "tizen",
    ip: tv.ip,
    name: safeLabel(tv.name, safeLabel(tv.model, "Samsung TV")),
    model: safeLabel(tv.model),
    ready: tv.ready === true,
    detail: tv.ready === true
      ? "Developer Mode is ready for this computer"
      : safeLabel(tv.notReadyReason, "Check Samsung Developer Mode and Host PC IP", 240)
  }));
}

async function discoverSamsung({
  ensureTool = ensureSamsungCli,
  run = runSamsungCli,
  toolsDir,
  onStatus = () => {}
} = {}) {
  const executable = await ensureTool({ toolsDir, onStatus });
  onStatus("Scanning for Samsung TVs using Apps2Samsung");
  const scan = await run(executable, ["scan"], { timeoutMs: 90000 });
  return {
    devices: normalizeSamsungScan(scan),
    blocked: scan.blockedByOs === true
  };
}

function parseSsdpResponse(message) {
  if (!Buffer.isBuffer(message) || message.length > 8192) return null;
  const lines = message.toString("utf8").split(/\r?\n/);
  if (!/^HTTP\/1\.[01]\s+200(?:\s|$)/i.test(lines.shift() || "")) return null;
  const headers = Object.create(null);
  for (const line of lines) {
    if (!line) break;
    const colon = line.indexOf(":");
    if (colon <= 0) continue;
    const key = line.slice(0, colon).trim().toLowerCase();
    if (!/^[a-z0-9-]{1,40}$/.test(key) || headers[key]) continue;
    headers[key] = line.slice(colon + 1).trim().slice(0, 512);
  }
  return headers;
}

function safeDescriptionUrl(location, senderIp) {
  if (!isLanIPv4(senderIp) || typeof location !== "string" || location.length > 512) return null;
  try {
    const url = new URL(location);
    if (url.protocol !== "http:" || url.hostname !== senderIp ||
        url.username || url.password || url.hash || !["", "80"].includes(url.port) &&
        !/^\d{1,5}$/.test(url.port)) return null;
    return url;
  } catch {
    return null;
  }
}

function xmlField(xml, field) {
  const match = String(xml).match(new RegExp(
    "<(?:[a-zA-Z0-9_-]+:)?" + field + "(?:\\s[^>]*)?>([^<]{1,200})<\\/(?:[a-zA-Z0-9_-]+:)?" + field + "\\s*>", "i"
  ));
  return match ? safeLabel(match[1].replace(/&amp;/gi, "&").replace(/&quot;/gi, '"')) : "";
}

function parseLgDescription(xml) {
  if (typeof xml !== "string" || xml.length > MAX_DESCRIPTION_BYTES) return null;
  if (!/^LG(?:\s+Electronics)?\b/i.test(xmlField(xml, "manufacturer"))) return null;
  const model = xmlField(xml, "modelName") || xmlField(xml, "modelNumber");
  return {
    name: xmlField(xml, "friendlyName") || model || "LG webOS TV",
    model
  };
}

function lgHeaderHint(headers) {
  return /\bwebos\b|lge-com|LG (?:Smart )?TV|LG Electronics/i.test(
    [headers.server, headers.st, headers.usn].join(" ")
  );
}

function requestDescription(url, { httpGet = http.get, timeoutMs = 1500 } = {}) {
  return new Promise(resolve => {
    let finished = false;
    let request;
    function done(value) {
      if (finished) return;
      finished = true;
      resolve(value);
    }
    try {
      request = httpGet(url, {
        timeout: timeoutMs,
        headers: { Accept: "text/xml, application/xml" }
      }, response => {
        if (response.statusCode !== 200) {
          response.resume();
          return done(null);
        }
        let body = "", bytes = 0;
        response.setEncoding("utf8");
        response.on("data", chunk => {
          bytes += Buffer.byteLength(chunk);
          if (bytes > MAX_DESCRIPTION_BYTES) {
            request.destroy();
            done(null);
            return;
          }
          body += chunk;
        });
        response.once("end", () => done(body));
        response.once("error", () => done(null));
      });
      request.once("timeout", () => {
        request.destroy();
        done(null);
      });
      request.once("error", () => done(null));
    } catch {
      done(null);
    }
  });
}

function privateInterfaceIps(interfaces = os.networkInterfaces()) {
  return [...new Set(Object.values(interfaces).flat().filter(info =>
    info && (info.family === "IPv4" || info.family === 4) &&
    !info.internal && isLanIPv4(info.address)
  ).map(info => info.address))];
}

function listenOnInterface(interfaceIp, candidates, {
  socketFactory = dgram.createSocket,
  durationMs = 3200
} = {}) {
  return new Promise(resolve => {
    let socket;
    let finished = false;
    let timer;
    function finish() {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      try { socket?.close(); } catch { /* already closed */ }
      resolve();
    }
    try {
      socket = socketFactory("udp4");
      socket.on("error", finish);
      socket.on("message", (message, sender) => {
        if (!sender || !isLanIPv4(sender.address) || candidates.size >= MAX_CANDIDATES) return;
        const headers = parseSsdpResponse(message);
        const descriptionUrl = headers && safeDescriptionUrl(headers.location, sender.address);
        if (!descriptionUrl) return;
        const previous = candidates.get(sender.address);
        const hinted = lgHeaderHint(headers);
        if (!previous || (!previous.hinted && hinted)) {
          candidates.set(sender.address, { ip: sender.address, url: descriptionUrl, hinted });
        }
      });
      timer = setTimeout(finish, durationMs + 200);
      socket.bind(0, interfaceIp, () => {
        if (finished) return;
        try {
          socket.setMulticastInterface(interfaceIp);
          for (const st of SEARCH_TARGETS) {
            const packet = Buffer.from(
              "M-SEARCH * HTTP/1.1\r\nHOST: " + SSDP_ADDRESS + ":" + SSDP_PORT +
              "\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n"
            );
            socket.send(packet, SSDP_PORT, SSDP_ADDRESS);
          }
        } catch {
          finish();
        }
      });
    } catch {
      finish();
    }
  });
}

async function discoverLg({
  interfaces = os.networkInterfaces(),
  socketFactory = dgram.createSocket,
  readDescription = requestDescription,
  durationMs = 3200
} = {}) {
  const ips = privateInterfaceIps(interfaces);
  if (!ips.length) return { devices: [], noLan: true };
  const candidates = new Map();
  await Promise.all(ips.map(ip => listenOnInterface(ip, candidates, { socketFactory, durationMs })));
  const devices = [];
  const entries = [...candidates.values()].slice(0, MAX_CANDIDATES);
  for (let offset = 0; offset < entries.length; offset += 4) {
    const batch = await Promise.all(entries.slice(offset, offset + 4).map(async entry => {
      let description = null;
      try { description = parseLgDescription(await readDescription(entry.url)); } catch { /* unreachable */ }
      // Some webOS firmwares announce their identity but block the descriptor.
      // A strong, TV-specific SSDP signature is still useful for manual selection.
      if (!description && !entry.hinted) return null;
      return {
        platform: "webos",
        ip: entry.ip,
        name: safeLabel(description?.name, "LG webOS TV"),
        model: safeLabel(description?.model),
        ready: false,
        detail: "Enable Developer Mode and Key Server to pair"
      };
    }));
    devices.push(...batch.filter(Boolean));
  }
  return { devices, noLan: false };
}

function mergeDevices(groups) {
  const byIdentity = new Map();
  for (const devices of groups) {
    for (const tv of devices || []) {
      if (!tv || !["tizen", "webos"].includes(tv.platform) || !isLanIPv4(tv.ip)) continue;
      const key = tv.platform + ":" + tv.ip;
      if (!byIdentity.has(key)) byIdentity.set(key, tv);
    }
  }
  return [...byIdentity.values()].sort((a, b) =>
    a.platform.localeCompare(b.platform) || a.ip.localeCompare(b.ip, "en", { numeric: true })
  ).slice(0, 32);
}

async function discoverTvs({
  samsung = discoverSamsung, lg = discoverLg,
  samsungOptions = {}, lgOptions = {}, onStatus = () => {}
} = {}) {
  onStatus("Searching the local network for Samsung and LG TVs");
  const results = await Promise.allSettled([
    samsung({ ...samsungOptions, onStatus }),
    lg(lgOptions)
  ]);
  const warnings = [];
  const groups = [];
  if (results[0].status === "fulfilled") {
    groups.push(results[0].value.devices);
    if (results[0].value.blocked) warnings.push("Samsung scan was blocked by Windows network permissions.");
  } else {
    warnings.push("Samsung discovery unavailable: " + safeLabel(results[0].reason?.message, "Scanner error", 180));
  }
  if (results[1].status === "fulfilled") {
    groups.push(results[1].value.devices);
    if (results[1].value.noLan) warnings.push("No private LAN interface available for LG discovery.");
  } else {
    warnings.push("LG discovery unavailable: " + safeLabel(results[1].reason?.message, "Network error", 180));
  }
  return { devices: mergeDevices(groups), warnings };
}

module.exports = {
  safeLabel, normalizeSamsungScan, discoverSamsung, parseSsdpResponse,
  safeDescriptionUrl, parseLgDescription, requestDescription, privateInterfaceIps,
  listenOnInterface, discoverLg, mergeDevices, discoverTvs
};
