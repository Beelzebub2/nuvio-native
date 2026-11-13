"use strict";
const { isIP } = require("node:net");
const os = require("node:os");

const PLATFORMS = Object.freeze({
  webos: Object.freeze({ label: "LG webOS", extension: ".ipk", appId: "app.starlit.webos", filePrefix: "Starlit-WebOS", checksumFile: "SHA256SUMS-WebOS" }),
  tizen: Object.freeze({ label: "Samsung Tizen", extension: ".wgt", appId: "StarlitTV1.StarlitTV", filePrefix: "Starlit-Tizen", checksumFile: "SHA256SUMS-Tizen" })
});
function platformInfo(name) {
  if (!Object.prototype.hasOwnProperty.call(PLATFORMS, name)) throw new Error("Choose LG webOS or Samsung Tizen");
  return PLATFORMS[name];
}
function isLanIPv4(value) {
  if (isIP(value) !== 4) return false;
  const parts = value.split(".").map(Number);
  return parts[0] === 10 || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    (parts[0] === 192 && parts[1] === 168);
}
function localLanIps(interfaces = os.networkInterfaces()) {
  return [...new Set(Object.values(interfaces).flat().filter(info =>
    info && info.family === "IPv4" && !info.internal && isLanIPv4(info.address)
  ).map(info => info.address))].sort();
}
module.exports = { PLATFORMS, platformInfo, isLanIPv4, localLanIps };
