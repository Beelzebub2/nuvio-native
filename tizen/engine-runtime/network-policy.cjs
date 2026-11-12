/* MIT. Public-address DNS policy for outbound service requests. */
"use strict";
var dns = require("dns");
var net = require("net");
var http = require("http");
var https = require("https");
var url = require("url");

function blockedAddress(address) {
  var value = String(address || "").toLowerCase().split("%")[0];
  var family = net.isIP(value);
  if (family === 4) {
    var p = value.split(".").map(Number);
    return p[0] === 0 || p[0] === 10 || p[0] === 127 || p[0] >= 224 ||
      (p[0] === 100 && p[1] >= 64 && p[1] <= 127) ||
      (p[0] === 169 && p[1] === 254) ||
      (p[0] === 172 && p[1] >= 16 && p[1] <= 31) ||
      (p[0] === 192 && (p[1] === 0 || p[1] === 168 || (p[1] === 88 && p[2] === 99))) ||
      (p[0] === 198 && (p[1] === 18 || p[1] === 19 || (p[1] === 51 && p[2] === 100))) ||
      (p[0] === 203 && p[1] === 0 && p[2] === 113);
  }
  if (family === 6) {
    // Restrict to globally allocated unicast; reject mapped, ULA and link-local forms.
    if (!/^[23][0-9a-f]{0,3}:/.test(value)) return true;
    return /^(2001:(?:0:|db8:|2:|10:|20:)|2002:)/.test(value);
  }
  return true;
}

function safeLookup(hostname, options, callback) {
  if (typeof options === "function") { callback = options; options = {}; }
  if (typeof options === "number") options = { family: options };
  options = options || {};
  dns.lookup(hostname, { family: options.family || 0, all: true }, function (error, addresses) {
    if (error) return callback(error);
    if (!addresses || !addresses.length || addresses.some(function (entry) { return blockedAddress(entry.address); })) {
      var denied = new Error("Proxy destination is not a public address");
      denied.code = "EACCES";
      return callback(denied);
    }
    if (options.all) return callback(null, addresses);
    callback(null, addresses[0].address, addresses[0].family);
  });
}

function origin(destination) {
  var parsed = typeof destination === "string" ? url.parse(destination) : destination;
  if (!parsed || (parsed.protocol !== "http:" && parsed.protocol !== "https:") ||
      !parsed.hostname || parsed.auth || /[\r\n]/.test(parsed.href || "")) throw new Error("Invalid proxy destination");
  var hostname = String(parsed.hostname).toLowerCase();
  var bare = hostname.replace(/^\[|\]$/g, "");
  if (net.isIP(bare) && blockedAddress(bare)) throw new Error("Proxy destination is not a public address");
  var port = parsed.port || (parsed.protocol === "https:" ? "443" : "80");
  if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) throw new Error("Invalid proxy port");
  return parsed.protocol + "//" + hostname + ":" + Number(port);
}

var httpAgent = new http.Agent({ lookup: safeLookup });
var httpsAgent = new https.Agent({ lookup: safeLookup, rejectUnauthorized: true });
var portable = /^(accept|accept-encoding|accept-language|range|if-range|user-agent)$/i;

function protectedFetch(destination, request, headers, options, fetch) {
  var currentOrigin = origin(destination);
  if (!options.__starlitCredentialOrigin) options.__starlitCredentialOrigin = currentOrigin;
  var safeHeaders = new fetch.Headers();
  headers.forEach(function (value, name) {
    if (/[\r\n]/.test(name) || /[\r\n]/.test(value)) throw new Error("Invalid proxy header");
    if (/^(host|connection|transfer-encoding|content-length|proxy-authorization)$/i.test(name)) return;
    if (currentOrigin !== options.__starlitCredentialOrigin && !portable.test(name)) return;
    safeHeaders.set(name, value);
  });
  if (request.method !== "GET" && request.method !== "HEAD") throw new Error("Unsupported proxy method");
  return fetch(url.format(destination), {
    method: request.method,
    headers: safeHeaders,
    agent: destination.protocol === "https:" ? httpsAgent : httpAgent,
    redirect: "manual"
  });
}

module.exports = { blockedAddress: blockedAddress, safeLookup: safeLookup, origin: origin, protectedFetch: protectedFetch };
