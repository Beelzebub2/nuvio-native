"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const {
  normalizeSamsungScan, discoverSamsung, parseSsdpResponse, safeDescriptionUrl,
  parseLgDescription, discoverLg, privateInterfaceIps, mergeDevices, discoverTvs
} = require("../src/discovery.cjs");

const LG_XML = [
  '<?xml version="1.0"?><root><device>',
  '<deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>',
  '<manufacturer>LG Electronics</manufacturer>',
  '<friendlyName>Living Room LG webOS TV</friendlyName>',
  '<modelName>OLED55C2</modelName>',
  '</device></root>'
].join("");

test("Samsung scan uses the installed, verified Apps2Samsung command and keeps readiness details", async () => {
  const calls = [];
  const result = await discoverSamsung({
    ensureTool: async () => "/tmp/verified-cli",
    run: async (exe, args, options) => {
      calls.push([exe, args, options.timeoutMs]);
      return {
        ok: true, blockedByOs: false,
        tvs: [
          { ip: "192.168.1.24", name: "Living Room", model: "RU7025", ready: true },
          { ip: "10.0.0.42", model: "Samsung Frame", ready: false, notReadyReason: "Host PC IP needs updating" },
          { ip: "8.8.8.8", ready: true },
          { ip: "127.0.0.1", ready: true },
          { ip: "bad;rm", ready: true }
        ]
      };
    }
  });
  assert.deepEqual(calls, [["/tmp/verified-cli", ["scan"], 90000]]);
  assert.equal(result.devices.length, 2);
  assert.deepEqual(result.devices.map(tv => tv.ip), ["192.168.1.24", "10.0.0.42"]);
  assert.equal(result.devices[0].ready, true);
  assert.equal(result.devices[1].ready, false);
  assert.match(result.devices[1].detail, /Host PC IP/);
  assert.throws(() => normalizeSamsungScan({ ok: true, tvs: {} }), /valid device list/);
});

test("SSDP parser accepts only bounded HTTP 200 responses and case-insensitive headers", () => {
  const response = Buffer.from(
    "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.55:3000/ssdp/device.xml\r\n" +
    "Server: webOS/6.0 UPnP/1.0\r\nST: urn:schemas-upnp-org:device:Basic:1\r\n\r\n"
  );
  const parsed = parseSsdpResponse(response);
  assert.equal(parsed.location, "http://192.168.1.55:3000/ssdp/device.xml");
  assert.match(parsed.server, /webOS/);
  assert.equal(parseSsdpResponse(Buffer.from("NOTIFY * HTTP/1.1\r\n\r\n")), null);
  assert.equal(parseSsdpResponse(Buffer.alloc(8193)), null);
});

test("Device description fetch URLs cannot escape the SSDP sender's private IP", () => {
  assert.equal(safeDescriptionUrl("http://192.168.1.55:3000/desc.xml", "192.168.1.55").hostname, "192.168.1.55");
  for (const uri of [
    "http://8.8.8.8/ssrf", "http://127.0.0.1/secret", "https://192.168.1.55/a",
    "http://192.168.1.55@evil.example/secret", "file:///etc/passwd", "http://192.168.1.1/other"
  ]) {
    assert.equal(safeDescriptionUrl(uri, "192.168.1.55"), null, uri);
  }
  assert.equal(safeDescriptionUrl("http://192.168.1.55/", "8.8.8.8"), null);
});

test("Only genuine LG manufacturer descriptions are labeled webOS", () => {
  assert.deepEqual(parseLgDescription(LG_XML), { name: "Living Room LG webOS TV", model: "OLED55C2" });
  assert.equal(parseLgDescription(LG_XML.replace("LG Electronics", "Samsung Electronics")), null);
  assert.equal(parseLgDescription("<root><friendlyName>LG webOS TV</friendlyName></root>"), null);
  assert.equal(parseLgDescription("x".repeat(32769)), null);
});

test("LG discovery listens on private interfaces, deduplicates and validates descriptions", async () => {
  const sent = [];
  const visited = [];
  const factory = () => {
    const socket = new EventEmitter();
    socket.bind = (_port, ip, callback) => { visited.push(ip); queueMicrotask(callback); };
    socket.setMulticastInterface = () => {};
    socket.send = packet => {
      sent.push(String(packet));
      queueMicrotask(() => {
        socket.emit("message", Buffer.from(
          "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.55:3000/desc.xml\r\n" +
          "ST: urn:schemas-upnp-org:device:Basic:1\r\n\r\n"
        ), { address: "192.168.1.55" });
        socket.emit("message", Buffer.from(
          "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.60:3000/desc.xml\r\n" +
          "SERVER: Generic UPnP\r\n\r\n"
        ), { address: "192.168.1.60" });
        socket.emit("message", Buffer.from(
          "HTTP/1.1 200 OK\r\nLOCATION: http://8.8.8.8/ssrf\r\n\r\n"
        ), { address: "8.8.8.8" });
      });
    };
    socket.close = () => {};
    return socket;
  };
  const result = await discoverLg({
    interfaces: {
      Ethernet: [{ family: "IPv4", address: "192.168.1.50", internal: false }],
      VPN: [{ family: "IPv4", address: "127.0.0.1", internal: true }]
    },
    socketFactory: factory,
    durationMs: 5,
    readDescription: async url => url.hostname === "192.168.1.55"
      ? LG_XML : "<root><manufacturer>Acme Corp</manufacturer></root>"
  });
  assert.deepEqual(visited, ["192.168.1.50"]);
  assert.equal(sent.length, 2);
  assert.ok(sent[0].includes("M-SEARCH * HTTP/1.1"));
  assert.deepEqual(result.devices.map(tv => tv.ip), ["192.168.1.55"]);
  assert.equal(result.devices[0].platform, "webos");
  assert.equal(result.devices[0].name, "Living Room LG webOS TV");
  assert.equal(result.devices[0].ready, false);
});

test("Private adapter selection excludes public and loopback interfaces", () => {
  assert.deepEqual(privateInterfaceIps({
    a: [{ family: "IPv4", address: "192.168.0.7", internal: false }],
    b: [{ family: 4, address: "10.2.3.4", internal: false }],
    c: [{ family: "IPv4", address: "8.8.8.8", internal: false }],
    d: [{ family: "IPv4", address: "127.0.0.1", internal: true }]
  }), ["192.168.0.7", "10.2.3.4"]);
  assert.deepEqual(mergeDevices([
    [{ ip: "192.168.1.55", platform: "webos", name: "LG TV" }],
    [{ ip: "192.168.1.55", platform: "webos", name: "Same TV" }]
  ]).map(tv => tv.name), ["LG TV"]);
});

test("A failed Samsung scanner does not hide available LG TVs", async () => {
  const result = await discoverTvs({
    samsung: async () => { throw new Error("Unavailable"); },
    lg: async () => ({ devices: [{ platform: "webos", ip: "192.168.1.55", name: "LG TV" }] })
  });
  assert.equal(result.devices.length, 1);
  assert.equal(result.devices[0].platform, "webos");
  assert.match(result.warnings[0], /Samsung discovery unavailable/);
});
